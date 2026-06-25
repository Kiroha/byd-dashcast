package com.byd.dashcast.hud

import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.system.CanBusController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic bench for the BYD instrument-cluster / HUD navigation API (DL3).
 *
 * Two tools to find why the HUD nav doesn't show on DL3:
 *  1. **Scrape** — dump the REAL `INSTRUMENT_*` feature-ID constants from the car
 *     framework ([HudFeatureScraper]). DL3 IDs differ from the OpenBYD DiLink-5.1
 *     set DashCast currently hardcodes, so a write can silently target the wrong
 *     register.
 *  2. **Bench** — fire nav ON/OFF + a test guidance via [CanBusController] (the
 *     existing daemon path), plus a RAW `featureId + value` write so any candidate
 *     ID (current / repo / scraped) can be validated live against the cluster.
 *
 * Dev-only screen; built programmatically (no layout/strings → no i18n burden).
 */
class HudDiagActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var fidField: EditText
    private lateinit var valField: EditText
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "HUD nav bench (DL3)"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(header("1 · Scrape real feature IDs from this car"))
        root.addView(button("Scrape BYD instrument/nav IDs") { runScrape() })

        root.addView(header("2 · Activate / test the HUD nav"))
        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navRow.addView(button("Nav ON") { bg { CanBusController.setNaviActive(true); log("setNaviActive(true) ok") } }, rowLp())
        navRow.addView(button("Nav OFF") { bg { CanBusController.setNaviActive(false); log("setNaviActive(false) ok") } }, rowLp())
        root.addView(navRow)
        root.addView(button("Test guidance: turn-right 300 m") {
            bg {
                CanBusController.setNaviActive(true)
                CanBusController.sendSimpleGuidance(CanBusController.ICON_TURN_RIGHT, 300)
                CanBusController.sendNextStreetName("TEST 300m")
                log("sent ICON_TURN_RIGHT + 300m + name")
            }
        })

        root.addView(header("3 · Raw write (try any featureId)"))
        root.addView(hint("featureId — hex (0x43D10010) or decimal. Try your IDs, the repo's, or a scraped one."))
        fidField = field("0x43D10010", InputType.TYPE_CLASS_TEXT)
        root.addView(fidField)
        valField = field("2", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        root.addView(valField)
        val rawRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rawRow.addView(button("Send int") { sendRawInt() }, rowLp())
        rawRow.addView(button("Send text→bytes") { sendRawBytes() }, rowLp())
        root.addView(rawRow)
        root.addView(button("Print known ID sets (DashCast vs repo)") { printKnownIds() })

        root.addView(header("4 · AAOS cluster (DX_BYD_AUTO only — no-op on DiLink)"))
        root.addView(hint("Switch the cluster mode via a vendor VHAL command WHILE an app is on Display 1, then look at the panel. Or probe the display-proxy HAL that owns the panel."))
        for ((name, prop) in AaosClusterProbe.CANDIDATES) {
            root.addView(button("%s = 1  (0x%08X)".format(name, prop)) { aaosSetProp(prop, 1) })
        }
        root.addView(button("Send VHAL int (free featureId + value above)") { aaosSetFree() })
        root.addView(button("Probe display-proxy HAL") {
            if (!AaosClusterProbe.isAaos(this)) { logUi("not AAOS — skipped"); return@button }
            bg { log(AaosClusterProbe.probeDisplayProxy()) }
        })

        out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val outScroll = ScrollView(this).apply {
            addView(out)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280)
            ).apply { topMargin = dp(12) }
        }
        root.addView(outScroll)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        // Make sure the proxy daemon is connected so the bench writes can reach it.
        bg {
            if (!ProxyClient.isConnected()) {
                ProxyClient.connect(this)
                log("ProxyClient.connect() — connected=${ProxyClient.isConnected()}")
            } else {
                log("ProxyClient already connected")
            }
        }
    }

    // ── actions ───────────────────────────────────────────────────────────────

    private fun runScrape() {
        log("scraping… (see below)")
        bg { log(HudFeatureScraper.scrape()) }
    }

    private fun sendRawInt() {
        val fid = parseId(fidField.text.toString()) ?: return logUi("bad featureId")
        val v = valField.text.toString().trim().toIntOrNull() ?: return logUi("bad value")
        bg {
            val rc = CanBusController.setFeatureInt(fid, v)
            log("setFeatureInt(0x%08X, %d) → rc=%d".format(fid, v, rc))
        }
    }

    private fun sendRawBytes() {
        val fid = parseId(fidField.text.toString()) ?: return logUi("bad featureId")
        val s = valField.text.toString()
        bg {
            val rc = CanBusController.setFeatureBytes(fid, s.toByteArray(Charsets.UTF_8))
            log("setFeatureBytes(0x%08X, \"%s\") → rc=%d".format(fid, s, rc))
        }
    }

    private fun aaosSetProp(propId: Int, value: Int) {
        if (!AaosClusterProbe.isAaos(this)) { logUi("not AAOS — skipped"); return }
        bg { log(AaosClusterProbe.setCarIntProperty(this, propId, 0, value)) }
    }

    private fun aaosSetFree() {
        val fid = parseId(fidField.text.toString()) ?: return logUi("bad featureId")
        val v = valField.text.toString().trim().toIntOrNull() ?: return logUi("bad value")
        if (!AaosClusterProbe.isAaos(this)) { logUi("not AAOS — skipped"); return }
        bg { log(AaosClusterProbe.setCarIntProperty(this, fid, 0, v)) }
    }

    private fun printKnownIds() {
        log(
            "DashCast (OpenBYD 2.2 RE)   vs   repo (DiLink 5.1)\n" +
            "NAVI_STATUS   0x43C0007A   |   0x43E0003A\n" +
            "GUIDE_SIMPLE  0x43D10010   |   0x43F01010\n" +
            "ROAD_DISTANCE 0x43D10030   |   0x43F01030\n" +
            "FRONT_CROSS   0x43D10018   |   0x43F01018\n" +
            "NEXT_PATHNAME 0x43E10008   |   0x43FA1008\n" +
            "LEAD_MSG      0x43EC0010   |   0x43F08010\n" +
            "SETTING_NAVI  0x4C1A0015   |   0x4C10E015"
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Accepts "0x..", "..h" hex or decimal; values up to 0xFFFFFFFF fit a signed int. */
    private fun parseId(raw: String): Int? = try {
        java.lang.Long.decode(raw.trim()).toInt()
    } catch (t: Throwable) { null }

    private inline fun bg(crossinline work: () -> Unit) {
        Thread {
            try { work() } catch (t: Throwable) { log("ERROR: ${t.javaClass.simpleName}: ${t.message}") }
        }.start()
    }

    private fun log(msg: String) = runOnUiThread { logUi(msg) }

    private fun logUi(msg: String) {
        out.append("[${ts.format(Date())}] $msg\n")
        (out.parent as? ScrollView)?.post { (out.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    // ── tiny view builders ──────────────────────────────────────────────────

    private fun header(t: String) = TextView(this).apply {
        text = t
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 12f }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun field(initial: String, type: Int) = EditText(this).apply {
        setText(initial)
        inputType = type
        gravity = Gravity.START
    }

    private fun rowLp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
