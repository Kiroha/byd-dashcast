package com.byd.dashcast.ui.main;

import android.content.Context;
import android.view.View;

import com.byd.dashcast.AdbLocalClient;
import com.byd.dashcast.AppLogger;
import com.byd.dashcast.R;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.ime.ClusterImeWatcherService;
import com.byd.dashcast.platform.Platform;
import com.byd.dashcast.proxy.ShellGateway;

/**
 * Owns the two onboarding banners:
 * <ul>
 *   <li>IME / Accessibility service banner (DL5 only) — {@code card_ime_a11y_banner}
 *   <li>HUD notification-listener banner (all devices) — {@code card_hud_notif_banner}
 * </ul>
 * Call {@link #refresh()} from {@code onStart} / {@code onResume} to recompute visibility.
 */
public final class PermissionBannerCoordinator {

    private static final String TAG = "PermBannerCoord";

    public interface Host {
        Context getContext();
        void runOnUiThread(Runnable r);
        /** Returns false once the host Activity is finishing or destroyed. */
        boolean isActivityAlive();
        void startActivity(android.content.Intent intent);
    }

    private final View mCardIme;
    private final View mCardHudNotif;
    private final Host mHost;

    public PermissionBannerCoordinator(View cardIme, View cardHudNotif, Host host) {
        mCardIme      = cardIme;
        mCardHudNotif = cardHudNotif;
        mHost         = host;
        try { setupIme();      } catch (Throwable t) { AppLogger.e(TAG, "setupIme failed", t); }
        try { setupHudNotif(); } catch (Throwable t) { AppLogger.e(TAG, "setupHudNotif failed", t); }
    }

    /** Recomputes both banners' visibility. Safe to call repeatedly from lifecycle methods. */
    public void refresh() {
        try { refreshIme();      } catch (Throwable t) { AppLogger.e(TAG, "refreshIme failed", t); }
        try { refreshHudNotif(); } catch (Throwable t) { AppLogger.e(TAG, "refreshHudNotif failed", t); }
    }

    // ── IME / A11Y banner ─────────────────────────────────────────────────────

