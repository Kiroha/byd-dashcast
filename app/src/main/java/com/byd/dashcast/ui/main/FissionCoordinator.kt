package com.byd.dashcast.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.byd.dashcast.R
import com.byd.dashcast.fission.LayoutManagerActivity
import com.byd.dashcast.fission.LayoutMiniPreviewView
import com.byd.dashcast.fission.LayoutPrefs
import com.byd.dashcast.fission.LayoutPreset
import com.byd.dashcast.proxy.DaemonConfig
import com.google.android.material.card.MaterialCardView

/**
 * Owns the layout carousel on the Apps page: horizontally scrollable cards — free
 * mode, one card per saved preset (with a mini preview of its zones), and a manage
 * card opening {@link LayoutManagerActivity}. Tapping a card sets the favourite
 * layout, which is what the auto-start activates at DashCast launch.
 *
 * Call {@link #refresh()} from onCreate and onResume to recompute visibility and
 * rebuild the cards (presets may have changed in the Layouts screen).
 *
 * Kotlin port of the former FissionCoordinator.java — behaviour identical.
 * Interop notes for the single call site (MainActivity.kt):
 *  - `carouselSection` / `cardsContainer` stay NULLABLE: {@link #refresh()} null-checks both
 *    before touching them, and MainActivity passes bare `findViewById(...)` results, so a
 *    non-null parameter would turn that tolerated null into a runtime intrinsic throw.
 *  - [Host] keeps two members, so it stays a plain interface (no SAM conversion at the call
 *    site); `getContext()` is non-null to match `override fun getContext(): Context = this`
 *    and `startActivity(Intent)` is served by Activity's own inherited method.
 */
class FissionCoordinator(
    private val mSection: View?,
    private val mCardsContainer: LinearLayout?,
    private val mHost: Host
) {

    interface Host {
        fun getContext(): Context
        fun startActivity(intent: Intent)
    }

    init {
        refresh()
    }

    /** Recomputes visibility and rebuilds the cards. Safe to call repeatedly. */
    fun refresh() {
        val section = mSection
        val cardsContainer = mCardsContainer
        if (section == null || cardsContainer == null) return
        val ctx = mHost.getContext()
        val enabled = DaemonConfig.isFissionModeEnabled(ctx)
        section.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return

        val presets = LayoutPrefs.load(ctx)
        val favId = LayoutPrefs.getValidFavoriteId(ctx, presets)

        cardsContainer.removeAllViews()

        // Free mode card (selected when no favourite is set).
        cardsContainer.addView(buildPresetCard(ctx, null, favId == null))

        for (p in presets) {
            cardsContainer.addView(buildPresetCard(ctx, p, p.id == favId))
        }

        cardsContainer.addView(buildManageCard(ctx))
    }

    // ── Card builders ─────────────────────────────────────────────────────────

    /** {@code preset == null} builds the "free mode" card. */
    private fun buildPresetCard(ctx: Context, preset: LayoutPreset?, selected: Boolean): View {
        val card = newCard(ctx, CARD_WIDTH_DP, selected)

        val content = LinearLayout(ctx)
        content.orientation = LinearLayout.VERTICAL
        val pad = dp(ctx, 10)
        content.setPadding(pad, pad, pad, pad)

        val preview = LayoutMiniPreviewView(ctx)
        preview.setSlots(if (preset != null) preset.slots else null)
        content.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val name = TextView(ctx)
        name.text = if (preset != null)
            (if (selected) "⭐ " + preset.name else preset.name)
        else
            ctx.getString(R.string.fission_layout_mode_free)
        name.textSize = 13f
        name.setTypeface(null, Typeface.BOLD)
        name.setTextColor(ctx.getColor(if (selected) R.color.md_primary else R.color.md_on_surface))
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        val nameLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nameLp.topMargin = dp(ctx, 8)
        content.addView(name, nameLp)

        val sub = TextView(ctx)
        sub.text = if (preset != null)
            ctx.getString(R.string.fission_zone_count, preset.slots.size)
        else
            ctx.getString(R.string.main_layout_free_sub)
        sub.textSize = 11f
        sub.setTextColor(ctx.getColor(R.color.md_on_surface_variant))
        sub.maxLines = 1
        content.addView(sub)

        card.addView(content)
        val newFavId = if (preset != null) preset.id else null
        card.setOnClickListener {
            val saved = if (newFavId == null)
                LayoutPrefs.setFavoriteId(mHost.getContext(), null)
            else
                LayoutPrefs.setFavoriteIdIfPresent(mHost.getContext(), newFavId)
            if (saved) {
                refresh()
            } else {
                Toast.makeText(mHost.getContext(), R.string.lm_layout_selection_save_failed,
                    Toast.LENGTH_LONG).show()
            }
        }
        return card
    }

    private fun buildManageCard(ctx: Context): View {
        val card = newCard(ctx, MANAGE_WIDTH_DP, false)

        val content = LinearLayout(ctx)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER
        val pad = dp(ctx, 10)
        content.setPadding(pad, pad, pad, pad)
        // Match the preset cards' height so the row stays visually aligned.
        content.minimumHeight = dp(ctx, 96)

        val plus = TextView(ctx)
        plus.text = "＋"
        plus.textSize = 24f
        plus.gravity = Gravity.CENTER
        plus.setTextColor(ctx.getColor(R.color.md_primary))
        content.addView(plus)

        val label = TextView(ctx)
        label.text = ctx.getString(R.string.main_layout_manage)
        label.textSize = 12f
        label.gravity = Gravity.CENTER
        label.setTextColor(ctx.getColor(R.color.md_on_surface_variant))
        content.addView(label)

        card.addView(content)
        card.setOnClickListener {
            mHost.startActivity(Intent(mHost.getContext(), LayoutManagerActivity::class.java))
        }
        return card
    }

    private fun newCard(ctx: Context, widthDp: Int, selected: Boolean): MaterialCardView {
        val card = MaterialCardView(ctx)
        card.radius = dp(ctx, 14).toFloat()
        card.cardElevation = 0f
        card.strokeWidth = dp(ctx, if (selected) 2 else 1)
        card.setStrokeColor(ctx.getColor(if (selected) R.color.md_primary else R.color.md_outline_variant))
        card.setCardBackgroundColor(ctx.getColor(
            if (selected) R.color.md_secondary_container else R.color.md_surface_container))
        val lp = LinearLayout.LayoutParams(
            dp(ctx, widthDp), ViewGroup.LayoutParams.MATCH_PARENT)
        lp.marginEnd = dp(ctx, 10)
        card.layoutParams = lp
        return card
    }

    companion object {
        private const val CARD_WIDTH_DP = 150
        private const val MANAGE_WIDTH_DP = 96

        private fun dp(ctx: Context, dp: Int): Int =
            Math.round(dp * ctx.resources.displayMetrics.density)
    }
}
