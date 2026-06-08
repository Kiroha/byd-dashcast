package com.byd.dashcast.ime;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import android.annotation.SuppressLint;
import java.util.List;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.KeyboardBridgeActivity;
import com.byd.dashcast.platform.Platform;

/**
 * v1.2.9 — Optional helper that auto-launches {@link KeyboardBridgeActivity}
 * when an EditText (or any editable view) on the <em>cluster</em> display
 * receives focus.
 *
 * <p>DL5 only:
 * <ul>
 *   <li>On DL3 the system IME renders on the cluster natively, so this
 *       watcher is unnecessary — {@link #onAccessibilityEvent(AccessibilityEvent)}
 *       early-exits via {@link Platform#isDiLink5(Context)}.</li>
 *   <li>On DL5 the cluster lives on a 1×1 shadow framebuffer where the IME
 *       has nowhere to render. We detect the focus event here and launch the
 *       relay Activity on the main display.</li>
 * </ul>
 *
 * <p>Defensive design — must never crash the host app:
 * <ul>
 *   <li>All node retrievals are try/finally with {@code recycle()}.</li>
 *   <li>De-bounce: at most one launch per {@link #DEBOUNCE_MS}.</li>
 *   <li>If the bridge is already foreground, no relaunch.</li>
 *   <li>Any uncaught exception is swallowed with a single log line; the
 *       AccessibilityService keeps running.</li>
 * </ul>
 *
 * <p>User-facing UX: enabling this service is purely opt-in. The MainActivity
 * surfaces a dismissible banner on DL5 with a deep-link to
 * {@link Settings#ACTION_ACCESSIBILITY_SETTINGS}. The ⌨ button stays available
 * as a manual fallback even if the user declines.
 */
public class ClusterImeWatcherService extends AccessibilityService {

    private static final String TAG = "ClusterImeWatcher";
    private static final long   DEBOUNCE_MS = 600L;

    /** v1.2.12 — Live service instance for cross-process text relay
     *  ({@link #setTextOnCluster(CharSequence)} / {@link #performImeEnterOnCluster()}).
     *  Set in {@link #onServiceConnected()} / cleared in {@link #onDestroy()}.
     *  Volatile because read from the bridge activity's UI thread. */
    private static volatile ClusterImeWatcherService sInstance = null;

    private long mLastLaunchAt = 0L;
    private boolean mIsDiLink5  = false;

    // v1.2.24 — Background worker for setTextOnCluster / performImeEnterOnCluster.
    // Field log BYD_RE_Sniffer_20260523_164557.txt showed an Input-dispatch ANR
    // (5000ms KeyEvent wait → process killed at 16:46:47) caused by running the
    // expensive a11y tree walk (`findClusterFocusedEditable() → getWindows() →
    // findFocus()`, all cross-display Binder calls) on the UI thread on every
    // keystroke. We now hop to a dedicated HandlerThread and coalesce rapid
    // text updates with a small debounce so a fast typist stays single-flight.
    private android.os.HandlerThread mWorkerThread;
    private android.os.Handler       mWorker;
    /** Latest text submitted by {@link #setTextOnCluster(CharSequence)}. */
    private volatile CharSequence    mPendingText;
    /** Coalescing window for rapid keystrokes — last-writer-wins. */
    private static final long        SET_TEXT_DEBOUNCE_MS = 80L;
    private final Runnable mSetTextRunner = new Runnable() {
        @Override public void run() {
            CharSequence text = mPendingText;
            if (text == null) return;
            AccessibilityNodeInfo node = findClusterFocusedEditable();
            if (node == null) {
                AppLogger.w(TAG, "setTextOnCluster no-op: no focused editable on cluster");
                return;
            }
            try {
                Bundle args = new Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text);
                boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                if (!ok) AppLogger.w(TAG, "setTextOnCluster ACTION_SET_TEXT refused");
            } catch (Throwable t) {
                AppLogger.e(TAG, "setTextOnCluster failed", t);
            } finally {
                try { node.recycle(); } catch (Throwable ignored) { }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            mIsDiLink5 = Platform.get().isDiLink5(this);
            AppLogger.i(TAG, "onCreate — DL5=" + mIsDiLink5);
        } catch (Throwable t) {
            AppLogger.e(TAG, "onCreate Platform check failed", t);
            mIsDiLink5 = false;
        }
        // v1.2.24 — Spin up the background worker once; reused for every
        // setText / performImeEnter dispatch.
        try {
            mWorkerThread = new android.os.HandlerThread(
                    "cluster-ime-relay",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            mWorkerThread.start();
            mWorker = new android.os.Handler(mWorkerThread.getLooper());
        } catch (Throwable t) {
            AppLogger.e(TAG, "onCreate worker setup failed", t);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            // Refresh in case the user toggled DL5 mode after the service was created.
            mIsDiLink5 = Platform.get().isDiLink5(this);
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED
                        | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                info.notificationTimeout = 100;
                // v1.2.12 — required so getWindows() returns the cluster windows
                // (otherwise only the active head-unit window is visible).
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
            sInstance = this;
            AppLogger.i(TAG, "onServiceConnected — watching cluster focus events (DL5=" + mIsDiLink5 + ")");
        } catch (Throwable t) {
            AppLogger.e(TAG, "onServiceConnected failed", t);
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (sInstance == this) sInstance = null;
        } catch (Throwable ignored) { }
        // v1.2.24 — Tear down the worker so the HandlerThread does not leak
        // past service unbind (system may rebind us repeatedly).
        try {
            if (mWorker != null) mWorker.removeCallbacksAndMessages(null);
            if (mWorkerThread != null) mWorkerThread.quitSafely();
        } catch (Throwable ignored) { }
        mWorker = null;
        mWorkerThread = null;
        mPendingText = null;
        super.onDestroy();
    }

    @SuppressLint("NewApi")
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!mIsDiLink5 || event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_FOCUSED) return;

