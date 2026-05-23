package com.byd.dashcast;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.byd.dashcast.dashboard.ClusterInputForwarder;

/**
 * v1.2.8 — Keyboard Bridge: workaround for the DL5 limitation where the system
 * IME cannot render on the cluster display (its window is a Presentation on a
 * 1×1 shadow framebuffer, layerStack 3/4 composed into a 1920×720 virtual
 * output that has no DisplayManager entry).
 *
 * <p>Pattern:
 * <ol>
 *   <li>User taps the ⌨ button in the mirror toolbar (or anywhere else)</li>
 *   <li>This Activity launches on the main 15.6" head-unit display</li>
 *   <li>An EditText auto-focuses → the system IME pops up normally on the
 *       head unit</li>
 *   <li>{@link TextWatcher} converts each typed char to a {@link KeyEvent}
 *       sequence via {@link KeyCharacterMap#getEvents(char[])} (handles
 *       SHIFT/ALT meta for uppercase &amp; symbols)</li>
 *   <li>Each {@link KeyEvent} is forwarded to the cluster window via
 *       {@link ClusterInputForwarder#injectKeyEvent(KeyEvent)} which routes
 *       through the existing MirrorDaemon (uid=2000) Binder path</li>
 * </ol>
 *
 * <p>The activity is purely a relay — it does not display the captured text
 * (avoids confusion: the user types on the cluster app and sees the result
 * there). The local EditText is kept tiny &amp; transparent.
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
    private CharSequence       mLastText = "";
    private KeyCharacterMap    mKcm;
    private InputMethodManager mImm;
    private boolean            mSuppressWatcher = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Floating dialog at the top so the on-screen keyboard occupies the
        // bottom half without our window covering it.
        Window w = getWindow();
        if (w != null) {
            w.setGravity(Gravity.TOP);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            // Ask the system to NOT dim the background — the user must still
            // see the cluster mirror live to confirm characters arrive.
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setDimAmount(0f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        mKcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        mImm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xCC202020); // semi-opaque dark, MD-ish

        TextView title = new TextView(this);
        title.setText(getString(R.string.keyboard_bridge_title));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(14f);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.keyboard_bridge_hint));
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(12f);
        hint.setPadding(0, dp(4), 0, dp(8));
        root.addView(hint);

        mInput = new EditText(this);
        mInput.setHint(getString(R.string.keyboard_bridge_input_hint));
        mInput.setTextColor(0xFFFFFFFF);
        mInput.setHintTextColor(0x80FFFFFF);
        mInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mInput.setSingleLine(false);
        mInput.setMaxLines(3);
        mInput.addTextChangedListener(mWatcher);
        mInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_SEND) {
                    forwardKeyCode(KeyEvent.KEYCODE_ENTER);
                    return true;
                }
                return false;
            }
        });
        root.addView(mInput);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, dp(8), 0, 0);

        Button btnEnter = new Button(this);
        btnEnter.setText(getString(R.string.keyboard_bridge_btn_enter));
        btnEnter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { forwardKeyCode(KeyEvent.KEYCODE_ENTER); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(4));
        buttonRow.addView(btnEnter, lp);

        Button btnBack = new Button(this);
        btnBack.setText(getString(R.string.keyboard_bridge_btn_back));
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { forwardKeyCode(KeyEvent.KEYCODE_BACK); }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMarginEnd(dp(4));
        buttonRow.addView(btnBack, lp2);

        Button btnClose = new Button(this);
        btnClose.setText(getString(R.string.keyboard_bridge_btn_close));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        buttonRow.addView(btnClose,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttonRow);

        setContentView(root);

        mInput.requestFocus();
        // Ensure the IME pops up reliably on entry.
        mInput.post(new Runnable() {
            @Override public void run() {
                if (mImm != null) mImm.showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
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
    // TextWatcher → KeyEvent translation
    // ─────────────────────────────────────────────────────────────────────────

    private final TextWatcher mWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void afterTextChanged(Editable s) { }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (mSuppressWatcher) return;
            try {
                // Diff against previous content.
                //   before > 0 && count == 0  → pure deletion of `before` chars at `start`
                //   count  > 0 && before == 0 → pure insertion of `count` chars at `start`
                //   else                      → replacement (delete `before`, insert `count`)
                if (before > 0) {
                    for (int i = 0; i < before; i++) {
                        forwardKeyCode(KeyEvent.KEYCODE_DEL);
                    }
                }
                if (count > 0) {
                    char[] added = new char[count];
                    for (int i = 0; i < count; i++) added[i] = s.charAt(start + i);
                    KeyEvent[] events = (mKcm != null) ? mKcm.getEvents(added) : null;
                    if (events != null) {
                        for (KeyEvent ev : events) {
                            forwardKeyEvent(ev);
                        }
                    } else {
                        // Fallback: char-by-char as plain ASCII when KCM cannot map.
                        for (char c : added) {
                            int kc = mapAsciiToKeyCode(c);
                            if (kc != KeyEvent.KEYCODE_UNKNOWN) forwardKeyCode(kc);
                        }
                    }
                }
            } catch (Exception e) {
                AppLogger.e(TAG, "TextWatcher forward failed", e);
            } finally {
                mLastText = s.toString();
            }

            // Keep our local EditText short so the diff window doesn't grow unboundedly.
            // After 80 chars we silently reset, leaving the cluster app unaffected.
            if (s.length() > 80) {
                mSuppressWatcher = true;
                try { mInput.setText(""); } catch (Exception ignored) { }
                mLastText = "";
                mSuppressWatcher = false;
            }
        }
    };

    private void forwardKeyCode(int keyCode) {
        long now = SystemClock.uptimeMillis();
        forwardKeyEvent(new KeyEvent(now, now,     KeyEvent.ACTION_DOWN, keyCode, 0));
        forwardKeyEvent(new KeyEvent(now, now + 1, KeyEvent.ACTION_UP,   keyCode, 0));
    }

    private void forwardKeyEvent(KeyEvent ev) {
        if (ev == null) return;
        ClusterService svc = ClusterService.getInstance();
        if (svc == null) return;
        ClusterInputForwarder fwd = svc.getInputForwarder();
        if (fwd == null) return;
        fwd.injectKeyEvent(ev);
    }

    private static int mapAsciiToKeyCode(char c) {
        if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
        if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
        if (c >= 'A' && c <= 'Z') return KeyEvent.KEYCODE_A + (c - 'A');
        switch (c) {
            case ' ':  return KeyEvent.KEYCODE_SPACE;
            case '\n': return KeyEvent.KEYCODE_ENTER;
            case '\t': return KeyEvent.KEYCODE_TAB;
            case '.':  return KeyEvent.KEYCODE_PERIOD;
            case ',':  return KeyEvent.KEYCODE_COMMA;
            case '-':  return KeyEvent.KEYCODE_MINUS;
            case '/':  return KeyEvent.KEYCODE_SLASH;
            default:   return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
