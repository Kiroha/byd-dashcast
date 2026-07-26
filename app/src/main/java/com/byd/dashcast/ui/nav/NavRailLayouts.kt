package com.byd.dashcast.ui.nav

import android.app.Activity
import android.content.Intent
import android.view.View
import com.byd.dashcast.fission.LayoutManagerActivity

/**
 * Wires the "Layouts" nav-rail entry present on every activity from v1.4.9-beta.
 * Always visible (no runtime gate) — tap navigates to [LayoutManagerActivity].
 */
object NavRailLayouts {

    @JvmStatic
    fun apply(host: Activity?, viewId: Int, finishOnNav: Boolean) {
        if (host == null) return
        val v = host.findViewById<View>(viewId) ?: return
        v.setOnClickListener {
            host.startActivity(Intent(host, LayoutManagerActivity::class.java))
            if (finishOnNav) host.finish()
        }
    }
}
