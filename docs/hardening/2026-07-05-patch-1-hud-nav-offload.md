# Patch 1 — Offload nav-notification HUD updates off the listener main thread

- **Status:** ⚠️ **APPLIED — in production since 1.6.108-beta. Do not apply this diff.**
  It shipped in a more developed form than the draft below: instead of the proposed
  `ExecutorService` plus coalescing `AtomicReference`, the code uses a dedicated
  `LatestValueDispatcher<HudNavigationData>` on a `hud-nav-writer` thread. See
  `MapNotificationListenerService` for what actually runs. The "Why" and "Side effects"
  sections still explain the reasoning; the diff is history.
- **File:** `app/src/main/java/com/byd/dashcast/hud/MapNotificationListenerService.java`
- **Fixes:** Finding #16 (HIGH), also #17 benefits once the resolver runs off-main.
- **Fix risk:** Medium
- **Roadmap rank:** 1

## Why

`NotificationListenerService` callbacks (`onNotificationPosted` L451, `onNotificationRemoved` L460,
`onListenerDisconnected` L378) run on the service **main/dispatch thread**. Each calls
`HudController.INSTANCE.updateNavigation/closeNavigation`, which fans out to
`CanBusController.send*` → `ProxyClient` blocking binder/daemon round-trips (up to ~9 verbs, plus an
8 s cold-daemon bootstrap) + `sendBroadcast`. A cold/slow uid-2000 daemon therefore stalls the system
notification dispatch thread while the driver is navigating → ANR / dropped notifications on the cluster.

## Mechanic that immunizes it

Route every `HudController` call through one **service-owned single-thread (serial) `ExecutorService`**
created in `onCreate` and shut down in `onDestroy`. A single `AtomicReference<HudNavigationData>` holds
the latest queued nav state so bursts **coalesce** (newest wins, stale guidance frames dropped) while the
serial executor **preserves guidance-frame order** so CAN frames are never reordered. `close` cancels any
queued-but-unrun update first so a stale frame can't re-open the HUD after removal. Warm daemon: tasks
drain immediately, one write per update — no behavior change.

## Full patch (BEFORE / AFTER)

```java
/* =======================================================================
 * HUNK 1 — imports: add Context + serial-executor / coalescing types
 * ======================================================================= */
// BEFORE (lines 3-15):
import android.app.Notification;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.byd.dashcast.system.CanBusController;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// AFTER:
import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.byd.dashcast.system.CanBusController;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/* =======================================================================
 * HUNK 2 — new fields: serial executor + latest-wins coalescing state
 * (insert immediately after the TAG constant)
 * ======================================================================= */
// BEFORE:
    private static final String TAG = "MapNavListener";

    // Notification-level deduplication — avoid reprocessing identical notification content
    // (same pattern as OpenBYD MapNotificationListenerService lastNotification* fields).
    private String lastTitle   = "";

// AFTER:
    private static final String TAG = "MapNavListener";

    // ─── HUD write offloading ─────────────────────────────────────────────
    // All ProxyClient/CAN writes (HudController.updateNavigation/closeNavigation
    // → CanBusController.send* → ProxyClient binder round-trips + sendBroadcast)
    // run on this single-thread SERIAL executor so the system notification
    // dispatch thread never blocks on the daemon. Serial (not pooled) preserves
    // guidance-frame order → CAN frames are never reordered. `pendingNav`
    // coalesces bursts: only the latest queued nav state survives; stale frames
    // are dropped. `appContext` is the process-scoped application context (safe
    // to retain — not the service instance, so no leak).
    private ExecutorService hudExecutor;
    private volatile Context appContext;
    private final AtomicReference<HudNavigationData> pendingNav = new AtomicReference<>();

    /** Drains the newest queued nav state on the serial writer thread. */
    private final Runnable navUpdateTask = () -> {
        HudNavigationData d = pendingNav.getAndSet(null);
        if (d == null) return;            // superseded by a newer frame — coalesced away
        Context ctx = appContext;
        if (ctx == null) return;          // service torn down between enqueue and drain
        HudController.INSTANCE.updateNavigation(ctx, d);
    };

    // Notification-level deduplication — avoid reprocessing identical notification content
    // (same pattern as OpenBYD MapNotificationListenerService lastNotification* fields).
    private String lastTitle   = "";


/* =======================================================================
 * HUNK 3 — lifecycle (onCreate/onDestroy own the executor) + route
 * onListenerDisconnected through the serial writer
 * ======================================================================= */
// BEFORE:
    // ─── NotificationListenerService callbacks ────────────────────────────

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        // System unbound the listener (e.g. permission revoked, system crash).
        // Close the HUD so the cluster doesn't stay frozen on the last nav state.
        HudController.INSTANCE.closeNavigation(getApplicationContext());
    }

// AFTER:
    // ─── NotificationListenerService callbacks ────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        hudExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hud-nav-writer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onDestroy() {
        pendingNav.set(null);
        ExecutorService ex = hudExecutor;
        hudExecutor = null;
        if (ex != null) ex.shutdownNow();
        // appContext is the application context (process-scoped); leave it set so a
        // task racing shutdownNow still has a valid context and NPEs are impossible.
        super.onDestroy();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        // System unbound the listener (e.g. permission revoked, system crash).
        // Close the HUD so the cluster doesn't stay frozen on the last nav state.
        postNavClose();
    }


/* =======================================================================
 * HUNK 4 — onNotificationPosted: enqueue instead of blocking-call
 * (only the terminal call changes)
 * ======================================================================= */
// BEFORE (last line of onNotificationPosted):
        HudController.INSTANCE.updateNavigation(this, data);

// AFTER:
        // Offload the ProxyClient/CAN write off the notification dispatch thread.
        postNavUpdate(data);


/* =======================================================================
 * HUNK 5 — onNotificationRemoved routes through the writer, and add the
 * two enqueue helpers (insert helpers right after onNotificationRemoved)
 * ======================================================================= */
// BEFORE:
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !isNavPackage(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            Log.d(TAG, "nav notification removed → closeNavigation");
            HudController.INSTANCE.closeNavigation(this);
            lastTitle   = "";
            lastText    = "";
            lastSubText = "";
        }
    }

// AFTER:
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !isNavPackage(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            Log.d(TAG, "nav notification removed → closeNavigation");
            postNavClose();
            lastTitle   = "";
            lastText    = "";
            lastSubText = "";
        }
    }

    // ─── HUD write dispatch (serial writer thread) ────────────────────────

    /**
     * Queue a nav update on the serial HUD writer thread. Publishes the latest
     * state and enqueues a drain task; if updates arrive faster than the daemon
     * drains them, {@link #navUpdateTask} coalesces to the newest state (older
     * frames dropped). Never blocks the notification dispatch thread. When the
     * daemon is warm the task drains immediately → one write per update, no
     * behavior change.
     */
    private void postNavUpdate(HudNavigationData data) {
        if (data == null) return;
        pendingNav.set(data);
        ExecutorService ex = hudExecutor;
        if (ex == null) return; // service torn down — nothing left to drive the HUD
        try {
            ex.execute(navUpdateTask);
        } catch (RejectedExecutionException ignore) {
            // Executor shutting down: drop this frame; teardown close resets the cluster.
        }
    }

    /**
     * Queue a HUD close on the serial writer thread. Cancels any queued-but-unrun
     * guidance frame first so a stale update cannot re-open the HUD after removal.
     * Falls back to a synchronous close ONLY when the executor is already gone or
     * rejects (service teardown) so the cluster never freezes on the last nav state.
     */
    private void postNavClose() {
        pendingNav.set(null); // cancel any pending guidance frame (close wins)
        final Context ctx = appContext;
        if (ctx == null) return;
        ExecutorService ex = hudExecutor;
        if (ex == null) {
            HudController.INSTANCE.closeNavigation(ctx);
            return;
        }
        try {
            ex.execute(() -> HudController.INSTANCE.closeNavigation(ctx));
        } catch (RejectedExecutionException ignore) {
            HudController.INSTANCE.closeNavigation(ctx);
        }
    }
```

