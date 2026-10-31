package com.byd.dashcast.ui.diag

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.byd.dashcast.R

/**
 * Guided page for the « map + BYD instruments » full-screen theme. Presentation only: every action is
 * a callback owned by [ClusterThemeLiveControls], which keeps the safety checks and sends the requests.
 */
internal class FullscreenThemeView(private val context: Context) {
    enum class Tone { NEUTRAL, OK, ACTIVE, WARNING, DANGER }
    class Action(val label: String, val run: () -> Unit)
    class State(val tone: Tone, val chip: String, val preview: ClusterPreviewView.Mode,
        /** Current step 0..4, or -1 while recovery takes over the page. */
        val step: Int, val waiting: Boolean, val title: String, val message: String,
        val expected: String? = null, val primary: Action? = null, val danger: Boolean = false,
        val secondary: List<Action> = emptyList())

    private enum class Mark { DONE, CURRENT, WAITING, TODO }
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun color(id: Int) = context.getColor(id)
    private val match = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(16)) }
    /** Hosts the diagnostic-report shortcut supplied by the activity. */
    val footer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var state: State? = null
        private set
    private val preview = ClusterPreviewView(context)
    private val chip = TextView(context)
    private val steps = ArrayList<Pair<TextView, TextView>>()
    private val counter = TextView(context)
    private val title = TextView(context)
    private val message = TextView(context)
    private val expectedBox = LinearLayout(context)
    private val expected = TextView(context)
    private val progress = LinearLayout(context)
    private val primary = Button(context)
    private var primaryAction: (() -> Unit)? = null
    private var primaryDanger: Boolean? = null
    private val secondaryRow = LinearLayout(context)
    private var secondaryKey: String? = null
    private var secondaryActions: List<() -> Unit> = emptyList()
    private val noticeView = TextView(context)
    private val details = TextView(context)

    init {
        root.addView(card().apply {
            addView(TextView(context).apply {
                text = context.getString(R.string.fs_page_title); textSize = 22f
                setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.md_on_surface)); isAccessibilityHeading = true
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.fs_page_subtitle); textSize = 15f
                setTextColor(color(R.color.md_on_surface_variant)); setPadding(0, dp(4), 0, dp(12))
            })
            addView(preview, LinearLayout.LayoutParams(match, wrap))
            addView(chip.apply {
                textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(16), dp(6), dp(16), dp(6))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })
        }, LinearLayout.LayoutParams(match, wrap))
        root.addView(stepper(), LinearLayout.LayoutParams(match, wrap))
        root.addView(noticeView.apply {
            textSize = 16f; visibility = View.GONE; setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(R.color.md_error_container, 14); setTextColor(color(R.color.md_on_error_container))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            setOnClickListener { notice(null) }
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        root.addView(card().apply {
            addView(counter.apply { textSize = 14f; setTextColor(color(R.color.md_on_surface_variant)) })
            addView(title.apply {
                textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.md_on_surface))
                isAccessibilityHeading = true; setPadding(0, dp(2), 0, dp(6))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            addView(message.apply { textSize = 17f; setTextColor(color(R.color.md_on_surface)) })
            addView(expectedBox.apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12))
                background = rounded(R.color.md_primary_container, 12)
                addView(TextView(context).apply {
                    text = context.getString(R.string.fs_expected_label); textSize = 14f
                    setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.md_on_primary_container))
                })
                addView(expected.apply { textSize = 16f; setTextColor(color(R.color.md_on_primary_container)); setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
            addView(progress.apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
                addView(ProgressBar(context).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(TextView(context).apply {
                    text = context.getString(R.string.fs_waiting); textSize = 16f
                    setTextColor(color(R.color.md_on_surface_variant)); setPadding(dp(10), 0, 0, 0)
                })
            }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
            addView(primary.apply {
                isAllCaps = false; textSize = 19f; setTypeface(typeface, Typeface.BOLD); stateListAnimator = null
                setOnClickListener { notice(null); primaryAction?.invoke() }
            }, LinearLayout.LayoutParams(match, dp(64)).apply { topMargin = dp(16) })
            addView(secondaryRow.apply { orientation = LinearLayout.HORIZONTAL }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        root.addView(TextView(context).apply {
            text = context.getString(R.string.fs_safety_note); textSize = 15f
            setTextColor(color(R.color.md_on_surface_variant)); setPadding(dp(4), dp(12), dp(4), 0)
        })
        val toggle = Button(context)
        root.addView(toggle.apply {
            isAllCaps = false; textSize = 15f; stateListAnimator = null; setBackgroundColor(Color.TRANSPARENT)
            setTextColor(color(R.color.md_primary)); gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(48); text = context.getString(R.string.fs_details) + " ▾"
            setOnClickListener {
                val show = details.visibility != View.VISIBLE
                details.visibility = if (show) View.VISIBLE else View.GONE
                text = context.getString(R.string.fs_details) + if (show) " ▴" else " ▾"
            }
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        root.addView(details.apply {
            textSize = 13f; typeface = Typeface.MONOSPACE; visibility = View.GONE; setTextIsSelectable(true)
            setTextColor(color(R.color.md_on_surface_variant)); setPadding(dp(4), 0, dp(4), dp(8))
        })
        root.addView(footer)
    }

    fun render(next: State) {
        state = next
        preview.mode = next.preview
        setText(chip, next.chip)
        chip.background = when (next.tone) {
            Tone.NEUTRAL -> rounded(R.color.md_pill_off_fill, 18, R.color.md_pill_off_stroke)
            Tone.OK -> rounded(R.color.md_pill_ok_fill, 18, R.color.md_pill_ok_stroke)
            Tone.ACTIVE -> rounded(R.color.md_primary_container, 18, R.color.md_primary)
            Tone.WARNING -> rounded(R.color.md_pill_warn_fill, 18, R.color.md_pill_warn_stroke)
            Tone.DANGER -> rounded(R.color.md_error_container, 18, R.color.md_error)
        }
        chip.setTextColor(color(when (next.tone) {
            Tone.NEUTRAL -> R.color.md_on_surface_variant
            Tone.OK -> R.color.md_pill_ok_text
            Tone.ACTIVE -> R.color.md_on_primary_container
            Tone.WARNING -> R.color.md_pill_warn_text
            Tone.DANGER -> R.color.md_on_error_container
        }))
        steps.forEachIndexed { index, (dot, name) ->
            val mark = when {
                next.step < 0 || index > next.step -> Mark.TODO
                index < next.step -> Mark.DONE
                next.waiting -> Mark.WAITING
                else -> Mark.CURRENT
            }
            dot.text = if (mark == Mark.DONE) "✓" else "${index + 1}"
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when (mark) {
                    Mark.DONE -> setColor(color(R.color.md_status_ok))
                    Mark.CURRENT -> setColor(color(R.color.md_primary))
                    Mark.WAITING -> { setColor(color(R.color.md_primary_container)); setStroke(dp(2), color(R.color.md_primary)) }
                    Mark.TODO -> { setColor(Color.TRANSPARENT); setStroke(dp(2), color(R.color.md_outline_variant)) }
                }
            }
            dot.setTextColor(when (mark) {
                Mark.DONE -> Color.WHITE
                Mark.CURRENT -> color(R.color.md_on_primary)
                Mark.WAITING -> color(R.color.md_on_primary_container)
                Mark.TODO -> color(R.color.md_on_surface_variant)
            })
            name.setTextColor(color(if (mark == Mark.TODO) R.color.md_on_surface_variant else R.color.md_on_surface))
            name.setTypeface(null, if (mark == Mark.CURRENT || mark == Mark.WAITING) Typeface.BOLD else Typeface.NORMAL)
        }
        counter.visibility = if (next.step >= 0) View.VISIBLE else View.GONE
        setText(counter, context.getString(R.string.fs_step_counter, next.step + 1, steps.size))
        setText(title, next.title)
        setText(message, next.message)
        expectedBox.visibility = if (next.expected == null) View.GONE else View.VISIBLE
        setText(expected, next.expected.orEmpty())
        progress.visibility = if (next.waiting) View.VISIBLE else View.GONE
        primaryAction = next.primary?.run
        primary.visibility = if (next.primary == null) View.GONE else View.VISIBLE
        next.primary?.let { setText(primary, it.label); primary.contentDescription = it.label }
        if (primaryDanger != next.danger) {
            primaryDanger = next.danger
            primary.background = rounded(if (next.danger) R.color.md_error else R.color.md_primary, 16)
            primary.setTextColor(color(if (next.danger) R.color.md_on_error else R.color.md_on_primary))
        }
        val key = next.secondary.joinToString("\u0000") { it.label }
        if (key != secondaryKey) {
            secondaryKey = key
            secondaryRow.removeAllViews()
            next.secondary.forEachIndexed { index, action -> secondaryRow.addView(Button(context).apply {
                text = action.label; contentDescription = action.label; isAllCaps = false; textSize = 16f
                stateListAnimator = null; minHeight = dp(56); setTextColor(color(R.color.md_primary))
                background = rounded(android.R.color.transparent, 14, R.color.md_outline)
                setOnClickListener { notice(null); secondaryActions.getOrNull(index)?.invoke() }
            }, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = if (index == 0) 0 else dp(8) }) }
        }
        secondaryActions = next.secondary.map { it.run }
        secondaryRow.visibility = if (next.secondary.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Persistent banner for refusals and failures; it disappears on tap or on the next user action. */
    fun notice(text: String?) {
        noticeView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        setText(noticeView, text.orEmpty())
    }

    fun details(text: String) = setText(details, text)

    private fun stepper() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, dp(8))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS // The card counter reads the step.
        listOf(R.string.fs_step_check, R.string.fs_step_load, R.string.fs_step_app, R.string.fs_step_start, R.string.fs_step_finish)
            .forEachIndexed { index, label ->
                val dot = TextView(context).apply {
                    gravity = Gravity.CENTER; textSize = 16f; setTypeface(typeface, Typeface.BOLD); text = "${index + 1}"
                }
                val name = TextView(context).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; textSize = 14f; maxLines = 2; text = context.getString(label)
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    addView(dot, LinearLayout.LayoutParams(dp(38), dp(38)))
                    addView(name, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(4) })
                }, LinearLayout.LayoutParams(0, wrap, 1f))
                steps += dot to name
            }
    }

    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(18))
        background = rounded(R.color.md_surface_container_low, 20, R.color.md_outline_variant)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color(fill))
        if (stroke != null) setStroke(dp(1), color(stroke))
    }

    private fun setText(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }
}
