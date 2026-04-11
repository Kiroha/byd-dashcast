package com.byd.myapp.dashboard;

import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.bydauto.energy.BYDAutoEnergyDevice;
import android.hardware.bydauto.gearbox.BYDAutoGearboxDevice;
import android.hardware.bydauto.speed.AbsBYDAutoSpeedListener;
import android.hardware.bydauto.speed.BYDAutoSpeedDevice;
import android.os.Bundle;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.view.Display;
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

        // Figer la taille de la fenêtre AVANT setContentView().
        // En mode FREEFORM (windowingMode=5), le layout match_parent + l'absence de bornes
        // fixes causent un agrandissement progressif du rectangle visible sur le cluster.
        // getRealSize() depuis l'Activity retourne les dimensions du display sur lequel
        // elle s'exécute (display 1 = cluster), pas du display principal.
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Point size = new Point(1920, 480); // défaut BYD Seal cluster
            d.getRealSize(size);
            getWindow().setLayout(size.x, size.y);
        } catch (Exception ignored) {
            getWindow().setLayout(1920, 480);
        }

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
        // Chaque permission COMMON est vérifiée indépendamment avant son getInstance().
        // getInstance() retourne null si la permission n'est pas accordée → affichage "--".
        if (ContextCompat.checkSelfPermission(this,
                "android.permission.BYDAUTO_SPEED_COMMON") == PackageManager.PERMISSION_GRANTED) {
            mSpeedDevice = BYDAutoSpeedDevice.getInstance(this);
        }
        if (ContextCompat.checkSelfPermission(this,
                "android.permission.BYDAUTO_ENERGY_COMMON") == PackageManager.PERMISSION_GRANTED) {
            mEnergyDevice = BYDAutoEnergyDevice.getInstance(this);
        }
        if (ContextCompat.checkSelfPermission(this,
                "android.permission.BYDAUTO_GEARBOX_COMMON") == PackageManager.PERMISSION_GRANTED) {
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
