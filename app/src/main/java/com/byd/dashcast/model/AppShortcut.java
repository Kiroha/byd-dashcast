package com.byd.dashcast.model;

import android.content.Intent;
import android.graphics.drawable.Drawable;

public class AppShortcut {
    public final String id;
    public final String label;
    public final Drawable icon;
    public final Intent intent;

    public AppShortcut(String id, String label, Drawable icon, Intent intent) {
        this.id = id;
        this.label = label;
        this.icon = icon;
        this.intent = intent;
    }
}
