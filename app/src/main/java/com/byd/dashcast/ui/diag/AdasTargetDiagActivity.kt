package com.byd.dashcast.ui.diag

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.TypedValue
import android.view.View
import android.view.Display
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.byd.dashcast.proxy.AdasTargetProperties.Variant
import com.byd.dashcast.proxy.ProxyClient

/** Central-display bench only. Opening this screen never requests vehicle data. */
@SuppressLint("SetTextI18n")
open class AdasTargetDiagActivity : Activity() {
    private lateinit var readButton: Button
    private lateinit var cancelButton: Button
    private lateinit var parked: CheckBox
    private lateinit var variant: Spinner
    private lateinit var status: TextView
    private lateinit var raw: TextView
    private lateinit var plot: AdasTargetPlot
    private val handler = Handler(Looper.getMainLooper())
    private var showCapturedPoints = false
    private val update = object : Runnable {
        override fun run() {
            render()
            if (AdasTargetDiagnostics.capture.isBusy()) handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 12) }
        val compact = resources.configuration.screenWidthDp < 720
        root.addView(TextView(this).apply { text = "ADAS target diagnostic · manual snapshot"; textSize = 20f })
        root.addView(TextView(this).apply {
            text = "Stationary test only. Movement unknown. Cached values are historical, non-atomic and may be stale.\n" +
                "SX326 firmware candidates; runtime access unverified. Four front groups; rear/sides unknown. Units and physical axes unknown."
            textSize = 16f
        })
        parked = CheckBox(this).apply {
            text = "I am testing while parked (acknowledgment only; parking is not verified)"
            tag = "adas_parked"
        }
        root.addView(parked)
        val controls = LinearLayout(this).apply { orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        variant = Spinner(this).apply {
            tag = "adas_variant"
            adapter = ArrayAdapter(this@AdasTargetDiagActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Decoder unknown · raw only", Variant.NEW.label, Variant.OLD.label))
        }
        controls.addView(variant, if (compact) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        readButton = Button(this).apply { text = "Read one snapshot"; tag = "adas_read"; setOnClickListener { readSnapshot() } }
        controls.addView(readButton)
        cancelButton = Button(this).apply {
            text = "Cancel"; tag = "adas_cancel"
            setOnClickListener { showCapturedPoints = false; AdasTargetDiagnostics.capture.cancel("Manual cancel"); render() }
        }
        controls.addView(cancelButton)
        root.addView(controls)
        status = TextView(this).apply { textSize = 16f; tag = "adas_status" }
        root.addView(status)
        val body = LinearLayout(this).apply { orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val schematic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        schematic.addView(TextView(this).apply { text = "Historical schematic · units / physical axes unknown"; textSize = 16f })
        plot = AdasTargetPlot(this) { diagnosticDisplayId() == Display.DEFAULT_DISPLAY }.apply { tag = "adas_plot" }
        schematic.addView(plot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 280f, resources.displayMetrics).toInt()))
        schematic.addView(TextView(this).apply { text = "Decoder-accepted values only. Rear / sides unknown. No point does not mean clear surroundings."; textSize = 16f })
        body.addView(schematic, if (compact) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        raw = TextView(this).apply { textSize = 16f; setTextIsSelectable(true); tag = "adas_raw" }
        body.addView(raw, if (compact) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        root.addView(body)
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(update) // Formats retained state only; no OEM reads or automatic capture.
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (::plot.isInitialized) render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::plot.isInitialized) render()
    }

    protected open fun diagnosticDisplayId(): Int? = window.decorView.display?.displayId

    private fun requireDefaultDisplay(): Boolean {
        if (diagnosticDisplayId() == Display.DEFAULT_DISPLAY) return true
        showCapturedPoints = false
        AdasTargetDiagnostics.capture.cancel("Android display unknown or secondary; no subsequent keys admitted")
        plot.show(null, emptyList())
        readButton.isEnabled = false
        cancelButton.isEnabled = false
        status.text = "Diagnostic disabled: attached Android default display required. Physical screen mapping is not established."
        return false
    }

    override fun onPause() {
        showCapturedPoints = false
        AdasTargetDiagnostics.capture.cancel("Screen hidden; no subsequent keys admitted; one already admitted/in-flight read may complete")
        handler.removeCallbacks(update)
        plot.show(null, emptyList())
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun readSnapshot() {
        showCapturedPoints = false
        plot.show(null, emptyList())
        if (!requireDefaultDisplay()) return
        if (!parked.isChecked) {
            status.text = "Acknowledge a stationary, parked test before reading. Movement remains unknown."
            return
        }
        val hypothesis = when (variant.selectedItemPosition) { 1 -> Variant.NEW; 2 -> Variant.OLD; else -> null }
        val binder = ProxyClient.getProxyDaemonBinder()
        val context = "androidDisplayId=0; appUid=${Process.myUid()}; daemonUid=${ProxyClient.getCallerUid()}; daemonPid=${ProxyClient.getDaemonPid()}; " +
            "daemonProtocol=${ProxyClient.getProtocolVersion() ?: "unknown"}; api=${Build.VERSION.SDK_INT}; " +
            "model=${Build.MODEL}; display=${Build.DISPLAY}; fingerprint=${Build.FINGERPRINT}"
        val started = AdasTargetDiagnostics.capture.start(hypothesis, context) { property ->
            if (binder == null) throw IllegalStateException("No connected PROXY daemon; connect using existing DashCast controls. No bootstrap attempted")
            ProxyClient.readAdasTarget(binder, property)
        }
        if (!started) {
            status.text = "Another diagnostic or physical read is pending. No new capture was submitted."
            return
        }
        showCapturedPoints = true
        handler.removeCallbacks(update)
        handler.post(update)
    }

    private fun render() {
        val capture = AdasTargetDiagnostics.capture
        val snapshot = capture.snapshot()
        val busy = capture.isBusy()
        if (!requireDefaultDisplay()) { raw.text = capture.summary(); return }
        readButton.isEnabled = !busy
        variant.isEnabled = !busy
        cancelButton.isEnabled = snapshot?.outcome == "RUNNING"
        status.text = if (snapshot == null) "No capture. Reading requires an explicit button press. Results are included in bug reports."
        else "Sequence ${snapshot.outcome} · ${snapshot.rows.count { it.receivedEpochMs != null }}/33 attempts finished · epoch ${snapshot.startedEpochMs}" +
            (if (busy && snapshot.outcome != "RUNNING") " · Physical call still pending; no retry" else "") +
            "\nRaw=${snapshot.rows.count { it.status == "RAW" }} · sentinel=${snapshot.rows.count { it.status == "SENTINEL" }} · error=${snapshot.rows.count { it.status == "ERROR" }} · not attempted=${snapshot.rows.count { it.status == "NOT_ATTEMPTED" }}" +
            "\nHistorical cache snapshot; decoder ${snapshot.variant?.name ?: "UNKNOWN"}. Not a driving aid."
        raw.text = capture.summary()
        plot.show(snapshot?.variant, if (showCapturedPoints && snapshot != null) decodeAdasTargets(snapshot) else emptyList())
    }
}

/** Schematic of decoded numbers only; intentionally no lane, distance or physical-axis labels. */
@SuppressLint("ViewConstructor") // Programmatic diagnostic view; never inflated from XML.
private class AdasTargetPlot(context: Context, private val displayAllowed: () -> Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var points: List<AdasTargetPoint> = emptyList()
    private var variant: Variant? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun show(variant: Variant?, points: List<AdasTargetPoint>) {
        this.variant = variant; this.points = points
        contentDescription = "Historical schematic, units and physical axes unknown. " +
            if (points.isEmpty()) "No decoded points shown; surroundings unknown." else points.joinToString { "${it.label}, type ${it.type}, X ${it.x}, Y ${it.y}" }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 22, 34))
        // Reparenting can redraw without resume/configuration callbacks. Never retain points there.
        if (!displayAllowed()) {
            points = emptyList()
            contentDescription = "Schematic disabled: Android display unknown or secondary. Surroundings unknown."
            return
        }
        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        val top = dp(36f)
        val bottom = (height - dp(36f)).coerceAtLeast(top + 1)
        val left = dp(24f); val right = (width - dp(24f)).coerceAtLeast(left + 1)
        val yMin = if (variant == Variant.OLD) -100.0 else -10.0
        val yMax = if (variant == Variant.OLD) 100.0 else 150.0
        fun screenY(y: Double) = (bottom - ((y - yMin) / (yMax - yMin)) * (bottom - top)).toFloat()
        paint.color = Color.DKGRAY; paint.strokeWidth = dp(1f)
        canvas.drawLine(width / 2f, top, width / 2f, bottom, paint)
        canvas.drawLine(left, screenY(0.0), right, screenY(0.0), paint)
        paint.color = Color.LTGRAY
        canvas.drawRoundRect(width / 2f - dp(10f), screenY(0.0) - dp(16f), width / 2f + dp(10f), screenY(0.0) + dp(16f), dp(4f), dp(4f), paint)
        for (point in points) {
            val x = (left + (point.x + 12) / 24 * (right - left)).toFloat()
            val y = screenY(point.y)
            paint.color = Color.CYAN
            canvas.drawCircle(x, y, dp(7f), paint)
            paint.color = Color.WHITE
            val label = "${point.label} (${point.type})"
            val labelX = (x - paint.measureText(label) / 2).coerceAtMost((width - paint.measureText(label)).coerceAtLeast(0f)).coerceAtLeast(0f)
            canvas.drawText(label, labelX, y - dp(14f), paint)
        }
    }
}
