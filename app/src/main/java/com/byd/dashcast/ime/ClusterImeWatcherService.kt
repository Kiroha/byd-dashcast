package com.byd.dashcast.ime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.SparseArray
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.util.AppLogger

/**
 * v1.2.9 — Optional helper that auto-launches [KeyboardBridgeActivity] when an EditText (or any
 * editable view) on the *cluster* display receives focus.
 *
 * DL5 only:
 *  - On DL3 the system IME renders on the cluster natively, so this watcher is unnecessary —
 *    [onAccessibilityEvent] early-exits via [Platform.isDiLink5].
 *  - On DL5 the cluster lives on a 1×1 shadow framebuffer where the IME has nowhere to render.
 *    We detect the focus event here and launch the relay Activity on the main display.
 *
 * Defensive design — must never crash the host app:
 *  - All node retrievals are try/finally with `recycle()`.
 *  - De-bounce: at most one launch per [DEBOUNCE_MS].
 *  - If the bridge is already foreground, no relaunch.
 *  - Any uncaught exception is swallowed with a single log line; the AccessibilityService keeps
 *    running.
 *
 * User-facing UX: enabling this service is purely opt-in. The MainActivity surfaces a dismissible
 * banner on DL5 with a deep-link to `Settings.ACTION_ACCESSIBILITY_SETTINGS`. The ⌨ button stays
 * available as a manual fallback even if the user declines.
 */
class ClusterImeWatcherService : AccessibilityService() {

    @Volatile private var mLastLaunchAt = 0L
    private var mIsDiLink5 = false

    // v1.2.24 — Background worker for setTextOnCluster / performImeEnterOnCluster.
    // Field log BYD_RE_Sniffer_20260523_164557.txt showed an Input-dispatch ANR
    // (5000ms KeyEvent wait → process killed at 16:46:47) caused by running the
    // expensive a11y tree walk (`findClusterFocusedEditable() → getWindows() →
    // findFocus()`, all cross-display Binder calls) on the UI thread on every
    // keystroke. We now hop to a dedicated HandlerThread and coalesce rapid
    // text updates with a small debounce so a fast typist stays single-flight.
    private var mWorkerThread: HandlerThread? = null
    private var mWorker: Handler? = null

    /** Latest text submitted by [setTextOnCluster]. */
    private val mPendingText = ImePendingText()

    /**
     * The setter last posted to the worker, so a newer request can cancel it — AUD-007.
     *
     * There used to be one shared Runnable reading [mPendingText] at run time. That is the defect:
     * what reached the cluster was whatever the field held when the worker got round to it, not
     * what the user had typed when the request was made.
     */
    @Volatile private var mPostedSetText: Runnable? = null

    private val mRelaySession = ClusterImeRelaySession()
    private val mImeActionGate = ImeActionGate()

    /**
     * Applies [text] to the cluster's focused editable. Takes its text as a parameter — never from
     * a field — so a request cannot be overwritten between being made and being run.
     */
    private fun applyTextOnCluster(text: CharSequence?): Boolean = applyTextOnCluster(text, null)

