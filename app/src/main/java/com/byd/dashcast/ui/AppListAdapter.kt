package com.byd.dashcast.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.byd.dashcast.R
import com.byd.dashcast.model.AppInfo
import java.util.Locale

@SuppressLint("SetTextI18n")
class AppListAdapter(
    ctx: Context,
    private val mListener: OnSendToDashboardListener?
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    interface OnSendToDashboardListener {
        fun onSendToDashboard(app: AppInfo)
        fun onSendToMain(app: AppInfo)
        fun onKillApp(app: AppInfo)
        fun onToggleFavorite(app: AppInfo)
        fun onSetAutoLaunch(app: AppInfo, enable: Boolean)
        /** v0.9.72 — long-press now opens an action bottom sheet (favorite/move/kill/auto). */
        fun onShowActions(app: AppInfo)
    }

    private var mAllApps: List<AppInfo> = ArrayList() // full unfiltered list
    private var mApps: List<AppInfo> = ArrayList() // currently displayed (filtered)
    private var mCurrentPackage: String? = null
    private var mMainPackage: String? = null
    /** Packages currently projected via a fission layout (headless orchestrator). */
    private var mLayoutPackages: Set<String> = emptySet()

    /** v0.9.72 — exposed for the long-press bottom sheet. */
    fun getCurrentPackage(): String? = mCurrentPackage
    fun getMainPackage(): String? = mMainPackage

    private val mPackageIndexMap = HashMap<String, Int>()
    private var mCurrentFilter = ""
    private var mCategoryFilter = 0 // 0=all, 1=nav, 2=media

    private var mIsGridMode = false

    // Resolved once at construction — onBindViewHolder runs on every scroll recycle.
    private val mFavoritePrefix = ctx.getString(R.string.favorite_prefix)
    private val mCategoryNavLabel = ctx.getString(R.string.category_navigation)
    private val mCategoryMediaLabel = ctx.getString(R.string.category_media)
    private val mColorFavorite = ctx.getColor(R.color.favorite_gold)
    private val mColorTextPrimary = ctx.getColor(R.color.text_primary)
    private val mColorShortcutBg = ctx.getColor(R.color.shortcut_btn_bg)
    private val mColorShortcutText = ctx.getColor(R.color.shortcut_btn_text)

    @SuppressLint("NotifyDataSetChanged") // full layout swap
    fun setGridMode(isGridMode: Boolean) {
        if (mIsGridMode != isGridMode) {
            mIsGridMode = isGridMode
            notifyDataSetChanged()
        }
    }

    fun isGridMode(): Boolean = mIsGridMode

    fun setApps(apps: List<AppInfo>) {
        mAllApps = apps
        applyFilter(mCurrentFilter)
    }

    /** Filters the displayed list to entries whose name contains [query]. */
    fun filter(query: String?) {
        mCurrentFilter = query ?: ""
        applyFilter(mCurrentFilter)
    }

    /** Filters the displayed list by category (0=all, CATEGORY_NAVIGATION, CATEGORY_MEDIA). */
    fun filterByCategory(category: Int) {
        mCategoryFilter = category
        applyFilter(mCurrentFilter)
    }

    fun getCategoryFilter(): Int = mCategoryFilter

    @SuppressLint("NotifyDataSetChanged") // bulk filter rebuild
    private fun applyFilter(query: String?) {
        var base = mAllApps
        // Category filter
        if (mCategoryFilter != 0) {
            base = mAllApps.filter { it.category == mCategoryFilter }
        }
        mApps = if (query == null || query.trim().isEmpty()) {
            ArrayList(base)
        } else {
            val lower = query.trim().lowercase(Locale.ROOT)
            base.filter { it.appName.lowercase(Locale.ROOT).contains(lower) }
        }
        mPackageIndexMap.clear()
        for (i in mApps.indices) {
            mPackageIndexMap[mApps[i].packageName] = i
        }
        notifyDataSetChanged()
    }

    fun setCurrentPackage(packageName: String?) {
        val old = mCurrentPackage
        mCurrentPackage = packageName
        notifyPackageChanged(old)
        notifyPackageChanged(packageName)
    }

    fun setMainPackage(packageName: String?) {
        val old = mMainPackage
        mMainPackage = packageName
        notifyPackageChanged(old)
        notifyPackageChanged(packageName)
    }

    /**
     * Updates the set of packages projected via a fission layout. Refreshes only the
     * rows whose membership changed (union of the previous and new sets).
     */
    fun setLayoutPackages(packages: Set<String>?) {
        val next = if (packages != null) HashSet(packages) else emptySet()
        val changed = HashSet(mLayoutPackages)
        changed.addAll(next)
        mLayoutPackages = next
        for (pkg in changed) notifyPackageChanged(pkg)
    }

    fun getLayoutPackages(): Set<String> = mLayoutPackages

    private fun notifyPackageChanged(packageName: String?) {
        if (packageName == null) return
        val idx = mPackageIndexMap[packageName]
        if (idx != null) notifyItemChanged(idx)
    }

    override fun getItemViewType(position: Int): Int = if (mIsGridMode) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = if (viewType == 1) R.layout.item_app_grid else R.layout.item_app
        val v = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(v, mListener, this)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = mApps[position]
        holder.ivIcon.setImageDrawable(app.icon)

        // Indicate pinned state with a star prefix
        if (app.isFavorite) {
            holder.tvName.text = mFavoritePrefix + app.appName
            holder.tvName.setTextColor(mColorFavorite)
        } else {
            holder.tvName.text = app.appName
            holder.tvName.setTextColor(mColorTextPrimary)
        }

        holder.tvCategory?.let { tvCat ->
            when (app.category) {
                AppInfo.CATEGORY_NAVIGATION -> {
                    tvCat.text = mCategoryNavLabel; tvCat.visibility = View.VISIBLE
                }
                AppInfo.CATEGORY_MEDIA -> {
                    tvCat.text = mCategoryMediaLabel; tvCat.visibility = View.VISIBLE
                }
                else -> tvCat.visibility = View.GONE
            }
        }

        // Render shortcuts if available — existing child buttons are reused.
        holder.llShortcutsContainer?.let { container ->
            val want = app.shortcuts.size
            if (want == 0) {
                container.visibility = View.GONE
                if (container.childCount > 0) container.removeAllViews()
            } else {
                container.visibility = View.VISIBLE
                while (container.childCount > want) {
                    container.removeViewAt(container.childCount - 1)
                }
                for (i in 0 until want) {
                    val shortcut = app.shortcuts[i]
                    val btn: Button
                    if (i < container.childCount) {
                        btn = container.getChildAt(i) as Button
                    } else {
                        btn = Button(container.context)
                        btn.textSize = 9f
                        btn.isAllCaps = false
                        btn.setPadding(8, 0, 8, 0)
                        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 48)
                        params.marginEnd = 8
                        btn.layoutParams = params
                        btn.setBackgroundColor(mColorShortcutBg)
                        btn.setTextColor(mColorShortcutText)
                        container.addView(btn)
                    }
                    btn.text = shortcut.label
                    btn.setOnClickListener {
                        if (mListener != null) {
                            mListener.onSendToDashboard(app)
                            try {
                                holder.launcherApps?.startShortcut(
                                    app.packageName, shortcut.id, null, null, Process.myUserHandle()
                                )
                            } catch (ignored: Exception) {
                                // Shortcut may have been removed or app uninstalled
                            }
                        }
                    }
                }
            }
        }

        // Handle shortcuts for Grid Mode via PopupMenu
        holder.tvBtnShortcuts?.let { btnShortcuts ->
            if (app.shortcuts.isNotEmpty()) {
                btnShortcuts.visibility = View.VISIBLE
                btnShortcuts.setOnClickListener { v ->
                    val popup = PopupMenu(v.context, v)
                    for (i in app.shortcuts.indices) {
                        popup.menu.add(0, i, 0, app.shortcuts[i].label)
                    }
                    popup.setOnMenuItemClickListener { item ->
                        val chosenShortcut = app.shortcuts[item.itemId]
                        if (holder.btnToCluster?.isVisible == true) {
                            holder.btnToCluster.performClick()
                        } else {
                            mListener?.onSendToDashboard(app)
                        }
                        try {
                            holder.launcherApps?.startShortcut(
                                app.packageName, chosenShortcut.id, null, null, Process.myUserHandle()
                            )
                        } catch (ignored: Exception) {
                            // Shortcut may have been removed or app uninstalled
                        }
                        true
                    }
                    popup.show()
                }
            } else {
                btnShortcuts.visibility = View.GONE
            }
        }

        val isActive = app.packageName == mCurrentPackage
        val isOnMain = app.packageName == mMainPackage
        // A fission-layout app is shown as "active" and exposes the kill action — but NOT
        // the classic move-to-main/cluster buttons (their ClusterService path is unaware of
        // the fission VD slots).
        val isLayout = mLayoutPackages.contains(app.packageName)

        holder.viewActiveIndicator?.visibility =
            if (isActive || isOnMain || isLayout) View.VISIBLE else View.GONE
        // In grid mode keep tiles minimal; all actions live in the long-press bottom sheet.
        if (mIsGridMode) {
            holder.btnToMain?.visibility = View.GONE
            holder.btnToCluster?.visibility = View.GONE
            holder.btnKill?.visibility = View.GONE
            holder.tvCategory?.visibility = View.GONE
        } else {
            holder.btnToMain?.visibility = if (isActive) View.VISIBLE else View.GONE
            holder.btnToCluster?.visibility = if (isOnMain) View.VISIBLE else View.GONE
            holder.btnKill?.visibility = if (isActive || isOnMain || isLayout) View.VISIBLE else View.GONE
        }

        // Subtle background tint on the active row — preserves the ripple via setForeground().
        val fgState = if (isActive || isLayout) 1 else if (isOnMain) 2 else 0
        if (holder.lastFgState != fgState) {
            holder.lastFgState = fgState
            holder.itemView.foreground = when (fgState) {
                1 -> CS_FG_ACTIVE?.newDrawable()
                2 -> CS_FG_ON_MAIN?.newDrawable()
                else -> null
            }
        }

        // Auto-launch badge on the app icon (list + grid).
        holder.badgeAutoLaunch?.visibility = if (app.isAutoLaunch) View.VISIBLE else View.GONE
    }

    override fun getItemCount(): Int = mApps.size

    /** Returns the full unfiltered app list (used for auto-launch lookup etc.). */
    fun getApps(): List<AppInfo> = mAllApps

    internal fun getAppAt(position: Int): AppInfo? =
        if (position in mApps.indices) mApps[position] else null

    class ViewHolder(
        itemView: View,
        listener: OnSendToDashboardListener?,
        adapter: AppListAdapter
    ) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
        val tvName: TextView = itemView.findViewById(R.id.tv_app_name)
        val tvCategory: TextView? = itemView.findViewById(R.id.tv_app_category)
        val llShortcutsContainer: LinearLayout? = itemView.findViewById(R.id.ll_shortcuts_container)
        val tvBtnShortcuts: TextView? = itemView.findViewById(R.id.tv_btn_shortcuts)
        val viewActiveIndicator: View? = itemView.findViewById(R.id.view_active_indicator)
        val btnToMain: Button? = itemView.findViewById(R.id.btn_to_main)
        val btnToCluster: Button? = itemView.findViewById(R.id.btn_to_cluster)
        val btnKill: Button? = itemView.findViewById(R.id.btn_kill_app)
        val badgeAutoLaunch: TextView? = itemView.findViewById(R.id.badge_auto_launch)
        val launcherApps: LauncherApps? =
            itemView.context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        /** Foreground tint on itemView: -1 unknown, 0 none, 1 active, 2 on-main. */
        var lastFgState = -1

        init {
            itemView.setOnClickListener {
                val app = adapter.getAppAt(adapterPosition)
                if (app != null) listener?.onSendToDashboard(app)
            }
            // Long click — v0.9.72 opens the action bottom sheet.
            itemView.setOnLongClickListener {
                val app = adapter.getAppAt(adapterPosition)
                if (app != null && listener != null) {
                    listener.onShowActions(app)
                    true
                } else {
                    false
                }
            }
            btnToMain?.setOnClickListener {
                val app = adapter.getAppAt(adapterPosition)
                if (app != null) listener?.onSendToMain(app)
            }
            btnToCluster?.setOnClickListener {
                val app = adapter.getAppAt(adapterPosition)
                if (app != null) listener?.onSendToDashboard(app)
            }
            btnKill?.setOnClickListener {
                val app = adapter.getAppAt(adapterPosition)
                if (app != null) listener?.onKillApp(app)
            }
        }
    }

    companion object {
        /** Foreground tint applied to the active row (cluster) — semi-transparent green. */
        private const val COLOR_FG_ACTIVE = 0x1A4CAF50
        /** Foreground tint applied to a row whose app is running on the main display. */
        private const val COLOR_FG_ON_MAIN = 0x141565C0
        // Reusable ConstantState for foreground tints — avoids a new ColorDrawable per bind.
        private val CS_FG_ACTIVE: Drawable.ConstantState? = COLOR_FG_ACTIVE.toDrawable().constantState
        private val CS_FG_ON_MAIN: Drawable.ConstantState? = COLOR_FG_ON_MAIN.toDrawable().constantState
    }
}
