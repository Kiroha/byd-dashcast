package com.byd.dashcast.report

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.byd.dashcast.R
import com.byd.dashcast.util.AppLogger

/**
 * Whether this driver has agreed to let diagnostics leave their car.
 *
 * The app collects a lot: an application journal, two logcat passes, ~20 dumpsys sections, and —
 * when the recorder is on — screenshots of the cluster and of the main screen, which can show a
 * navigation map with a destination on it. None of that is unusual for a diagnostic tool, and all
 * of it is useless to the owner unless it can be sent. What is not acceptable is sending it
 * without having said, once, in a language they read, what it contains.
 *
 * ## Where the gate is
 *
 * Not in the seven screens that can start an upload. In the transport: [TelegramBugReporter] and
 * [AzureBlobUploader] report themselves unconfigured until consent is granted. Every one of those
 * seven call sites already has an else-branch for "no channel configured" — a share sheet, a local
 * save, a message naming the file on disk — and those branches are shipped, exercised code. Gating
 * the transport reuses all of them at once; gating each screen would have meant seven new
 * branches in the app's most delicate flow, each with its own way of being wrong.
 *
 * The consequence to keep in mind: a report is never lost by a refusal, it is kept on the device.
 *
 * ## Asking
 *
 * [ensure] is the ask, and it belongs at the moment the user tries to send — not at first launch.
 * A consent question asked before the user has any idea what the feature is gets dismissed
 * reflexively; asked when they have just written a bug description, it is the one moment the
 * answer means something.
 *
 * ## Re-asking
 *
 * [NOTICE_VERSION] is stored alongside the answer. When what the app collects changes, the number
 * goes up and everyone is asked again. An agreement is to a specific description of what is
 * collected; silently widening the collection under an old "yes" would make the whole screen a
 * formality. Raising it costs one dialog per user, which is the correct price.
 */
object ReportConsent {

    private const val TAG = "ReportConsent"
    private const val PREFS_NAME = "dashcast_report_consent"
    private const val K_ANSWER = "answer"
    private const val K_VERSION = "notice_version"
    private const val K_AT = "answered_at"

    private const val ANSWER_GRANTED = "granted"
    private const val ANSWER_DENIED = "denied"

    /**
     * Bump when the notice's list of collected data changes — not for wording or translation fixes.
     *
     * 1 — first notice: journal, logcat, dumpsys, device/version, optional screenshots.
     */
    const val NOTICE_VERSION = 1

    enum class State { UNKNOWN, GRANTED, DENIED }

    @Volatile private var sApp: Context? = null

    /** Same reason as [ReportChannel.init]: the transport gate has no Context to hand. */
    @JvmStatic
    fun init(ctx: Context) {
        sApp = ctx.applicationContext
    }

    @JvmStatic
    fun state(ctx: Context): State {
        val p = prefs(ctx) ?: return State.UNKNOWN
        // An answer given to an older, shorter description of what is collected is not an answer
        // to this one.
        if (p.getInt(K_VERSION, 0) != NOTICE_VERSION) return State.UNKNOWN
        return when (p.getString(K_ANSWER, "")) {
            ANSWER_GRANTED -> State.GRANTED
            ANSWER_DENIED -> State.DENIED
            else -> State.UNKNOWN
        }
    }

    /**
     * The transport gate.
     *
     * Fails CLOSED — no context registered, unreadable store, anything unexpected — because the
     * failure mode of a false negative is a report kept on the device, and the failure mode of a
     * false positive is a screenshot of someone's home address sent to a group chat.
     */
    @JvmStatic
    fun isGranted(): Boolean {
        val ctx = sApp ?: return false
        return try { state(ctx) == State.GRANTED } catch (_: Throwable) { false }
    }

    @JvmStatic
    fun isGranted(ctx: Context): Boolean =
        try { state(ctx) == State.GRANTED } catch (_: Throwable) { false }

    @JvmStatic
    fun grant(ctx: Context) = store(ctx, ANSWER_GRANTED)

    @JvmStatic
    fun deny(ctx: Context) = store(ctx, ANSWER_DENIED)

