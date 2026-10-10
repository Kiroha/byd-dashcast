package com.byd.dashcast.satellite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.graphics.Typeface
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageView
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.R
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.util.LocaleHelper
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

/** Explicit opt-in, source selection and local pairing. Credentials never enter diagnostics. */
class SatelliteSettingsActivity : AppCompatActivity() {
    private lateinit var receiverSwitch: MaterialSwitch
    private lateinit var remoteSwitch: MaterialSwitch
    private var changing = false
    private lateinit var connectionStatus: StatusRow
    private lateinit var guidanceStatus: StatusRow
    private var lastStatus: SatelliteStatusTracker.Snapshot? = null
    private val pairingHandler = Handler(Looper.getMainLooper())
    private var pairingDialog: AlertDialog? = null
    private var pairingAttempt: Long? = null
    private var pairingCode: TextView? = null
    private var pairingCountdown: TextView? = null
    private var pairingAddress: TextView? = null
    private var pairingVisible = false
    private val pairingTicker = object : Runnable {
        override fun run() {
            if (!pairingVisible) return
            renderStatus()
            renderPairing(SatellitePairingSession.snapshot())
            pairingHandler.postDelayed(this, 500)
        }
    }
    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.applyLocale(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.apply { title = getString(R.string.satellite_title); setDisplayHomeAsUpEnabled(true) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        column.addView(TextView(this).apply { setText(R.string.satellite_description) })
        val statuses = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(16)
            setPadding(padding, padding, padding, padding)
        }
        connectionStatus = StatusRow(R.string.satellite_status_connection).apply {
            id = R.id.satellite_connection_status
        }
        guidanceStatus = StatusRow(R.string.satellite_status_guidance).apply {
            id = R.id.satellite_guidance_status
        }
        statuses.addView(connectionStatus)
        statuses.addView(guidanceStatus)
        column.addView(MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.md_surface_container))
            strokeColor = ContextCompat.getColor(context, R.color.md_outline_variant)
            strokeWidth = dp(1)
            addView(statuses)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(12) })
        renderStatus()
        remoteSwitch = MaterialSwitch(this).apply {
            setText(R.string.satellite_guidance)
            isChecked = SatellitePrefs.usesRemoteGuidance(context)
            isEnabled = SatellitePrefs.isEnabled(context)
            setOnCheckedChangeListener { _, enabled ->
                change { NavigationInputRouter.selectRemote(it, enabled) }
            }
        }
        receiverSwitch = MaterialSwitch(this).apply {
            setText(R.string.satellite_enable)
            isChecked = SatellitePrefs.isEnabled(context)
            setOnCheckedChangeListener { _, enabled ->
                change { app ->
                    NavigationInputRouter.enableReceiver(app, enabled)
                    if (enabled) SatelliteReceiverService.start(app) else {
                        SatelliteReceiverService.stop(app)
                        runOnUiThread { SatelliteVideoActivity.closeViewer() }
                    }
                }
            }
        }
        column.addView(receiverSwitch)
        column.addView(remoteSwitch)
        fun button(label: Int, action: () -> Unit) {
            column.addView(Button(this).apply { setText(label); setOnClickListener { action() } })
        }
        button(R.string.satellite_pair) { showPairing() }
        button(R.string.satellite_revoke) {
            AlertDialog.Builder(this).setMessage(R.string.satellite_revoke_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    change { app ->
                        SatellitePrefs.rotateToken(app)
                        SatelliteReceiverService.start(app, reload = true)
                    }
                }.show()
        }
        button(R.string.satellite_preview) {
            if (SatellitePrefs.isEnabled(this)) startActivity(Intent(this, SatelliteVideoActivity::class.java))
            else toast(R.string.satellite_enable_first)
        }
        button(R.string.satellite_project) { projectVideo() }
    }

    private fun change(action: (Context) -> Unit) {
        if (changing) return
        changing = true
        receiverSwitch.isEnabled = false
        remoteSwitch.isEnabled = false
        val app = applicationContext
        Thread {
            val success = try { action(app); true } catch (_: Exception) { false }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Keep listeners gated while reflecting the persisted selection.
                receiverSwitch.isChecked = SatellitePrefs.isEnabled(app)
                remoteSwitch.isChecked = SatellitePrefs.usesRemoteGuidance(app)
                receiverSwitch.isEnabled = true
                remoteSwitch.isEnabled = receiverSwitch.isChecked
                changing = false
                renderStatus()
                if (!success) toast(R.string.satellite_unavailable)
            }
        }.apply { isDaemon = true; name = "satellite-settings" }.start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Icons are decorative; each accessible row exposes both its category and its current label. */
    private inner class StatusRow(private val title: Int) : LinearLayout(this@SatelliteSettingsActivity) {
        private val icon = ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val label = TextView(context).apply {
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.md_on_surface))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        init {
            orientation = VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isScreenReaderFocusable = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(TextView(context).apply {
                setText(title)
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.md_on_surface_variant))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(LinearLayout(context).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(icon, LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
                addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        fun show(text: Int, color: Int, glyph: Int) {
            label.setText(text)
            icon.setImageResource(glyph)
            icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, color))
            contentDescription = getString(R.string.satellite_status_accessibility, getString(title), getString(text))
        }
    }

    private fun renderStatus() {
        val state = SatelliteStatus.snapshot(this)
        if (lastStatus == state) return
        lastStatus = state
        when (state.connection) {
            SatelliteStatusTracker.Connection.DISABLED -> connectionStatus.show(R.string.satellite_connection_disabled,
                R.color.md_on_surface_variant, R.drawable.ic_stop)
            SatelliteStatusTracker.Connection.STARTING -> connectionStatus.show(R.string.satellite_connection_starting,
                R.color.md_on_surface_variant, R.drawable.ic_refresh)
            SatelliteStatusTracker.Connection.WAITING -> connectionStatus.show(R.string.satellite_connection_waiting,
                R.color.md_status_warn, R.drawable.ic_autorenew)
            SatelliteStatusTracker.Connection.CONNECTED -> connectionStatus.show(R.string.satellite_connection_connected,
                R.color.md_status_ok, R.drawable.ic_check)
            SatelliteStatusTracker.Connection.UNAVAILABLE -> connectionStatus.show(R.string.satellite_connection_unavailable,
                R.color.md_status_err, R.drawable.ic_close)
        }
        when (state.guidance) {
            SatelliteStatusTracker.Guidance.DISABLED -> guidanceStatus.show(R.string.satellite_guidance_disabled,
                R.color.md_on_surface_variant, R.drawable.ic_pause)
            SatelliteStatusTracker.Guidance.WAITING -> guidanceStatus.show(R.string.satellite_guidance_waiting,
                R.color.md_status_warn, R.drawable.ic_autorenew)
            SatelliteStatusTracker.Guidance.ACTIVE -> guidanceStatus.show(R.string.satellite_guidance_active,
                R.color.md_status_ok, R.drawable.ic_check)
            SatelliteStatusTracker.Guidance.EXPIRED -> guidanceStatus.show(R.string.satellite_guidance_expired,
                R.color.md_status_warn, R.drawable.ic_info_outline)
        }
    }

    private fun showPairing() {
        if (!SatellitePrefs.isEnabled(this)) { toast(R.string.satellite_enable_first); return }
        SatellitePairingSession.snapshot()?.let { renderPairing(it); return }
        val app = applicationContext
        SatelliteReceiverService.start(app)
        val attempt = SatellitePairingSession.begin()
        val state = SatellitePairingSession.snapshot() ?: return
        renderPairing(state)
        // Capture the application and attempt only. The receiver owns the window after leaving this screen.
        Thread {
            val hosts = try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp }.flatMap { it.inetAddresses.toList() }
                    .filter { !it.isLoopbackAddress && SatelliteProtocol.isLocalAddress(it) }
                    .mapNotNull { it.hostAddress?.substringBefore('%') }.distinct()
            } catch (_: Exception) { emptyList() }
            val profile = try {
                check(hosts.isNotEmpty())
                JSONObject().put("version", SatelliteProtocol.VERSION).put("hosts", JSONArray(hosts))
                    .put("port", SatellitePrefs.PORT).put("path", SatelliteProtocol.PATH)
                    .put("certificateSha256", SatelliteTls.fingerprint())
                    .put("token", SatellitePrefs.token(app)).toString(2)
            } catch (_: Exception) { null }
            val current = SatellitePairingSession.snapshot()?.takeIf { it.attempt == attempt }
            if (profile == null || current == null || !SatellitePrefs.isEnabled(app)) {
                SatellitePairingSession.close(attempt)
                return@Thread
            }
            val server = SatellitePairingServer(profile, state.code,
                lifetimeMs = current.remainingMs, onClosed = { SatellitePairingSession.close(attempt) })
            if (!SatellitePairingSession.attach(attempt, server, profile, hosts) || !server.start()) {
                server.close()
                SatellitePairingSession.close(attempt)
            }
        }.apply { isDaemon = true; name = "satellite-pair-prepare" }.start()
    }

    private fun renderPairing(state: SatellitePairingSession.Snapshot?) {
        if (!pairingVisible) return
        if (state == null) { detachPairingDialog(); return }
        if (pairingAttempt != state.attempt || pairingDialog == null) {
            detachPairingDialog()
            pairingAttempt = state.attempt
            val codeText = TextView(this).apply {
                textSize = 32f
                typeface = Typeface.MONOSPACE
                isSaveEnabled = false
            }
            val countdown = TextView(this)
            val address = TextView(this)
            pairingCode = codeText
            pairingCountdown = countdown
            pairingAddress = address
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding / 2, padding, padding / 2)
                addView(TextView(context).apply { setText(R.string.satellite_pair_instructions) })
                addView(codeText)
                addView(countdown)
                addView(address)
            }
            val dialog = AlertDialog.Builder(this).setTitle(R.string.satellite_pair).setView(content)
                .setNegativeButton(android.R.string.cancel) { _, _ -> SatellitePairingSession.close(state.attempt) }
                .setNeutralButton(R.string.satellite_pair_copy_advanced, null).create()
            pairingDialog = dialog
            dialog.setOnDismissListener {
                if (pairingVisible && pairingAttempt == state.attempt) {
                    SatellitePairingSession.close(state.attempt)
                }
                codeText.text = ""
            }
            dialog.show()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                SatellitePairingSession.snapshot()?.takeIf { it.attempt == state.attempt }?.profile?.let {
                    copyProfile(it)
                    SatellitePairingSession.close(state.attempt)
                    detachPairingDialog()
                }
            }
        }
        pairingCode?.text = if (state.ready) SatellitePairingCode.format(state.code)
            else getString(R.string.satellite_pair_preparing)
        pairingCountdown?.text = getString(R.string.satellite_pair_remaining, (state.remainingMs + 999) / 1_000)
        pairingAddress?.text = if (state.addresses.isEmpty()) "" else
            getString(R.string.satellite_pair_address, state.addresses.joinToString(", "))
        pairingDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = state.ready && state.profile != null
    }

    private fun copyProfile(profile: String) {
        val clip = ClipData.newPlainText("DashCast satellite", profile)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    /** Leaving for the Tbox detaches views only; the foreground receiver keeps the original deadline. */
    private fun detachPairingDialog() {
        pairingAttempt = null
        pairingDialog?.setOnDismissListener(null)
        pairingDialog?.dismiss()
        pairingDialog = null
        pairingCode?.text = ""
        pairingCode = null
        pairingCountdown = null
        pairingAddress = null
    }

    override fun onStart() {
        super.onStart()
        pairingVisible = true
        pairingTicker.run()
    }

    override fun onStop() {
        pairingVisible = false
        pairingHandler.removeCallbacksAndMessages(null)
        detachPairingDialog()
        super.onStop()
    }

    private fun projectVideo() {
        if (!SatellitePrefs.isEnabled(this)) { toast(R.string.satellite_enable_first); return }
        // Reuse a READY projection only. Do not start a second activation sequence from settings.
        val cluster = ClusterService.getInstance()
        val display = cluster?.displayId ?: -1
        if (display <= 0) { toast(R.string.satellite_cluster_first); return }
        try {
            val intent = Intent(this, SatelliteVideoActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(SatelliteVideoActivity.EXPECTED_DISPLAY, display)
            startActivity(intent, cluster!!.launcher.createLaunchOptions(display).toBundle())
        } catch (_: Exception) { toast(R.string.satellite_unavailable) }
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
