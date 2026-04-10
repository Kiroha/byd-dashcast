package com.byd.myapp;

import android.content.pm.PackageManager;
import android.hardware.bydauto.energy.BYDAutoEnergyDevice;
import android.hardware.bydauto.gearbox.BYDAutoGearboxDevice;
import android.hardware.bydauto.speed.BYDAutoSpeedDevice;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * BYDLiveActivity — tableau de bord développeur.
 *
 * Affiche en temps réel (polling 1 s) :
 *  • Vitesse (km/h) via BYDAutoSpeedDevice
 *  • Rapport de boîte (P/R/N/D/S/M) via BYDAutoGearboxDevice
 *  • Mode énergie (EV/HEV/FUEL…) via BYDAutoEnergyDevice
 *  • Puissance régénérative (kW)
 *
 * + Journal de bord (AppLogger) défilant en bas, partageable sans ADB.
 */
public class BYDLiveActivity extends AppCompatActivity {

    private static final String TAG = "BYDLive";
    private static final long POLL_MS = 1000;

    private BYDAutoSpeedDevice   mSpeedDevice;
    private BYDAutoEnergyDevice  mEnergyDevice;
    private BYDAutoGearboxDevice mGearboxDevice;

    private TextView tvSpeed;
    private TextView tvGear;
    private TextView tvEnergy;
    private TextView tvPowerGen;
    private TextView tvPermStatus;
    private TextView tvLog;
    private ScrollView scrollLog;

    private final Handler mHandler = new Handler();
    private boolean mRunning = false;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_byd_live);

        tvSpeed      = (TextView)   findViewById(R.id.tv_live_speed);
        tvGear       = (TextView)   findViewById(R.id.tv_live_gear);
        tvEnergy     = (TextView)   findViewById(R.id.tv_live_energy);
        tvPowerGen   = (TextView)   findViewById(R.id.tv_live_powergen);
        tvPermStatus = (TextView)   findViewById(R.id.tv_live_perm_status);
        tvLog        = (TextView)   findViewById(R.id.tv_live_log);
        scrollLog    = (ScrollView) findViewById(R.id.scroll_live_log);

        ((Button) findViewById(R.id.btn_live_share)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        AppLogger.share(BYDLiveActivity.this);
                    }
                });

        ((Button) findViewById(R.id.btn_live_clear)).setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        AppLogger.clear();
                        tvLog.setText("");
                    }
                });

        initDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRunning = true;
        mHandler.post(mPollRunnable);
        AppLogger.log(TAG, "Panneau live ouvert");
    }

    @Override
    protected void onPause() {
        super.onPause();
        mRunning = false;
        mHandler.removeCallbacks(mPollRunnable);
        AppLogger.log(TAG, "Panneau live fermé");
    }

    // -------------------------------------------------------------------------

    private void initDevices() {
        boolean hasSpeed   = ContextCompat.checkSelfPermission(this,
                "android.permission.BYDAUTO_SPEED_COMMON")   == PackageManager.PERMISSION_GRANTED;
        boolean hasEnergy  = ContextCompat.checkSelfPermission(this,
                "android.permission.BYDAUTO_ENERGY_COMMON")  == PackageManager.PERMISSION_GRANTED;
        boolean hasGearbox = ContextCompat.checkSelfPermission(this,
                "android.permission.BYDAUTO_GEARBOX_COMMON") == PackageManager.PERMISSION_GRANTED;

        if (hasSpeed)   mSpeedDevice   = BYDAutoSpeedDevice.getInstance(this);
        if (hasEnergy)  mEnergyDevice  = BYDAutoEnergyDevice.getInstance(this);
        if (hasGearbox) mGearboxDevice = BYDAutoGearboxDevice.getInstance(this);

        String status = "Speed: "   + status(mSpeedDevice)
                + "  Energy: "  + status(mEnergyDevice)
                + "  Gearbox: " + status(mGearboxDevice);
        tvPermStatus.setText(status);
        AppLogger.log(TAG, "Devices: " + status);
    }

    private String status(Object device) {
        return device != null ? "✓" : "✗";
    }

    // -------------------------------------------------------------------------

    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            poll();
            mHandler.postDelayed(this, POLL_MS);
        }
    };

    private void poll() {
        if (mSpeedDevice != null) {
            double speed = mSpeedDevice.getCurrentSpeed();
            tvSpeed.setText(String.format("%.0f km/h", speed));
        } else {
            tvSpeed.setText("-- km/h");
        }

        if (mEnergyDevice != null) {
            tvEnergy.setText(energyLabel(mEnergyDevice.getEnergyMode()));
            tvPowerGen.setText(mEnergyDevice.getPowerGenerationValue() + " kW");
        } else {
            tvEnergy.setText("--");
            tvPowerGen.setText("-- kW");
        }

        if (mGearboxDevice != null) {
            tvGear.setText(gearLabel(mGearboxDevice.getGearboxAutoModeType()));
        } else {
            tvGear.setText("-");
        }

        // Mise à jour du journal
        final String log = AppLogger.get();
        tvLog.setText(log);
        scrollLog.post(new Runnable() {
            @Override public void run() { scrollLog.fullScroll(ScrollView.FOCUS_DOWN); }
        });
    }

    // -------------------------------------------------------------------------

    private String energyLabel(int mode) {
        switch (mode) {
            case BYDAutoEnergyDevice.ENERGY_MODE_EV:       return "EV";
            case BYDAutoEnergyDevice.ENERGY_MODE_FORCE_EV: return "EV+";
            case BYDAutoEnergyDevice.ENERGY_MODE_HEV:      return "HEV";
            case BYDAutoEnergyDevice.ENERGY_MODE_FUEL:     return "FUEL";
            case BYDAutoEnergyDevice.ENERGY_MODE_KEEP:     return "KEEP";
            default:                                        return "--";
        }
    }

    private String gearLabel(int gear) {
        switch (gear) {
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_P: return "P";
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_R: return "R";
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_N: return "N";
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_D: return "D";
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_S: return "S";
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_M: return "M";
            default:                                        return "-";
        }
    }
}