    private fun applyTextOnCluster(
            text: CharSequence?, operation: ImeActionGate.Operation?): Boolean {
        if (text == null) return false
        val node = findBoundClusterFocusedEditable()
        if (node == null) {
            AppLogger.w(TAG, "setTextOnCluster no-op: no focused editable on cluster")
            return false
        }
        try {
            if (operation != null && !mImeActionGate.isCurrent(operation)) return false
            if (!mRelaySession.accepts(activeClusterDisplayId(), node.packageName)) {
                AppLogger.w(TAG, "setTextOnCluster no-op: relay session changed")
                return false
            }
            val args = Bundle()
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!ok) AppLogger.w(TAG, "setTextOnCluster ACTION_SET_TEXT refused")
            return ok
        } catch (t: Throwable) {
            AppLogger.e(TAG, "setTextOnCluster failed", t)
            return false
        } finally {
            try { recycleNode(node) } catch (ignored: Throwable) { }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            mIsDiLink5 = Platform.get().isDiLink5(this)
            AppLogger.i(TAG, "onCreate — DL5=$mIsDiLink5")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "onCreate Platform check failed", t)
            mIsDiLink5 = false
        }
        // v1.2.24 — Spin up the background worker once; reused for every
        // setText / performImeEnter dispatch.
        try {
            val thread = HandlerThread("cluster-ime-relay", Process.THREAD_PRIORITY_BACKGROUND)
            thread.start()
            mWorkerThread = thread
            mWorker = Handler(thread.looper)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "onCreate worker setup failed", t)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            // Refresh in case the user toggled DL5 mode after the service was created.
            mIsDiLink5 = Platform.get().isDiLink5(this)
            val info: AccessibilityServiceInfo? = serviceInfo
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                info.notificationTimeout = 100
                // v1.2.12 — required so getWindows() returns the cluster windows
                // (otherwise only the active head-unit window is visible).
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                serviceInfo = info
            }
            sInstance = this
            AppLogger.i(TAG, "onServiceConnected — watching cluster focus events (DL5=$mIsDiLink5)")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "onServiceConnected failed", t)
        }
    }

    override fun onDestroy() {
        try {
            if (sInstance === this) sInstance = null
        } catch (ignored: Throwable) { }
        mImeActionGate.cancelCurrent()
        // v1.2.24 — Tear down the worker so the HandlerThread does not leak
        // past service unbind (system may rebind us repeatedly).
        try {
            mWorker?.removeCallbacksAndMessages(null)
            mWorkerThread?.quitSafely()
        } catch (ignored: Throwable) { }
        mWorker = null
        mWorkerThread = null
        mPendingText.clear()
        mRelaySession.clear()
        super.onDestroy()
    }

    @SuppressLint("NewApi")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!mIsDiLink5 || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return

        // v1.2.9 — Hard gate: only react while the DashCast cluster projection is
        // actually live. Outside of that window the cluster display is dormant
        // and any focus event from a secondary display is none of our business
        // (other in-vehicle systems, external HDMI, etc.).
        val activeDisplayId = activeClusterDisplayId()
        if (activeDisplayId <= 0) return

        try {
            // Cluster filter: focus events from the head-unit display are
            // explicitly ignored — the system IME already works there.
            // API 30+ returns the real displayId; older API returns -1 which
            // we treat as "unknown → do not auto-trigger" (the manual ⌨
            // button remains available).
            val evDisplayId = try {
                event.displayId
            } catch (t: Throwable) {
                -1
            }
            if (evDisplayId != activeDisplayId) return

            // Inspect the source node — must be editable (EditText, search bar, ...)
            val src = event.source ?: return
            val targetPackage: String
            try {
                if (!isEditable(src)) return
                val sourcePackage = src.packageName ?: return
                targetPackage = sourcePackage.toString()
                // Never relaunch when the focus event is from OUR OWN bridge
                // (avoids an infinite popup loop if the user taps it).
                if (TextUtils.equals(targetPackage, packageName)) return
            } finally {
                recycleNode(src)
            }

            // De-bounce + foreground check
            val now = SystemClock.uptimeMillis()
            if (now - mLastLaunchAt < DEBOUNCE_MS) return
            if (KeyboardBridgeActivity.isShowing()) return
            mLastLaunchAt = now

            mRelaySession.bind(evDisplayId, targetPackage)
            launchBridge()
        } catch (t: Throwable) {
            // Never let an a11y crash kill the host app. Log once and move on.
            AppLogger.e(TAG, "onAccessibilityEvent swallowed", t)
        }
    }

    override fun onInterrupt() { /* no-op */ }

    private fun launchBridge() {
        val activeDisplayId = activeClusterDisplayId()
        if (sInstance !== this || !mRelaySession.hasTargetOn(activeDisplayId)) {
            mRelaySession.clear()
            AppLogger.d(TAG, "launchBridge ignored: relay target is no longer active")
            return
        }
        try {
            val i = Intent(this, KeyboardBridgeActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            i.putExtra(KeyboardBridgeActivity.EXTRA_AUTO_OPENED, true)
            startActivity(i)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "launchBridge failed", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Walk every interactive window across every display, prefer those on a non-default display
     * (the cluster on DL3/DL5) and SKIP our own bridge package (so we never overwrite the local
     * EditText instead of the cluster one), then return the first editable focused node. Caller
     * owns the returned node — must `recycle()` it.
     *
     * v1.2.25 — Field log `BYD_RE_Sniffer_20260523_170436.txt` showed the v1.2.24 worker logging
     * `setTextOnCluster no-op: no focused editable on cluster` on every keystroke:
     * [AccessibilityService.getWindows] returns only the windows on the *default* display, so the
     * cluster Yandex editable on display 2/3 was never reachable. We now use
     * [AccessibilityService.getWindowsOnAllDisplays] (API 30+, DL5 ships API 32) and fall back to
     * the single-display walk only on older API levels. We also explicitly exclude windows from
     * our own package because v1.2.25 keeps the bridge open after ADB success → the bridge's own
     * EditText would otherwise compete for `findFocus(FOCUS_INPUT)` and win on the default display.
     */
    private fun findBoundClusterFocusedEditable(): AccessibilityNodeInfo? {
        val activeDisplayId = activeClusterDisplayId()
        val expectedPackage = mRelaySession.packageOn(activeDisplayId) ?: return null
        return findFocusedEditableOnDisplay(activeDisplayId, expectedPackage)
    }

    @Suppress("DEPRECATION")
    private fun findFocusedEditableOnDisplay(
            targetDisplayId: Int, expectedPackage: String?): AccessibilityNodeInfo? {
        if (targetDisplayId <= 0) return null
        val selfPkg = packageName
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val all: SparseArray<List<AccessibilityWindowInfo>>? = try {
                    windowsOnAllDisplays
                } catch (t: Throwable) {
                    null
                }
                if (all != null) {
                    val wins = all.get(targetDisplayId) ?: return null
                    var found: AccessibilityNodeInfo? = null
                    for (w in wins) {
                        val candidate: AccessibilityNodeInfo?
                        try {
                            candidate = pickFocusedEditableFrom(w, selfPkg, expectedPackage)
                        } finally {
                            w?.recycle()
                        }
                        if (candidate == null) continue
                        if (found == null) found = candidate
                        else recycleNode(candidate)
                    }
                    return found
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "findClusterFocusedEditable scan failed", t)
        }
        return null
    }

    /**
     * v1.2.12 — Trigger the IME-enter action on the cluster focused editable
     * (Search / Send / Done depending on the field's `imeOptions`).
     * Falls back to a synthesized click on API < 30.
     */
    interface ImeActionCallback {
        fun onComplete(accepted: Boolean)
    }

    companion object {

        private const val TAG = "ClusterImeWatcher"
        private const val DEBOUNCE_MS = 600L

        /** Coalescing window for rapid keystrokes — last-writer-wins. */
        private const val SET_TEXT_DEBOUNCE_MS = 80L

        /** v1.2.12 — Live service instance for cross-process text relay
         *  ([setTextOnCluster] / [performImeEnterOnCluster]).
         *  Set in [onServiceConnected] / cleared in [onDestroy].
         *  Volatile because read from the bridge activity's UI thread. */
        @Volatile private var sInstance: ClusterImeWatcherService? = null

        private fun isEditable(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isEditable) return true
            // Some apps mark EditText but Accessibility flags don't propagate;
            // also check className as a fallback.
            val cn = node.className ?: return false
            val s = cn.toString()
            return s.endsWith("EditText") ||
                    s.endsWith("AppCompatEditText") ||
                    s.endsWith("TextInputEditText") ||
                    s.endsWith("SearchEditText") ||
                    s.endsWith("AutoCompleteTextView")
        }

        /** True if the user has enabled our a11y service in system Settings. */
        @JvmStatic
        fun isEnabled(ctx: Context?): Boolean {
            if (ctx == null) return false
            try {
                val enabled = Settings.Secure.getString(
                        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                if (TextUtils.isEmpty(enabled)) return false
                val self = ComponentName(ctx, ClusterImeWatcherService::class.java)
                val selfFlat = self.flattenToString()
                val selfShort = self.flattenToShortString()
                // The setting stores a colon-separated list of ComponentName.flattenToString().
                for (token in enabled.split(":")) {
                    val t = token.trim()
                    if (t == selfFlat || t == selfShort) return true
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "isEnabled check failed", t)
            }
            return false
        }

        /**
         * v1.2.12 — Push `text` as the full content of the currently-focused editable node bound
         * to the active cluster relay session. Returns `true` iff a node was found AND
         * [AccessibilityNodeInfo.performAction] accepted
         * [AccessibilityNodeInfo.ACTION_SET_TEXT].
         *
         * Unlike the legacy KeyEvent path this routes through the accessibility subsystem, which
         * is cross-display by construction (TalkBack does the same) — no `displayId` bookkeeping,
         * no shadow-framebuffer routing surprise.
         */
        @JvmStatic
        fun setTextOnCluster(text: CharSequence?): Boolean {
            val self = sInstance
            if (self == null) {
                // v1.2.18 — was silent. Field log BYD_RE_Sniffer_20260523_161033.txt
                // showed the user typing into the keyboard bridge with no trace from
                // this class at all because the a11y service was not enabled. Log it
                // once per call so the diagnostic is visible.
                AppLogger.w(TAG, "setTextOnCluster no-op: a11y service not bound "
                        + "(enable ‘DashCast Cluster IME’ in Settings → Accessibility)")
                return false
            }
            // v1.2.24 — Never run the a11y tree walk on the caller's thread.
            // Coalesce rapid keystrokes (last-writer-wins) and dispatch to the
            // background worker. We optimistically return true; failures are
            // logged inside applyTextOnCluster.
            val worker = self.mWorker
            if (worker == null) {
                AppLogger.w(TAG, "setTextOnCluster no-op: worker not ready")
                return false
            }
            if (!self.mRelaySession.hasTargetOn(activeClusterDisplayId())) {
                AppLogger.w(TAG, "setTextOnCluster no-op: projection target is no longer active")
                return false
            }
            val captured: CharSequence = text ?: ""
            self.mPendingText.set(captured)
            // AUD-007 — bind the text to the runnable instead of leaving it in a field the runnable
            // will re-read. Cancel only the setter WE posted; a stale handle is harmless.
            val previous = self.mPostedSetText
            if (previous != null) worker.removeCallbacks(previous)
            val posted = Runnable { self.applyTextOnCluster(captured) }
            self.mPostedSetText = posted
            worker.postDelayed(posted, SET_TEXT_DEBOUNCE_MS)
            return true
        }

        /**
         * Forgets any text typed but never validated — call when a bridge session ends.
         *
         * The accessibility service outlives the bridge by a long way: the service runs for as
         * long as it is enabled, the bridge is a transient dialog dismissed on Back or a tap
         * outside. So a draft survived its own session. Someone typed a street, changed their
         * mind, dismissed the bridge — and the next time the bridge opened on a cluster field
         * with an empty box, pressing Done to get rid of the keyboard replayed that street:
         * ACTION_SET_TEXT is a full atomic replace, so it overwrote whatever was in the cluster
         * field, and Enter routed the car to an address the driver had abandoned. Zero keystrokes
         * were needed, because the editor action fires on Done regardless of what the box
         * contains.
         *
         * The Enter path already clears the field after consuming it. This closes the other exit.
         */
        @JvmStatic
        fun clearPendingText() {
            val self = sInstance ?: return
            self.mImeActionGate.cancelCurrent()
            self.mPendingText.clear()
            self.mRelaySession.clear()
            val worker = self.mWorker
            val posted = self.mPostedSetText
            if (worker != null && posted != null) worker.removeCallbacks(posted)
            self.mPostedSetText = null
        }

        @JvmStatic
        fun performImeEnterOnCluster(callback: ImeActionCallback?) {
            val self = sInstance
            if (self == null) {
                AppLogger.w(TAG, "performImeEnterOnCluster no-op: a11y service not bound")
                completeImeAction(callback, false)
                return
            }
            // v1.2.24 — Hop to the worker so we never block the UI thread on the
            // a11y tree walk. Flush any pending setText first so Enter sees the
            // final string, then run the IME-Enter action.
            val worker = self.mWorker
            if (worker == null) {
                AppLogger.w(TAG, "performImeEnterOnCluster no-op: worker not ready")
                completeImeAction(callback, false)
                return
            }
            if (!self.mRelaySession.hasTargetOn(activeClusterDisplayId())) {
                AppLogger.w(TAG,
                        "performImeEnterOnCluster no-op: projection target is no longer active")
                completeImeAction(callback, false)
                return
            }
            val operation = self.mImeActionGate.begin { accepted ->
                completeImeAction(callback, accepted)
            }
            if (operation == null) {
                AppLogger.w(TAG, "performImeEnterOnCluster ignored: action already in flight")
                completeImeAction(callback, false)
                return
            }
            // AUD-007 — capture NOW, on the caller's thread, at the instant the user pressed Done.
            //
            // The old code cancelled the debounced setter and then, on the worker, re-read
            // mPendingText. Between those two moments KeyboardBridgeActivity clears its own input
            // field — housekeeping for the next session — and that clear is relayed here as
            // setTextOnCluster(""). So the flush ran with an empty string: the destination the user
            // had typed was wiped and the Enter went out on an empty field. The keyboard bridge's
            // one job, typing a destination and validating it, returned nothing.
            val pendingText = self.mPendingText.snapshot()
            val flush = pendingText.text
            val pendingSetter = self.mPostedSetText
            if (pendingSetter != null) {
                worker.removeCallbacks(pendingSetter)
                self.mPostedSetText = null
            }
            val posted: Boolean
            try {
                posted = worker.post {
                    // Flush the text as it was when Done was pressed.
                    if (flush != null) {
                        try {
                            if (!self.applyTextOnCluster(flush, operation)) {
                                self.mImeActionGate.finish(operation, false)
                                return@post
                            }
                        } catch (ignored: Throwable) {
                            self.mImeActionGate.finish(operation, false)
                            return@post
                        }
                    }
                    if (!self.mImeActionGate.isCurrent(operation)) return@post
                    val node = self.findBoundClusterFocusedEditable()
                    if (node == null) {
                        AppLogger.w(TAG,
                                "performImeEnterOnCluster no-op: no focused editable on cluster")
                        self.mImeActionGate.finish(operation, false)
                        return@post
                    }
                    var accepted = false
                    try {
                        val actionId = if (Build.VERSION.SDK_INT >= 30) {
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        } else {
                            AccessibilityNodeInfo.ACTION_CLICK
                        }
                        if (self.mImeActionGate.isCurrent(operation)
                                && self.mRelaySession.accepts(
                                        activeClusterDisplayId(), node.packageName)) {
                            accepted = node.performAction(actionId)
                        }
                        if (!accepted) AppLogger.w(TAG, "performImeEnterOnCluster action refused")
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "performImeEnterOnCluster failed", t)
                    } finally {
                        try { recycleNode(node) } catch (ignored: Throwable) { }
                    }
                    if (accepted) self.mPendingText.clearIfCurrent(pendingText.generation)
                    self.mImeActionGate.finish(operation, accepted)
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "performImeEnterOnCluster dispatch failed", t)
                self.mImeActionGate.finish(operation, false)
                return
            }
            if (!posted) self.mImeActionGate.finish(operation, false)
        }

        private fun completeImeAction(callback: ImeActionCallback?, accepted: Boolean) {
            if (callback == null) return
            try {
                callback.onComplete(accepted)
            } catch (t: Throwable) {
                AppLogger.e(TAG, "IME completion callback failed", t)
            }
        }

        /**
         * v1.3.3 — Touch-driven keyboard auto-trigger. Called by MainActivity's
         * `forwardTouchFromMirror` after an ACTION_UP is forwarded to the cluster mirror (350 ms
         * delay to let the cluster app process the tap and move input focus). Posts the
         * editable-detection work to the background worker so `getWindowsOnAllDisplays()` is never
         * called on the UI thread. No-op when the bridge is already showing, the service is not
         * bound, or the debounce window has not elapsed.
         *
         * This complements the existing event-driven path (TYPE_VIEW_FOCUSED from the
         * accessibility subsystem). Both paths share the same debounce gate so they cannot
         * double-launch the bridge.
         */
        @JvmStatic
        fun checkAndLaunchBridgeIfNeeded(ctx: Context?) {
            val self = sInstance ?: return                     // service not enabled
            if (KeyboardBridgeActivity.isShowing()) return      // already open
            val now = SystemClock.uptimeMillis()
            if (now - self.mLastLaunchAt < DEBOUNCE_MS) return  // debounce
            val worker = self.mWorker ?: return
            val activeDisplayId = activeClusterDisplayId()
            if (activeDisplayId <= 0) return
            val svc = self
            worker.post {
                if (sInstance !== svc || activeClusterDisplayId() != activeDisplayId) return@post
                if (KeyboardBridgeActivity.isShowing()) return@post
                val node = svc.findFocusedEditableOnDisplay(activeDisplayId, null) ?: return@post
                try {
                    if (sInstance !== svc || activeClusterDisplayId() != activeDisplayId) return@post
                    val candidatePackage = node.packageName ?: return@post
                    svc.mRelaySession.bind(activeDisplayId, candidatePackage.toString())
                } finally {
                    try { recycleNode(node) } catch (ignored: Throwable) { }
                }
                svc.mLastLaunchAt = SystemClock.uptimeMillis()
                AppLogger.d(TAG, "checkAndLaunchBridgeIfNeeded — cluster editable detected after touch, launching bridge")
                svc.launchBridge()
            }
        }

        /**
         * Manual counterpart to the focus/touch launch paths. Resolves and binds the currently
         * focused cluster editable before opening the bridge, so a session cleared by the previous
         * bridge close is not left permanently unbound. Returns false when the service cannot
         * perform the lookup; callers may then open the bridge directly to preserve its
         * accessibility-settings fallback.
         */
        @JvmStatic
        fun prepareAndLaunchBridgeManually(): Boolean {
            val self = sInstance
            val worker = self?.mWorker ?: return false
            val activeDisplayId = activeClusterDisplayId()
            if (activeDisplayId <= 0) return false
            return worker.post {
                var node: AccessibilityNodeInfo? = null
                try {
                    node = self.findFocusedEditableOnDisplay(activeDisplayId, null)
                    val targetPackage = node?.packageName
                    if (targetPackage == null || targetPackage.isEmpty()) {
                        self.mRelaySession.clear()
                        AppLogger.w(TAG, "manual bridge launch: no focused cluster editable")
                    } else {
                        self.mRelaySession.bind(activeDisplayId, targetPackage.toString())
                    }
                } catch (t: Throwable) {
                    self.mRelaySession.clear()
                    AppLogger.e(TAG, "manual bridge target lookup failed", t)
                } finally {
                    val n = node
                    if (n != null) {
                        try { recycleNode(n) } catch (ignored: Throwable) { }
                    }
                }
                self.mLastLaunchAt = SystemClock.uptimeMillis()
                self.launchBridge()
            }
        }

        /** Helper for [findBoundClusterFocusedEditable] — returns the window's focused editable
         *  node (caller owns + must recycle) iff it exists and is NOT from our own bridge package;
         *  null otherwise. Always recycles the window root internally. */
        @Suppress("DEPRECATION")
        private fun pickFocusedEditableFrom(
                w: AccessibilityWindowInfo?, selfPkg: String,
                expectedPackage: String?): AccessibilityNodeInfo? {
            if (w == null) return null
            val root = w.root ?: return null
            var focused: AccessibilityNodeInfo? = null
            try {
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (ignored: Throwable) { }
            try {
                val node = focused ?: return null
                if (!node.isEditable) { recycleNode(node); return null }
                // v1.2.25 — Skip our own bridge EditText so we never push the
                // user's keystrokes back into the local field instead of the
                // cluster Yandex search.
                val pkg = node.packageName
                if (pkg != null && selfPkg.contentEquals(pkg)) {
                    recycleNode(node)
                    return null
                }
                if (expectedPackage != null
                        && (pkg == null || !expectedPackage.contentEquals(pkg))) {
                    recycleNode(node)
                    return null
                }
                return node
            } finally {
                recycleNode(root)
            }
        }

        @Suppress("DEPRECATION")
        private fun recycleNode(node: AccessibilityNodeInfo) {
            node.recycle()
        }

        private fun activeClusterDisplayId(): Int {
            val service = ClusterService.getInstance()
            if (service == null || !service.isProjectionActive()) return -1
            val displayId = service.displayId
            return if (displayId > 0) displayId else -1
        }
    }
}
