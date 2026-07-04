package com.byd.dashcast.ui.nav

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.core.net.toUri

import com.byd.dashcast.R
import com.byd.dashcast.report.BugWizardActivity
import com.byd.dashcast.ui.diag.DiagActivity
import com.byd.dashcast.ui.diag.SysInfoActivity
import com.byd.dashcast.ui.log.LogActivity
import com.byd.dashcast.ui.settings.SettingsActivity

/**
 * Wires the static nav-rail entries (Settings, Diag, SysInfo, Log, Help).
 * Call [wire] once from onCreate after setContentView.
 * Dynamic entries (Hotspot, Layouts) are handled by NavigationCoordinator and NavRailLayouts.
 */
object NavRailSetup {

    @JvmStatic
    fun wire(host: Activity?) {
        if (host == null) return

        wireNav(host, R.id.nav_settings, SettingsActivity::class.java)
        wireNav(host, R.id.nav_diag, DiagActivity::class.java)
        wireNav(host, R.id.nav_sysinfo, SysInfoActivity::class.java)
        wireNav(host, R.id.nav_log, LogActivity::class.java)
        wireNav(host, R.id.nav_bug_report, BugWizardActivity::class.java)

        val navHelp = host.findViewById<View>(R.id.nav_help)
        navHelp?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW,
                        "https://github.com/Kiroha/byd-dashcast".toUri())
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                host.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(host.applicationContext,
                        R.string.main_nav_help, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun wireNav(host: Activity, viewId: Int, target: Class<out Activity>) {
        val v = host.findViewById<View>(viewId)
        v?.setOnClickListener {
            host.startActivity(Intent(host, target))
        }
    }
}
