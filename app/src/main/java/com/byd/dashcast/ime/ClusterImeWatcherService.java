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
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!mIsDiLink5 || event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_FOCUSED) return;

        // v1.2.9 — Hard gate: only react while the DashCast cluster projection is
        // actually live. Outside of that window the cluster display is dormant
        // and any focus event from a secondary display is none of our business
        // (other in-vehicle systems, external HDMI, etc.).
        if (!com.byd.dashcast.ClusterService.sIsRunning) return;

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
        if (self == null) return false;
        AccessibilityNodeInfo node = self.findClusterFocusedEditable();
        if (node == null) return false;
        try {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text == null ? "" : text);
            boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (!ok) AppLogger.w(TAG, "setTextOnCluster ACTION_SET_TEXT refused");
            return ok;
        } catch (Throwable t) {
            AppLogger.e(TAG, "setTextOnCluster failed", t);
            return false;
        } finally {
            try { node.recycle(); } catch (Throwable ignored) { }
        }
    }

    /**
     * v1.2.12 — Trigger the IME-enter action on the cluster focused editable
     * (Search / Send / Done depending on the field's {@code imeOptions}).
     * Falls back to a synthesized click on API &lt; 30.
     */
    public static boolean performImeEnterOnCluster() {
        ClusterImeWatcherService self = sInstance;
        if (self == null) return false;
        AccessibilityNodeInfo node = self.findClusterFocusedEditable();
        if (node == null) return false;
        try {
            int actionId;
            if (Build.VERSION.SDK_INT >= 30) {
                actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
            } else {
                actionId = AccessibilityNodeInfo.ACTION_CLICK;
            }
            return node.performAction(actionId);
        } catch (Throwable t) {
            AppLogger.e(TAG, "performImeEnterOnCluster failed", t);
            return false;
        } finally {
            try { node.recycle(); } catch (Throwable ignored) { }
        }
    }

    /**
     * Walk every interactive window, prefer those on a non-default display
     * (the cluster on DL3/DL5), and return the first editable focused node.
     * Caller owns the returned node — must {@code recycle()} it.
     */
    private AccessibilityNodeInfo findClusterFocusedEditable() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (Throwable t) {
            return null;
        }
        if (windows == null || windows.isEmpty()) return null;

        AccessibilityNodeInfo bestNonDefault = null;
        AccessibilityNodeInfo bestAny = null;
        try {
            for (AccessibilityWindowInfo w : windows) {
                if (w == null) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                AccessibilityNodeInfo focused = null;
                try {
                    focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                } catch (Throwable ignored) { }
                if (focused == null || !focused.isEditable()) {
                    if (focused != null) focused.recycle();
                    root.recycle();
                    continue;
                }
                // Prefer cluster window (displayId > 0 on API 30+).
                int displayId = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    try { displayId = w.getDisplayId(); } catch (Throwable ignored) { }
                }
                if (displayId > 0 && bestNonDefault == null) {
                    bestNonDefault = focused;
                } else if (bestAny == null) {
                    bestAny = focused;
                } else {
                    focused.recycle();
                }
                root.recycle();
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "findClusterFocusedEditable scan failed", t);
        } finally {
            // AccessibilityWindowInfo released via the framework; explicit recycle
            // is not required on each entry of getWindows().
        }
        if (bestNonDefault != null) {
            if (bestAny != null) bestAny.recycle();
            return bestNonDefault;
        }
        return bestAny;
    }
}
