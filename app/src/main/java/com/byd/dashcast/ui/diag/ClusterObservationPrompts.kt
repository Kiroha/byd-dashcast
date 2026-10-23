package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import com.byd.dashcast.R
import com.byd.dashcast.clusterlive.ClusterThemeDiagnostics
import com.byd.dashcast.proxy.ClusterThemeSession

/** One visual question at a time. Answers are user observations, never render acknowledgments. */
internal class ClusterObservationPrompts(private val activity: Activity) {
    internal class Question(val key: String, val priority: Int, val session: ClusterThemeSession,
        val title: Int, val message: Int, val positive: Int,
        val current: () -> Boolean, val answer: () -> Unit)
    private val questions = linkedMapOf<String, Question>()
    private val shown = linkedSetOf<String>()
    private var visible = false
    private var dialog: AlertDialog? = null
    private var active: Question? = null

    fun update(lane: String, question: Question?) {
        if (question == null) questions.remove(lane) else questions[lane] = question
        val old = active
        if (old != null && (!old.session.isCurrent() || !old.current() || questions.values.none { it.key == old.key })) dismiss("stale")
        if (old != null && question != null && question.priority > old.priority) dismiss("superseded")
        showNext()
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (!value) { dismiss("hidden"); questions.clear() } else showNext()
    }

    fun reopen() {
        if (dialog?.isShowing == false) { dialog = null; active = null }
        questions.values.forEach { shown.remove(it.key) }; showNext()
    }

    fun explainBlocked() {
        if (!visible || activity.isFinishing || activity.isDestroyed) return
        dismiss("blocked-explanation")
        var response = "dismissed"
        val popup = AlertDialog.Builder(activity).setTitle(R.string.diag_cluster_rcc_blocked_title)
            .setMessage(R.string.diag_cluster_rcc_blocked_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                response = "acknowledged"
                questions["native"]?.let { shown.remove(it.key) }
            }.create()
        dialog = popup
        popup.setOnCancelListener { response = "cancel" }
        ClusterThemeDiagnostics.local(activity.applicationContext, "rcc-blocked-popup", "REFUSED", "shown; no RCC load, return or observation submitted")
        popup.setOnDismissListener { if (dialog === popup) {
            ClusterThemeDiagnostics.local(activity.applicationContext, "rcc-blocked-popup", "REFUSED", "$response; no RCC load, return or observation submitted")
            dialog = null; showNext()
        } }
        popup.show()
    }

    private fun showNext() {
        if (!visible || dialog != null || activity.isFinishing || activity.isDestroyed) return
        val q = questions.values.sortedByDescending { it.priority }.firstOrNull {
            it.key !in shown && it.session.isCurrent() && it.current()
        } ?: return
        shown.add(q.key)
        while (shown.size > 32) shown.remove(shown.first())
        active = q
        val popup = AlertDialog.Builder(activity).setTitle(q.title).setMessage(q.message)
            .setPositiveButton(q.positive, null)
            .setNegativeButton(R.string.diag_cluster_observation_no) { _, _ -> log(q, "no-not-yet") }
            .setNeutralButton(R.string.diag_cluster_observation_unknown) { _, _ -> log(q, "unknown") }.create()
        dialog = popup
        popup.setOnCancelListener { log(q, "cancel") }
        popup.setOnDismissListener { if (dialog === popup) { dialog = null; active = null } }
        popup.setOnShowListener {
            popup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (dialog !== popup) return@setOnClickListener
                if (!visible || !q.session.isCurrent() || !q.current()) {
                    log(q, "stale-positive-refused"); dismiss("stale"); return@setOnClickListener
                }
                log(q, "yes-user-observation")
                popup.dismiss()
                q.answer()
            }
        }
        log(q, "shown")
        popup.show()
    }

    private fun dismiss(reason: String) {
        active?.let { q ->
            log(q, reason)
            // Programmatic invalidation is not an answer; a fresh eligible receipt may ask again.
            shown.remove(q.key)
        }
        dialog?.dismiss(); dialog = null; active = null
    }

    private fun log(q: Question, response: String) = ClusterThemeDiagnostics.local(activity.applicationContext,
        "visual-confirmation", "USER_OBSERVATION", "context=${q.key}; response=$response; physicalPixels=UNKNOWN; QML ACK=UNKNOWN; no OEM command sent by prompt.")
}
