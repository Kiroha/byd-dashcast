package com.byd.dashcast.ui.diag

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Diagnostics host — intentionally EMPTIED.
 *
 * The former ~3600-line Java screen and all its Java test runners (DiLink2/4/5,
 * Mirror, Daemon) were removed to be rebuilt cleanly in Kotlin rather than
 * migrated. This stub keeps the launcher entry and the manifest component
 * (`.ui.diag.DiagActivity`) valid so no navigation or production path breaks in
 * the meantime.
 *
 * The removed diagnostics were dev-only and were never wired into any production
 * runtime path (cluster / HUD prod / mirror / boot / hotspot) — confirmed by a
 * full inbound-reference + manifest + type-usage audit before removal.
 */
class DiagActivity : Activity() {

    @SuppressLint("SetTextI18n") // dev-only diagnostics screen (English, SetTextI18n exempt)
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val tv = TextView(this)
        tv.text = "Diagnostics — being rebuilt in Kotlin."
        tv.gravity = Gravity.CENTER
        setContentView(tv)
    }
}
