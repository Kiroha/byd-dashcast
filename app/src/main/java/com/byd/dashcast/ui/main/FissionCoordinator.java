package com.byd.dashcast.ui.main;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.byd.dashcast.R;
import com.byd.dashcast.fission.LayoutManagerActivity;
import com.byd.dashcast.fission.LayoutMiniPreviewView;
import com.byd.dashcast.fission.LayoutPrefs;
import com.byd.dashcast.fission.LayoutPreset;
import com.byd.dashcast.proxy.DaemonConfig;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

/**
 * Owns the layout carousel on the Apps page: horizontally scrollable cards — free
 * mode, one card per saved preset (with a mini preview of its zones), and a manage
 * card opening {@link LayoutManagerActivity}. Tapping a card sets the favourite
 * layout, which is what the auto-start activates at DashCast launch.
 *
 * Call {@link #refresh()} from onCreate and onResume to recompute visibility and
 * rebuild the cards (presets may have changed in the Layouts screen).
 */
public final class FissionCoordinator {

    private static final int CARD_WIDTH_DP   = 150;
    private static final int MANAGE_WIDTH_DP = 96;

    public interface Host {
        Context getContext();
        void startActivity(Intent intent);
    }

    private final View         mSection;
    private final LinearLayout mCardsContainer;
    private final Host         mHost;

    public FissionCoordinator(View carouselSection, LinearLayout cardsContainer, Host host) {
        mSection        = carouselSection;
        mCardsContainer = cardsContainer;
        mHost           = host;
        refresh();
    }

    /** Recomputes visibility and rebuilds the cards. Safe to call repeatedly. */
    public void refresh() {
        if (mSection == null || mCardsContainer == null) return;
        Context ctx = mHost.getContext();
        boolean enabled = DaemonConfig.isFissionModeEnabled(ctx);
        mSection.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;

        List<LayoutPreset> presets = LayoutPrefs.load(ctx);
        String favId = LayoutPrefs.getFavoriteId(ctx);

        mCardsContainer.removeAllViews();

        // Free mode card (selected when no favourite is set).
        mCardsContainer.addView(buildPresetCard(ctx, null, favId == null));

        for (LayoutPreset p : presets) {
            mCardsContainer.addView(buildPresetCard(ctx, p, p.id.equals(favId)));
        }

        mCardsContainer.addView(buildManageCard(ctx));
    }

    // ── Card builders ─────────────────────────────────────────────────────────

    /** {@code preset == null} builds the "free mode" card. */
    private View buildPresetCard(Context ctx, LayoutPreset preset, boolean selected) {
        MaterialCardView card = newCard(ctx, CARD_WIDTH_DP, selected);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 10);
        content.setPadding(pad, pad, pad, pad);

        LayoutMiniPreviewView preview = new LayoutMiniPreviewView(ctx);
        preview.setSlots(preset != null ? preset.slots : null);
        content.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(ctx);
        name.setText(preset != null
                ? (selected ? "⭐ " + preset.name : preset.name)
                : ctx.getString(R.string.fission_layout_mode_free));
        name.setTextSize(13f);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(ctx.getColor(selected ? R.color.md_primary : R.color.md_on_surface));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(ctx, 8);
        content.addView(name, nameLp);

        TextView sub = new TextView(ctx);
        sub.setText(preset != null
                ? ctx.getString(R.string.fission_zone_count, preset.slots.size())
                : ctx.getString(R.string.main_layout_free_sub));
        sub.setTextSize(11f);
        sub.setTextColor(ctx.getColor(R.color.md_on_surface_variant));
        sub.setMaxLines(1);
        content.addView(sub);

        card.addView(content);
        final String newFavId = (preset != null) ? preset.id : null;
        card.setOnClickListener(v -> {
            LayoutPrefs.setFavoriteId(mHost.getContext(), newFavId);
            refresh();
        });
        return card;
    }

    private View buildManageCard(Context ctx) {
        MaterialCardView card = newCard(ctx, MANAGE_WIDTH_DP, false);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int pad = dp(ctx, 10);
        content.setPadding(pad, pad, pad, pad);
        // Match the preset cards' height so the row stays visually aligned.
        content.setMinimumHeight(dp(ctx, 96));

        TextView plus = new TextView(ctx);
        plus.setText("＋");
        plus.setTextSize(24f);
        plus.setGravity(Gravity.CENTER);
        plus.setTextColor(ctx.getColor(R.color.md_primary));
        content.addView(plus);

        TextView label = new TextView(ctx);
        label.setText(ctx.getString(R.string.main_layout_manage));
        label.setTextSize(12f);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(ctx.getColor(R.color.md_on_surface_variant));
        content.addView(label);

        card.addView(content);
        card.setOnClickListener(v ->
                mHost.startActivity(new Intent(mHost.getContext(), LayoutManagerActivity.class)));
        return card;
    }

    private MaterialCardView newCard(Context ctx, int widthDp, boolean selected) {
        MaterialCardView card = new MaterialCardView(ctx);
        card.setRadius(dp(ctx, 14));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(ctx, selected ? 2 : 1));
        card.setStrokeColor(ctx.getColor(selected ? R.color.md_primary : R.color.md_outline_variant));
        card.setCardBackgroundColor(ctx.getColor(
                selected ? R.color.md_secondary_container : R.color.md_surface_container));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(ctx, widthDp), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMarginEnd(dp(ctx, 10));
        card.setLayoutParams(lp);
        return card;
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
