package com.byd.dashcast.ui.main

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast

import androidx.core.content.edit

import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.ui.diag.DiagActivity
import com.byd.dashcast.util.LocaleHelper
import com.byd.dashcast.ui.log.LogActivity
import com.byd.dashcast.update.OtaProgressUi
import com.byd.dashcast.R
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.ui.diag.SysInfoActivity
import com.byd.dashcast.update.UpdateChecker
import com.byd.dashcast.ui.welcome.WelcomeActivity

/**
 * Builds and shows the main overflow PopupMenu. Stateless — call [show].
 */
object OverflowMenuHelper {

    private const val TAG = "OverflowMenuHelper"

    interface Host {
        fun getContext(): Context
        fun startActivity(intent: Intent)
        fun isAppListGridMode(): Boolean
        fun toggleAppListViewMode()
        fun onOriginCluster()
        fun showUsageStats()
    }

    @JvmStatic
    fun show(anchor: View, host: Host) {
        val ctx = host.getContext()
        val popup = PopupMenu(ctx, anchor)

        // Group 0: user actions
        popup.menu.add(0, 1, 0, ctx.getString(R.string.menu_settings))
        popup.menu.add(0, 5, 1, ctx.getString(R.string.menu_language))
        popup.menu.add(0, 6, 2, ctx.getString(R.string.menu_check_updates))
        popup.menu.add(0, 7, 3,
                if (host.isAppListGridMode())
                    ctx.getString(R.string.menu_view_list)
                else
                    ctx.getString(R.string.menu_view_grid))
        popup.menu.add(0, 8, 4, ctx.getString(R.string.btn_origin_cluster))
        popup.menu.add(0, 9, 5, ctx.getString(R.string.menu_usage_stats))
        // Group 1: dev tools (with divider)
        popup.menu.add(1, 2, 6, ctx.getString(R.string.menu_diagnostic))
        popup.menu.add(1, 3, 7, ctx.getString(R.string.menu_system_report))
        popup.menu.add(1, 4, 8, ctx.getString(R.string.menu_log))

        // Visual divider between groups (API 28+, safe on our API 29 target)
        try {
            popup.menu.javaClass
                    .getDeclaredMethod("setGroupDividerEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(popup.menu, true)
        } catch (ignored: Exception) {
            AppLogger.d(TAG, "setGroupDividerEnabled unavailable: ${ignored.message}")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    host.startActivity(Intent(ctx, SettingsActivity::class.java))
                    true
                }
                7 -> {
                    host.toggleAppListViewMode()
                    Toast.makeText(ctx,
                            if (host.isAppListGridMode())
                                ctx.getString(R.string.toast_grid_mode_enabled)
                            else
                                ctx.getString(R.string.toast_list_mode_enabled),
                            Toast.LENGTH_SHORT).show()
                    true
                }
                8 -> {
                    host.onOriginCluster()
                    true
                }
                2 -> {
                    host.startActivity(Intent(ctx, DiagActivity::class.java))
                    true
                }
                3 -> {
                    host.startActivity(Intent(ctx, SysInfoActivity::class.java))
                    true
                }
                4 -> {
                    host.startActivity(Intent(ctx, LogActivity::class.java))
                    true
                }
                5 -> {
                    val p = ctx.getSharedPreferences(
                            LocaleHelper.PREF_FILE, Context.MODE_PRIVATE)
                    p.edit { remove(LocaleHelper.PREF_SETUP_DONE) }
                    val welcome = Intent(ctx, WelcomeActivity::class.java)
                    welcome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    host.startActivity(welcome)
                    true
                }
                6 -> {
                    if (ctx is android.app.Activity) {
                        UpdateChecker.checkUpdate(ctx,
                                OtaProgressUi.makeListener(ctx, true))
                    }
                    true
                }
                9 -> {
                    host.showUsageStats()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}
