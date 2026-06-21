package com.byd.dashcast.ui.main

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.byd.dashcast.R
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.cluster.dpi.ClusterDpiPrefs
import com.byd.dashcast.cluster.dpi.ClusterResizeActivity
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.model.AppInfo
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Shows the per-app bottom sheet and DPI override dialogs.
 * Stateless — call [show] from the host activity.
 */
object AppActionSheet {

    private const val TAG = "AppActionSheet"

    interface Host {
        fun getContext(): Context
        fun isActivityAlive(): Boolean
        fun startActivity(intent: Intent)
        fun getClusterServiceIfBound(): ClusterService?
        /** Package currently projected on the cluster. */
        fun getCurrentClusterPkg(): String?
        /** Package last sent to the main display. */
        fun getMainDisplayPkg(): String?
        /** Packages currently projected via a fission layout (headless orchestrator). */
        fun getLayoutPkgs(): Set<String>?
        fun onSendToDashboard(app: AppInfo)
        fun onSendToMain(app: AppInfo)
        fun onKillApp(app: AppInfo)
        fun onToggleFavorite(app: AppInfo)
        fun onSetAutoLaunch(app: AppInfo, enable: Boolean)
    }

    // ── Public entry point ────────────────────────────────────────────────────

    @JvmStatic
    fun show(app: AppInfo?, host: Host) {
        if (app == null || !host.isActivityAlive()) return

        val ctx = host.getContext()
        val dialog = BottomSheetDialog(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_app_actions, null)
        dialog.setContentView(v)

        val icon = v.findViewById<ImageView>(R.id.sheet_icon)
        val name = v.findViewById<TextView>(R.id.sheet_name)
        val pkg = v.findViewById<TextView>(R.id.sheet_pkg)
        val swAuto = v.findViewById<MaterialSwitch>(R.id.sheet_sw_auto)
        val rowFav = v.findViewById<View>(R.id.sheet_action_favorite)
        val lblFav = v.findViewById<TextView>(R.id.sheet_lbl_favorite)
        val rowToMain = v.findViewById<View>(R.id.sheet_action_to_main)
        val rowToClus = v.findViewById<View>(R.id.sheet_action_to_cluster)
        val rowResize = v.findViewById<View>(R.id.sheet_action_resize)
        val rowDpi = v.findViewById<View>(R.id.sheet_action_dpi)
        val tvDpiVal = v.findViewById<TextView>(R.id.sheet_dpi_value)
        val rowKill = v.findViewById<View>(R.id.sheet_action_kill)

        icon.setImageDrawable(app.icon)
        name.text = app.appName
        pkg.text = app.packageName
        lblFav.setText(if (app.isFavorite) R.string.sheet_remove_favorite else R.string.sheet_add_favorite)

        swAuto.isChecked = app.isAutoLaunch
        swAuto.setOnCheckedChangeListener { _, checked -> host.onSetAutoLaunch(app, checked) }

        rowFav.setOnClickListener {
            host.onToggleFavorite(app)
            dialog.dismiss()
        }

        val isActive = app.packageName == host.getCurrentClusterPkg()
        val isOnMain = app.packageName == host.getMainDisplayPkg()
        // Fission-layout app: projected via the headless orchestrator on a dedicated VD slot.
        // The classic move/resize actions go through ClusterService and don't know about the
        // fission VD, so only expose kill (routed to FissionOrchestrator.killLayoutSlot).
        val layoutPkgs = host.getLayoutPkgs()
        val isLayout = layoutPkgs != null && layoutPkgs.contains(app.packageName)
        rowToMain.visibility = if (isActive) View.VISIBLE else View.GONE
        rowToClus.visibility = if (!isActive && !isLayout) View.VISIBLE else View.GONE
        rowResize.visibility = if (isActive) View.VISIBLE else View.GONE
        rowKill.visibility = if (isActive || isOnMain || isLayout) View.VISIBLE else View.GONE

        rowToMain.setOnClickListener { host.onSendToMain(app); dialog.dismiss() }
        rowToClus.setOnClickListener { host.onSendToDashboard(app); dialog.dismiss() }

        rowResize.setOnClickListener {
            val svc = host.getClusterServiceIfBound()
            var displayId = svc?.getDisplayId() ?: 1
            if (displayId < 0) displayId = 1
            val resizeIntent = Intent(ctx, ClusterResizeActivity::class.java)
            resizeIntent.putExtra(ClusterResizeActivity.EXTRA_PACKAGE, app.packageName)
            resizeIntent.putExtra(ClusterResizeActivity.EXTRA_DISPLAY_ID, displayId)
            // Re-open on the last saved rectangle so the frame reflects the current size
            // instead of resetting to full-screen.
            readSavedClusterRect(ctx, app.packageName)?.let {
                resizeIntent.putExtra(ClusterResizeActivity.EXTRA_INIT_LTRB, it)
            }
            host.startActivity(resizeIntent)
            dialog.dismiss()
        }

        val curDpi = ClusterDpiPrefs.getDpi(ctx, app.packageName)
        if (curDpi > 0) {
            tvDpiVal.text = ctx.getString(R.string.sheet_dpi_value_fmt, curDpi)
        } else {
            tvDpiVal.setText(R.string.sheet_dpi_value_default)
        }
        rowDpi.setOnClickListener {
            dialog.dismiss()
            showDpiDialog(app, host)
        }

        rowKill.setOnClickListener {
            host.onKillApp(app)
            dialog.dismiss()
        }

        dialog.show()
        // v0.9.73 — open fully expanded (skip the half-collapsed peek that hides actions).
        try {
            val b = dialog.behavior
            b.skipCollapsed = true
            b.state = BottomSheetBehavior.STATE_EXPANDED
        } catch (t: Throwable) {
            AppLogger.w(TAG, "BottomSheet expand failed: " + t.message)
        }
    }

