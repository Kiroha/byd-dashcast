package com.byd.dashcast;

import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

import com.byd.dashcast.model.AppInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    public interface OnSendToDashboardListener {
        void onSendToDashboard(AppInfo app);
        void onSendToMain(AppInfo app);
        void onKillApp(AppInfo app);
        void onToggleFavorite(AppInfo app);
        void onSetAutoLaunch(AppInfo app, boolean enable);
    }

    private List<AppInfo> mAllApps = new ArrayList<>();   // full unfiltered list
    private List<AppInfo> mApps    = new ArrayList<>();   // currently displayed (filtered)
    private final OnSendToDashboardListener mListener;
    private String mCurrentPackage = null;
    private String mMainPackage = null;
    private final HashMap<String, Integer> mPackageIndexMap = new HashMap<>();
    private String mCurrentFilter = "";
    private int mCategoryFilter = 0; // 0=all, 1=nav, 2=media

    private boolean mIsGridMode = false;

    public AppListAdapter(OnSendToDashboardListener listener) {
        mListener = listener;
    }

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
            holder.tvName.setText(holder.tvName.getContext().getString(R.string.favorite_prefix) + app.appName);
            holder.tvName.setTextColor(holder.tvName.getContext().getColor(R.color.favorite_gold));
        } else {
            holder.tvName.setText(app.appName);
            holder.tvName.setTextColor(holder.tvName.getContext().getColor(R.color.text_primary));
        }

        if (holder.tvCategory != null) {
            if (app.category == AppInfo.CATEGORY_NAVIGATION) {
                holder.tvCategory.setText(holder.tvCategory.getContext().getString(R.string.category_navigation));
                holder.tvCategory.setVisibility(View.VISIBLE);
            } else if (app.category == AppInfo.CATEGORY_MEDIA) {
                holder.tvCategory.setText(holder.tvCategory.getContext().getString(R.string.category_media));
                holder.tvCategory.setVisibility(View.VISIBLE);
            } else {
                holder.tvCategory.setVisibility(View.GONE);
            }
        }

        // Render shortcuts if available
        if (holder.llShortcutsContainer != null) {
            holder.llShortcutsContainer.removeAllViews();
            if (app.shortcuts != null && !app.shortcuts.isEmpty()) {
                holder.llShortcutsContainer.setVisibility(View.VISIBLE);
                for (final com.byd.dashcast.model.AppShortcut shortcut : app.shortcuts) {
                    Button btn = new Button(holder.llShortcutsContainer.getContext());
                    btn.setText(shortcut.label);
                    btn.setTextSize(9);
                    btn.setAllCaps(false);
                    btn.setPadding(8, 0, 8, 0);
                    
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        48
                    );
                    params.setMarginEnd(8);
                    btn.setLayoutParams(params);
                    
                    btn.setBackgroundColor(btn.getContext().getColor(R.color.shortcut_btn_bg));
                    btn.setTextColor(btn.getContext().getColor(R.color.shortcut_btn_text));
                    
                    btn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (mListener != null) {
                                mListener.onSendToDashboard(app);
                                try {
                                    android.content.pm.LauncherApps la = (android.content.pm.LauncherApps)
                                            v.getContext().getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE);
                                    if (la != null) {
                                        la.startShortcut(app.packageName, shortcut.id, null, null, android.os.Process.myUserHandle());
                                    }
                                } catch (Exception ignored) {
                                    // Shortcut may have been removed or app uninstalled
                                }
                            }
                        }
                    });
                    
                    holder.llShortcutsContainer.addView(btn);
                }
            } else {
                holder.llShortcutsContainer.setVisibility(View.GONE);
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
                                    android.content.pm.LauncherApps la = (android.content.pm.LauncherApps)
                                            v.getContext().getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE);
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
        if (holder.btnToMain != null) {
            holder.btnToMain.setVisibility(isActive ? View.VISIBLE : View.GONE);
        }
        if (holder.btnToCluster != null) {
            holder.btnToCluster.setVisibility(isOnMain ? View.VISIBLE : View.GONE);
        }
        if (holder.btnKill != null) {
            holder.btnKill.setVisibility((isActive || isOnMain) ? View.VISIBLE : View.GONE);
        }

        // Subtle background tint on the active row — preserves the ripple via setForeground()
        if (isActive) {
            holder.itemView.setForeground(new android.graphics.drawable.ColorDrawable(0x1A4CAF50)); // green 10%
        } else if (isOnMain) {
            holder.itemView.setForeground(new android.graphics.drawable.ColorDrawable(0x141565C0)); // blue 8%
        } else {
            holder.itemView.setForeground(null);
        }

        if (holder.cbAutoLaunch != null) {
            holder.cbAutoLaunch.setVisibility(View.VISIBLE);
            holder.cbAutoLaunch.setOnCheckedChangeListener(null); // prevent false triggers
            holder.cbAutoLaunch.setChecked(app.isAutoLaunch);
            holder.cbAutoLaunch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    if (mListener != null) {
                        mListener.onSetAutoLaunch(app, isChecked);
                    }
                }
            });
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
        final CheckBox  cbAutoLaunch;

        ViewHolder(View itemView, final OnSendToDashboardListener listener, final AppListAdapter adapter) {
            super(itemView);
            ivIcon              = (ImageView) itemView.findViewById(R.id.iv_app_icon);
            tvName              = (TextView)  itemView.findViewById(R.id.tv_app_name);
            tvCategory          = (TextView)  itemView.findViewById(R.id.tv_app_category);
            llShortcutsContainer = itemView.findViewById(R.id.ll_shortcuts_container);
            tvBtnShortcuts      = itemView.findViewById(R.id.tv_btn_shortcuts);
            viewActiveIndicator = itemView.findViewById(R.id.view_active_indicator);
            btnToMain           = (Button)    itemView.findViewById(R.id.btn_to_main);
            btnToCluster        = (Button)    itemView.findViewById(R.id.btn_to_cluster);
            btnKill             = (Button)    itemView.findViewById(R.id.btn_kill_app);
            cbAutoLaunch        = (CheckBox)  itemView.findViewById(R.id.cb_auto_launch);

            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) listener.onSendToDashboard(app);
                }
            });

            // Long click to trigger favorite toggle
            itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    AppInfo app = adapter.getAppAt(getAdapterPosition());
                    if (app != null && listener != null) {
                        listener.onToggleFavorite(app);
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
