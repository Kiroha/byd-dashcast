package com.byd.myapp.dashboard;

import android.app.Presentation;
import android.content.Context;
import android.hardware.bydauto.energy.BYDAutoEnergyDevice;
import android.hardware.bydauto.speed.BYDAutoSpeedDevice;
import android.os.Bundle;
import android.view.Display;
import android.widget.TextView;

import com.byd.myapp.R;

/**
 * DashboardPresentation — affiché sur le second écran (instrument cluster).
 *
 * Le système Android expose le dashboard du BYD Seal comme un Display secondaire
 * de catégorie DISPLAY_CATEGORY_PRESENTATION. On utilise l'API standard
 * android.app.Presentation pour y projeter une vue.
 *
 * Cycle de vie :
 *   - Instancier avec le Display trouvé par DashboardDisplayHelper
 *   - Appeler show() pour afficher
 *   - Appeler update*() depuis MainActivity pour rafraîchir les données
 *   - Appeler dismiss() dans onDestroy() de l'Activity hôte
 */
public class DashboardPresentation extends Presentation {

    private TextView tvSpeed;
    private TextView tvBattery;
    private TextView tvRange;
    private TextView tvGear;

    public DashboardPresentation(Context context, Display display) {
        super(context, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.presentation_dashboard);

        tvSpeed   = (TextView) findViewById(R.id.dash_speed);
        tvBattery = (TextView) findViewById(R.id.dash_battery);
        tvRange   = (TextView) findViewById(R.id.dash_range);
        tvGear    = (TextView) findViewById(R.id.dash_gear);
    }

    public void updateSpeed(int speedKmh) {
        if (tvSpeed != null) {
            tvSpeed.setText(String.valueOf(speedKmh));
        }
    }

    public void updateBattery(int percent) {
        if (tvBattery != null) {
            tvBattery.setText(percent + "%");
        }
    }

    public void updateRange(int km) {
        if (tvRange != null) {
            tvRange.setText(km + " km");
        }
    }

    public void updateGear(String gear) {
        if (tvGear != null) {
            tvGear.setText(gear);
        }
    }
}
