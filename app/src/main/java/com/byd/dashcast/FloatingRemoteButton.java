package com.byd.dashcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * FloatingRemoteButton — persistent overlay button visible over all screens.
 *
 * Displays a draggable 📺 badge. Only visible when an app is active on the cluster.
 * • Tap  → starts MainActivity with ACTION_SHOW_MIRROR via startActivity() (not a broadcast).
 * • Long press → closes this overlay service.
 *
 * Visibility is controlled externally via the static show() / hide() helpers
 * called by MainActivity whenever mCurrentDashboardApp changes.
 */
public class FloatingRemoteButton extends Service {

    private static final String TAG     = "FloatingRemoteBtn";
    private static final String CHANNEL = "floating_remote_btn";
    private static final int    NOTIF_ID = 9989;

    /** Broadcast action sent to MainActivity to open the mirror panel. */
    public static final String ACTION_SHOW_MIRROR =
            "com.byd.dashcast.action.SHOW_MIRROR";

    // ── Static helpers so MainActivity can show/hide without a Service reference ──
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static volatile FloatingRemoteButton sInstance;

    /**
     * Desired visibility state — survives even when sInstance or mFloatView is not yet
     * ready (service starting, overlay permission being granted via ADB).
     * When the overlay is finally created, it reads this flag to apply the correct state.
     */
    private static volatile boolean sShouldBeVisible = false;

    private android.os.Handler mDimHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mDimRunnable = new Runnable() {
        @Override
        public void run() {
            if (mFloatView != null) {
                mFloatView.animate().alpha(0.35f).setDuration(300).start();
            }
        }
    };

    public void triggerDimTimer() {
        if (mFloatView != null) mFloatView.setAlpha(1.0f);
        mDimHandler.removeCallbacks(mDimRunnable);
        mDimHandler.postDelayed(mDimRunnable, 3000);
    }

    public static void show() {
        sShouldBeVisible = true;
        FloatingRemoteButton inst = sInstance;
        if (inst != null && inst.mFloatView != null) {
            inst.mFloatView.post(() -> {
                FloatingRemoteButton i = sInstance;
                if (i != null && i.mFloatView != null && sShouldBeVisible) {
                    i.mFloatView.setVisibility(View.VISIBLE);
                    i.triggerDimTimer();
                }
            });
        }
    }

    public static void hide() {
        sShouldBeVisible = false;
        FloatingRemoteButton inst = sInstance;
        if (inst == null || inst.mFloatView == null) return;
        inst.mFloatView.post(() -> {
            FloatingRemoteButton i = sInstance;
            if (i != null && i.mFloatView != null) {
                i.mFloatView.setVisibility(View.GONE);
            }
        });
    }
    // ─────────────────────────────────────────────────────────────────────────

