package com.byd.dashcast.ui.nav;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.Toast;

import com.byd.dashcast.ui.diag.DiagActivity;
import com.byd.dashcast.ui.log.LogActivity;
import com.byd.dashcast.R;
import com.byd.dashcast.ui.settings.SettingsActivity;
import com.byd.dashcast.ui.diag.SysInfoActivity;

/**
 * Wires the static nav-rail entries (Settings, Diag, SysInfo, Log, Help).
 * Call {@link #wire(Activity)} once from onCreate after setContentView.
 * Dynamic entries (Hotspot, Layouts) are handled by NavigationCoordinator and NavRailLayouts.
 */
public final class NavRailSetup {

    private NavRailSetup() {}

    public static void wire(Activity host) {
        if (host == null) return;

        wireNav(host, R.id.nav_settings, SettingsActivity.class);
        wireNav(host, R.id.nav_diag,     DiagActivity.class);
        wireNav(host, R.id.nav_sysinfo,  SysInfoActivity.class);
        wireNav(host, R.id.nav_log,      LogActivity.class);

        View navHelp = host.findViewById(R.id.nav_help);
        if (navHelp != null) navHelp.setOnClickListener(v -> {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/Kiroha/byd-dashcast"));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                host.startActivity(it);
            } catch (Exception e) {
                Toast.makeText(host.getApplicationContext(),
                        R.string.main_nav_help, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void wireNav(Activity host, int viewId, Class<? extends Activity> target) {
        View v = host.findViewById(viewId);
        if (v != null) v.setOnClickListener(view ->
                host.startActivity(new Intent(host, target)));
    }
}
