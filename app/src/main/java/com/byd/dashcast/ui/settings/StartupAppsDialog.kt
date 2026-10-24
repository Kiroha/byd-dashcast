package com.byd.dashcast.ui.settings

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.R
import com.byd.dashcast.data.apps.AppRepository
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.data.prefs.StartupAppsMode
import com.byd.dashcast.data.prefs.StartupAppsSelection
import com.byd.dashcast.fission.LayoutPrefs

/** The settings row and app shortcut edit the same choice; opening a dialog never saves it. */
object StartupAppsDialog {
    fun summary(host: AppCompatActivity): String {
        val choice = ClusterPrefs.getStartupApps(host)
        return when (choice.mode) {
            StartupAppsMode.NONE -> host.getString(R.string.startup_none)
            StartupAppsMode.LAST_SESSION -> host.getString(R.string.startup_last_session)
            StartupAppsMode.APP -> {
                val pkg = choice.target.orEmpty()
                val name = try {
                    if (host.packageManager.getLaunchIntentForPackage(pkg) == null) null else
                        host.packageManager.getApplicationLabel(host.packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { null }
                host.getString(if (name == null) R.string.startup_unavailable_fmt else R.string.startup_app_fmt, name ?: pkg)
            }
            StartupAppsMode.LAYOUT -> {
                val layout = LayoutPrefs.load(host).firstOrNull { it.id == choice.target }
                val available = layout?.slots?.any { it.packageName?.let(host.packageManager::getLaunchIntentForPackage) != null } == true
                host.getString(if (available) R.string.startup_layout_fmt else R.string.startup_unavailable_fmt,
                    layout?.name ?: choice.target.orEmpty())
            }
        }
    }

    fun show(host: AppCompatActivity, repository: AppRepository, preselectedApp: String? = null, changed: () -> Unit) {
        val current = ClusterPrefs.getStartupApps(host)
        val modes = StartupAppsMode.entries.toTypedArray()
        val labels = arrayOf(R.string.startup_none, R.string.startup_last_session,
            R.string.startup_choose_app, R.string.startup_choose_layout).map(host::getString).toTypedArray()
        var selected = if (preselectedApp == null) current.mode.ordinal else StartupAppsMode.APP.ordinal
        AlertDialog.Builder(host)
            .setTitle(R.string.setting_boot_auto_start)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.startup_continue) { _, _ ->
                when (val mode = modes[selected]) {
                    StartupAppsMode.NONE, StartupAppsMode.LAST_SESSION -> save(host, StartupAppsSelection(mode), changed)
                    StartupAppsMode.APP -> {
                        if (preselectedApp != null) confirmApp(host, preselectedApp, changed)
                        else {
                            var delivered = false
                            repository.loadApps(host) { apps ->
                                if (!delivered && !host.isFinishing && !host.isDestroyed) {
                                    delivered = true
                                    pick(host, apps.map { it.appName }, apps.map { it.packageName }, current.target,
                                        R.string.startup_choose_app) { save(host, StartupAppsSelection(mode, it), changed) }
                                }
                            }
                        }
                    }
                    StartupAppsMode.LAYOUT -> {
                        val layouts = LayoutPrefs.load(host).filter { it.slots.any { slot -> !slot.packageName.isNullOrBlank() } }
                        pick(host, layouts.map { it.name }, layouts.map { it.id }, current.target,
                            R.string.startup_choose_layout) { save(host, StartupAppsSelection(mode, it), changed) }
                    }
                }
            }.show()
    }

    private fun confirmApp(host: AppCompatActivity, pkg: String, changed: () -> Unit) {
        val name = try { host.packageManager.getApplicationLabel(host.packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (_: Exception) { pkg }
        AlertDialog.Builder(host).setTitle(R.string.startup_choose_app)
            .setMessage(host.getString(R.string.startup_confirm_app_fmt, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> save(host, StartupAppsSelection(StartupAppsMode.APP, pkg), changed) }
            .show()
    }

    private fun pick(host: AppCompatActivity, labels: List<String>, targets: List<String>, current: String?,
                     title: Int, selected: (String) -> Unit) {
        if (targets.isEmpty()) {
            AlertDialog.Builder(host).setTitle(title).setMessage(R.string.startup_no_targets)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        var index = targets.indexOf(current)
        val dialog = AlertDialog.Builder(host).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), index) { d, which ->
                index = which
                (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }.setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> if (index >= 0) selected(targets[index]) }
            .create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = index >= 0 }
        dialog.show()
    }

    private fun save(host: AppCompatActivity, choice: StartupAppsSelection, changed: () -> Unit) {
        if (choice.mode == StartupAppsMode.APP && host.packageManager.getLaunchIntentForPackage(choice.target!!) == null) {
            AlertDialog.Builder(host).setMessage(R.string.startup_no_targets).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        ClusterPrefs.setStartupApps(host, choice)
        changed()
    }
}
