package com.byd.dashcast.model;

import android.graphics.drawable.Drawable;

public class AppInfo {
    public final String packageName;
    public final String appName;
    public final Drawable icon;

    public AppInfo(String packageName, String appName, Drawable icon) {
        this.packageName = packageName;
        this.appName     = appName;
        this.icon        = icon;
    }
}
