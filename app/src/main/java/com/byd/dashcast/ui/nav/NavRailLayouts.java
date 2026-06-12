package com.byd.dashcast.ui.nav;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import com.byd.dashcast.fission.LayoutManagerActivity;

/**
 * Wires the "Layouts" nav-rail entry present on every activity from v1.4.9-beta.
 * Always visible (no runtime gate) — tap navigates to {@link LayoutManagerActivity}.
 */
public final class NavRailLayouts {
    private NavRailLayouts() {}

    public static void apply(Activity host, int viewId, boolean finishOnNav) {
        if (host == null) return;
        View v = host.findViewById(viewId);
        if (v == null) return;
        v.setOnClickListener(view -> {
            host.startActivity(new Intent(host, LayoutManagerActivity.class));
            if (finishOnNav) host.finish();
        });
    }
}