## Side effects (honest)

- **Threading model change:** `HudController.INSTANCE` (mutable singleton with `lastIconId`/`lastDistance`/`isHudActive` dedup state) is now driven from the dedicated `hud-nav-writer` thread instead of whatever thread the framework used per callback — strictly *more* consistent than before (all normal-path calls serialize on one thread).
- **Context change:** `updateNavigation/closeNavigation` now receive the **application** context instead of the service instance `this`. `sendBroadcast`/`createPackageContext` behave identically and it avoids retaining the service instance in a queued task (leak-safe).
- **Coalescing is intentionally lossy under back-pressure:** if the daemon is cold and several notifications queue before the writer drains, intermediate guidance frames are dropped and only the newest renders — this is the required stale-drop guard, not a regression (those frames were about to be superseded; ordering preserved because only the latest is ever written). Warm daemon renders every update.
- **Teardown edge:** `onDestroy` calls `shutdownNow()` (interrupts an in-flight `ProxyClient` call — `HudController` already catches `Throwable`, so an interrupt surfaces as a caught failure, never a crash). `appContext` is deliberately left non-null so a task racing shutdown cannot NPE.
- No new permissions, no manifest change, no public API change.

## Verification

1. **Build:** assemble the app module; confirm the new imports resolve and no unused-import lint.
2. **On-car (DL3/DL5):** grant Notification access, start Google Maps turn-by-turn; `adb logcat -s MapNavListener:D HudController:W` — confirm `nav update:` logs every maneuver and the cluster HUD icon/distance/road update within one guidance-frame cadence (visually indistinguishable).
3. **Off-main proof:** `adb shell ps -T -p <pid> | grep hud-nav-writer` shows the thread; **kill the proxy daemon mid-navigation** and confirm notifications keep flowing with **no ANR** while HUD resumes once the daemon warms.
4. **Close path:** end navigation / swipe the notification → `nav notification removed → closeNavigation` logs and the cluster HUD clears; toggle Notification access off to hit `onListenerDisconnected` and confirm the HUD clears.
5. **Coalescing/ordering:** with a cold daemon, trigger a rapid maneuver sequence → final rendered state matches the latest notification (no stale icon sticks); a removal right after an update always ends with the HUD closed.
6. **Warm-daemon regression:** run a full route → each maneuver renders exactly once (no skipped turns).
