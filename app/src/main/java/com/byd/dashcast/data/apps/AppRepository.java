package com.byd.dashcast.data.apps;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.model.AppInfo;
import com.byd.dashcast.model.AppShortcut;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AppRepository — single source of truth for the list of installable applications.
 *
 * <p><b>Problem solved:</b> Previously ~150 lines of app-loading logic were embedded
 * directly in {@code MainActivity.loadInstalledApps()}, {@code setFavorite()}, and
 * {@code setAutoLaunch()}, mixed with UI event handling. Every change to sorting,
 * filtering, or persistence required editing the 3 900-line god class.
 *
 * <p><b>Architecture role:</b> Data layer. Owns the PackageManager query, caching,
 * favorite/auto-launch persistence, and initial sort. The caller (UI layer) only
 * receives a {@code List<AppInfo>} on the main thread — no threading awareness required.
 *
 * <p><b>Thread safety:</b>
 * <ul>
 *   <li>{@link #loadApps} dispatches the PM query on a single-threaded daemon executor.
 *   <li>The callback is always posted back to the main thread via {@link Handler}.
 *   <li>The cached list is written once per load; mutations ({@link #setFavorite},
 *       {@link #setAutoLaunch}) are in-place on the same objects and are only called
 *       from the main thread (Activity callback).
 * </ul>
 */
public final class AppRepository {

    private static final String TAG = "AppRepository";

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Callback for async app loading. Invoked on the <em>main thread</em>.
     */
    public interface Callback {
        void onAppsLoaded(List<AppInfo> apps);
    }

    /**
     * Loads the list of installed launchable apps asynchronously.
     *
     * <p>If a cached result is available it is delivered immediately (synchronously
     * before this method returns) so the list appears with zero latency on screen
     * rotation. A background refresh is always scheduled regardless.
     *
     * @param ctx      any Context (Application context is extracted internally)
     * @param callback delivered on the main thread
     */
    public void loadApps(Context ctx, Callback callback) {
        final Context appCtx = ctx.getApplicationContext();
        // Serve cached result immediately for instant UX on rotation / re-open.
        List<AppInfo> cached = mCachedApps;
        if (cached != null) {
            callback.onAppsLoaded(cached);
        }
        mExecutor.submit(() -> {
            List<AppInfo> apps = queryPackageManager(appCtx);
            mCachedApps = apps;
            mMainHandler.post(() -> callback.onAppsLoaded(apps));
        });
    }

    /**
     * Marks or unmarks a package as a favorite, persisting the change and updating
     * the in-memory cache. Triggers a re-sort since favorites float to the top.
     *
     * <p>Must be called from the <b>main thread</b>.
     */
    public void setFavorite(Context ctx, String packageName, boolean isFavorite) {
        Set<String> favorites = ClusterPrefs.getFavorites(ctx); // returns a mutable copy
        if (isFavorite) {
            favorites.add(packageName);
        } else {
            favorites.remove(packageName);
        }
        ClusterPrefs.setFavorites(ctx, favorites);

        // Patch the cached list in-place and re-sort so the row floats up/down immediately.
        List<AppInfo> cached = mCachedApps;
        if (cached != null) {
            for (AppInfo app : cached) {
                if (packageName.equals(app.packageName)) {
                    app.isFavorite = isFavorite;
                    break;
                }
            }
            sortApps(cached);
        }
    }

    /**
     * Sets one package as the auto-launch target, clearing any previous one.
     * Persists the change and patches the in-memory cache.
     *
     * <p>Pass {@code enable=false} to clear auto-launch entirely.
     *
     * <p>Must be called from the <b>main thread</b>.
     */
    public void setAutoLaunch(Context ctx, String packageName, boolean enable) {
        ClusterPrefs.setAutoLaunchPkg(ctx, enable ? packageName : null);

        // Patch the cache: clear all then set the one that won, so stale entries
        // from a previous auto-launch package are also cleared.
        List<AppInfo> cached = mCachedApps;
        if (cached != null) {
            for (AppInfo app : cached) {
                app.isAutoLaunch = enable && packageName.equals(app.packageName);
            }
        }
    }

    /**
     * Invalidates the cache so the next {@link #loadApps} call forces a fresh
     * PackageManager query (e.g., after an APK install/uninstall).
     */
    public void invalidateCache() {
        mCachedApps = null;
    }

    /**
     * Returns the current in-memory app list without triggering a reload.
     * Safe to call from the main thread immediately after {@link #setFavorite} or
     * {@link #setAutoLaunch} to refresh the UI without a PackageManager re-query.
     *
     * @return the cached sorted list, or an empty list if no load has completed yet.
     */
    public List<AppInfo> getApps() {
        List<AppInfo> cached = mCachedApps;
        return cached != null ? cached : Collections.emptyList();
    }

    /** Shuts down the background executor. Call from Application.onTerminate() if needed. */
    public void shutdown() {
        mExecutor.shutdown();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "app-repo-loader");
        t.setDaemon(true);
        return t;
    });
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** Volatile so the main-thread fast-path in {@link #loadApps} reads the latest write. */
    private volatile List<AppInfo> mCachedApps = null;

    private List<AppInfo> queryPackageManager(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolveInfos;
        try {
            resolveInfos = pm.queryIntentActivities(intent, 0);
        } catch (Exception e) {
            AppLogger.e(TAG, "PackageManager query failed: " + e.getMessage());
            return Collections.emptyList();
        }

        Set<String> favorites  = ClusterPrefs.getFavorites(ctx);
        String autoLaunchPkg   = ClusterPrefs.getAutoLaunchPkg(ctx);
        String selfPkg         = ctx.getPackageName();
        SharedPreferences prefs = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE);

        // Exclude well-known system launchers that should never appear in the list.
        Set<String> exclusions = buildExclusionSet(selfPkg);

        // Check shortcut host permission once — avoids per-app SecurityException overhead.
        android.content.pm.LauncherApps launcherApps = null;
        boolean hasShortcutPerm = false;
        int densityDpi = ctx.getResources().getDisplayMetrics().densityDpi;
        try {
            launcherApps = (android.content.pm.LauncherApps)
                    ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            hasShortcutPerm = launcherApps != null && launcherApps.hasShortcutHostPermission();
        } catch (Exception ignored) { }

        List<AppInfo> apps = new ArrayList<>(resolveInfos.size());
        for (ResolveInfo ri : resolveInfos) {
            String pkg = ri.activityInfo.packageName;
            if (exclusions.contains(pkg)) continue;

            String name;
            try {
                name = ri.loadLabel(pm).toString();
            } catch (Exception e) {
                name = pkg; // graceful fallback
            }

            android.graphics.drawable.Drawable icon;
            try {
                icon = ri.loadIcon(pm);
            } catch (Exception e) {
                icon = pm.getDefaultActivityIcon();
            }

            AppInfo info      = new AppInfo(pkg, name, icon);
            info.isFavorite   = favorites.contains(pkg);
            info.isAutoLaunch = pkg.equals(autoLaunchPkg);
            info.launchCount  = prefs.getInt("launch_count_" + pkg, 0);

            // Load pinned/dynamic/manifest shortcuts. Permission checked once above.
            if (hasShortcutPerm) {
                try {
                    android.content.pm.LauncherApps.ShortcutQuery query =
                            new android.content.pm.LauncherApps.ShortcutQuery();
                    query.setQueryFlags(
                              android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                    query.setPackage(pkg);
                    List<android.content.pm.ShortcutInfo> shortcuts =
                            launcherApps.getShortcuts(query, android.os.Process.myUserHandle());
                    if (shortcuts != null) {
                        for (android.content.pm.ShortcutInfo s : shortcuts) {
                            android.graphics.drawable.Drawable sIcon =
                                    launcherApps.getShortcutIconDrawable(s, densityDpi);
                            CharSequence shortLabel = s.getShortLabel();
                            info.shortcuts.add(new AppShortcut(
                                    s.getId(),
                                    shortLabel != null ? shortLabel.toString() : s.getId(),
                                    sIcon));
                        }
                    }
                } catch (Exception ignored) { }
            }

            apps.add(info);
        }

        sortApps(apps);
        AppLogger.d(TAG, "Loaded " + apps.size() + " apps (favs=" + favorites.size() + ")");
        return apps;
    }

    /**
     * 4-level sort matching the UI contract:
     * 1. Category  (Navigation → Media → Other)
     * 2. Favorites first within each category
     * 3. Usage frequency (descending launch count)
     * 4. Alphabetical fallback (locale-aware, case-insensitive)
     */
    private static void sortApps(List<AppInfo> apps) {
        Collections.sort(apps, (a, b) -> {
            if (a.category != b.category) {
                return Integer.compare(a.category, b.category);
            }
            if (a.isFavorite && !b.isFavorite) return -1;
            if (!a.isFavorite && b.isFavorite) return 1;
            if (a.launchCount != b.launchCount) {
                return Integer.compare(b.launchCount, a.launchCount); // descending
            }
            return a.appName.compareToIgnoreCase(b.appName);
        });
    }

    private static Set<String> buildExclusionSet(String selfPkg) {
        Set<String> excl = new HashSet<>();
        excl.add(selfPkg);                    // DashCast itself
        excl.add("com.android.launcher");
        excl.add("com.android.launcher2");
        excl.add("com.android.launcher3");
        excl.add("com.miui.home");
        excl.add("com.byd.launcher");
        return excl;
    }
}
