package com.byd.dashcast.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.byd.dashcast.R
import com.byd.dashcast.data.apps.AppRepository
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.model.AppInfo
import com.byd.dashcast.ui.AppListAdapter
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger

/**
 * Owns the app list RecyclerView, search bar, category filters, favorites strip,
 * and grid/list toggle.
 */
class AppListCoordinator(
    private val mRvApps: RecyclerView,
    private val mEtSearch: EditText?,
    private val mLlFavoritesStrip: LinearLayout?,
    private val mLlFavoritesSection: View?,
    private val mBtnFilterAll: Button?,
    private val mBtnFilterNav: Button?,
    private val mBtnFilterMedia: Button?,
    private val mBtnViewToggle: Button?,
    private val mAppRepo: AppRepository,
    private val mHost: Host
) {

    interface Host {
        fun getContext(): Context
        fun getSendListener(): AppListAdapter.OnSendToDashboardListener
        fun onShowAppActions(app: AppInfo)
        /** Called once on first app launch if the welcome tip has never been shown. */
        fun onFirstLaunchTip()
    }

    private val mMainHandler = Handler(Looper.getMainLooper())
    private lateinit var mAdapter: AppListAdapter
    private var mPendingSearchQuery: String? = null
    private var mActiveCategory = 0 // 0 = all

    init {
        setup()
    }

    private fun setup() {
        val ctx = mHost.getContext()
        mAdapter = AppListAdapter(ctx, mHost.getSendListener())
        applyLayoutManager(ctx)
        mRvApps.adapter = mAdapter

        // Search debounce
        mEtSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                mPendingSearchQuery = s.toString().trim()
                mMainHandler.removeCallbacksAndMessages(null)
                mMainHandler.postDelayed({
                    mPendingSearchQuery?.let { mAdapter.filter(it) }
                }, DEBOUNCE_MS)
            }
        })

        // Category filters
        setupFilterButton(mBtnFilterAll, 0)
        setupFilterButton(mBtnFilterNav, AppInfo.CATEGORY_NAVIGATION)
        setupFilterButton(mBtnFilterMedia, AppInfo.CATEGORY_MEDIA)
        applyFilterTints(0)

        // View toggle
        mBtnViewToggle?.setOnClickListener { toggleViewMode() }
    }

    private fun setupFilterButton(btn: Button?, category: Int) {
        if (btn == null) return
        btn.setOnClickListener {
            mActiveCategory = category
            applyFilterTints(category)
            mAdapter.filterByCategory(category)
        }
    }

    private fun applyFilterTints(activeCategory: Int) {
        applyTint(mBtnFilterAll, activeCategory == 0)
        applyTint(mBtnFilterNav, activeCategory == AppInfo.CATEGORY_NAVIGATION)
        applyTint(mBtnFilterMedia, activeCategory == AppInfo.CATEGORY_MEDIA)
    }

    private fun applyTint(btn: Button?, active: Boolean) {
        if (btn == null) return
        btn.backgroundTintList = ColorStateList.valueOf(
            if (active) FILTER_TINT_ACTIVE else FILTER_TINT_INACTIVE
        )
    }

    fun toggleViewMode() {
        val ctx = mHost.getContext()
        val nowGrid = !mAdapter.isGridMode()
        ClusterPrefs.setGridMode(ctx, nowGrid)
        applyLayoutManager(ctx, nowGrid)
        AppLogger.i(TAG, "View mode → " + if (nowGrid) "grid" else "list")
    }

    private fun applyLayoutManager(ctx: Context) {
        applyLayoutManager(ctx, ClusterPrefs.isGridMode(ctx, true))
    }

    private fun applyLayoutManager(ctx: Context, isGrid: Boolean) {
        mAdapter.setGridMode(isGrid)
        if (isGrid) {
            val prefs = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            val compact = prefs.getBoolean(SettingsActivity.PREF_COMPACT_APPS_PANEL, false)
            mRvApps.layoutManager = GridLayoutManager(ctx, if (compact) 2 else 5)
        } else {
            mRvApps.layoutManager = LinearLayoutManager(ctx)
        }
        mRvApps.adapter = mAdapter
        mBtnViewToggle?.text = if (isGrid) "☰" else "⊞"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Loads the app list asynchronously and populates the RecyclerView. */
    fun loadApps(currentClusterPkg: String?, mainPkg: String?) {
        mAppRepo.loadApps(mHost.getContext()) { apps ->
            mAdapter.setApps(apps)
            mAdapter.setCurrentPackage(currentClusterPkg)
            mAdapter.setMainPackage(mainPkg)
            refreshFavoritesStrip(apps)
        }
    }

    /** Updates the highlighted package (currently projected on cluster). */
    fun setCurrentPackage(pkg: String?) {
        mAdapter.setCurrentPackage(pkg)
        updateFavoritesIndicators()
    }

    /** Updates the main-display package indicator. */
    fun setMainPackage(pkg: String?) {
        mAdapter.setMainPackage(pkg)
        updateFavoritesIndicators()
    }

    /** Updates the set of packages projected via a fission layout. */
    fun setLayoutPackages(pkgs: Set<String>?) {
        mAdapter.setLayoutPackages(pkgs)
        updateFavoritesIndicators()
    }

    fun getApps(): List<AppInfo> = mAdapter.getApps()
    fun getCurrentPackage(): String? = mAdapter.getCurrentPackage()
    fun getMainPackage(): String? = mAdapter.getMainPackage()
    fun isGridMode(): Boolean = mAdapter.isGridMode()
    fun setApps(apps: List<AppInfo>) = mAdapter.setApps(apps)

    /**
     * Splits [apps] into favorites (strip) and non-favorites (grid), pushes both to the
     * UI, and fires [Host.onFirstLaunchTip] on first use.
     */
    fun deliver(apps: List<AppInfo>?, showFirstTip: Boolean) {
        if (apps == null) return
        val nonFavs = ArrayList<AppInfo>(apps.size)
        for (a in apps) {
            if (!a.isFavorite) nonFavs.add(a)
        }
        setApps(nonFavs)
        refreshFavoritesStrip(apps)
        if (showFirstTip && !ClusterPrefs.isFirstLaunchTipShown(mHost.getContext())) {
            ClusterPrefs.setFirstLaunchTipShown(mHost.getContext())
            mMainHandler.postDelayed({ mHost.onFirstLaunchTip() }, 1200)
        }
    }

    /** Rebuilds the favorites strip from the given app list. */
    fun refreshFavoritesStrip(apps: List<AppInfo>?) {
        val strip = mLlFavoritesStrip ?: return
        val section = mLlFavoritesSection ?: return
        strip.removeAllViews()
        if (apps == null) {
            section.visibility = View.GONE
            return
        }
        val ctx = mHost.getContext()
        val inflater = LayoutInflater.from(ctx)
        var added = 0
        for (app in apps) {
            if (!app.isFavorite) continue
            val tile = inflater.inflate(R.layout.item_favorite_strip, strip, false)
            val iv = tile.findViewById<ImageView>(R.id.iv_fav_icon)
            val tv = tile.findViewById<TextView>(R.id.tv_fav_name)
            iv.setImageDrawable(app.icon)
            tv.text = app.appName
            tile.tag = app.packageName
            tile.setOnClickListener { mHost.getSendListener().onSendToDashboard(app) }
            tile.setOnLongClickListener { mHost.onShowAppActions(app); true }
            strip.addView(tile)
            added++
        }
        section.visibility = if (added > 0) View.VISIBLE else View.GONE
        updateFavoritesIndicators()
    }

    /**
     * Syncs the green active-indicator bar on each favorite tile to the adapter's
     * current/main package state.
     */
    fun updateFavoritesIndicators() {
        val strip = mLlFavoritesStrip ?: return
        val curPkg = mAdapter.getCurrentPackage()
        val mainPkg = mAdapter.getMainPackage()
        val layoutPkgs = mAdapter.getLayoutPackages()
        for (i in 0 until strip.childCount) {
            val tile = strip.getChildAt(i)
            val tag = tile.tag
            if (tag !is String) continue
            val ind = tile.findViewById<View>(R.id.view_fav_active_indicator) ?: continue
            val active = AppProjectionIndicator.isActive(tag, curPkg, mainPkg, layoutPkgs)
            ind.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    /** Notifies the adapter that the dataset changed (e.g. after auto-launch toggle). */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun notifyAppsChanged() {
        mAdapter.notifyDataSetChanged()
    }

    /**
     * Ensures the RecyclerView uses a GridLayoutManager with the given span count,
     * only switching if the current span differs. No-op when in list mode.
     */
    fun ensureGridSpanCount(spanCount: Int) {
        if (!mAdapter.isGridMode()) return
        val cur = mRvApps.layoutManager
        val curSpan = if (cur is GridLayoutManager) cur.spanCount else -1
        if (curSpan != spanCount) {
            mRvApps.layoutManager = GridLayoutManager(mHost.getContext(), spanCount)
        }
    }

    fun getAdapter(): AppListAdapter = mAdapter

    companion object {
        private const val TAG = "AppListCoordinator"
        private const val DEBOUNCE_MS = 150L

        private val FILTER_TINT_ACTIVE = 0xFF1976D2.toInt()
        private val FILTER_TINT_INACTIVE = 0xFF607D8B.toInt()
    }
}