    private WindowManager mWindowManager;
    private View          mFloatView;
    private boolean       mGrantAttempted = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sInstance = this;
        if (mFloatView != null) return START_STICKY;
        startForegroundCompat();
        createOverlay();
        AppLogger.d(TAG, "FloatingRemoteButton started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mDimHandler.removeCallbacksAndMessages(null);
        sInstance = null;
        if (mFloatView != null) {
            try { mWindowManager.removeView(mFloatView); } catch (Exception ignored) {
                AppLogger.d(TAG, "removeView skipped (view already detached): " + ignored.getMessage());
            }
            mFloatView = null;
        }
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private void createOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            if (mGrantAttempted) {
                AppLogger.e(TAG, "SYSTEM_ALERT_WINDOW still denied after ADB attempt — badge not shown");
                return;
            }
            mGrantAttempted = true;
            AppLogger.w(TAG, "SYSTEM_ALERT_WINDOW not granted — attempting auto-grant via ADB…");
            AdbLocalClient.grantOverlayPermission(this, new AdbLocalClient.Callback() {
                @Override
                public void onSuccess(String report) {
                    AppLogger.i(TAG, "SYSTEM_ALERT_WINDOW granted via ADB ✓");
                    mDimHandler.post(new Runnable() {
                        @Override public void run() { createOverlay(); }
                    });
                }
                @Override
                public void onError(String error) {
                    AppLogger.e(TAG, "Auto-grant SYSTEM_ALERT_WINDOW failed: " + error);
                }
            });
            return;
        }
        mGrantAttempted = false;
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        final TextView badge = new TextView(this);
        badge.setText("\uD83D\uDCFA"); // 📺
        badge.setTextSize(22f);
        badge.setBackgroundColor(Color.argb(220, 0, 105, 92)); // teal #00695C
        badge.setPadding(20, 12, 20, 12);
        badge.setElevation(8f);
        badge.setVisibility(View.GONE); // hidden until an app is on the cluster

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                2038,  // TYPE_APPLICATION_OVERLAY
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 12;
        params.y = 220;

badge.setOnTouchListener(new View.OnTouchListener() {
            private int   initX, initY;
            private float initTX, initTY;
            private long  downTime;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                // Reset opacity and cancel pending dim
                badge.setAlpha(1.0f);
                mDimHandler.removeCallbacks(mDimRunnable);
                
                // Trigger dim
                if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    mDimHandler.postDelayed(mDimRunnable, 3000);
                }

                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX  = params.x;
                        initY  = params.y;
                        initTX = e.getRawX();
                        initTY = e.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = initTX - e.getRawX();
                        float dy = e.getRawY() - initTY;
                        params.x = initX + (int) dx;
                        params.y = initY + (int) dy;
                        mWindowManager.updateViewLayout(mFloatView, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        float movX = Math.abs(e.getRawX() - initTX);
                        float movY = Math.abs(e.getRawY() - initTY);
                        long held = System.currentTimeMillis() - downTime;
                        if (movX < 12 && movY < 12) {
                            if (held > 600) {
                                // Long press → show recent apps popup for quick switching
                                AppLogger.i(TAG, "Long-press → show quick-switch popup");
                                showQuickSwitchPopup();
                            } else {
                                // Tap → bring MainActivity to front + open mirror panel
                                Intent bringFront = new Intent(
                                        FloatingRemoteButton.this, MainActivity.class);
                                bringFront.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                                bringFront.setAction(ACTION_SHOW_MIRROR);
                                startActivity(bringFront);
                                AppLogger.d(TAG, "Tap → ACTION_SHOW_MIRROR");
                            }
                        } else {
                            // Snap to edge logic
                            int screenWidth = getResources().getDisplayMetrics().widthPixels;
                            int halfWidth = screenWidth / 2;
                            // Gravity is END, so params.x is the margin from the RIGHT.
                            // If params.x > halfWidth, it's closer to the LEFT edge.
                            int targetX = (params.x > halfWidth) ? (screenWidth - badge.getWidth()) : 0;
                            
                            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofInt(params.x, targetX);
                            anim.setDuration(250);
                            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
                            anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                                @Override
                                public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                                    params.x = (Integer) animation.getAnimatedValue();
                                    try {
                                        if (mFloatView != null) mWindowManager.updateViewLayout(mFloatView, params);
                                    } catch (Exception ignored) {
                                        AppLogger.d(TAG, "updateViewLayout skipped: " + ignored.getMessage());
                                    }
                                }
                            });
                            anim.start();
                        }
                        return true;
                }
                return false;
            }
        });

        mFloatView = badge;
        try {
            mWindowManager.addView(mFloatView, params);
            // Apply the desired visibility that may have been requested before
            // the overlay was ready (race: show() called before createOverlay completed).
            if (sShouldBeVisible) {
                mFloatView.setVisibility(View.VISIBLE);
                triggerDimTimer();
                AppLogger.d(TAG, "Overlay created — applying deferred show()");
            } else {
                mDimHandler.postDelayed(mDimRunnable, 3000);
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "addView overlay failed", e);
            mFloatView = null;
        }
    }

    /** Broadcast action sent to MainActivity to quick-switch to a specific app. */
    public static final String ACTION_QUICK_SWITCH =
            "com.byd.dashcast.action.QUICK_SWITCH";
    public static final String EXTRA_QUICK_SWITCH_PKG = "quick_switch_pkg";

    private void showQuickSwitchPopup() {
        android.content.SharedPreferences prefs =
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String raw = prefs.getString(SettingsActivity.PREF_RECENT_APPS, "");
        if (raw.isEmpty()) {
            android.widget.Toast.makeText(this,
                    getString(R.string.quick_switch_empty), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String[] entries = raw.split(";;");
        final String[] names = new String[entries.length];
        final String[] pkgs  = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            String[] parts = entries[i].split("\\|", 2);
            pkgs[i]  = parts[0];
            names[i] = parts.length > 1 ? parts[1] : parts[0];
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.quick_switch_title))
                .setItems(names, (dialog, which) -> {
                    Intent intent = new Intent(FloatingRemoteButton.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    intent.setAction(ACTION_QUICK_SWITCH);
                    intent.putExtra(EXTRA_QUICK_SWITCH_PKG, pkgs[which]);
                    startActivity(intent);
                    AppLogger.d(TAG, "Quick-switch → " + pkgs[which]);
                })
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        dlg.show();
    }

    // ── Foreground service ────────────────────────────────────────────────────

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.notif_remote_channel_name), NotificationManager.IMPORTANCE_MIN));

        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("DashCast")
                .setContentText(getString(R.string.notif_remote_content))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIF_ID, notif);
    }
}

