package com.byd.myapp;

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
 * • Tap  → broadcasts ACTION_SHOW_MIRROR so MainActivity opens the mirror panel.
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
            "com.byd.myapp.action.SHOW_MIRROR";

    // ── Static helpers so MainActivity can show/hide without a Service reference ──
    private static FloatingRemoteButton sInstance;

    public static void show() {
        FloatingRemoteButton inst = sInstance;
        if (inst != null && inst.mFloatView != null) {
            inst.mFloatView.post(new Runnable() {
                @Override public void run() {
                    FloatingRemoteButton i = sInstance;
                    if (i != null && i.mFloatView != null) {
                        i.mFloatView.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    }

    public static void hide() {
        FloatingRemoteButton inst = sInstance;
        if (inst == null || inst.mFloatView == null) return;
        inst.mFloatView.post(new Runnable() {
            @Override public void run() {
                FloatingRemoteButton i = sInstance;
                if (i != null && i.mFloatView != null) {
                    i.mFloatView.setVisibility(View.GONE);
                }
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
        sInstance = null;
        if (mFloatView != null) {
            try { mWindowManager.removeView(mFloatView); } catch (Exception ignored) {}
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
            final android.os.Handler mainHandler =
                    new android.os.Handler(getMainLooper());
            AdbLocalClient.grantOverlayPermission(this, new AdbLocalClient.Callback() {
                @Override
                public void onSuccess(String report) {
                    AppLogger.i(TAG, "SYSTEM_ALERT_WINDOW granted via ADB ✓");
                    mainHandler.post(new Runnable() {
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
                                // Long press → close overlay
                                AppLogger.i(TAG, "Long-press → stop FloatingRemoteButton");
                                stopSelf();
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
                        }
                        return true;
                }
                return false;
            }
        });

        mFloatView = badge;
        try {
            mWindowManager.addView(mFloatView, params);
        } catch (Exception e) {
            AppLogger.e(TAG, "addView overlay failed", e);
            mFloatView = null;
        }
    }

    // ── Foreground service ────────────────────────────────────────────────────

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Bouton miroir cluster", NotificationManager.IMPORTANCE_MIN));

        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("DashCast")
                .setContentText("📺 Miroir cluster actif")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIF_ID, notif);
    }
}