    /** Back to unanswered, so the notice is shown again on the next send. */
    @JvmStatic
    fun reset(ctx: Context) {
        try {
            prefs(ctx)?.edit { clear() }
            AppLogger.i(TAG, "consent reset")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "consent reset failed (" + t.javaClass.simpleName + ")")
        }
    }

    private fun store(ctx: Context, answer: String) {
        try {
            prefs(ctx)?.edit {
                putString(K_ANSWER, answer)
                putInt(K_VERSION, NOTICE_VERSION)
                putLong(K_AT, System.currentTimeMillis())
            }
            AppLogger.i(TAG, "consent " + answer + " (notice v" + NOTICE_VERSION + ")")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "consent not stored (" + t.javaClass.simpleName + ")")
        }
    }

    /**
     * Runs [onGranted] if diagnostics may be sent, asking first when the answer is not yet known.
     *
     * [onGranted] runs on the main thread, and only ever after a positive answer — a refusal is
     * final for this attempt, and [onRefused] gets to say what happened to the report instead.
     * When the activity is gone the ask is impossible, so nothing is sent: see the class note on
     * failing closed.
     */
    @JvmStatic
    @JvmOverloads
    fun ensure(activity: Activity, onGranted: Runnable, onRefused: Runnable? = null) {
        if (activity.isFinishing || activity.isDestroyed) {
            onRefused?.run()
            return
        }
        when (state(activity)) {
            State.GRANTED -> onGranted.run()
            State.DENIED -> onRefused?.run()
            State.UNKNOWN -> showNotice(activity, onGranted, onRefused)
        }
    }

    /**
     * Asks when the answer is not yet known, then runs [next] whichever way it went.
     *
     * This — not [ensure] — is what belongs at a Send button, and the difference matters. Refusing
     * does not mean "do nothing": the capture still runs and the transport gate turns the upload
     * into the share sheet the app already offers when no channel is configured. The user gets a
     * report they can pass on by hand, which is what they asked for by tapping Send. Cancelling
     * the whole action instead would punish a perfectly reasonable answer.
     */
    @JvmStatic
    fun askThen(activity: Activity, next: Runnable) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (state(activity) != State.UNKNOWN) {
            next.run()
            return
        }
        showNotice(activity, next, next)
    }

    /**
     * The notice itself.
     *
     * Not cancellable by tapping outside: a dismissed dialog would leave the state UNKNOWN and the
     * report in limbo, and on a car touchscreen an accidental outside-tap is likely. The two
     * buttons are the only exits, and both are answers.
     */
    @JvmStatic
    @JvmOverloads
    fun showNotice(activity: Activity, onGranted: Runnable? = null, onRefused: Runnable? = null) {
        if (activity.isFinishing || activity.isDestroyed) {
            onRefused?.run()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_body)
            .setCancelable(false)
            .setPositiveButton(R.string.consent_accept) { _, _ ->
                grant(activity)
                onGranted?.run()
            }
            .setNegativeButton(R.string.consent_refuse) { _, _ ->
                deny(activity)
                onRefused?.run()
            }
            .show()
    }

    /**
     * Why an upload is impossible right now, for the diagnostic screens' log pane.
     *
     * Those screens are developer tools and their log is English by convention, so this string is
     * not translated. It exists because "Telegram not configured" now has two very different
     * causes, and a tester staring at it has no way to tell which one they are looking at — one is
     * fixed by a tap in Settings, the other needs the maintainer.
     */
    @JvmStatic
    fun transportBlockReason(): String =
        if (!isGranted()) "diagnostics sending is off (Settings -> Bug report delivery)"
        else "no reporting channel configured on this device"

    private fun prefs(ctx: Context): android.content.SharedPreferences? =
        try {
            ctx.applicationContext.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "consent store unavailable (" + t.javaClass.simpleName + ")")
            null
        }

    /**
     * Test seam. Synchronous on purpose — an asynchronous clear would race the next case's first
     * read and make the suite flaky in a way that only shows up under load.
     */
    @JvmStatic
    fun clearForTesting(ctx: Context) {
        prefs(ctx)?.edit(commit = true) { clear() }
    }
}
