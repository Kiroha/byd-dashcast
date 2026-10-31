package com.byd.dashcast.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.dashcast.R

/**
 * One consistent confirmation popup: what will happen, what the cluster should show, and explicit
 * assertions that must all be ticked before the positive button becomes active.
 */
internal class GuidedDialog(private val activity: Activity) {
    class Choice(val label: String, val preview: ClusterPreviewView.Mode? = null, val icon: Drawable? = null, val run: () -> Unit)

    var title = ""
    var lead: String? = null
    var preview: ClusterPreviewView.Mode? = null
    var steps: List<String> = emptyList()
    var expected: String? = null
    var checks: List<String> = emptyList()
    var choices: List<Choice> = emptyList()
    var positive: String? = null
    var negative: String? = activity.getString(android.R.string.cancel)
    var neutral: String? = null
    var danger = false
    val boxes = ArrayList<CheckBox>()

    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
    private val match = LinearLayout.LayoutParams.MATCH_PARENT

    /** [onPositive] runs only when every assertion is ticked; it owns dismissal so it can refuse stale clicks. */
    fun create(onNeutral: (() -> Unit)? = null, onPositive: ((AlertDialog) -> Unit)? = null): AlertDialog {
        lateinit var dialog: AlertDialog
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), dp(8)) }
        preview?.let { mode -> content.addView(ClusterPreviewView(activity).apply { this.mode = mode; maxHeightDp = 120 }, params(8)) }
        if (steps.isNotEmpty()) {
            content.addView(heading(R.string.fs_dialog_happens), params(14))
            steps.forEachIndexed { index, step -> content.addView(text(if (steps.size == 1) step else "${index + 1}. $step", 16f), params(4)) }
        }
        expected?.let { value ->
            content.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(activity.getColor(R.color.md_primary_container)) }
                addView(heading(R.string.fs_dialog_expected).apply { setTextColor(activity.getColor(R.color.md_on_primary_container)) })
                addView(text(value, 16f).apply { setTextColor(activity.getColor(R.color.md_on_primary_container)) })
            }, params(14))
        }
        checks.forEach { label ->
            content.addView(CheckBox(activity).apply { text = label; textSize = 16f; minHeight = dp(48) }.also(boxes::add), params(8))
        }
        choices.forEach { choice -> content.addView(choiceRow(choice) { dialog.dismiss(); choice.run() }, params(10)) }
        val builder = AlertDialog.Builder(activity).setTitle(title)
        lead?.let { builder.setMessage(it) }
        if (content.childCount > 0) builder.setView(ScrollView(activity).apply { addView(content) })
        positive?.let { builder.setPositiveButton(it, null) }
        negative?.let { builder.setNegativeButton(it, null) }
        neutral?.let { builder.setNeutralButton(it, null) }
        dialog = builder.create()
        dialog.setOnShowListener {
            if (positive != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { send ->
                fun refresh() { send.isEnabled = boxes.all { it.isChecked } }
                boxes.forEach { box -> box.setOnCheckedChangeListener { _, _ -> refresh() } }
                refresh()
                if (danger) send.setTextColor(activity.getColor(R.color.md_error))
                send.setOnClickListener {
                    if (!boxes.all { it.isChecked }) return@setOnClickListener
                    if (onPositive != null) onPositive(dialog) else dialog.dismiss()
                }
            }
            if (neutral != null && onNeutral != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                dialog.dismiss(); onNeutral()
            }
        }
        return dialog
    }

    private fun choiceRow(choice: Choice, onClick: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true; minimumHeight = dp(64)
        setPadding(dp(12), dp(10), dp(12), dp(10)); contentDescription = choice.label
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat(); setColor(activity.getColor(R.color.md_surface_container_low))
            setStroke(dp(1), activity.getColor(R.color.md_outline_variant))
        }
        val ripple = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) && ripple.resourceId != 0)
            foreground = activity.getDrawable(ripple.resourceId)
        choice.preview?.let { mode -> addView(ClusterPreviewView(activity).apply { this.mode = mode; maxHeightDp = 48 }, LinearLayout.LayoutParams(dp(128), wrap)) }
        choice.icon?.let { icon -> addView(ImageView(activity).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(44), dp(44))) }
        addView(text(choice.label, 17f).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
        setOnClickListener { onClick() }
    }

    private fun heading(id: Int) = text(activity.getString(id), 15f).apply { setTypeface(typeface, Typeface.BOLD) }
    private fun text(value: String, size: Float) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(activity.getColor(R.color.md_on_surface))
    }
    private fun params(top: Int) = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(top) }
}
