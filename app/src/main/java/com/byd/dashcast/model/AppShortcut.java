package com.byd.dashcast.model;

import android.graphics.drawable.Drawable;

public class AppShortcut {
    public final String id;
    public final String label;
    public final Drawable icon;

    public AppShortcut(String id, String label, Drawable icon) {
        this.id = id;
        this.label = label;
        this.icon = icon;
    }
}
