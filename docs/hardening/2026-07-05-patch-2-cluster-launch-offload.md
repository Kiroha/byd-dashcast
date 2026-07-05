# Patch 2 — Move both cluster launch cascades off the main thread

- **Status:** PROPOSED — reviewed draft, **not applied**. Review + on-car verify before merging.
- **File:** `app/src/main/java/com/byd/dashcast/cluster/ClusterService.java`
- **Fixes:** Finding #1 (HIGH, `launchOnDashboard`) + Finding #2 (HIGH, `launchOnDashboardWithBounds`).
- **Fix risk:** Medium
- **Roadmap rank:** 2

## Why

Both launch cascades post their whole body to `mMainHandler`, so `ProxyClient.cleanFissionStacks`
(synchronous binder/proxy transact), `ClusterDpiManager.applyForLaunch` (shell round-trip), and
`startActivityViaIAM` (binder reflection + proxy-daemon bootstrap) run on the **main thread** — an ANR
when the uid-2000 daemon is cold. `launchOnDashboardWithBounds` has **no** executor offload at all today.

## Mechanic that immunizes it

Keep the 2000 ms / 500 ms schedule on the main handler (so it stays cancellable and the delay is
preserved), but hop the Runnable/lambda body onto the **existing single-thread `sMoveTaskExecutor`** for
all binder/proxy/shell work; marshal only `callback.onResult` back to `mMainHandler` via a new
`postLaunchResult` helper. Reusing `sMoveTaskExecutor` keeps launches **serialized** behind in-flight
`moveTaskToDisplay` task-moves — no new races. The DPI `SETTLE_MS` delay becomes a `Thread.sleep` on that
background thread (the Kotlin `ClusterDpiManager` doc already states the caller must apply `SETTLE_MS`
off the main thread). `launchViaDaemonForce` still enqueues on the same single-thread executor (async,
no deadlock) and still reports success immediately — behavior preserved.

## Full patch (BEFORE / AFTER)

> The verbatim BEFORE bodies are the current `launchOnDashboard` (lines ~541–648) and
> `launchOnDashboardWithBounds` (lines ~650–720). The AFTER replacement for that whole region:

