package com.byd.dashcast.ui;

import com.byd.dashcast.R;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

import com.byd.dashcast.model.AppInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@android.annotation.SuppressLint("SetTextI18n")
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    public interface OnSendToDashboardListener {
        void onSendToDashboard(AppInfo app);
        void onSendToMain(AppInfo app);
        void onKillApp(AppInfo app);
        void onToggleFavorite(AppInfo app);
        void onSetAutoLaunch(AppInfo app, boolean enable);
        /** v0.9.72 — long-press now opens an action bottom sheet (favorite/move/kill/auto). */
        void onShowActions(AppInfo app);
    }

    private List<AppInfo> mAllApps = new ArrayList<>();   // full unfiltered list
    private List<AppInfo> mApps    = new ArrayList<>();   // currently displayed (filtered)
    private final OnSendToDashboardListener mListener;
    private String mCurrentPackage = null;
    private String mMainPackage = null;

    /** v0.9.72 — exposed for the long-press bottom sheet. */
    public String getCurrentPackage() { return mCurrentPackage; }
    public String getMainPackage()    { return mMainPackage; }
    private final HashMap<String, Integer> mPackageIndexMap = new HashMap<>();
    private String mCurrentFilter = "";
    private int mCategoryFilter = 0; // 0=all, 1=nav, 2=media

    /** Foreground tint applied to the active row (cluster) — semi-transparent green. */
    private static final int COLOR_FG_ACTIVE  = 0x1A4CAF50;
    /** Foreground tint applied to a row whose app is running on the main display. */
    private static final int COLOR_FG_ON_MAIN = 0x141565C0;
    /** Reusable ConstantState for foreground tints — avoids allocating a new ColorDrawable per bind. */
    private static final android.graphics.drawable.Drawable.ConstantState CS_FG_ACTIVE =
            new android.graphics.drawable.ColorDrawable(COLOR_FG_ACTIVE).getConstantState();
    private static final android.graphics.drawable.Drawable.ConstantState CS_FG_ON_MAIN =
            new android.graphics.drawable.ColorDrawable(COLOR_FG_ON_MAIN).getConstantState();

    private boolean mIsGridMode = false;

    // Resolved once at construction — onBindViewHolder runs on every scroll
    // recycle, so per-bind getString()/getColor() lookups are avoidable work.
    private final String mFavoritePrefix;
    private final String mCategoryNavLabel;
    private final String mCategoryMediaLabel;
    private final int    mColorFavorite;
    private final int    mColorTextPrimary;
    private final int    mColorShortcutBg;
    private final int    mColorShortcutText;

    public AppListAdapter(android.content.Context ctx, OnSendToDashboardListener listener) {
        mListener           = listener;
        mFavoritePrefix     = ctx.getString(R.string.favorite_prefix);
        mCategoryNavLabel   = ctx.getString(R.string.category_navigation);
        mCategoryMediaLabel = ctx.getString(R.string.category_media);
        mColorFavorite      = ctx.getColor(R.color.favorite_gold);
        mColorTextPrimary   = ctx.getColor(R.color.text_primary);
        mColorShortcutBg    = ctx.getColor(R.color.shortcut_btn_bg);
        mColorShortcutText  = ctx.getColor(R.color.shortcut_btn_text);
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged") // full layout swap
    public void setGridMode(boolean isGridMode) {
        if (mIsGridMode != isGridMode) {
            mIsGridMode = isGridMode;
            notifyDataSetChanged();
        }
    }

    public boolean isGridMode() {
        return mIsGridMode;
    }

    public void setApps(List<AppInfo> apps) {
        mAllApps = apps;
        applyFilter(mCurrentFilter);
    }

    /**
     * Filters the displayed list to entries whose name contains {@code query}.
     * Pass an empty string to clear the filter.
     */
    public void filter(String query) {
        mCurrentFilter = query == null ? "" : query;
        applyFilter(mCurrentFilter);
    }

    /**
     * Filters the displayed list by category.
     * @param category 0=all, AppInfo.CATEGORY_NAVIGATION, AppInfo.CATEGORY_MEDIA
     */
    public void filterByCategory(int category) {
        mCategoryFilter = category;
        applyFilter(mCurrentFilter);
    }

    public int getCategoryFilter() {
        return mCategoryFilter;
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged") // bulk filter rebuild
    private void applyFilter(String query) {
        List<AppInfo> base = mAllApps;
        // Category filter
        if (mCategoryFilter != 0) {
            base = new ArrayList<>();
            for (AppInfo a : mAllApps) {
                if (a.category == mCategoryFilter) base.add(a);
            }
        }
        if (query == null || query.trim().isEmpty()) {
            mApps = new ArrayList<>(base);
        } else {
            String lower = query.trim().toLowerCase(java.util.Locale.ROOT);
            List<AppInfo> filtered = new ArrayList<>();
            for (AppInfo a : base) {
                if (a.appName.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                    filtered.add(a);
                }
            }
            mApps = filtered;
        }
        mPackageIndexMap.clear();
        for (int i = 0; i < mApps.size(); i++) {
            mPackageIndexMap.put(mApps.get(i).packageName, i);
        }
        notifyDataSetChanged();
    }

    public void setCurrentPackage(String packageName) {
        String old = mCurrentPackage;
        mCurrentPackage = packageName;
        notifyPackageChanged(old);
        notifyPackageChanged(packageName);
    }

    public void setMainPackage(String packageName) {
        String old = mMainPackage;
        mMainPackage = packageName;
        notifyPackageChanged(old);
        notifyPackageChanged(packageName);
    }

    private void notifyPackageChanged(String packageName) {
        if (packageName == null) return;
        Integer idx = mPackageIndexMap.get(packageName);
        if (idx != null) notifyItemChanged(idx);
    }

    @Override
    public int getItemViewType(int position) {
        return mIsGridMode ? 1 : 0;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        int layoutId = viewType == 1 ? R.layout.item_app_grid : R.layout.item_app;
        View v = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return new ViewHolder(v, mListener, this);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        final AppInfo app = mApps.get(position);
        holder.ivIcon.setImageDrawable(app.icon);
        
        // Indicate pinned state with a star prefix
        if (app.isFavorite) {
            holder.tvName.setText(mFavoritePrefix + app.appName);
            holder.tvName.setTextColor(mColorFavorite);
        } else {
            holder.tvName.setText(app.appName);
            holder.tvName.setTextColor(mColorTextPrimary);
        }

        if (holder.tvCategory != null) {
            if (app.category == AppInfo.CATEGORY_NAVIGATION) {
                holder.tvCategory.setText(mCategoryNavLabel);
                holder.tvCategory.setVisibility(View.VISIBLE);
            } else if (app.category == AppInfo.CATEGORY_MEDIA) {
                holder.tvCategory.setText(mCategoryMediaLabel);
                holder.tvCategory.setVisibility(View.VISIBLE);
            } else {
                holder.tvCategory.setVisibility(View.GONE);
            }
        }

        // Render shortcuts if available. Existing child buttons are reused
        // (retext + relisten) instead of removeAllViews() + new Button per bind:
        // inflating Buttons on every scroll recycle caused allocation and
        // layout-pass churn. Only this adapter ever adds children here.
        if (holder.llShortcutsContainer != null) {
            int want = (app.shortcuts != null) ? app.shortcuts.size() : 0;
            if (want == 0) {
                holder.llShortcutsContainer.setVisibility(View.GONE);
                if (holder.llShortcutsContainer.getChildCount() > 0) {
                    holder.llShortcutsContainer.removeAllViews();
                }
            } else {
                holder.llShortcutsContainer.setVisibility(View.VISIBLE);
                while (holder.llShortcutsContainer.getChildCount() > want) {
                    holder.llShortcutsContainer.removeViewAt(
                            holder.llShortcutsContainer.getChildCount() - 1);
                }
                for (int i = 0; i < want; i++) {
                    final com.byd.dashcast.model.AppShortcut shortcut = app.shortcuts.get(i);
                    Button btn;
                    if (i < holder.llShortcutsContainer.getChildCount()) {
                        btn = (Button) holder.llShortcutsContainer.getChildAt(i);
                    } else {
                        btn = new Button(holder.llShortcutsContainer.getContext());
                        btn.setTextSize(9);
                        btn.setAllCaps(false);
                        btn.setPadding(8, 0, 8, 0);

                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            48
                        );
                        params.setMarginEnd(8);
                        btn.setLayoutParams(params);

                        btn.setBackgroundColor(mColorShortcutBg);
                        btn.setTextColor(mColorShortcutText);

                        holder.llShortcutsContainer.addView(btn);
                    }
                    btn.setText(shortcut.label);
                    btn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (mListener != null) {
                                mListener.onSendToDashboard(app);
                                try {
                                    android.content.pm.LauncherApps la = holder.launcherApps;
                                    if (la != null) {
                                        la.startShortcut(app.packageName, shortcut.id, null, null, android.os.Process.myUserHandle());
                                    }
                                } catch (Exception ignored) {
                                    // Shortcut may have been removed or app uninstalled
                                }
                            }
                        }
                    });
                }
            }
        }
        
        // Handle shortcuts for Grid Mode via PopupMenu
        if (holder.tvBtnShortcuts != null) {
            if (app.shortcuts != null && !app.shortcuts.isEmpty()) {
                holder.tvBtnShortcuts.setVisibility(View.VISIBLE);
                holder.tvBtnShortcuts.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        android.widget.PopupMenu popup = new android.widget.PopupMenu(v.getContext(), v);
                        for (int i = 0; i < app.shortcuts.size(); i++) {
                            popup.getMenu().add(0, i, 0, app.shortcuts.get(i).label);
                        }
                        popup.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
                            @Override
                            public boolean onMenuItemClick(android.view.MenuItem item) {
                                com.byd.dashcast.model.AppShortcut chosenShortcut = app.shortcuts.get(item.getItemId());
                                if (holder.btnToCluster != null && holder.btnToCluster.getVisibility() == View.VISIBLE) {
                                    holder.btnToCluster.performClick();
                                } else {
                                    if (mListener != null) {
                                        mListener.onSendToDashboard(app);
                                    }
                                }
                                try {
                                    android.content.pm.LauncherApps la = holder.launcherApps;
                                    if (la != null) {
                                        la.startShortcut(app.packageName, chosenShortcut.id, null, null, android.os.Process.myUserHandle());
                                    }
                                } catch (Exception ignored) {
                                    // Shortcut may have been removed or app uninstalled
                                }
                                return true;
                            }
                        });
                        popup.show();
                    }
                });
            } else {
                holder.tvBtnShortcuts.setVisibility(View.GONE);
            }
        }

        boolean isActive = app.packageName != null && app.packageName.equals(mCurrentPackage);
        boolean isOnMain = app.packageName != null && app.packageName.equals(mMainPackage);
        
        if (holder.viewActiveIndicator != null) {
            holder.viewActiveIndicator.setVisibility((isActive || isOnMain) ? View.VISIBLE : View.GONE);
        }
        // v0.9.72 \u2014 in grid mode keep tiles minimal: icon + name + favorite badge + active dot.
        // All actions (auto-launch, move-to-main/cluster, kill) live in the long-press bottom sheet.
        if (mIsGridMode) {
            if (holder.btnToMain != null) holder.btnToMain.setVisibility(View.GONE);
            if (holder.btnToCluster != null) holder.btnToCluster.setVisibility(View.GONE);
            if (holder.btnKill != null) holder.btnKill.setVisibility(View.GONE);

            if (holder.tvCategory != null) holder.tvCategory.setVisibility(View.GONE);
        } else {
            if (holder.btnToMain != null) {
                holder.btnToMain.setVisibility(isActive ? View.VISIBLE : View.GONE);
            }
            if (holder.btnToCluster != null) {
                holder.btnToCluster.setVisibility(isOnMain ? View.VISIBLE : View.GONE);
            }
            if (holder.btnKill != null) {
                holder.btnKill.setVisibility((isActive || isOnMain) ? View.VISIBLE : View.GONE);
            }
        }

        // Subtle background tint on the active row — preserves the ripple via setForeground().
        // Skipped when the holder already shows the right state: newDrawable()
        // allocates, and at most two rows ever change state per update.
        int fgState = isActive ? 1 : (isOnMain ? 2 : 0);
        if (holder.lastFgState != fgState) {
            holder.lastFgState = fgState;
            if (fgState == 1) {
                holder.itemView.setForeground(CS_FG_ACTIVE.newDrawable());
            } else if (fgState == 2) {
                holder.itemView.setForeground(CS_FG_ON_MAIN.newDrawable());
            } else {
                holder.itemView.setForeground(null);
            }
        }

        // Auto-launch badge on the app icon (list + grid). The toggle itself
        // lives in the long-press bottom sheet (onShowActions → swAuto).
        if (holder.badgeAutoLaunch != null) {
            holder.badgeAutoLaunch.setVisibility(app.isAutoLaunch ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return mApps.size();
    }

    /** Returns the full unfiltered app list (used for auto-launch lookup etc.). */
    public List<AppInfo> getApps() { return mAllApps; }

    AppInfo getAppAt(int position) {
        if (position >= 0 && position < mApps.size()) {
            return mApps.get(position);
        }
        return null;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView  tvName;
        final TextView  tvCategory;
        final LinearLayout llShortcutsContainer;
        final TextView  tvBtnShortcuts; // Shortcut popup trigger for grid mode
        final View      viewActiveIndicator;
        final Button    btnToMain;
        final Button    btnToCluster;
        final Button    btnKill;
        final TextView  badgeAutoLaunch;
        final android.content.pm.LauncherApps launcherApps;
        /** Foreground tint currently applied to itemView: -1 unknown, 0 none, 1 active, 2 on-main. */
        int lastFgState = -1;

        ViewHolder(View itemView, final OnSendToDashboardListener listener, final AppListAdapter adapter) {
            super(itemView);
            launcherApps = (android.content.pm.LauncherApps)
                    itemView.getContext().getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE);
            ivIcon              = (ImageView) itemView.findViewById(R.id.iv_app_icon);
            tvName              = (TextView)  itemView.findViewById(R.id.tv_app_name);
            tvCategory          = (TextView)  itemView.findViewById(R.id.tv_app_category);
            llShortcutsContainer = itemView.findViewById(R.id.ll_shortcuts_container);
            tvBtnShortcuts      = itemView.findViewById(R.id.tv_btn_shortcuts);
            viewActiveIndicator = itemView.findViewById(R.id.view_active_indicator);
            btnToMain           = (Button)    itemView.findViewById(R.id.btn_to_main);
            btnToCluster        = (Button)    itemView.findViewById(R.id.btn_to_cluster);
            btnKill             = (Button)    itemView.findViewById(R.id.btn_kill_app);
            badgeAutoLaunch     = (TextView)  itemView.findViewById(R.id.badge_auto_launch);

            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onSendToDashboard(app);
                }
            });

            // Long click — v0.9.72 opens the action bottom sheet (was: toggle favorite).
            itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) {
                        listener.onShowActions(app);
                        return true;
                    }
                    return false;
                }
            });

            if (btnToMain != null) btnToMain.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onSendToMain(app);
                }
            });

            if (btnToCluster != null) btnToCluster.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onSendToDashboard(app);
                }
            });

            if (btnKill != null) btnKill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onKillApp(app);
                }
            });
        }
    }
}
