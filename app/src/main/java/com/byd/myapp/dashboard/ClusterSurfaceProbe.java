package com.byd.myapp.dashboard;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Display;

import com.byd.myapp.AppLogger;
import com.xdja.containerservice.ContainerService;
import com.xdja.containerservice.QtDisplayInfo;

import java.io.File;

/**
 * ClusterSurfaceProbe — récupère la Surface Qt du combiné ET crée NOTRE PROPRE
 * VirtualDisplay autour, dont notre app devient l'owner.
 *
 * Pourquoi ?
 *   `AutoDisplayService` (uid system, com.xdja.containerservice) crée un
 *   VirtualDisplay (id=1, name=fission_bg_xdjaVirtualSurface) avec le flag
 *   `OWN_CONTENT_ONLY` → seul lui peut lancer des activités dessus. Toutes
 *   nos tentatives `setLaunchDisplayId(1)` échouent silencieusement car nous
 *   ne sommes pas l'owner.
 *
 *   En recréant un VirtualDisplay autour de la MÊME Surface (récupérée via
 *   le JNI natif `libxdjacontainerservice_jni.so`), notre app devient owner
 *   du nouveau display → on peut lancer des activités dessus → elles seront
 *   rendues vers la Surface Qt = visibles sur la dalle physique du combiné.
 *
 * Étapes :
 *   1. `System.load("/system/lib64/libxdjacontainerservice_jni.so")` (essai
 *      lib64 puis lib).
 *   2. Appel `ContainerService.getQtProjectionDispInfoArrayNative()` pour
 *      énumérer toutes les Qt surfaces (id=0, id=1, …).
 *   3. Si ≥1 Surface valide → `DisplayManager.createVirtualDisplay()` avec
 *      flags = PUBLIC | PRESENTATION (PAS OWN_CONTENT_ONLY) pour permettre
 *      à des activités tierces de rendre dessus.
 *   4. Retourne le `Display.getDisplayId()` du nouveau VirtualDisplay.
 *
 * Limites :
 *   - Si `libxdjacontainerservice_jni.so` fait un check UID interne ou si
 *     le natif appelle un service qui refuse uid 10100, on obtient null
 *     ou une exception. Le log indique précisément l'échec.
 *   - Pour qu'AutoDisplayService ne libère pas la Surface sous nos pieds,
 *     il vaut mieux NE PAS appeler `startService(AutoDisplayService)` quand
 *     on utilise ce mode (sinon deux VirtualDisplays sur la même Surface
 *     entrent en conflit). On désactive donc startService de ClusterManager
 *     dans le flow "OwnedDisplay".
 */
public final class ClusterSurfaceProbe {

    private static final String TAG = "ClusterSurfaceProbe";
    private static final String LIB_NAME = "xdjacontainerservice_jni";
    private static final String[] LIB_PATHS = new String[] {
            "/system/lib64/libxdjacontainerservice_jni.so",
            "/system/lib/libxdjacontainerservice_jni.so",
            "/vendor/lib64/libxdjacontainerservice_jni.so",
            "/vendor/lib/libxdjacontainerservice_jni.so"
    };

    private static volatile boolean sLibLoaded = false;
    private static volatile VirtualDisplay sOwnedDisplay = null;

    private ClusterSurfaceProbe() { /* static */ }

    /**
     * Tente de charger la lib JNI BYD et retourne true si OK.
     * Idempotent : la lib n'est chargée qu'une seule fois.
     */
    public static synchronized boolean ensureLibLoaded() {
        if (sLibLoaded) return true;
        // Essai 1 : par nom abrégé (cherche dans le dossier lib de notre APK
        // — ne devrait pas exister — puis dans les paths système)
        try {
            System.loadLibrary(LIB_NAME);
            sLibLoaded = true;
            AppLogger.i(TAG, "loadLibrary OK : " + LIB_NAME);
            return true;
        } catch (UnsatisfiedLinkError e) {
            AppLogger.w(TAG, "loadLibrary " + LIB_NAME + " indisponible : "
                    + e.getMessage());
        }
        // Essai 2 : chemins absolus (la lib BYD vit dans /system/lib*/)
        for (String p : LIB_PATHS) {
            try {
                if (!new File(p).exists()) continue;
                System.load(p);
                sLibLoaded = true;
                AppLogger.i(TAG, "System.load OK : " + p);
                return true;
            } catch (UnsatisfiedLinkError e) {
                AppLogger.w(TAG, "System.load " + p + " échoué : " + e.getMessage());
            } catch (Throwable t) {
                AppLogger.e(TAG, "System.load " + p + " exception", t);
            }
        }
        AppLogger.e(TAG, "Aucune lib " + LIB_NAME + " chargeable");
        return false;
    }