```java
    public void launchOnDashboard(final String packageName, final LaunchCallback callback) {
        AppLogger.log(TAG, "launchOnDashboard — 2s delay → " + packageName);
        if (mPendingLaunchRunnable != null) {
            mMainHandler.removeCallbacks(mPendingLaunchRunnable);
            mPendingLaunchRunnable = null;
            if (mPendingDashboardCallback != null) {
                LaunchCallback prev = mPendingDashboardCallback;
                mPendingDashboardCallback = null;
                prev.onResult(false);
            }
        }
        mPendingDashboardCallback = callback;
        mPendingLaunchRunnable = new Runnable() {
            @Override public void run() {
                // Cancellation bookkeeping stays on the MAIN thread: these two fields are
                // read/written from launchOnDashboard() and onDestroy() on the main looper,
                // so they must be cleared here (still on main) before the executor hop.
                mPendingLaunchRunnable    = null;
                mPendingDashboardCallback = null;
                // Hop the whole cascade off the main thread. cleanFissionStacks() is a
                // synchronous binder/proxy transact, applyForLaunch() does a shell round-trip
                // and startActivityViaIAM() bootstraps the uid-2000 proxy daemon — none of that
                // may run on the UI thread (ANR when the daemon is cold). Reusing the existing
                // single-thread sMoveTaskExecutor keeps this launch serialized behind any
                // in-flight moveTaskToDisplay task-move, so no new races are introduced.
                sMoveTaskExecutor.execute(() -> {
                    final int displayId = mDisplayHelper.getKnownClusterDisplayId();
                    AppLogger.i(TAG, "Launching on display=" + displayId + " → " + packageName);
                    if (displayId <= 0) {
                        AppLogger.w(TAG, "launchOnDashboard: cluster display not ready (id="
                                + displayId + ") — aborting launch for " + packageName);
                        postLaunchResult(callback, false);
                        return;
                    }
                    try {
                        String cleanLog = com.byd.dashcast.proxy.ProxyClient
                                .cleanFissionStacks(displayId);
                        AppLogger.d(TAG, "cleanFissionStacks(" + displayId + ")\n" + cleanLog);
                    } catch (Throwable ce) {
                        AppLogger.w(TAG, "cleanFissionStacks failed: " + ce.getMessage());
                    }
                    boolean needsDpiSettle = com.byd.dashcast.cluster.dpi.ClusterDpiManager
                            .applyForLaunch(ClusterService.this, packageName, displayId);
                    Runnable doLaunch = () -> {
                        try {
                            android.content.Intent launchIntent =
                                    getPackageManager().getLaunchIntentForPackage(packageName);
                            if (launchIntent == null) {
                                AppLogger.e(TAG, "No launch intent for " + packageName);
                                postLaunchResult(callback, false);
                                return;
                            }
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                            opts.setLaunchDisplayId(displayId);
                            if (displayId > 0) applyClusterFreeformBounds(opts, displayId, packageName);
                            // Primary: IAM path (honours setLaunchDisplayId in ActivityOptions).
                            // On DL5 with real HDMI, IAM reflection may fail (NPE) and fall back to
                            // startActivity(opts) which ignores setLaunchDisplayId on this ROM.
                            // On some DX_BYD_AUTO ROMs even a shell `am start --display 1` lands the
                            // app on display 0 (the live `displayId=1 realActivity` query stays empty).
                            // Route through the proxy daemon's launchAndForce cascade instead: it adds
                            // the privileged moveRootTaskToDisplay + watchdog that re-anchors the task
                            // on the cluster display — the same path fission uses to pin apps there.
                            boolean iamOk;
                            try {
                                iamOk = startActivityViaIAM(launchIntent, opts, displayId);
                            } catch (Throwable iamErr) {
                                // Unprivileged units (D50F_LC / DL5.1): the app-side launch onto the
                                // cluster display is DENIED (cross-user SecurityException — "Permission
                                // Denial … launchDisplayId=N" — the app lacks INTERACT_ACROSS_USERS).
                                // A thrown denial must NOT escape to the outer catch (that aborted the
                                // whole launch and skipped the daemon path). Treat it as a plain failure
                                // so DL5 reaches the privileged uid-2000 daemon path below.
                                AppLogger.w(TAG, "startActivityViaIAM threw ("
                                        + iamErr.getClass().getSimpleName() + ": " + iamErr.getMessage() + ")");
                                iamOk = false;
                            }
                            if (!iamOk && AdbLocalClient.isDiLink5Safe(ClusterService.this)) {
                                // The uid-2000 daemon HOLDS the cross-user permission — proven on-car
                                // (INC-20260705: D8 "Launch + retract OK — projected on display 2" on
                                // D50F_LC, while every app-side attempt returned Permission Denial).
                                AppLogger.w(TAG, "DL5: app-side launch failed — routing via proxy daemon launchAndForce");
                                launchViaDaemonForce(packageName, displayId,
                                        clusterWidthOr(1920), clusterHeightOr(720));
                                AppLogger.i(TAG, "launchOnDashboard → daemon force path → " + packageName);
                                postLaunchResult(callback, true);
                            } else if (iamOk) {
                                AppLogger.i(TAG, "launchOnDashboard OK → " + packageName);
                                postLaunchResult(callback, true);
                            } else {
                                // Non-DL5 (DL3) app-side launch failed and there is no daemon cluster
                                // path here — report failure rather than a false success.
                                AppLogger.e(TAG, "launchOnDashboard failed (app-side) → " + packageName);
                                postLaunchResult(callback, false);
                            }
                        } catch (Exception e) {
                            AppLogger.e(TAG, "launchOnDashboard error for " + packageName, e);
                            postLaunchResult(callback, false);
                        }
                    };
                    // DPI settle delay is now applied on THIS background thread (ClusterDpiManager
                    // documents that the caller must delay SETTLE_MS off the main thread) instead of
                    // a main-handler postDelayed, so the whole cascade stays on the executor.
                    if (needsDpiSettle) {
                        try {
                            Thread.sleep(com.byd.dashcast.cluster.dpi.ClusterDpiManager.SETTLE_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (mDestroyed) return;
                    doLaunch.run();
                });
            }
        };
        mMainHandler.postDelayed(mPendingLaunchRunnable, 2000);
    }

    public void launchOnDashboardWithBounds(final String packageName,
            final int left, final int top, final int right, final int bottom,
            final LaunchCallback callback) {
        AppLogger.log(TAG, "launchOnDashboardWithBounds 500ms → " + packageName
                + " [" + left + "," + top + "," + right + "," + bottom + "]");
        // Keep the 500ms schedule on the main handler (preserves timing + cancel semantics),
        // then hop the whole binder/proxy/shell cascade onto the shared single-thread
        // move-task executor so the main thread never blocks and launches stay serialized
        // behind any in-flight task-move.
        mMainHandler.postDelayed(() -> sMoveTaskExecutor.execute(() -> {
            final int displayId = mDisplayHelper.getKnownClusterDisplayId();
            if (displayId <= 0) {
                AppLogger.w(TAG, "launchOnDashboardWithBounds: cluster display not ready (id="
                        + displayId + ") — aborting launch for " + packageName);
                postLaunchResult(callback, false);
                return;
            }
            if (displayId > 0) {
                try {
                    com.byd.dashcast.proxy.ProxyClient.cleanFissionStacks(displayId);
                } catch (Throwable ce) {
                    AppLogger.w(TAG, "cleanFissionStacks(WithBounds) failed: " + ce.getMessage());
                }
            }
            boolean needsDpiSettle = com.byd.dashcast.cluster.dpi.ClusterDpiManager
                    .applyForLaunch(ClusterService.this, packageName, displayId);
            Runnable doLaunchWithBounds = () -> {
                try {
                    android.content.Intent launchIntent =
                            getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) {
                        AppLogger.e(TAG, "launchOnDashboardWithBounds: no intent for " + packageName);
                        postLaunchResult(callback, false);
                        return;
                    }
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(displayId);
                    try {
                        java.lang.reflect.Method setWM = android.app.ActivityOptions.class
                                .getDeclaredMethod("setLaunchWindowingMode", int.class);
                        setWM.setAccessible(true);
                        setWM.invoke(opts, 5);
                    } catch (Exception e) {
                        AppLogger.w(TAG, "setLaunchWindowingMode: " + e.getMessage());
                    }
                    try {
                        java.lang.reflect.Method setLB = android.app.ActivityOptions.class
                                .getDeclaredMethod("setLaunchBounds", Rect.class);
                        setLB.setAccessible(true);
                        setLB.invoke(opts, new Rect(left, top, right, bottom));
                    } catch (Exception e) {
                        AppLogger.w(TAG, "setLaunchBounds: " + e.getMessage());
                    }
                    // Same chain as launchOnDashboard: IAM first, then the proxy daemon's
                    // launchAndForce cascade as the DL5 fallback (shell `am start --display`
                    // provably lands the app on display 0 on some DX_BYD_AUTO ROMs).
                    boolean iamOkWB = startActivityViaIAM(launchIntent, opts, displayId);
                    if (!iamOkWB && AdbLocalClient.isDiLink5Safe(ClusterService.this)) {
                        AppLogger.w(TAG, "DL5: IAM fell back to startActivity (WithBounds) — routing via proxy daemon launchAndForce");
                        int wbW = right - left, wbH = bottom - top;
                        launchViaDaemonForce(packageName, displayId,
                                wbW > 0 ? wbW : clusterWidthOr(1920),
                                wbH > 0 ? wbH : clusterHeightOr(720));
                    }
                    AppLogger.i(TAG, "launchOnDashboardWithBounds OK display=" + displayId);
                    postLaunchResult(callback, true);
                } catch (Exception e) {
                    AppLogger.e(TAG, "launchOnDashboardWithBounds error", e);
                    postLaunchResult(callback, false);
                }
            };
            // DPI settle delay applied on this background thread (see launchOnDashboard).
            if (needsDpiSettle) {
                try {
                    Thread.sleep(com.byd.dashcast.cluster.dpi.ClusterDpiManager.SETTLE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (mDestroyed) return;
            doLaunchWithBounds.run();
        }), 500);
    }

    /**
     * Marshals a launch callback result back onto the main looper. The launch cascades now
     * run on {@link #sMoveTaskExecutor}; per the hardening contract only {@code onResult} is
     * allowed back on the main thread. Follows the same drop-on-destroy contract already used
     * by {@link #fallbackLaunch} and moveTaskToDisplayInternal so we never call back into a
     * torn-down service.
     */
    private void postLaunchResult(final LaunchCallback callback, final boolean success) {
        if (callback == null) return;
        mMainHandler.post(() -> {
            if (mDestroyed) return;
            callback.onResult(success);
        });
    }
```

