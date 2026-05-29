package com.byd.dashcast;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import com.byd.dashcast.ime.ClusterImeWatcherService;

/**
 * v1.2.8 — Keyboard Bridge: workaround for the DL5 limitation where the system
 * IME cannot render natively on the cluster display.
 *
 * <p>v1.2.12 — Forwarding pivot. The original v1.2.8 design injected synthetic
 * {@link KeyEvent}s through {@code MirrorDaemon.injectKey()} routed via
 * {@code KeyEvent.setDisplayId(clusterId)}. Field testing showed two problems:
 * <ul>
 *   <li>v1.2.11 made the EditText 1×1 to remove visual chrome — but at that
 *       size the IMM logged {@code "Ignoring showSoftInput() … is not served"},
 *       no {@link android.view.inputmethod.InputConnection} was ever
 *       established, the {@link TextWatcher} never fired and nothing reached
 *       the daemon (zero {@code injectKey FIRST OK} traces in the captured
 *       logs).</li>
 *   <li>Even if v1.2.11 had been "served", the focus + window of cluster apps
 *       live on the 1×1 shadow framebuffer ({@code mDisplayId=3}) while only
 *       the composed face is on {@code displayId=2}. Routing keys with
 *       {@code setDisplayId(2)} is not symmetric to the touch path and was
 *       never empirically validated.</li>
 * </ul>
 *
 * <p>The new pivot uses the accessibility subsystem instead. The bridge hosts
 * a normally-sized (220 dp × 48 dp, transparent) EditText so the IMM serves it
 * → IME pops up on the head unit → {@link TextWatcher} fires per character →
 * we forward the full content via
 * {@link ClusterImeWatcherService#setTextOnCluster(CharSequence)} which calls
 * {@link android.view.accessibility.AccessibilityNodeInfo#ACTION_SET_TEXT} on
 * the cluster-focused editable node. {@code IME_ACTION_DONE} → Search/Send →
 * {@link ClusterImeWatcherService#performImeEnterOnCluster()} fires
 * {@code ACTION_IME_ENTER}.
 *
 * <p>The accessibility path is cross-display by construction (TalkBack uses
 * the same mechanism) and is immune to per-display IME isolation or
 * compositor / framebuffer asymmetry.
 */
public class KeyboardBridgeActivity extends Activity {

    private static final String TAG = "KeyboardBridge";

    /** v1.2.9 — set by the AccessibilityService when the bridge was auto-opened
     *  on a cluster EditText focus. Currently informational only (used for logs). */
    public static final String EXTRA_AUTO_OPENED = "auto_opened";

    /** v1.2.9 — tracked so the a11y watcher does not re-launch us if we are
     *  already on screen. Volatile because read from the a11y thread. */
    private static volatile boolean sShowing = false;
    /** v1.2.21 — guard so we only prompt the user to enable a11y once
     *  per Activity instance (onWindowFocusChanged fires repeatedly). */
    private boolean mPromptedA11y = false;
    public static boolean isShowing() { return sShowing; }

