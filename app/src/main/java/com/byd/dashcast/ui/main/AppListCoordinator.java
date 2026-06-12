package com.byd.dashcast.ui.main;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.byd.dashcast.ui.AppListAdapter;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.ui.settings.SettingsActivity;
import com.byd.dashcast.data.apps.AppRepository;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.model.AppInfo;

import java.util.List;

/**
 * Owns the app list RecyclerView, search bar, category filters, favorites strip,
 * and grid/list toggle.
 */
public final class AppListCoordinator {

    private static final String TAG         = "AppListCoordinator";
    private static final long   DEBOUNCE_MS = 150;

    private static final int FILTER_TINT_ACTIVE   = 0xFF1976D2;
    private static final int FILTER_TINT_INACTIVE = 0xFF607D8B;

    public interface Host {
        Context getContext();
        AppListAdapter.OnSendToDashboardListener getSendListener();
        void onShowAppActions(com.byd.dashcast.model.AppInfo app);
        /** Called once on first app launch if the welcome tip has never been shown. */
        void onFirstLaunchTip();
    }

    private final RecyclerView  mRvApps;
    private final EditText      mEtSearch;
    private final LinearLayout  mLlFavoritesStrip;
    private final View          mLlFavoritesSection;
    private final Button        mBtnFilterAll;
    private final Button        mBtnFilterNav;
    private final Button        mBtnFilterMedia;
    private final Button        mBtnViewToggle;
    private final AppRepository mAppRepo;
    private final Host          mHost;
    private final Handler       mMainHandler = new Handler(Looper.getMainLooper());

    private AppListAdapter mAdapter;
    private String         mPendingSearchQuery = null;
    private int            mActiveCategory     = 0; // 0 = all

    public AppListCoordinator(RecyclerView rvApps, EditText etSearch,
                               LinearLayout llFavoritesStrip, View llFavoritesSection,
                               Button btnFilterAll, Button btnFilterNav, Button btnFilterMedia,
                               Button btnViewToggle, AppRepository appRepo, Host host) {
        mRvApps             = rvApps;
        mEtSearch           = etSearch;
        mLlFavoritesStrip   = llFavoritesStrip;
        mLlFavoritesSection = llFavoritesSection;
        mBtnFilterAll       = btnFilterAll;
        mBtnFilterNav       = btnFilterNav;
        mBtnFilterMedia     = btnFilterMedia;
        mBtnViewToggle      = btnViewToggle;
        mAppRepo            = appRepo;
        mHost               = host;
        setup();
    }

