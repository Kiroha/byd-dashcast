package com.byd.myapp.dashboard;

import android.content.pm.PackageManager;
import android.hardware.bydauto.energy.BYDAutoEnergyDevice;
import android.hardware.bydauto.gearbox.BYDAutoGearboxDevice;
import android.hardware.bydauto.speed.AbsBYDAutoSpeedListener;
import android.hardware.bydauto.speed.BYDAutoSpeedDevice;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.widget.TextView;

import com.byd.myapp.R;

/**
 * BYDDashboardActivity — s'exécute sur l'écran dashboard (instrument cluster).
 *
 * Lancée via setLaunchDisplayId() sur le display secondaire. Elle s'y affiche
 * en plein écran et affiche les données véhicule en temps réel.
 *
 * "Restaurer BYD" depuis MainActivity = relancer cette Activity sur le même display
 * avec FLAG_ACTIVITY_SINGLE_TOP → elle revient au premier plan, repoussant l'app tierce.
 */
public class BYDDashboardActivity extends AppCompatActivity {

    private BYDAutoSpeedDevice  mSpeedDevice;
    private BYDAutoEnergyDevice mEnergyDevice;
    private BYDAutoGearboxDevice mGearboxDevice;

    private TextView tvSpeed;
    private TextView tvBattery;
    private TextView tvRange;
    private TextView tvGear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        tvSpeed   = (TextView) findViewById(R.id.dash_speed);
        tvBattery = (TextView) findViewById(R.id.dash_battery);
        tvRange   = (TextView) findViewById(R.id.dash_range);
        tvGear    = (TextView) findViewById(R.id.dash_gear);

        initDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // (re)connecter les listeners quand l'activity revient au premier plan
        if (mSpeedDevice != null) {
            mSpeedDevice.registerListener(mSpeedListener);
        }
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Déconnecter les listeners quand on passe en arrière-plan
        // (une app tierce prend le dessus sur le dashboard)
        if (mSpeedDevice != null) {
            mSpeedDevice.unregisterListener(mSpeedListener);
        }
    }

    private void initDevices() {
        // Les permissions COMMON ont été accordées dans MainActivity.
        // getInstance() retourne null si elles ne l'ont pas été → affichage "--".
        boolean speedPermsGranted = ContextCompat.checkSelfPermission(this,
                "android.permission.BYDAUTO_SPEED_COMMON") == PackageManager.PERMISSION_GRANTED;

        if (speedPermsGranted) {
            mSpeedDevice   = BYDAutoSpeedDevice.getInstance(this);
            mEnergyDevice  = BYDAutoEnergyDevice.getInstance(this);
            mGearboxDevice = BYDAutoGearboxDevice.getInstance(this);
        }
    }

    private void refreshAll() {
        if (mSpeedDevice != null) {
            updateSpeed(mSpeedDevice.getCurrentSpeed());
        }
        if (mEnergyDevice != null) {
            updateEnergyMode(mEnergyDevice.getEnergyMode());
            updatePowerGen(mEnergyDevice.getPowerGenerationValue());
        }
        if (mGearboxDevice != null) {
            updateGear(mGearboxDevice.getGearboxAutoModeType());
        }
    }

    private void updateSpeed(double speedKmh) {
        tvSpeed.setText(String.valueOf((int) speedKmh));
    }

    private void updateEnergyMode(int mode) {
        String label;
        switch (mode) {
            case BYDAutoEnergyDevice.ENERGY_MODE_EV:       label = "EV";   break;
            case BYDAutoEnergyDevice.ENERGY_MODE_FORCE_EV: label = "EV+";  break;
            case BYDAutoEnergyDevice.ENERGY_MODE_HEV:      label = "HEV";  break;
            case BYDAutoEnergyDevice.ENERGY_MODE_FUEL:     label = "FUEL"; break;
            case BYDAutoEnergyDevice.ENERGY_MODE_KEEP:     label = "KEEP"; break;
            default:                                        label = "--";   break;
        }
        tvBattery.setText(label);
    }

    private void updatePowerGen(int kw) {
        tvRange.setText(kw + " kW");
    }

    private void updateGear(int gearMode) {
        String label;
        switch (gearMode) {
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_P: label = "P"; break;
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_R: label = "R"; break;
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_N: label = "N"; break;
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_D: label = "D"; break;
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_S: label = "S"; break;
            case BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_M: label = "M"; break;
            default: label = "-";
        }
        tvGear.setText(label);
    }

    private final AbsBYDAutoSpeedListener mSpeedListener = new AbsBYDAutoSpeedListener() {
        @Override
        public void onSpeedChanged(final double speed) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateSpeed(speed);
                }
            });
        }
    };
}
