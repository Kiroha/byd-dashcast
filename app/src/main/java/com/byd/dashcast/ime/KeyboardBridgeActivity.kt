package com.byd.dashcast.ime

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

import com.byd.dashcast.R
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.util.AppLogger

import java.util.regex.Pattern

/**
 * v1.2.8 — Keyboard Bridge: workaround for the DL5 limitation where the system IME cannot render
 * natively on the cluster display.
 *
 * v1.2.12 — Forwarding pivot. The original v1.2.8 design injected synthetic [KeyEvent]s through
 * `SurfaceDaemon.injectKey()` routed via `KeyEvent.setDisplayId(clusterId)`. Field testing showed
 * two problems:
 *  - v1.2.11 made the EditText 1×1 to remove visual chrome — but at that size the IMM logged
 *    `"Ignoring showSoftInput() … is not served"`, no InputConnection was ever established, the
 *    [TextWatcher] never fired and nothing reached the daemon (zero `injectKey FIRST OK` traces in
 *    the captured logs).
 *  - Even if v1.2.11 had been "served", the focus + window of cluster apps live on the 1×1 shadow
 *    framebuffer (`mDisplayId=3`) while only the composed face is on `displayId=2`. Routing keys
 *    with `setDisplayId(2)` is not symmetric to the touch path and was never empirically validated.
 *
 * The new pivot uses the accessibility subsystem instead. The bridge hosts a normally-sized
 * (220 dp × 48 dp, transparent) EditText so the IMM serves it → IME pops up on the head unit →
 * [TextWatcher] fires per character → we forward the full content via
 * [ClusterImeWatcherService.setTextOnCluster] which calls
 * `AccessibilityNodeInfo.ACTION_SET_TEXT` on the cluster-focused editable node.
 * `IME_ACTION_DONE` → Search/Send → [ClusterImeWatcherService.performImeEnterOnCluster] fires
 * `ACTION_IME_ENTER`.
 *
 * The accessibility path is cross-display by construction (TalkBack uses the same mechanism) and
 * is immune to per-display IME isolation or compositor / framebuffer asymmetry.
 */
class KeyboardBridgeActivity : Activity() {

    /** v1.2.21 — guard so we only prompt the user to enable a11y once
     *  per Activity instance (onWindowFocusChanged fires repeatedly). */
    private var mPromptedA11y = false

    private var mInput: EditText? = null

    /** AUD-007 — true while this activity edits its own field; such edits are not relayed. */
    private var mSuppressRelay = false
    private var mInputGeneration = 0L
    private var mImeActionInFlight = false
    private var mImeActionGeneration = 0L
    private var mImm: InputMethodManager? = null

    // ─────────────────────────────────────────────────────────────────────────
    // TextWatcher → AccessibilityNodeInfo.ACTION_SET_TEXT on cluster
    // ─────────────────────────────────────────────────────────────────────────

