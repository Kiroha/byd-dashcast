package com.byd.dashcast.model;

import android.graphics.drawable.Drawable;
import java.util.List;
import java.util.ArrayList;

public class AppInfo {
    public static final int CATEGORY_NAVIGATION = 1;
    public static final int CATEGORY_MEDIA = 2;
    public static final int CATEGORY_OTHER = 3;

    public final String packageName;
    public final String appName;
    public final Drawable icon;
    public boolean isFavorite = false;
    public boolean isAutoLaunch = false;
    public int launchCount = 0;
    
    public int category = CATEGORY_OTHER;
    public List<AppShortcut> shortcuts = new ArrayList<>();

    public AppInfo(String packageName, String appName, Drawable icon) {
        this.packageName = packageName;
        this.appName     = appName;
        this.icon        = icon;
        
        // Auto-Categorization logic based on package name or app name
        String pkg = packageName.toLowerCase();
        if (pkg.contains("maps") || pkg.contains("waze") || pkg.contains("tomtom") || 
            pkg.contains("sygic") || pkg.contains("navigation") || pkg.contains("here") || 
            pkg.contains("yandex.navi") || pkg.contains("radarbot") || pkg.contains("coyote") || pkg.contains("osmand")) {
            category = CATEGORY_NAVIGATION;
        } else if (pkg.contains("spotify") || pkg.contains("music") || pkg.contains("youtube") || 
                   pkg.contains("podcast") || pkg.contains("radio") || pkg.contains("vlc") || 
                   pkg.contains("audible") || pkg.contains("media") || pkg.contains("player") || pkg.contains("sound")) {
            category = CATEGORY_MEDIA;
        }
    }
}
