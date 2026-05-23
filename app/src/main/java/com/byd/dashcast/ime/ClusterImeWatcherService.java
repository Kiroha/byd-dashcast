package com.byd.dashcast.ime;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
                setServiceInfo(info);
            }
            AppLogger.i(TAG, "onServiceConnected — watching cluster focus events (DL5=" + mIsDiLink5 + ")");
        } catch (Throwable t) {
            AppLogger.e(TAG, "onServiceConnected failed", t);
        }
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
}