    private void setup() {
        Context ctx = mHost.getContext();
        mAdapter = new AppListAdapter(mHost.getSendListener());
        applyLayoutManager(ctx);
        mRvApps.setAdapter(mAdapter);

        // Search debounce
        if (mEtSearch != null) {
            mEtSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    mPendingSearchQuery = s.toString().trim();
                    mMainHandler.removeCallbacksAndMessages(null);
                    mMainHandler.postDelayed(() -> {
                        if (mPendingSearchQuery != null)
                            mAdapter.filter(mPendingSearchQuery);
                    }, DEBOUNCE_MS);
                }
            });
        }

        // Category filters
        setupFilterButton(mBtnFilterAll,   0);
        setupFilterButton(mBtnFilterNav,   AppInfo.CATEGORY_NAVIGATION);
        setupFilterButton(mBtnFilterMedia, AppInfo.CATEGORY_MEDIA);
        applyFilterTints(0);

        // View toggle
        if (mBtnViewToggle != null) {
            mBtnViewToggle.setOnClickListener(v -> toggleViewMode());
        }
    }

    private void setupFilterButton(Button btn, int category) {
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            mActiveCategory = category;
            applyFilterTints(category);
            mAdapter.filterByCategory(category);
        });
    }

    private void applyFilterTints(int activeCategory) {
        applyTint(mBtnFilterAll,   activeCategory == 0);
        applyTint(mBtnFilterNav,   activeCategory == AppInfo.CATEGORY_NAVIGATION);
        applyTint(mBtnFilterMedia, activeCategory == AppInfo.CATEGORY_MEDIA);
    }

    private void applyTint(Button btn, boolean active) {
        if (btn == null) return;
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                active ? FILTER_TINT_ACTIVE : FILTER_TINT_INACTIVE));
    }

    public void toggleViewMode() {
        Context ctx = mHost.getContext();
        boolean nowGrid = !mAdapter.isGridMode();
        ClusterPrefs.setGridMode(ctx, nowGrid);
        applyLayoutManager(ctx, nowGrid);
        AppLogger.i(TAG, "View mode → " + (nowGrid ? "grid" : "list"));
    }

    private void applyLayoutManager(Context ctx) {
        applyLayoutManager(ctx, ClusterPrefs.isGridMode(ctx, true));
    }

    private void applyLayoutManager(Context ctx, boolean isGrid) {
        mAdapter.setGridMode(isGrid);
        if (isGrid) {
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE);
            boolean compact = prefs.getBoolean(SettingsActivity.PREF_COMPACT_APPS_PANEL, false);
            mRvApps.setLayoutManager(new GridLayoutManager(ctx, compact ? 2 : 5));
        } else {
            mRvApps.setLayoutManager(new LinearLayoutManager(ctx));
        }
        mRvApps.setAdapter(mAdapter);
        if (mBtnViewToggle != null)
            mBtnViewToggle.setText(isGrid ? "☰" : "⊞");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Loads the app list asynchronously and populates the RecyclerView. */
    public void loadApps(String currentClusterPkg, String mainPkg) {
        mAppRepo.loadApps(mHost.getContext(), apps -> {
            if (mAdapter != null) {
                mAdapter.setApps(apps);
                mAdapter.setCurrentPackage(currentClusterPkg);
                mAdapter.setMainPackage(mainPkg);
                refreshFavoritesStrip(apps);
            }
        });
    }

    /** Updates the highlighted package (currently projected on cluster). */
    public void setCurrentPackage(String pkg) {
        if (mAdapter != null) mAdapter.setCurrentPackage(pkg);
        updateFavoritesIndicators();
    }

    /** Updates the main-display package indicator. */
    public void setMainPackage(String pkg) {
        if (mAdapter != null) mAdapter.setMainPackage(pkg);
        updateFavoritesIndicators();
    }

    public List<AppInfo> getApps() {
        return mAdapter != null ? mAdapter.getApps() : java.util.Collections.emptyList();
    }
    public String  getCurrentPackage() { return mAdapter != null ? mAdapter.getCurrentPackage() : null; }
    public String  getMainPackage()    { return mAdapter != null ? mAdapter.getMainPackage()    : null; }
    public boolean isGridMode()        { return mAdapter != null && mAdapter.isGridMode(); }
    public void    setApps(List<AppInfo> apps) { if (mAdapter != null) mAdapter.setApps(apps); }

    /**
     * Splits {@code apps} into favorites (strip) and non-favorites (grid), pushes both to the
     * UI, and fires {@link Host#onFirstLaunchTip()} on first use.
     */
    public void deliver(List<AppInfo> apps, boolean showFirstTip) {
        if (apps == null) return;
        List<AppInfo> nonFavs = new java.util.ArrayList<>(apps.size());
        for (AppInfo a : apps) {
            if (!a.isFavorite) nonFavs.add(a);
        }
        setApps(nonFavs);
        refreshFavoritesStrip(apps);
        if (showFirstTip && !ClusterPrefs.isFirstLaunchTipShown(mHost.getContext())) {
            ClusterPrefs.setFirstLaunchTipShown(mHost.getContext());
            mMainHandler.postDelayed(mHost::onFirstLaunchTip, 1200);
        }
    }

    /** Rebuilds the favorites strip from the given app list (call from deliverAppsToUI). */
    public void refreshFavoritesStrip(List<AppInfo> apps) {
        if (mLlFavoritesStrip == null || mLlFavoritesSection == null) return;
        mLlFavoritesStrip.removeAllViews();
        if (apps == null) {
            mLlFavoritesSection.setVisibility(View.GONE);
            return;
        }
        Context ctx = mHost.getContext();
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(ctx);
        int added = 0;
        for (final AppInfo app : apps) {
            if (!app.isFavorite) continue;
            android.view.View tile = inflater.inflate(
                    com.byd.dashcast.R.layout.item_favorite_strip, mLlFavoritesStrip, false);
            android.widget.ImageView iv = tile.findViewById(com.byd.dashcast.R.id.iv_fav_icon);
            android.widget.TextView  tv = tile.findViewById(com.byd.dashcast.R.id.tv_fav_name);
            iv.setImageDrawable(app.icon);
            tv.setText(app.appName);
            tile.setTag(app.packageName);
            tile.setOnClickListener(v -> mHost.getSendListener().onSendToDashboard(app));
            tile.setOnLongClickListener(v -> { mHost.onShowAppActions(app); return true; });
            mLlFavoritesStrip.addView(tile);
            added++;
        }
        mLlFavoritesSection.setVisibility(added > 0 ? View.VISIBLE : View.GONE);
        updateFavoritesIndicators();
    }

    /**
     * Syncs the green active-indicator bar on each favorite tile to the adapter's
     * current/main package state. Call whenever those packages change.
     */
    public void updateFavoritesIndicators() {
        if (mLlFavoritesStrip == null || mAdapter == null) return;
        String curPkg  = mAdapter.getCurrentPackage();
        String mainPkg = mAdapter.getMainPackage();
        for (int i = 0; i < mLlFavoritesStrip.getChildCount(); i++) {
            android.view.View tile = mLlFavoritesStrip.getChildAt(i);
            Object tag = tile.getTag();
            if (!(tag instanceof String)) continue;
            String pkg = (String) tag;
            android.view.View ind = tile.findViewById(com.byd.dashcast.R.id.view_fav_active_indicator);
            if (ind == null) continue;
            boolean active = pkg.equals(curPkg) || pkg.equals(mainPkg);
            ind.setVisibility(active ? View.VISIBLE : View.GONE);
        }
    }

    /** Notifies the adapter that the dataset changed (e.g. after auto-launch toggle). */
    public void notifyAppsChanged() {
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
    }

    /**
     * Ensures the RecyclerView uses a GridLayoutManager with the given span count,
     * only switching if the current span differs. No-op when in list mode.
     */
    public void ensureGridSpanCount(int spanCount) {
        if (mAdapter == null || !mAdapter.isGridMode()) return;
        androidx.recyclerview.widget.RecyclerView.LayoutManager cur = mRvApps.getLayoutManager();
        int curSpan = (cur instanceof GridLayoutManager) ? ((GridLayoutManager) cur).getSpanCount() : -1;
        if (curSpan != spanCount) {
            mRvApps.setLayoutManager(new GridLayoutManager(mHost.getContext(), spanCount));
        }
    }

    public AppListAdapter getAdapter() { return mAdapter; }

    private int dpToPx(int dp) {
        return Math.round(dp * mHost.getContext().getResources().getDisplayMetrics().density);
    }
}