    private void setupIme() {
        if (mCardIme == null) return;
        View btnEnable  = mCardIme.findViewById(R.id.btn_ime_banner_enable);
        View btnLater   = mCardIme.findViewById(R.id.btn_ime_banner_later);
        View btnDismiss = mCardIme.findViewById(R.id.btn_ime_banner_dismiss);

        if (btnEnable  != null) btnEnable.setOnClickListener(v -> enableImeA11yOneClick(v));
        if (btnLater   != null) btnLater.setOnClickListener(v -> mCardIme.setVisibility(View.GONE));
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                try { ClusterPrefs.setImeBannerDismissed(mHost.getContext()); } catch (Throwable ignored) {}
                mCardIme.setVisibility(View.GONE);
            });
        }
        refreshIme();
    }

    private void refreshIme() {
        if (mCardIme == null) return;
        boolean shouldShow = false;
        try {
            if (Platform.get().isDiLink5(mHost.getContext())) {
                boolean dismissed = ClusterPrefs.isImeBannerDismissed(mHost.getContext());
                boolean enabled   = ClusterImeWatcherService.isEnabled(mHost.getContext());
                shouldShow = !dismissed && !enabled;
                if (enabled && mCardIme.getVisibility() == View.VISIBLE) {
                    try {
                        android.widget.Toast.makeText(mHost.getContext(),
                                R.string.ime_banner_toast_enabled,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "refreshIme inner failed", t);
        }
        mCardIme.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    private void enableImeA11yOneClick(final View btnEnable) {
        if (btnEnable != null) btnEnable.setEnabled(false);
        try {
            final String comp = "com.byd.dashcast/com.byd.dashcast.ime.ClusterImeWatcherService";
            final String cmd =
                    "COMP='" + comp + "'; "
                  + "CURRENT=$(settings get secure enabled_accessibility_services 2>/dev/null); "
                  + "if [ \"$CURRENT\" = \"null\" ] || [ -z \"$CURRENT\" ]; then "
                  +   "NEW=\"$COMP\"; "
                  + "elif echo \"$CURRENT\" | grep -q \"$COMP\"; then "
                  +   "NEW=\"$CURRENT\"; "
                  + "else "
                  +   "NEW=\"$CURRENT:$COMP\"; "
                  + "fi; "
                  + "settings put secure enabled_accessibility_services \"$NEW\"; "
                  + "settings put secure accessibility_enabled 1; "
                  + "echo OUT=$(settings get secure enabled_accessibility_services)";

            ShellGateway.execShellWithResult(mHost.getContext(), cmd, new AdbLocalClient.Callback() {
                @Override public void onSuccess(final String report) {
                    mHost.runOnUiThread(() -> {
                        if (!mHost.isActivityAlive()) return;
                        boolean ok = ClusterImeWatcherService.isEnabled(mHost.getContext());
                        if (ok) {
                            AppLogger.i(TAG, "IME a11y enabled via shell ✓");
                            try {
                                android.widget.Toast.makeText(mHost.getContext(),
                                        R.string.ime_banner_toast_enabled,
                                        android.widget.Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {}
                            if (mCardIme != null) mCardIme.setVisibility(View.GONE);
                            if (btnEnable != null) btnEnable.setEnabled(true);
                        } else {
                            AppLogger.w(TAG, "shell OK but a11y not enabled — fallback. report=" + report);
                            openA11ySettingsFallback();
                            if (btnEnable != null) btnEnable.setEnabled(true);
                        }
                    });
                }
                @Override public void onError(final String error) {
                    mHost.runOnUiThread(() -> {
                        if (!mHost.isActivityAlive()) return;
                        AppLogger.w(TAG, "one-click a11y shell failed: " + error + " — fallback");
                        openA11ySettingsFallback();
                        if (btnEnable != null) btnEnable.setEnabled(true);
                    });
                }
            });
        } catch (Throwable t) {
            AppLogger.e(TAG, "enableImeA11yOneClick threw", t);
            openA11ySettingsFallback();
            if (btnEnable != null) btnEnable.setEnabled(true);
        }
    }

    private void openA11ySettingsFallback() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            mHost.startActivity(i);
            return;
        } catch (Throwable t) {
            AppLogger.w(TAG, "ACTION_ACCESSIBILITY_SETTINGS unavailable: " + t.getMessage());
        }
        try {
            android.content.Intent i = new android.content.Intent();
            i.setComponent(new android.content.ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings$AccessibilitySettingsActivity"));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            mHost.startActivity(i);
            return;
        } catch (Throwable t) {
            AppLogger.w(TAG, "direct AccessibilitySettingsActivity unavailable: " + t.getMessage());
        }
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_SETTINGS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            mHost.startActivity(i);
        } catch (Throwable t) {
            AppLogger.e(TAG, "no Settings activity reachable on this ROM", t);
            try {
                android.widget.Toast.makeText(mHost.getContext(),
                        R.string.ime_banner_toast_cannot_open_settings,
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }

    // ── HUD notification-listener banner ──────────────────────────────────────

    private void setupHudNotif() {
        if (mCardHudNotif == null) return;
        View btnEnable  = mCardHudNotif.findViewById(R.id.btn_hud_banner_enable);
        View btnLater   = mCardHudNotif.findViewById(R.id.btn_hud_banner_later);
        View btnDismiss = mCardHudNotif.findViewById(R.id.btn_hud_banner_dismiss);

        if (btnEnable  != null) btnEnable.setOnClickListener(v -> enableHudNotifOneClick(v));
        if (btnLater   != null) btnLater.setOnClickListener(v -> mCardHudNotif.setVisibility(View.GONE));
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> {
                try { ClusterPrefs.setHudBannerDismissed(mHost.getContext()); } catch (Throwable ignored) {}
                mCardHudNotif.setVisibility(View.GONE);
            });
        }
        refreshHudNotif();
    }

    private void refreshHudNotif() {
        if (mCardHudNotif == null) return;
        boolean shouldShow = false;
        try {
            boolean dismissed = ClusterPrefs.isHudBannerDismissed(mHost.getContext());
            if (!dismissed) {
                String listeners = android.provider.Settings.Secure.getString(
                        mHost.getContext().getContentResolver(), "enabled_notification_listeners");
                boolean granted = listeners != null && listeners.contains(mHost.getContext().getPackageName());
                shouldShow = !granted;
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "refreshHudNotif inner failed", t);
        }
        mCardHudNotif.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    private void enableHudNotifOneClick(final View btnEnable) {
        if (btnEnable != null) btnEnable.setEnabled(false);
        try {
            final String comp = "com.byd.dashcast/com.byd.dashcast.hud.MapNotificationListenerService";
            final String cmd  = "cmd notification allow_listener " + comp;

            ShellGateway.execShellWithResult(mHost.getContext(), cmd, new AdbLocalClient.Callback() {
                @Override public void onSuccess(final String report) {
                    mHost.runOnUiThread(() -> {
                        if (!mHost.isActivityAlive()) return;
                        String listeners = android.provider.Settings.Secure.getString(
                                mHost.getContext().getContentResolver(), "enabled_notification_listeners");
                        boolean ok = listeners != null && listeners.contains(mHost.getContext().getPackageName());
                        if (ok) {
                            AppLogger.i(TAG, "Notification listener enabled via shell ✓");
                            try {
                                android.widget.Toast.makeText(mHost.getContext(),
                                        R.string.hud_notif_banner_toast_enabled,
                                        android.widget.Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {}
                            if (mCardHudNotif != null) mCardHudNotif.setVisibility(View.GONE);
                            if (btnEnable != null) btnEnable.setEnabled(true);
                        } else {
                            AppLogger.w(TAG, "shell OK but listener not enabled — fallback. report=" + report);
                            openNotifListenerSettingsFallback();
                            if (btnEnable != null) btnEnable.setEnabled(true);
                        }
                    });
                }
                @Override public void onError(final String error) {
                    mHost.runOnUiThread(() -> {
                        if (!mHost.isActivityAlive()) return;
                        AppLogger.w(TAG, "one-click notif listener shell failed: " + error + " — fallback");
                        openNotifListenerSettingsFallback();
                        if (btnEnable != null) btnEnable.setEnabled(true);
                    });
                }
            });
        } catch (Throwable t) {
            AppLogger.e(TAG, "enableHudNotifOneClick threw", t);
            openNotifListenerSettingsFallback();
            if (btnEnable != null) btnEnable.setEnabled(true);
        }
    }

    private void openNotifListenerSettingsFallback() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            mHost.startActivity(i);
        } catch (Throwable t) {
            AppLogger.w(TAG, "ACTION_NOTIFICATION_LISTENER_SETTINGS unavailable: " + t.getMessage());
        }
    }
}