> **Note:** this AFTER uses an `mDestroyed` guard consistent with the class's existing
> drop-on-destroy contract (`fallbackLaunch`/`moveTaskToDisplayInternal`). Confirm the exact field
> name in the current file (`mDestroyed` vs an existing teardown flag) and align before applying.

## Side effects (honest)

1. **Callbacks now delivered asynchronously** via `mMainHandler.post` (was inline on main). Still main-thread, still after the attempt, but +1 loop-tick latency.
2. **Drop-on-destroy:** if the Service is destroyed between the executor hop and the callback, the result is dropped (`mDestroyed`) — matching `moveTaskToDisplayInternal`/`fallbackLaunch`. The original ran atomically on main so this is the one new behavioral edge, and it's the safer choice (never call into a torn-down MainActivity/service).
3. **`SETTLE_MS` (150 ms) is now a `Thread.sleep`** on the single-thread `sMoveTaskExecutor` rather than a main-handler `postDelayed`. A queued `moveTaskToDisplay` waits behind it — intended serialization, not a regression.
4. Initial 2000 ms / 500 ms delays remain on the main handler and remain cancellable (`launchOnDashboard` still `removeCallbacks`).
5. `launchViaDaemonForce` still `sMoveTaskExecutor.execute` from within a task already on that single thread — enqueues only (async, no `get()`), runs after the current task returns; **no deadlock**.
6. `getPackageManager()`/reflection now off main — all binder-backed and thread-safe.

