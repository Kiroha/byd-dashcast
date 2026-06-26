package com.byd.dashcast.hud

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A full-screen, high-contrast [Presentation] used to probe whether a normal app can
 * render its own content on the AAOS instrument cluster (a secondary/presentation
 * display) via the standard Android secondary-display API — the one path we never
 * tried (all prior attempts used privileged SurfaceControl / `am start --display`).
 *
 * If this shows on the cluster, app-content projection via Presentation is viable.
 */
class AaosClusterPresentation(outerContext: Context, display: Display) :
    Presentation(outerContext, display) {

    @android.annotation.SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(0x15, 0x65, 0xC0)) // bright blue
        }
        root.addView(TextView(context).apply {
            text = "DASHCAST"
            textSize = 80f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(context).apply {
            text = "cluster projection test (display ${display.displayId})"
            textSize = 22f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
        })
        setContentView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