    /**
     * Énumère toutes les Qt projection surfaces et logue leurs propriétés.
     * Utile en diagnostic avant de tenter la création du VirtualDisplay.
     */
    public static QtDisplayInfo[] dumpAllQtSurfaces() {
        if (!ensureLibLoaded()) return new QtDisplayInfo[0];
        QtDisplayInfo[] all;
        try {
            all = ContainerService.getQtProjectionDispInfoArrayNative();
        } catch (Throwable t) {
            AppLogger.e(TAG, "getQtProjectionDispInfoArrayNative() exception", t);
            // Fallback : essais individuels id=0..3
            all = null;
        }
        if (all == null) {
            // Fallback énumération individuelle
            java.util.List<QtDisplayInfo> list = new java.util.ArrayList<>();
            for (int id = 0; id < 4; id++) {
                try {
                    QtDisplayInfo info = ContainerService.getQtProjectionDispInfoNative(id);
                    if (info != null) list.add(info);
                    AppLogger.i(TAG, "  id=" + id + " → " + info);
                } catch (Throwable t) {
                    AppLogger.e(TAG, "  id=" + id + " exception", t);
                    break;
                }
            }
            all = list.toArray(new QtDisplayInfo[0]);
        } else {
            AppLogger.i(TAG, "getQtProjectionDispInfoArray() = " + all.length + " surfaces");
            for (int i = 0; i < all.length; i++) {
                AppLogger.i(TAG, "  [" + i + "] " + all[i]);
            }
        }
        return all;
    }

    /**
     * Crée un VirtualDisplay owned par notre app autour de la première Surface
     * Qt valide trouvée. Retourne le `Display.getDisplayId()` ou -1 en cas
     * d'échec.
     *
     * Flags utilisés : `PUBLIC | PRESENTATION` (pas OWN_CONTENT_ONLY) pour
     * permettre les activités tierces. On ajoute aussi
     * `VIRTUAL_DISPLAY_FLAG_TRUSTED` (0x100, API 30+) si dispo, sinon ignoré.
     */
    public static synchronized int createOwnedClusterDisplay(Context context) {
        if (sOwnedDisplay != null) {
            int existing = sOwnedDisplay.getDisplay().getDisplayId();
            AppLogger.i(TAG, "createOwnedClusterDisplay : déjà créé id=" + existing);
            return existing;
        }
        if (!ensureLibLoaded()) {
            AppLogger.e(TAG, "Lib non chargeable → impossible de récupérer la Surface Qt");
            return -1;
        }
        QtDisplayInfo info = null;
        try {
            info = ContainerService.getQtProjectionDispInfoNative(0);
        } catch (Throwable t) {
            AppLogger.e(TAG, "getQtProjectionDispInfoNative(0) exception", t);
            return -1;
        }
        if (info == null) {
            AppLogger.e(TAG, "getQtProjectionDispInfoNative(0) → null");
            return -1;
        }
        if (info.surface == null || !info.surface.isValid()) {
            AppLogger.e(TAG, "Surface invalide : " + info);
            return -1;
        }
        AppLogger.i(TAG, "Surface Qt récupérée : " + info);

        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            AppLogger.e(TAG, "DisplayManager null");
            return -1;
        }

        String name = (info.name != null && !info.name.isEmpty())
                ? "byd_myapp_owned_" + info.name
                : "byd_myapp_owned_cluster";
        int width = info.width > 0 ? info.width : 1920;
        int height = info.height > 0 ? info.height : 480;
        int dpi = 320;
        // PUBLIC = 1, PRESENTATION = 8 → 9. Pas OWN_CONTENT_ONLY (=2).
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        // VIRTUAL_DISPLAY_FLAG_TRUSTED = 0x100 (API 30+) — utile si dispo
        // pour autoriser des activités sécurisées. API 29 → flag inconnu, on
        // s'abstient pour éviter IllegalArgumentException.

        try {
            sOwnedDisplay = dm.createVirtualDisplay(
                    name, width, height, dpi, info.surface, flags);
        } catch (Throwable t) {
            AppLogger.e(TAG, "createVirtualDisplay exception", t);
            return -1;
        }
        if (sOwnedDisplay == null) {
            AppLogger.e(TAG, "createVirtualDisplay → null");
            return -1;
        }
        Display d = sOwnedDisplay.getDisplay();
        int id = d.getDisplayId();
        AppLogger.i(TAG, "VirtualDisplay owned créé : id=" + id
                + " name=" + name + " " + width + "×" + height
                + " flags=0x" + Integer.toHexString(flags));
        // Affiche les flags réels du display (peut différer si stripping système)
        AppLogger.i(TAG, "  Display.getFlags() = 0x"
                + Integer.toHexString(d.getFlags())
                + " state=" + d.getState());
        return id;
    }

    /** Libère le VirtualDisplay owned (à appeler dans onDestroy du service). */
    public static synchronized void release() {
        if (sOwnedDisplay != null) {
            try {
                sOwnedDisplay.release();
                AppLogger.i(TAG, "VirtualDisplay owned libéré");
            } catch (Throwable t) {
                AppLogger.e(TAG, "release exception", t);
            }
            sOwnedDisplay = null;
        }
    }
}