        // v1.2.9 — Hard gate: only react while the DashCast cluster projection is
        // actually live. Outside of that window the cluster display is dormant
        // and any focus event from a secondary display is none of our business
        // (other in-vehicle systems, external HDMI, etc.).
        if (!com.byd.dashcast.ClusterService.isRunning()) return;

        try {
            // Cluster filter: focus events from the head-unit display are
            // explicitly ignored — the system IME already works there.
            // API 30+ returns the real displayId; older API returns -1 which
            // we treat as "unknown → do not auto-trigger" (the manual ⌨
            // button remains available).
            int evDisplayId;
            try {
                evDisplayId = event.getDisplayId();
            } catch (Throwable t) {
                evDisplayId = -1;
            }
            if (evDisplayId <= 0) return; // 0 = head unit, -1 = unknown

            // Inspect the source node — must be editable (EditText, search bar, ...)
            AccessibilityNodeInfo src = event.getSource();
            if (src == null) return;
            try {
                if (!isEditable(src)) return;
                String pkg = String.valueOf(src.getPackageName());
                // Never relaunch when the focus event is from OUR OWN bridge
                // (avoids an infinite popup loop if the user taps it).
                if (TextUtils.equals(pkg, getPackageName())) return;
            } finally {
                src.recycle();
            }

            // De-bounce + foreground check
            long now = SystemClock.uptimeMillis();
            if (now - mLastLaunchAt < DEBOUNCE_MS) return;
            if (KeyboardBridgeActivity.isShowing()) return;
            mLastLaunchAt = now;

            launchBridge();
        } catch (Throwable t) {
            // Never let an a11y crash kill the host app. Log once and move on.
            AppLogger.e(TAG, "onAccessibilityEvent swallowed", t);
        }
    }

    @Override
    public void onInterrupt() { /* no-op */ }

    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isEditable(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        // Some apps mark EditText but Accessibility flags don't propagate;
        // also check className as a fallback.
        CharSequence cn = node.getClassName();
        if (cn == null) return false;
        String s = cn.toString();
        return s.endsWith("EditText")
                || s.endsWith("AppCompatEditText")
                || s.endsWith("TextInputEditText")
                || s.endsWith("SearchEditText")
                || s.endsWith("AutoCompleteTextView");
    }

    private void launchBridge() {
        try {
            Intent i = new Intent(this, KeyboardBridgeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            i.putExtra(KeyboardBridgeActivity.EXTRA_AUTO_OPENED, true);
            startActivity(i);
        } catch (Throwable t) {
            AppLogger.e(TAG, "launchBridge failed", t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public helpers used by MainActivity's onboarding banner
    // ─────────────────────────────────────────────────────────────────────────

    /** True if the user has enabled our a11y service in system Settings. */
    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            String enabled = Settings.Secure.getString(
                    ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            ComponentName self = new ComponentName(ctx, ClusterImeWatcherService.class);
            String selfFlat = self.flattenToString();
            String selfShort = self.flattenToShortString();
            // The setting stores a colon-separated list of ComponentName.flattenToString().
            for (String token : enabled.split(":")) {
                if (token == null) continue;
                String t = token.trim();
                if (t.equals(selfFlat) || t.equals(selfShort)) return true;
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "isEnabled check failed", t);
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // v1.2.12 — A11y-based text relay (replaces KeyEvent injection)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * v1.2.12 — Push {@code text} as the full content of the currently-focused
     * editable node on any cluster (non-default) display. Returns {@code true}
     * iff a node was found AND {@link AccessibilityNodeInfo#performAction}
     * accepted {@link AccessibilityNodeInfo#ACTION_SET_TEXT}.
     *
     * <p>Unlike the legacy KeyEvent path this routes through the accessibility
     * subsystem, which is cross-display by construction (TalkBack does the same)
     * — no {@code displayId} bookkeeping, no shadow-framebuffer routing surprise.
     */
    public static boolean setTextOnCluster(CharSequence text) {
        ClusterImeWatcherService self = sInstance;
        if (self == null) {
            // v1.2.18 — was silent. Field log BYD_RE_Sniffer_20260523_161033.txt
            // showed the user typing into the keyboard bridge with no trace from
            // this class at all because the a11y service was not enabled. Log it
            // once per call so the diagnostic is visible.
            AppLogger.w(TAG, "setTextOnCluster no-op: a11y service not bound "
                    + "(enable ‘DashCast Cluster IME’ in Settings → Accessibility)");
            return false;
        }
        // v1.2.24 — Never run the a11y tree walk on the caller's thread.
        // Coalesce rapid keystrokes (last-writer-wins) and dispatch to the
        // background worker. We optimistically return true; failures are
        // logged inside mSetTextRunner.
        android.os.Handler worker = self.mWorker;
        if (worker == null) {
            AppLogger.w(TAG, "setTextOnCluster no-op: worker not ready");
            return false;
        }
        self.mPendingText = text == null ? "" : text;
        worker.removeCallbacks(self.mSetTextRunner);
        worker.postDelayed(self.mSetTextRunner, SET_TEXT_DEBOUNCE_MS);
        return true;
    }

    /**
     * v1.2.12 — Trigger the IME-enter action on the cluster focused editable
     * (Search / Send / Done depending on the field's {@code imeOptions}).
     * Falls back to a synthesized click on API &lt; 30.
     */
    public static boolean performImeEnterOnCluster() {
        ClusterImeWatcherService self = sInstance;
        if (self == null) {
            AppLogger.w(TAG, "performImeEnterOnCluster no-op: a11y service not bound");
            return false;
        }
        // v1.2.24 — Hop to the worker so we never block the UI thread on the
        // a11y tree walk. Flush any pending setText first so Enter sees the
        // final string, then run the IME-Enter action.
        final android.os.Handler worker = self.mWorker;
        if (worker == null) {
            AppLogger.w(TAG, "performImeEnterOnCluster no-op: worker not ready");
            return false;
        }
        // Cancel debounced setText and run it now, then perform Enter.
        worker.removeCallbacks(self.mSetTextRunner);
        worker.post(new Runnable() {
            @Override public void run() {
                // Flush any pending text first.
                if (self.mPendingText != null) {
                    try { self.mSetTextRunner.run(); } catch (Throwable ignored) { }
                }
                AccessibilityNodeInfo node = self.findClusterFocusedEditable();
                if (node == null) {
                    AppLogger.w(TAG, "performImeEnterOnCluster no-op: no focused editable on cluster");
                    return;
                }
                try {
                    int actionId;
                    if (Build.VERSION.SDK_INT >= 30) {
                        actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
                    } else {
                        actionId = AccessibilityNodeInfo.ACTION_CLICK;
                    }
                    node.performAction(actionId);
                } catch (Throwable t) {
                    AppLogger.e(TAG, "performImeEnterOnCluster failed", t);
                } finally {
                    try { node.recycle(); } catch (Throwable ignored) { }
                }
            }
        });
        return true;
    }

    /**
     * v1.3.3 — Touch-driven keyboard auto-trigger. Called by MainActivity's
     * {@code forwardTouchFromMirror} after an ACTION_UP is forwarded to the
     * cluster mirror (350 ms delay to let the cluster app process the tap and
     * move input focus). Posts the editable-detection work to the background
     * worker so {@code getWindowsOnAllDisplays()} is never called on the UI
     * thread. No-op when the bridge is already showing, the service is not
     * bound, or the debounce window has not elapsed.
     *
     * <p>This complements the existing event-driven path (TYPE_VIEW_FOCUSED
     * from the accessibility subsystem). Both paths share the same debounce
     * gate so they cannot double-launch the bridge.
     */
    public static void checkAndLaunchBridgeIfNeeded(Context ctx) {
        ClusterImeWatcherService self = sInstance;
        if (self == null) return;                          // service not enabled
        if (KeyboardBridgeActivity.isShowing()) return;    // already open
        long now = android.os.SystemClock.uptimeMillis();
        if (now - self.mLastLaunchAt < DEBOUNCE_MS) return; // debounce
        android.os.Handler worker = self.mWorker;
        if (worker == null) return;
        final ClusterImeWatcherService svc = self;
        worker.post(new Runnable() {
            @Override public void run() {
                if (KeyboardBridgeActivity.isShowing()) return;
                AccessibilityNodeInfo node = svc.findClusterFocusedEditable();
                if (node == null) return;
                try { node.recycle(); } catch (Throwable ignored) { }
                svc.mLastLaunchAt = android.os.SystemClock.uptimeMillis();
                AppLogger.d(TAG, "checkAndLaunchBridgeIfNeeded — cluster editable detected after touch, launching bridge");
                svc.launchBridge();
            }
        });
    }

    /**
     * Walk every interactive window across every display, prefer those on a
     * non-default display (the cluster on DL3/DL5) and SKIP our own bridge
     * package (so we never overwrite the local EditText instead of the
     * cluster one), then return the first editable focused node. Caller
     * owns the returned node — must {@code recycle()} it.
     *
     * <p>v1.2.25 — Field log {@code BYD_RE_Sniffer_20260523_170436.txt}
     * showed the v1.2.24 worker logging
     * {@code setTextOnCluster no-op: no focused editable on cluster} on
     * every keystroke: {@link AccessibilityService#getWindows()} returns
     * only the windows on the <em>default</em> display, so the cluster
     * Yandex editable on display 2/3 was never reachable. We now use
     * {@link AccessibilityService#getWindowsOnAllDisplays()} (API 30+,
     * DL5 ships API 32) and fall back to the single-display walk only on
     * older API levels. We also explicitly exclude windows from our own
     * package because v1.2.25 keeps the bridge open after ADB success →
     * the bridge's own EditText would otherwise compete for {@code
     * findFocus(FOCUS_INPUT)} and win on the default display.
     */
    private AccessibilityNodeInfo findClusterFocusedEditable() {
        final String selfPkg = getPackageName();
        AccessibilityNodeInfo bestNonDefault = null;
        AccessibilityNodeInfo bestAny = null;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.util.SparseArray<List<AccessibilityWindowInfo>> all;
                try {
                    all = getWindowsOnAllDisplays();
                } catch (Throwable t) {
                    all = null;
                }
                if (all != null) {
                    for (int i = 0; i < all.size(); i++) {
                        int displayId = all.keyAt(i);
                        List<AccessibilityWindowInfo> wins = all.valueAt(i);
                        if (wins == null) continue;
                        for (AccessibilityWindowInfo w : wins) {
                            AccessibilityNodeInfo cand = pickFocusedEditableFrom(w, selfPkg);
                            if (cand == null) continue;
                            if (displayId != 0 && bestNonDefault == null) {
                                bestNonDefault = cand;
                            } else if (bestAny == null) {
                                bestAny = cand;
                            } else {
                                cand.recycle();
                            }
                        }
                    }
                }
            }
            // Fallback (or supplement): default-display walk.
            if (bestNonDefault == null) {
                List<AccessibilityWindowInfo> windows;
                try {
                    windows = getWindows();
                } catch (Throwable t) {
                    windows = null;
                }
                if (windows != null) {
                    for (AccessibilityWindowInfo w : windows) {
                        AccessibilityNodeInfo cand = pickFocusedEditableFrom(w, selfPkg);
                        if (cand == null) continue;
                        int displayId = 0;
                        if (Build.VERSION.SDK_INT >= 30) {
                            try { displayId = w.getDisplayId(); } catch (Throwable ignored) { }
                        }
                        if (displayId > 0 && bestNonDefault == null) {
                            bestNonDefault = cand;
                        } else if (bestAny == null) {
                            bestAny = cand;
                        } else {
                            cand.recycle();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "findClusterFocusedEditable scan failed", t);
        }
        if (bestNonDefault != null) {
            if (bestAny != null) bestAny.recycle();
            return bestNonDefault;
        }
        return bestAny;
    }

    /** Helper for {@link #findClusterFocusedEditable()} — returns the
     *  window's focused editable node (caller owns + must recycle) iff it
     *  exists and is NOT from our own bridge package; null otherwise.
     *  Always recycles the window root internally. */
    private static AccessibilityNodeInfo pickFocusedEditableFrom(
            AccessibilityWindowInfo w, String selfPkg) {
        if (w == null) return null;
        AccessibilityNodeInfo root = w.getRoot();
        if (root == null) return null;
        AccessibilityNodeInfo focused = null;
        try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        } catch (Throwable ignored) { }
        try {
            if (focused == null) return null;
            if (!focused.isEditable()) { focused.recycle(); return null; }
            // v1.2.25 — Skip our own bridge EditText so we never push the
            // user's keystrokes back into the local field instead of the
            // cluster Yandex search.
            CharSequence pkg = focused.getPackageName();
            if (pkg != null && selfPkg.contentEquals(pkg)) {
                focused.recycle();
                return null;
            }
            return focused;
        } finally {
            root.recycle();
        }
    }
}
