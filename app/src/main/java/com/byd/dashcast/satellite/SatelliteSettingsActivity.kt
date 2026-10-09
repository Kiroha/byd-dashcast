package com.byd.dashcast.satellite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
                if (!success) toast(R.string.satellite_unavailable)
            }
        }.apply { isDaemon = true; name = "satellite-settings" }.start()
    }

    private fun showPairing() {
        Thread {
            val profile = try {
                val hosts = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp }.flatMap { it.inetAddresses.toList() }
                    .filter { !it.isLoopbackAddress && SatelliteProtocol.isLocalAddress(it) }
                    .mapNotNull { it.hostAddress?.substringBefore('%') }.distinct()
                JSONObject().put("version", SatelliteProtocol.VERSION).put("hosts", JSONArray(hosts))
                    .put("port", SatellitePrefs.PORT).put("path", SatelliteProtocol.PATH)
                    .put("certificateSha256", SatelliteTls.fingerprint())
                    .put("token", SatellitePrefs.token(applicationContext)).toString(2)
            } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (profile == null) { toast(R.string.satellite_unavailable); return@runOnUiThread }
                val text = TextView(this).apply { this.text = profile; setTextIsSelectable(true); setPadding(24, 16, 24, 16) }
                val dialog = AlertDialog.Builder(this).setTitle(R.string.satellite_pair).setView(text)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.copy) { _, _ ->
                        val clip = ClipData.newPlainText("DashCast satellite", profile)
                        if (Build.VERSION.SDK_INT >= 33) {
                            clip.description.extras = PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                        }
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(clip)
                    }.show()
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }.start()
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
