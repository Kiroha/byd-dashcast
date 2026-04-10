package com.byd.myapp.dashboard;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

/**
 * DashboardDisplayHelper — détecte le display secondaire (instrument cluster).
 *
 * Sur le BYD Seal, le dashboard est exposé comme un Display Android de catégorie
 * DISPLAY_CATEGORY_PRESENTATION. Ce helper trouve ce display et permet d'écouter
 * son branchement/débranchement.
 */
public class DashboardDisplayHelper {

    private static final String TAG = "DashboardDisplayHelper";

    public interface Listener {
        void onDashboardDisplayConnected(Display display, int displayId);
        void onDashboardDisplayDisconnected();
    }

    private final DisplayManager mDisplayManager;
    private final Listener mListener;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    Display d = mDisplayManager.getDisplay(displayId);
                    if (isPresentationDisplay(d)) {
                        Log.i(TAG, "Dashboard display connecté : id=" + displayId);
                        mListener.onDashboardDisplayConnected(d, displayId);
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    Log.i(TAG, "Display supprimé : id=" + displayId);
                    mListener.onDashboardDisplayDisconnected();
                }

                @Override
                public void onDisplayChanged(int displayId) { }
            };

    public DashboardDisplayHelper(Context context, Listener listener) {
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mListener = listener;
    }

    /**
     * Cherche immédiatement un display Presentation parmi les displays connectés,
     * et enregistre le listener pour les branchements futurs.
     */
    public void start() {
        mDisplayManager.registerDisplayListener(mDisplayListener, null);

        Display[] displays = mDisplayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION);

        if (displays != null && displays.length > 0) {
            // Sur le BYD Seal : le premier display de catégorie PRESENTATION est le dashboard
            Log.i(TAG, "Display dashboard trouvé au démarrage : " + displays[0]);
            mListener.onDashboardDisplayConnected(displays[0], displays[0].getDisplayId());
        } else {
            Log.w(TAG, "Aucun display Presentation détecté — "
                    + "le dashboard n'est peut-être pas encore disponible");
        }
    }

    public void stop() {
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
    }

    private boolean isPresentationDisplay(Display d) {
        if (d == null) return false;
        Display[] presentations = mDisplayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display p : presentations) {
            if (p.getDisplayId() == d.getDisplayId()) return true;
        }
        return false;
    }
}
