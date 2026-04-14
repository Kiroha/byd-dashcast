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
 * FloatingLogButton — bouton overlay persistant (visible sur tous les écrans).
 *
 * Affiche un petit badge "LOG" déplaçable dans le coin de l'écran.
 * • Un tap ouvre LogActivity.
 * • Long press efface le journal.
 *
 * Démarre comme foreground Service depuis MainActivity.onCreate() et reste
 * actif tanto que l'app est vivante.
 *
 * Utilise TYPE_APPLICATION_OVERLAY (2038) — SYSTEM_ALERT_WINDOW est déclaré
 * dans le manifest ET l'APK est signé avec platform.keystore (= accordé).
 */
public class FloatingLogButton extends Service {

    private static final String TAG     = "FloatingLogBtn";
    private static final String CHANNEL = "floating_log_btn";
    private static final int    NOTIF_ID = 9988;

    private WindowManager mWindowManager;
    private View          mFloatView;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mFloatView != null) return START_STICKY; // déjà créé

        startForegroundCompat();
        createOverlay();
        AppLogger.d(TAG, "FloatingLogButton démarré");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mFloatView != null) {
            try { mWindowManager.removeView(mFloatView); } catch (Exception ignored) {}
            mFloatView = null;
        }
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private void createOverlay() {
        // Guard : SYSTEM_ALERT_WINDOW (AppOp) doit être accordée avant addView().
        // Sur Android 10+, même avec platform.keystore, l'AppOp n'est pas accordé
        // automatiquement pour une app en /data/app.  On tente un auto-grant via le
        // shell ADB local (dadb), puis on relance createOverlay() sur le main thread.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            AppLogger.w(TAG, "SYSTEM_ALERT_WINDOW non accordée — tentative auto-grant via ADB…");
            final android.os.Handler mainHandler =
                    new android.os.Handler(getMainLooper());
            AdbLocalClient.grantOverlayPermission(this, new AdbLocalClient.Callback() {
                @Override
                public void onSuccess(String report) {
                    AppLogger.i(TAG, "SYSTEM_ALERT_WINDOW accordée via ADB ✓");
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            createOverlay(); // relance — canDrawOverlays() est désormais true
                        }
                    });
                }
                @Override
                public void onError(String error) {
                    AppLogger.e(TAG, "Auto-grant SYSTEM_ALERT_WINDOW échoué: " + error
                            + " — badge LOG non affiché");
                }
            });
            return;
        }
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Badge textuel compact
        final TextView badge = new TextView(this);
        badge.setText("LOG");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(11f);
        badge.setBackgroundColor(Color.argb(220, 20, 90, 180)); // bleu semi-transparent
        badge.setPadding(18, 10, 18, 10);
        badge.setElevation(8f);

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
                                // Long press → effacer
                                AppLogger.clear();
                                AppLogger.i(TAG, "Journal effacé via long press");
                            } else {
                                // Tap → ouvrir LogActivity
                                Intent intent = new Intent(FloatingLogButton.this, LogActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                                startActivity(intent);
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
            AppLogger.e(TAG, "addView overlay échoué — permission refusée ?", e);
            mFloatView = null;
        }
    }

    // ── Foreground service (obligatoire API 26+) ──────────────────────────────

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Journal de bord", NotificationManager.IMPORTANCE_MIN));

        Intent tapIntent = new Intent(this, LogActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("MyBYDApp")
                .setContentText("Journal actif — tap pour ouvrir")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIF_ID, notif);
    }
}