    private val mWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
        override fun afterTextChanged(s: Editable?) { }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            // v1.2.12 — forward the FULL current content of the local field as
            // the cluster editable's text. No per-character KeyEvent path, no
            // diff calculation — ACTION_SET_TEXT is atomic by design and
            // matches what the user sees in their local field.
            // AUD-007 — a change this activity made to its own field is not a change the user
            // made, and must not travel to the cluster.
            if (mSuppressRelay) return
            mInputGeneration++
            try {
                ClusterImeWatcherService.setTextOnCluster(s?.toString() ?: "")
            } catch (t: Throwable) {
                AppLogger.e(TAG, "setTextOnCluster relay failed", t)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        val w = window
        val wPx = dp(220)
        val hPx = dp(48)
        if (w != null) {
            w.setGravity(Gravity.BOTTOM or Gravity.END)
            w.setLayout(wPx, hPx)
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setDimAmount(0f)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }

        mImm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?

        val input = EditText(this)
        mInput = input
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.setSingleLine(true)
        input.addTextChangedListener(mWatcher)
        input.setOnEditorActionListener(object : TextView.OnEditorActionListener {
            override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_SEND) {
                    if (mImeActionInFlight) return true
                    mImeActionInFlight = true
                    val actionGeneration = ++mImeActionGeneration
                    val submitted = mInput?.text?.toString() ?: ""
                    val submittedGeneration = mInputGeneration
                    try {
                        ClusterImeWatcherService.performImeEnterOnCluster(
                                object : ClusterImeWatcherService.ImeActionCallback {
                            override fun onComplete(accepted: Boolean) {
                                runOnUiThread {
                                    if (actionGeneration != mImeActionGeneration) return@runOnUiThread
                                    mImeActionInFlight = false
                                    // Read the field ONCE. The Java compared against
                                    // mInput.getText() after null-checking it; a Kotlin
                                    // CharSequence?.contentEquals would answer TRUE for two
                                    // nulls, which would turn this fail-closed guard open.
                                    val current = mInput?.text
                                    if (!accepted || isFinishing || isDestroyed
                                            || current == null
                                            || submittedGeneration != mInputGeneration
                                            || !submitted.contentEquals(current)) {
                                        return@runOnUiThread
                                    }
                                    mSuppressRelay = true
                                    try { mInput?.setText("") } catch (ignored: Throwable) { }
                                    finally { mSuppressRelay = false }
                                }
                            }
                        })
                    } catch (t: Throwable) {
                        if (actionGeneration == mImeActionGeneration) {
                            mImeActionInFlight = false
                        }
                        AppLogger.e(TAG, "performImeEnterOnCluster failed", t)
                    }
                    return true
                }
                return false
            }
        })
        // Visually neutral but still served by the IMM (non-zero size, no chrome).
        input.background = null
        input.setTextColor(0x00000000)
        input.setHintTextColor(0x00000000)
        input.isCursorVisible = false
        input.setPadding(0, 0, 0, 0)
        input.minWidth = wPx
        input.minHeight = hPx

        // v1.2.17 — Field log BYD_RE_Sniffer_20260523_153544.txt showed the
        // EditText laid out at 0×37 px with the new floating Dialog theme.
        // The Dialog wraps to its content, the empty EditText wraps to 0
        // width, and IMM rejects width-0 views as "not served". Force the
        // EditText to wPx × hPx explicitly via its own LayoutParams instead
        // of MATCH_PARENT — the floating Dialog window then wraps around
        // those concrete pixels, IMM sees a real bounding box and serves it.
        setContentView(input, ViewGroup.LayoutParams(wPx, hPx))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // v1.2.12 — wait for the window to actually have focus before requesting
        // the IME. Posting from onCreate raced with the IMM's startInput which
        // is what produced the "is not served" warning in v1.2.11.
        val input = mInput
        val imm = mImm
        if (!hasFocus || input == null || imm == null) return
        try {
            input.requestFocus()
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "onWindowFocusChanged showSoftInput failed", t)
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
                mPromptedA11y = true
                AppLogger.w(TAG, "ClusterImeWatcherService is NOT enabled — "
                        + "text typed in the bridge will not reach the cluster. "
                        + "Trying ADB auto-enable, with Accessibility settings as fallback.")
                // v1.2.23 — Try to flip the secure flag via the local-ADB
                // shell (uid=shell can `settings put secure
                // enabled_accessibility_services`). If that works we never
                // need to bother the user with the Settings UI at all.
                tryAdbEnableA11y()
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "a11y enablement check failed", t)
        }
    }

    /**
     * v1.2.23 — Attempt to enable [ClusterImeWatcherService] headlessly via local ADB
     * ([AdbLocalClient], uid=shell). The `settings` binary running as uid 2000 can write
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (which third-party apps cannot — see
     * Android source `SettingsProvider#assertWritePermissionsForSecureSettings`). Shell preserves
     * any pre-existing a11y services (TalkBack, etc.) by appending our component to the
     * colon-separated list. On success a brief Toast confirms and the bridge finishes — the user
     * can retap the ⌨ icon and typing will route through the now-bound service. On error (port
     * 5555 closed, ADB pairing dialog declined, etc.) falls back to the existing 5-intent Settings
     * launcher.
     */
    private fun tryAdbEnableA11y() {
        AppLogger.i(TAG, "tryAdbEnableA11y: invoking local ADB settings put secure …")
        ShellGateway.execShellWithResult(this, a11yEnableCommand(packageName),
                object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                val r = out?.trim() ?: ""
                val ok = r.startsWith("OK")
                runOnUiThread {
                    // 1.2.30 — the ADB callback fires on a worker thread well after
                    // the bridge activity may have been finished by the user.
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (ok) {
                        AppLogger.i(TAG, "tryAdbEnableA11y SUCCESS: $r")
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
                        Toast.makeText(this@KeyboardBridgeActivity,
                                R.string.keyboard_bridge_active_toast,
                                Toast.LENGTH_SHORT).show()
                    } else {
                        AppLogger.w(TAG, "tryAdbEnableA11y unexpected reply: $r"
                                + " — falling back to Settings UI")
                        promptAndOpenSettings()
                    }
                }
            }
            override fun onError(err: String?) {
                runOnUiThread {
                    // 1.2.30 — same guard as onSuccess: don't open Settings on
                    // top of a finished bridge activity.
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    AppLogger.w(TAG, "tryAdbEnableA11y ADB unavailable: $err"
                            + " — falling back to Settings UI")
                    promptAndOpenSettings()
                }
            }
        })
    }

    /** Fallback chain when ADB auto-enable is unavailable: localized Toast +
     * deferred Accessibility settings launcher. v1.2.23. */
    private fun promptAndOpenSettings() {
        Toast.makeText(this,
                getString(R.string.keyboard_bridge_a11y_required_toast),
                Toast.LENGTH_LONG).show()
        val input = mInput
        if (input != null) {
            input.post { openAccessibilitySettings() }
        } else {
            openAccessibilitySettings()
        }
    }

    /**
     * v1.2.22 — Best-effort launch of the system Accessibility settings.
     * Field log on DL5 (BYD ROM) showed that the canonical
     * `android.settings.ACCESSIBILITY_SETTINGS` action has NO resolver (BYD's
     * `com.byd.carsettings` does not declare that intent-filter); instead it ships a BYD-specific
     * action `android.intent.action.BYD_ACCESSIBILITY` →
     * `com.byd.carsettings/com.byd.systemsettings.accessibility.AccessibilityMainActivity`
     * which is the same list of installed a11y services.
     *
     * Attempt order:
     * (1) BYD action `BYD_ACCESSIBILITY` (preferred on BYD ROMs),
     * (2) BYD explicit component (in case the action is stripped),
     * (3) canonical `Settings.ACTION_ACCESSIBILITY_SETTINGS` (AOSP),
     * (4) explicit AOSP component `com.android.settings/.Settings$AccessibilitySettingsActivity`,
     * (5) generic `Settings.ACTION_SETTINGS` as a last resort. Each attempt is gated by
     * `PackageManager.resolveActivity` so we never call startActivity on an unresolvable Intent.
     * Always finishes the bridge after launch.
     */
    @Suppress("DEPRECATION")
    private fun openAccessibilitySettings() {
        val pm = packageManager
        val attempts = arrayOf(
                Intent("android.intent.action.BYD_ACCESSIBILITY")
                        .addCategory(Intent.CATEGORY_DEFAULT),
                Intent().setComponent(ComponentName(
                        "com.byd.carsettings",
                        "com.byd.systemsettings.accessibility.AccessibilityMainActivity")),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                Intent().setComponent(ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings\$AccessibilitySettingsActivity")),
                Intent(Settings.ACTION_SETTINGS)
        )
        for (i in attempts.indices) {
            val it = attempts[i]
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                if (pm.resolveActivity(it, 0) == null) {
                    AppLogger.w(TAG, "openAccessibilitySettings attempt " + (i + 1)
                            + " unresolved: " + it)
                    continue
                }
                startActivity(it)
                AppLogger.i(TAG, "openAccessibilitySettings attempt " + (i + 1)
                        + " launched: " + it)
                finish()
                return
            } catch (t: Throwable) {
                AppLogger.w(TAG, "openAccessibilitySettings attempt " + (i + 1)
                        + " failed: " + t.javaClass.simpleName + " "
                        + t.message)
            }
        }
        AppLogger.e(TAG, "openAccessibilitySettings: all attempts failed — "
                + "user must navigate manually to Settings → Accessibility")
        finish()
    }

    /**
     * v1.2.18 — Returns true if the user has enabled our [ClusterImeWatcherService] in Android
     * Accessibility settings. Checks the colon-separated `enabled_accessibility_services` secure
     * setting against our service's component name.
     */
    private fun isClusterImeWatcherEnabled(): Boolean {
        try {
            val enabled = Settings.Secure.getString(
                    contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (enabled == null || enabled.isEmpty()) return false
            val myComp = packageName + "/" + ClusterImeWatcherService::class.java.name
            val myCompShort = "$packageName/.ime.ClusterImeWatcherService"
            for (s in P_COLON.split(enabled)) {
                if (s == null) continue
                val t = s.trim()
                if (t.equals(myComp, ignoreCase = true)
                        || t.equals(myCompShort, ignoreCase = true)) return true
            }
        } catch (ignored: Throwable) { }
        return false
    }

    override fun onStart() {
        super.onStart()
        sShowing = true
    }

    override fun onStop() {
        sShowing = false
        endSession()
        super.onStop()
    }

    override fun onDestroy() {
        sShowing = false
        endSession()
        try {
            val imm = mImm
            val input = mInput
            if (imm != null && input != null) {
                imm.hideSoftInputFromWindow(input.windowToken, 0)
            }
        } catch (ignored: Exception) { }
        super.onDestroy()
    }

    /**
     * Discard anything typed in this session but never validated.
     *
     * AUD-007 put this in `onDestroy` only, and that is not where the session ends. This activity
     * is `launchMode="singleTask"` (AndroidManifest.xml:175), so re-opening the bridge reuses the
     * existing instance: the lifecycle is onStop then onStart, and onDestroy never runs. The
     * abandoned draft therefore survived exactly the case the fix was written for — leave the
     * keyboard without validating, come back later, press Done, and the previous session's
     * destination is what leaves for the cluster. In a car that means being routed to an address
     * the driver had given up on, with zero keystrokes.
     *
     * Both the relay's pending text and the local field are cleared, the latter under
     * `mSuppressRelay` so the TextWatcher does not forward the housekeeping clear to the cluster
     * as `setTextOnCluster("")` — the other half of AUD-007, and the same trap.
     *
     * Idempotent: onStop is followed by onDestroy on a real teardown, and clearing twice costs
     * nothing.
     */
    private fun endSession() {
        mImeActionGeneration++
        mImeActionInFlight = false
        try { ClusterImeWatcherService.clearPendingText() } catch (ignored: Throwable) { }
        if (mInput != null) {
            mSuppressRelay = true
            try { mInput?.setText("") } catch (ignored: Throwable) { }
            finally { mSuppressRelay = false }
        }
    }

    private fun dp(v: Int): Int =
            TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {

        private const val TAG = "KeyboardBridge"
        private val P_COLON: Pattern = Pattern.compile(":")

        /** v1.2.9 — set by the AccessibilityService when the bridge was auto-opened
         *  on a cluster EditText focus. Currently informational only (used for logs). */
        const val EXTRA_AUTO_OPENED = "auto_opened"

        /** v1.2.9 — tracked so the a11y watcher does not re-launch us if we are
         *  already on screen. Volatile because read from the a11y thread. */
        @Volatile private var sShowing = false

        @JvmStatic
        fun isShowing(): Boolean = sShowing

        /**
         * The a11y-enable script, built once for both callers.
         *
         * It was duplicated verbatim in tryAdbEnableA11y and ensureClusterImeEnabled (verified
         * character-identical before this port). Two copies of a shell script that must stay in
         * step is the kind of invariant that only prose was holding, so there is one now.
         *
         * POSIX shell — works with `dadb` toybox/mksh on Android.
         * Note: settings(1) prints "null" (literal) when the row is unset.
         * Every `$` below is escaped: an unescaped one is Kotlin string interpolation and would
         * silently compile a DIFFERENT command with no error anywhere.
         */
        private fun a11yEnableCommand(pkg: String): String {
            val comp = "$pkg/com.byd.dashcast.ime.ClusterImeWatcherService"
            return "COMP=" + comp + ";" +
                    " CUR=\$(settings get secure enabled_accessibility_services);" +
                    " if [ -z \"\$CUR\" ] || [ \"\$CUR\" = \"null\" ]; then NEW=\"\$COMP\";" +
                    " else case \":\$CUR:\" in *\":\$COMP:\"*) NEW=\"\$CUR\";;" +
                    "             *) NEW=\"\$CUR:\$COMP\";; esac; fi;" +
                    " settings put secure enabled_accessibility_services \"\$NEW\"" +
                    " && settings put secure accessibility_enabled 1" +
                    " && echo OK:\$NEW || echo FAIL"
        }

        /**
         * v1.3.5 — Proactive background enable called by `ClusterService` on DL5 session start.
         * Fire-and-forget, no UI. Safe to call repeatedly (idempotent: the shell script checks
         * whether the component is already listed).
         */
        @JvmStatic
        fun ensureClusterImeEnabled(ctx: Context) {
            // Quick Java-side pre-check to skip the ADB round-trip when already enabled.
            try {
                val enabled = Settings.Secure.getString(
                        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                if (enabled != null) {
                    val myComp = ctx.packageName + "/" + ClusterImeWatcherService::class.java.name
                    for (s in P_COLON.split(enabled)) {
                        if (s != null && s.trim().equals(myComp, ignoreCase = true)) return
                    }
                }
            } catch (ignored: Throwable) { }
            ShellGateway.execShellWithResult(ctx, a11yEnableCommand(ctx.packageName),
                    object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    if (out != null && out.trim().startsWith("OK")) {
                        AppLogger.i(TAG, "ensureClusterImeEnabled proactive OK")
                    } else {
                        AppLogger.w(TAG, "ensureClusterImeEnabled unexpected reply: $out")
                    }
                }
                override fun onError(err: String?) {
                    AppLogger.w(TAG, "ensureClusterImeEnabled ADB error: $err")
                }
            })
        }
    }
}
