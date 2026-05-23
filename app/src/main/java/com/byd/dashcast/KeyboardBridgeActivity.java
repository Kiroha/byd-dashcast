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

        setContentView(mInput,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
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
