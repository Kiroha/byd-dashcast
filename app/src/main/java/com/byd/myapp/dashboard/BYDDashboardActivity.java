package com.byd.myapp.dashboard;

import android.graphics.Point;
import android.hardware.bydauto.energy.BYDAutoEnergyDevice;
import android.hardware.bydauto.gearbox.BYDAutoGearboxDevice;
import android.hardware.bydauto.speed.AbsBYDAutoSpeedListener;
import android.hardware.bydauto.speed.BYDAutoSpeedDevice;
import android.os.Bundle;
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

    // Référence statique pour permettre à DashboardDisplayHelper.stop() de terminer
    // cette Activity AVANT d'appeler restoreNative() — Qt ne peut recapturer la surface
    // que si aucune Activity Android ne la détient.
    private static java.lang.ref.WeakReference<BYDDashboardActivity> sInstance;

    /** Appelé par DashboardDisplayHelper.stop() juste avant sendInfo(0). */
    public static void finishIfActive() {
        java.lang.ref.WeakReference<BYDDashboardActivity> ref = sInstance;
        if (ref != null) {
            BYDDashboardActivity act = ref.get();
            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                act.finish();
            }
            sInstance = null;
        }
    }

    private BYDAutoSpeedDevice   mSpeedDevice;
    private BYDAutoEnergyDevice mEnergyDevice;
    private BYDAutoGearboxDevice mGearboxDevice;

    private TextView tvSpeed;
    private TextView tvBattery;
    private TextView tvRange;
    private TextView tvGear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = new java.lang.ref.WeakReference<>(this);

        // Supprimer les animations et décorations FREEFORM AVANT setContentView().
        // FLAG_LAYOUT_IN_SCREEN : empêche le WM de repositionner la fenêtre hors des bornes display.
        // FLAG_LAYOUT_NO_LIMITS : désactive les contraintes de taille imposées par le WM en FREEFORM.
        // FLAG_FULLSCREEN       : supprime la barre de caption FREEFORM (déco resize-handle).
        // Ces 3 flags combinés stoppent l'animation "grow-from-zero" du mode FREEFORM.
        getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Figer la taille de la fenêtre AVANT setContentView().
        // En mode FREEFORM (windowingMode=5), le layout match_parent + l'absence de bornes
        // fixes causent un agrandissement progressif du rectangle visible sur le cluster.
        // getRealSize() depuis l'Activity retourne les dimensions du display sur lequel
        // elle s'exécute (display 1 = cluster), pas du display principal.
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Point size = new Point(1920, 1080); // fallback VirtualDisplay AutoDisplayService (1920×1080)
            d.getRealSize(size);
            getWindow().setLayout(size.x, size.y);
        } catch (Exception ignored) {
            getWindow().setLayout(1920, 1080);
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
        // Sur Seal EU ROM, pm grant retourne "OK" silencieusement pour certaines _COMMON
        // (ex: SPEED, GEARBOX) mais checkSelfPermission retourne NOT_GRANTED (elles n'apparaissent
        // pas dans dumpsys granted=true). Cause probable : protection level différent sur cette ROM.
        // Fix : appel getInstance() directement dans try/catch SecurityException.
        // Si l'APK est signé platform.keystore ET que la ROM accorde implicitement la permission,
        // getInstance() retourne l'objet. Si vraiment refusé, SecurityException → device reste null.
        try { mSpeedDevice   = BYDAutoSpeedDevice.getInstance(this);   } catch (Exception ignored) {}
        try { mEnergyDevice  = BYDAutoEnergyDevice.getInstance(this);  } catch (Exception ignored) {}
        try { mGearboxDevice = BYDAutoGearboxDevice.getInstance(this); } catch (Exception ignored) {}
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Nettoyer la référence statique si c'est bien notre instance
        java.lang.ref.WeakReference<BYDDashboardActivity> ref = sInstance;
        if (ref != null && ref.get() == this) {
            sInstance = null;
        }
    }
}