    private EditText           mInput;
    private InputMethodManager mImm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // v1.2.12 — Small but non-degenerate window so the EditText actually
        // becomes "served" by the IMM (a 1×1 EditText was silently rejected
        // in v1.2.11 with "Ignoring showSoftInput() … is not served"). The
        // window is transparent and pinned bottom-end so it doesn't obscure
        // the cluster mirror. The IME chrome itself appears as usual at the
        // bottom of the head unit.
        //
        // v1.2.15 — Field log BYD_RE_Sniffer_20260523_150803.txt showed the
        // EditText laid out as 330×0 px (height=0) and IMM rejecting it again
        // with "is not served". Root cause: SOFT_INPUT_ADJUST_RESIZE shrinks
        // the window to fit above the IME, but our window IS at the bottom
        // → no vertical room → height collapses to 0. Switching to
        // SOFT_INPUT_ADJUST_NOTHING keeps the 220×48 dp footprint intact;
        // the IME overlaps it visually but that's fine (it's transparent
        // chrome, not user-visible content).
        Window w = getWindow();
        int wPx = dp(220);
        int hPx = dp(48);
        if (w != null) {
            w.setGravity(Gravity.BOTTOM | Gravity.END);
            w.setLayout(wPx, hPx);
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setDimAmount(0f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }

        mImm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

        mInput = new EditText(this);
        mInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mInput.setSingleLine(true);
        mInput.addTextChangedListener(mWatcher);
        mInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_SEND) {
                    boolean ok = false;
                    try {
                        ok = ClusterImeWatcherService.performImeEnterOnCluster();
                    } catch (Throwable t) {
                        AppLogger.e(TAG, "performImeEnterOnCluster failed", t);
                    }
                    if (ok) {
                        // Reset local field so next session starts fresh.
                        try { mInput.setText(""); } catch (Throwable ignored) { }
                    }
                    return true;
                }
                return false;
            }
        });
        // Visually neutral but still served by the IMM (non-zero size, no chrome).
        mInput.setBackground(null);
        mInput.setTextColor(0x00000000);
        mInput.setHintTextColor(0x00000000);
        mInput.setCursorVisible(false);
        mInput.setPadding(0, 0, 0, 0);
        mInput.setMinWidth(wPx);
        mInput.setMinHeight(hPx);

        // v1.2.17 — Field log BYD_RE_Sniffer_20260523_153544.txt showed the
        // EditText laid out at 0×37 px with the new floating Dialog theme.
        // The Dialog wraps to its content, the empty EditText wraps to 0
        // width, and IMM rejects width-0 views as "not served". Force the
        // EditText to wPx × hPx explicitly via its own LayoutParams instead
        // of MATCH_PARENT — the floating Dialog window then wraps around
        // those concrete pixels, IMM sees a real bounding box and serves it.
        setContentView(mInput, new ViewGroup.LayoutParams(wPx, hPx));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // v1.2.12 — wait for the window to actually have focus before requesting
        // the IME. Posting from onCreate raced with the IMM's startInput which
        // is what produced the "is not served" warning in v1.2.11.
        if (!hasFocus || mInput == null || mImm == null) return;
        try {
            mInput.requestFocus();
            mImm.showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable t) {
            AppLogger.e(TAG, "onWindowFocusChanged showSoftInput failed", t);
        }
        // v1.2.18 — If the user has not enabled DashCast Cluster IME under
        // Android Settings → Accessibility, our setTextOnCluster() is a
        // silent no-op (sInstance == null) and the IME keystrokes never
        // reach the cluster Editable. Detect it here and surface a Toast +
        // open the system Accessibility settings so the user can enable us.
        // v1.2.21 — onWindowFocusChanged fires multiple times; guard so we
        // only prompt + open Settings once per Activity instance. Also try
        // multiple intents because some BYD ROMs block the bare
        // ACTION_ACCESSIBILITY_SETTINGS or restrict it to system signature.
        try {
            if (!mPromptedA11y && !isClusterImeWatcherEnabled()) {
                mPromptedA11y = true;
                AppLogger.w(TAG, "ClusterImeWatcherService is NOT enabled — "
                        + "text typed in the bridge will not reach the cluster. "
                        + "Trying ADB auto-enable, with Accessibility settings as fallback.");
                // v1.2.23 — Try to flip the secure flag via the local-ADB
                // shell (uid=shell can `settings put secure
                // enabled_accessibility_services`). If that works we never
                // need to bother the user with the Settings UI at all.
                tryAdbEnableA11y();
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "a11y enablement check failed", t);
        }
    }

    /**
     * v1.2.23 — Attempt to enable {@link ClusterImeWatcherService} headlessly
     * via local ADB ({@link AdbLocalClient}, uid=shell). The {@code settings}
     * binary running as uid 2000 can write {@code Settings.Secure
     * .ENABLED_ACCESSIBILITY_SERVICES} (which third-party apps cannot — see
     * Android source {@code SettingsProvider#assertWritePermissionsForSecureSettings}).
     * Shell preserves any pre-existing a11y services (TalkBack, etc.) by
     * appending our component to the colon-separated list. On success a brief
     * Toast confirms and the bridge finishes — the user can retap the ⌨ icon
     * and typing will route through the now-bound service. On error (port
     * 5555 closed, ADB pairing dialog declined, etc.) falls back to the
     * existing 5-intent Settings launcher.
     */
    private void tryAdbEnableA11y() {
        final String comp = getPackageName()
                + "/com.byd.dashcast.ime.ClusterImeWatcherService";
        // POSIX shell — works with `dadb` toybox/mksh on Android.
        // Note: settings(1) prints "null" (literal) when the row is unset.
        final String cmd =
                "COMP=" + comp + ";"
                + " CUR=$(settings get secure enabled_accessibility_services);"
                + " if [ -z \"$CUR\" ] || [ \"$CUR\" = \"null\" ]; then NEW=\"$COMP\";"
                + " else case \":$CUR:\" in *\":$COMP:\"*) NEW=\"$CUR\";;"
                + "             *) NEW=\"$CUR:$COMP\";; esac; fi;"
                + " settings put secure enabled_accessibility_services \"$NEW\""
                + " && settings put secure accessibility_enabled 1"
                + " && echo OK:$NEW || echo FAIL";
        AppLogger.i(TAG, "tryAdbEnableA11y: invoking local ADB settings put secure …");
        AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) {
                final String r = report == null ? "" : report.trim();
                final boolean ok = r.startsWith("OK");
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // 1.2.30 — the ADB callback fires on a worker thread well after
                        // the bridge activity may have been finished by the user.
                        if (isFinishing() || isDestroyed()) return;
                        if (ok) {
                            AppLogger.i(TAG, "tryAdbEnableA11y SUCCESS: " + r);
                            // v1.2.25 — Do NOT finish() the bridge here.
                            // Field log BYD_RE_Sniffer_20260523_170436.txt
                            // shows AccessibilityManagerService rebinds our
                            // ClusterImeWatcherService within ~30 ms of the
                            // `settings put` (line 23491 onServiceConnected
                            // arrives at 17:05:02.243, before the SUCCESS
                            // line at 17:05:02.272). The bridge already has
                            // input focus on its EditText and the IME is up
                            // — finishing here forced the user to tap ⌨ a
                            // second time before typing could actually route.
                            // Keep the bridge open; the next keystroke will
                            // hit a bound sInstance and route end-to-end.
                            android.widget.Toast.makeText(
                                    KeyboardBridgeActivity.this,
                                    R.string.keyboard_bridge_active_toast,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        } else {
                            AppLogger.w(TAG, "tryAdbEnableA11y unexpected reply: " + r
                                    + " — falling back to Settings UI");
                            promptAndOpenSettings();
                        }
                    }
                });
            }
            @Override public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // 1.2.30 — same guard as onSuccess: don't open Settings on
                        // top of a finished bridge activity.
                        if (isFinishing() || isDestroyed()) return;
                        AppLogger.w(TAG, "tryAdbEnableA11y ADB unavailable: " + error
                                + " — falling back to Settings UI");
                        promptAndOpenSettings();
                    }
                });
            }
        });
    }

    /** Fallback chain when ADB auto-enable is unavailable: localized Toast +
     * deferred Accessibility settings launcher. v1.2.23. */
    private void promptAndOpenSettings() {
        android.widget.Toast.makeText(this,
                getString(R.string.keyboard_bridge_a11y_required_toast),
                android.widget.Toast.LENGTH_LONG).show();
        if (mInput != null) {
            mInput.post(new Runnable() {
                @Override public void run() { openAccessibilitySettings(); }
            });
        } else {
            openAccessibilitySettings();
        }
    }

    /**
     * v1.2.22 — Best-effort launch of the system Accessibility settings.
     * Field log on DL5 (BYD ROM) showed that the canonical
     * {@code android.settings.ACCESSIBILITY_SETTINGS} action has NO resolver
     * (BYD's {@code com.byd.carsettings} does not declare that intent-filter);
     * instead it ships a BYD-specific action
     * {@code android.intent.action.BYD_ACCESSIBILITY} →
     * {@code com.byd.carsettings/com.byd.systemsettings.accessibility.AccessibilityMainActivity}
     * which is the same list of installed a11y services.
     *
     * Attempt order:
     * (1) BYD action {@code BYD_ACCESSIBILITY} (preferred on BYD ROMs),
     * (2) BYD explicit component (in case the action is stripped),
     * (3) canonical {@code Settings.ACTION_ACCESSIBILITY_SETTINGS} (AOSP),
     * (4) explicit AOSP component
     *     {@code com.android.settings/.Settings$AccessibilitySettingsActivity},
     * (5) generic {@code Settings.ACTION_SETTINGS} as a last resort. Each
     * attempt is gated by {@link android.content.pm.PackageManager#resolveActivity}
     * so we never call startActivity on an unresolvable Intent. Always
     * finishes the bridge after launch.
     */
    private void openAccessibilitySettings() {
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.Intent[] attempts = new android.content.Intent[] {
                new android.content.Intent("android.intent.action.BYD_ACCESSIBILITY")
                        .addCategory(android.content.Intent.CATEGORY_DEFAULT),
                new android.content.Intent().setComponent(new android.content.ComponentName(
                        "com.byd.carsettings",
                        "com.byd.systemsettings.accessibility.AccessibilityMainActivity")),
                new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                new android.content.Intent().setComponent(new android.content.ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings$AccessibilitySettingsActivity")),
                new android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
        };
        for (int i = 0; i < attempts.length; i++) {
            android.content.Intent it = attempts[i];
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                if (pm.resolveActivity(it, 0) == null) {
                    AppLogger.w(TAG, "openAccessibilitySettings attempt " + (i + 1)
                            + " unresolved: " + it);
                    continue;
                }
                startActivity(it);
                AppLogger.i(TAG, "openAccessibilitySettings attempt " + (i + 1)
                        + " launched: " + it);
                finish();
                return;
            } catch (Throwable t) {
                AppLogger.w(TAG, "openAccessibilitySettings attempt " + (i + 1)
                        + " failed: " + t.getClass().getSimpleName() + " "
                        + t.getMessage());
            }
        }
        AppLogger.e(TAG, "openAccessibilitySettings: all attempts failed — "
                + "user must navigate manually to Settings → Accessibility");
        finish();
    }

    /**
     * v1.2.18 — Returns true if the user has enabled our
     * {@link ClusterImeWatcherService} in Android Accessibility settings.
     * Checks the comma-separated {@code enabled_accessibility_services}
     * secure setting against our service's component name.
     */
    /**
     * v1.3.5 — Proactive background enable called by {@link ClusterService} on DL5
     * session start. Fire-and-forget, no UI. Safe to call repeatedly (idempotent:
     * the shell script checks whether the component is already listed).
     */
    public static void ensureClusterImeEnabled(android.content.Context ctx) {
        // Quick Java-side pre-check to skip the ADB round-trip when already enabled.
        try {
            String enabled = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null) {
                String myComp = ctx.getPackageName()
                        + "/" + ClusterImeWatcherService.class.getName();
                for (String s : enabled.split(":")) {
                    if (s != null && s.trim().equalsIgnoreCase(myComp)) return;
                }
            }
        } catch (Throwable ignored) { }
        final String comp = ctx.getPackageName()
                + "/com.byd.dashcast.ime.ClusterImeWatcherService";
        final String cmd =
                "COMP=" + comp + ";"
                + " CUR=$(settings get secure enabled_accessibility_services);"
                + " if [ -z \"$CUR\" ] || [ \"$CUR\" = \"null\" ]; then NEW=\"$COMP\";"
                + " else case \"$CUR\" in *\"|$COMP|\"*) NEW=\"$CUR\";;"
                + "             *) NEW=\"$CUR:$COMP\";; esac; fi;"
                + " settings put secure enabled_accessibility_services \"$NEW\""
                + " && settings put secure accessibility_enabled 1"
                + " && echo OK:$NEW || echo FAIL";
        AdbLocalClient.executeShellWithResult(ctx, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String r) {
                if (r != null && r.trim().startsWith("OK")) {
                    AppLogger.i(TAG, "ensureClusterImeEnabled proactive OK");
                } else {
                    AppLogger.w(TAG, "ensureClusterImeEnabled unexpected reply: " + r);
                }
            }
            @Override public void onError(String err) {
                AppLogger.w(TAG, "ensureClusterImeEnabled ADB error: " + err);
            }
        });
    }

    private boolean isClusterImeWatcherEnabled() {
        try {
            String enabled = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            String myComp = getPackageName() + "/" + ClusterImeWatcherService.class.getName();
            String myCompShort = getPackageName() + "/." + "ime.ClusterImeWatcherService";
            for (String s : enabled.split(":")) {
                if (s == null) continue;
                String t = s.trim();
                if (t.equalsIgnoreCase(myComp) || t.equalsIgnoreCase(myCompShort)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        sShowing = true;
    }

    @Override
    protected void onStop() {
        sShowing = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        sShowing = false;
        try {
            if (mImm != null && mInput != null) {
                mImm.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
            }
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TextWatcher → AccessibilityNodeInfo.ACTION_SET_TEXT on cluster
    // ─────────────────────────────────────────────────────────────────────────

    private final TextWatcher mWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void afterTextChanged(Editable s) { }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // v1.2.12 — forward the FULL current content of the local field as
            // the cluster editable's text. No per-character KeyEvent path, no
            // diff calculation — ACTION_SET_TEXT is atomic by design and
            // matches what the user sees in their local field.
            try {
                ClusterImeWatcherService.setTextOnCluster(s == null ? "" : s.toString());
            } catch (Throwable t) {
                AppLogger.e(TAG, "setTextOnCluster relay failed", t);
            }
        }
    };

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