    // ── DPI dialogs ───────────────────────────────────────────────────────────

    // v1.2.81 — Cluster per-app DPI override picker. "Custom" accepts MIN..MAX dpi.
    @SuppressLint("InflateParams")
    private fun showDpiDialog(app: AppInfo?, host: Host) {
        if (app == null || !host.isActivityAlive()) return

        val ctx = host.getContext()
        val presets = intArrayOf(120, 160, 200, 240, 280, 320)
        val current = ClusterDpiPrefs.getDpi(ctx, app.packageName)

        val labels = arrayOfNulls<String>(presets.size + 2)
        labels[0] = ctx.getString(R.string.dpi_default)
        for (i in presets.indices) {
            labels[i + 1] = ctx.getString(R.string.dpi_value_fmt, presets[i])
        }
        labels[labels.size - 1] = ctx.getString(R.string.dpi_custom)

        var checked = 0
        if (current > 0) {
            checked = labels.size - 1
            for (i in presets.indices) {
                if (presets[i] == current) {
                    checked = i + 1
                    break
                }
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.dpi_dialog_title, app.appName))
            .setSingleChoiceItems(labels, checked) { d, which ->
                when {
                    which == 0 -> {
                        ClusterDpiPrefs.setDpi(ctx, app.packageName, 0)
                        d.dismiss()
                        Toast.makeText(ctx, R.string.dpi_applied_next_launch, Toast.LENGTH_SHORT).show()
                    }
                    which == labels.size - 1 -> {
                        d.dismiss()
                        showDpiCustomDialog(app, current, host)
                    }
                    else -> {
                        ClusterDpiPrefs.setDpi(ctx, app.packageName, presets[which - 1])
                        d.dismiss()
                        Toast.makeText(ctx, R.string.dpi_applied_next_launch, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // SetTextI18n: prefill is a plain integer dpi value (String.valueOf in the
    // original Java) — .toString() trips the stricter Kotlin lint.
    @SuppressLint("InflateParams", "SetTextI18n")
    private fun showDpiCustomDialog(app: AppInfo, prefill: Int, host: Host) {
        val ctx = host.getContext()
        val input = EditText(ctx)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = ctx.getString(R.string.dpi_custom_hint, ClusterDpiPrefs.MIN_DPI, ClusterDpiPrefs.MAX_DPI)
        if (prefill > 0) input.setText(prefill.toString())

        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(ctx)
        wrap.setPadding(pad, pad / 2, pad, 0)
        wrap.addView(input)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.dpi_custom)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val dpi = try {
                    input.text.toString().trim().toInt()
                } catch (e: NumberFormatException) {
                    0
                }
                if (dpi < ClusterDpiPrefs.MIN_DPI || dpi > ClusterDpiPrefs.MAX_DPI) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.dpi_custom_hint, ClusterDpiPrefs.MIN_DPI, ClusterDpiPrefs.MAX_DPI),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                ClusterDpiPrefs.setDpi(ctx, app.packageName, dpi)
                Toast.makeText(ctx, R.string.dpi_applied_next_launch, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Reads the per-app hand-drawn cluster rectangle ("l,t,r,b"), or null if none/invalid. */
    private fun readSavedClusterRect(ctx: Context, pkg: String): IntArray? {
        val s = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SettingsActivity.PREF_CLUSTER_RECT_PREFIX + pkg, null) ?: return null
        val parts = s.split(",")
        if (parts.size != 4) return null
        return try {
            intArrayOf(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
        } catch (e: NumberFormatException) {
            null
        }
    }
}
