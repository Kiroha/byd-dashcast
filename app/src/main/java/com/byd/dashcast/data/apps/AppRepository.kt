package com.byd.dashcast.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.model.AppInfo
import com.byd.dashcast.model.AppShortcut
import com.byd.dashcast.util.AppLogger
import java.util.concurrent.Executors

/**
 * AppRepository — single source of truth for the list of installable applications.
 *
 * **Problem solved:** Previously ~150 lines of app-loading logic were embedded
 * directly in `MainActivity.loadInstalledApps()`, `setFavorite()`, and
 * `setAutoLaunch()`, mixed with UI event handling. Every change to sorting,
 * filtering, or persistence required editing the 3 900-line god class.
 *
 * **Architecture role:** Data layer. Owns the PackageManager query, caching,
 * favorite/auto-launch persistence, and initial sort. The caller (UI layer) only
 * receives a `List<AppInfo>` on the main thread — no threading awareness required.
 *
 * **Thread safety:**
 *  - [loadApps] dispatches the PM query on a single-threaded daemon executor.
 *  - The callback is always posted back to the main thread via [Handler].
 *  - The cached list is written once per load; mutations ([setFavorite],
 *    [setAutoLaunch]) are in-place on the same objects and are only called
 *    from the main thread (Activity callback).
 */
class AppRepository {

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Callback for async app loading. Invoked on the *main thread*.
     */
    fun interface Callback {
        fun onAppsLoaded(apps: List<AppInfo>)
    }

    /**
     * Loads the list of installed launchable apps asynchronously.
     *
     * If a cached result is available it is delivered immediately (synchronously
     * before this method returns) so the list appears with zero latency on screen
     * rotation. A background refresh is always scheduled regardless.
     *
     * @param ctx      any Context (Application context is extracted internally)
     * @param callback delivered on the main thread
     */
    fun loadApps(ctx: Context, callback: Callback) {
        val appCtx = ctx.applicationContext
        // Serve cached result immediately for instant UX on rotation / re-open.
        val cached = mCachedApps
        if (cached != null) {
            callback.onAppsLoaded(cached)
        }
        mExecutor.submit(Runnable {
            val apps = queryPackageManager(appCtx)
            mCachedApps = apps
            mMainHandler.post { callback.onAppsLoaded(apps) }
        })
    }

    /**
     * Returns the cached [AppInfo] for [packageName] from the FULL loaded list
     * (favorites INCLUDED), or null if the list hasn't loaded yet or the app isn't
     * installed. This is the correct source for resolving an auto-launch / boot-
     * projection target: the UI grid excludes favorites into a separate strip, so a
     * favorited target must NOT be looked up via the grid.
     */
    fun findByPackage(packageName: String): AppInfo? =
        mCachedApps?.firstOrNull { it.packageName == packageName }

    /**
     * Marks or unmarks a package as a favorite, persisting the change and updating
     * the in-memory cache. Triggers a re-sort since favorites float to the top.
     *
     * Must be called from the **main thread**.
     */
    fun setFavorite(ctx: Context, packageName: String, isFavorite: Boolean) {
        val favorites = ClusterPrefs.getFavorites(ctx) // returns a mutable copy
        if (isFavorite) {
            favorites.add(packageName)
        } else {
            favorites.remove(packageName)
        }
        ClusterPrefs.setFavorites(ctx, favorites)

        // Patch the cached list in-place and re-sort so the row floats up/down immediately.
        val cached = mCachedApps
        if (cached != null) {
            for (app in cached) {
                if (packageName == app.packageName) {
                    app.isFavorite = isFavorite
                    break
                }
            }
            sortApps(cached)
        }
    }

    /**
     * Sets one package as the auto-launch target, clearing any previous one.
     * Persists the change and patches the in-memory cache.
     *
     * Pass `enable=false` to clear auto-launch entirely.
     *
     * Must be called from the **main thread**.
     */
    fun setAutoLaunch(ctx: Context, packageName: String?, enable: Boolean) {
        ClusterPrefs.setAutoLaunchPkg(ctx, if (enable) packageName else null)

        // Patch the cache: clear all then set the one that won, so stale entries
        // from a previous auto-launch package are also cleared.
        val cached = mCachedApps
        if (cached != null) {
            for (app in cached) {
                app.isAutoLaunch = enable && packageName == app.packageName
            }
        }
    }

    /**
     * Invalidates the cache so the next [loadApps] call forces a fresh
     * PackageManager query (e.g., after an APK install/uninstall).
     */
    fun invalidateCache() {
        mCachedApps = null
    }