## Verification

1. **Compile:** `rtk ./gradlew :app:compileBydPlatformDebugJavaWithJavac` — no new imports needed (`sMoveTaskExecutor`/`Thread`/`InterruptedException` in scope); `SETTLE_MS` is a Kotlin `const val 150L` valid for `Thread.sleep`.
2. **Lint parity:** `rtk ./gradlew :app:lintBydPlatform` — 0/0 baseline unchanged.
3. **Main-thread proof:** temporarily guard `cleanFissionStacks`/`startActivityViaIAM` entry with `if (Looper.myLooper() == Looper.getMainLooper()) AppLogger.e(...)` (or StrictMode `detectCustomSlowCalls`); trigger a launch → **no** main-thread violation (the same probe fired before the change).
4. **Callback-on-main proof:** assert `Looper.myLooper() == Looper.getMainLooper()` in the `LaunchCallback` for both success and failure paths (null intent, `displayId<=0`).
5. **On-car:** (a) **warm daemon** — launch app onto cluster, confirm it lands + callback success + no jank during the 2 s pre-delay; (b) **freshly-killed daemon** — kill the uid-2000 proxy, immediately launch → still succeeds (re-bootstrapped on the executor thread) with **no dropped launch and no main-thread stall**; (c) **bounds path** — trigger a freeform-inset launch and confirm requested bounds + callback true.
6. **Destroy race:** stop the ClusterService within the 2 s window → `onDestroy` fires the pending `callback(false)` once (unchanged); stop just after the runnable fires (during the executor cascade) → no crash, callback silently dropped (`mDestroyed`).