    /**
     * Returns the current in-memory app list without triggering a reload.
     * Safe to call from the main thread immediately after [setFavorite] or
     * [setAutoLaunch] to refresh the UI without a PackageManager re-query.
     *
     * @return the cached sorted list, or an empty list if no load has completed yet.
     */
    fun getApps(): List<AppInfo> = mCachedApps ?: emptyList()

    /** Shuts down the background executor. Call from Application.onTerminate() if needed. */
    fun shutdown() {
        mExecutor.shutdown()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private val mExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-repo-loader").apply { isDaemon = true }
    }
    private val mMainHandler = Handler(Looper.getMainLooper())

    /** Volatile so the main-thread fast-path in [loadApps] reads the latest write. */
    @Volatile
    private var mCachedApps: MutableList<AppInfo>? = null

    // queryIntentActivities(Intent, int) is deprecated as of API 33 but is the
    // only overload available on the API 29 target (the ResolveInfoFlags variant
    // is API 33+). Suppress the unavoidable deprecation, as the original Java did.
    @Suppress("DEPRECATION")
    private fun queryPackageManager(ctx: Context): MutableList<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        val resolveInfos: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            AppLogger.e(TAG, "PackageManager query failed: " + e.message)
            return mutableListOf()
        }

        val favorites = ClusterPrefs.getFavorites(ctx)
        val autoLaunchPkg = ClusterPrefs.getAutoLaunchPkg(ctx)
        val selfPkg = ctx.packageName
        val prefs = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)

        // Exclude well-known system launchers that should never appear in the list.
        val exclusions = buildExclusionSet(selfPkg)

        // Check shortcut host permission once — avoids per-app SecurityException overhead.
        var launcherApps: LauncherApps? = null
        var hasShortcutPerm = false
        val densityDpi = ctx.resources.displayMetrics.densityDpi
        try {
            launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            hasShortcutPerm = launcherApps != null && launcherApps.hasShortcutHostPermission()
        } catch (ignored: Exception) {
        }
        val la = launcherApps

        val apps = ArrayList<AppInfo>(resolveInfos.size)
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            if (exclusions.contains(pkg)) continue

            val name: String = try {
                ri.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg // graceful fallback
            }

            val icon: Drawable? = try {
                ri.loadIcon(pm)
            } catch (e: Exception) {
                pm.defaultActivityIcon
            }

            val info = AppInfo(pkg, name, icon)
            info.isFavorite = favorites.contains(pkg)
            info.isAutoLaunch = pkg == autoLaunchPkg
            info.launchCount = prefs.getInt("launch_count_$pkg", 0)

            // Load pinned/dynamic/manifest shortcuts. Permission checked once above.
            if (hasShortcutPerm && la != null) {
                try {
                    val query = LauncherApps.ShortcutQuery()
                    query.setQueryFlags(
                        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                    )
                    query.setPackage(pkg)
                    val shortcuts = la.getShortcuts(query, Process.myUserHandle())
                    if (shortcuts != null) {
                        for (s in shortcuts) {
                            val sIcon = la.getShortcutIconDrawable(s, densityDpi)
                            val shortLabel = s.shortLabel
                            info.shortcuts.add(
                                AppShortcut(
                                    s.id,
                                    shortLabel?.toString() ?: s.id,
                                    sIcon
                                )
                            )
                        }
                    }
                } catch (ignored: Exception) {
                }
            }

            apps.add(info)
        }

        sortApps(apps)
        AppLogger.d(TAG, "Loaded " + apps.size + " apps (favs=" + favorites.size + ")")
        return apps
    }

    /**
     * 4-level sort matching the UI contract:
     * 1. Category  (Navigation → Media → Other)
     * 2. Favorites first within each category
     * 3. Usage frequency (descending launch count)
     * 4. Alphabetical fallback (locale-aware, case-insensitive)
     */
    private fun sortApps(apps: MutableList<AppInfo>) {
        apps.sortWith { a, b ->
            when {
                a.category != b.category -> a.category.compareTo(b.category)
                a.isFavorite && !b.isFavorite -> -1
                !a.isFavorite && b.isFavorite -> 1
                a.launchCount != b.launchCount -> b.launchCount.compareTo(a.launchCount) // descending
                else -> a.appName.compareTo(b.appName, ignoreCase = true)
            }
        }
    }

    private fun buildExclusionSet(selfPkg: String): Set<String> = hashSetOf(
        selfPkg, // DashCast itself
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.miui.home",
        "com.byd.launcher"
    )

    companion object {
        private const val TAG = "AppRepository"
    }
}
