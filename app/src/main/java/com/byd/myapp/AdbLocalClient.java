package com.byd.myapp;

import android.content.Context;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

import java.io.File;

/**
 * AdbLocalClient — se connecte au daemon ADB local (localhost:5555) depuis l'intérieur
 * de la tablette, en utilisant la bibliothèque dadb (identique à Overdrive).
 *
 * Flux :
 *  1. Génère (ou recharge) une paire de clés RSA ADB stockée dans les fichiers internes.
 *  2. Dadb.create() initie la connexion → adbd envoie un challenge → dadb répond avec
 *     la signature RSA → si la clé est inconnue, le système affiche le popup
 *     "Autoriser le débogage USB ?" sur l'écran de la tablette.
 *  3. Trois passages d'escalade :
 *     [1] setprop persist.sys.acc.whitelist — mécanisme BYD DiLink natif
 *     [2] abb_exec package grant — via Binder direct (Android 9+)
 *     [3] Énumération des services BYD dans service list (préparation proxy)
 *
 * La paire de clés est persistée → le popup n'apparaît qu'une seule fois (ou après
 * révocation manuelle dans les paramètres développeur du véhicule).
 */
public class AdbLocalClient {

    private static final String TAG = "AdbLocalClient";

    /** Port ADB TCP — identique pour Android 7–10 en mode développeur */
    private static final int ADB_PORT = 5555;

    // -------------------------------------------------------------------------

    public interface Callback {
        /** Appelé sur un thread background quand la connexion + les grants sont terminés. */
        void onSuccess(String report);
        /** Appelé si la connexion échoue (port fermé, timeout, refus…). */
        void onError(String error);
    }

    // -------------------------------------------------------------------------

    /**
     * Lance la connexion ADB locale dans un thread background.
     *
     * Stratégie :
     *  1. setprop persist.sys.acc.whitelist — mécanisme BYD-spécifique (DiLink whitelist)
     *  2. abb_exec package grant — essai via Binder direct (UID 1000 possible sur certaines ROM)
     *  3. Énumération des services BYD disponibles via service list (pour un futur proxy)
     */
    public static void connectAndGrant(final Context context, final Callback callback) {
        new Thread(() -> {
            try {
                File privateKey = new File(context.getFilesDir(), "adb.key");
                File publicKey  = new File(context.getFilesDir(), "adb.pub");
                boolean newKey  = !privateKey.exists() || !publicKey.exists();

                AppLogger.log(TAG, newKey
                        ? "Nouvelle clé ADB générée → popup attendu"
                        : "Clé ADB existante rechargée");
                AppLogger.log(TAG, "Connexion dadb → localhost:" + ADB_PORT + " …");
                try (Dadb dadb = connect(context)) {
                AppLogger.log(TAG, "Connexion ADB établie ✓");

                StringBuilder sb = new StringBuilder();
                String pkg = context.getPackageName();

                // ── PASSE 1 : persist.sys.acc.whitelist (mécanisme BYD DiLink) ──────────
                sb.append("=== [1] BYD DiLink whitelist ===\n");

                // Lire la valeur courante
                AdbShellResponse rGet = dadb.shell("getprop persist.sys.acc.whitelist 2>&1");
                String currentWhitelist = rGet.getAllOutput().trim();
                sb.append("Valeur actuelle : '").append(currentWhitelist).append("'\n");

                // Ajouter notre package si pas déjà présent
                String newVal = currentWhitelist.isEmpty() ? pkg
                        : (currentWhitelist.contains(pkg) ? currentWhitelist
                        : currentWhitelist + "," + pkg);

                AdbShellResponse rSet = dadb.shell(
                        "setprop persist.sys.acc.whitelist \"" + newVal + "\" 2>&1 && echo SETPROP_OK");
                boolean setpropOk = rSet.getAllOutput().contains("SETPROP_OK");
                sb.append("setprop : ").append(setpropOk ? "OK" : "ERREUR — " + rSet.getAllOutput().trim()).append("\n");

                if (setpropOk) {
                    // Vérifier que la valeur a bien été persistée
                    AdbShellResponse rVerify = dadb.shell("getprop persist.sys.acc.whitelist");
                    sb.append("Valeur après : '").append(rVerify.getAllOutput().trim()).append("'\n");
                    sb.append("\n⚠ Whitelist mise à jour.\n")
                      .append("→ Fermez complètement l'application puis relancez-la.\n")
                      .append("  Si ça fonctionne, les *_GET seront accordés au redémarrage.\n");
                } else {
                    sb.append("→ setprop refusé (propriété protégée sur cette ROM).\n");
                }
                sb.append("\n");

                // ── PASSE 2 : test pm grant sur _COMMON (dangerous?) et _GET (signature) ──
                boolean abbSupported = dadb.supportsFeature("abb_exec");
                sb.append("=== [2] abb_exec disponible : ").append(abbSupported).append(" ===\n");

                // Vérifier l'UID effectif
                AdbShellResponse rUid = dadb.shell("id 2>&1");
                sb.append("UID shell : ").append(rUid.getAllOutput().trim()).append("\n");

                // _COMMON : dangerous confirmé → pm grant fonctionne (toutes accordées ici)
                // _GET    : signature confirmés — toujours refusés via pm grant
                String[] commonPerms = {
                    "android.permission.BYDAUTO_SPEED_COMMON",
                    "android.permission.BYDAUTO_ENERGY_COMMON",
                    "android.permission.BYDAUTO_GEARBOX_COMMON",
                    "android.permission.BYDAUTO_BODYWORK_COMMON",
                    "android.permission.BYDAUTO_AC_COMMON",
                    "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
                    "android.permission.BYDAUTO_ENGINE_COMMON",
                    "android.permission.BYDAUTO_INSTRUMENT_COMMON",
                    "android.permission.BYDAUTO_LIGHT_COMMON",
                    "android.permission.BYDAUTO_TYRE_COMMON",
                    "android.permission.BYDAUTO_RADAR_COMMON",
                    // SAFETYBELT_COMMON retirée : Unknown permission sur ROM Seal EU
                    // "android.permission.BYDAUTO_SAFETYBELT_COMMON",
                };
                String[] getPerms = {
                    "android.permission.BYDAUTO_SPEED_GET",
                    "android.permission.BYDAUTO_ENERGY_GET",
                    "android.permission.BYDAUTO_GEARBOX_GET",
                };
                sb.append("── pm grant ALL _COMMON (dangerous confirmé) ──\n");
                for (String perm : commonPerms) {
                    String shortName = perm.replace("android.permission.BYDAUTO_", "");
                    AdbShellResponse r = dadb.shell("pm grant " + pkg + " " + perm + " 2>&1 && echo GRANTED || echo DENIED");
                    String out = r.getAllOutput().trim();
                    sb.append(shortName).append(": ").append(
                        out.contains("GRANTED") ? "OK ✓ (dangerous — accordée)" :
                        out.contains("not a changeable") ? "SIGNATURE — non accordable via pm" :
                        out.contains("Unknown permission") ? "⚠️ Non disponible sur cette ROM" :
                        out).append("\n");
                }
                sb.append("── pm grant _GET (signature confirmés — pour référence) ──\n");
                for (String perm : getPerms) {
                    String shortName = perm.replace("android.permission.BYDAUTO_", "");
                    AdbShellResponse r = dadb.shell("pm grant " + pkg + " " + perm + " 2>&1 && echo GRANTED || echo DENIED");
                    String out = r.getAllOutput().trim();
                    sb.append(shortName).append(": ").append(
                        out.contains("GRANTED") ? "OK ✓ (inattendu)" :
                        out.contains("not a changeable") ? "SIGNATURE (attendu)" :
                        out).append("\n");
                }
                sb.append("\n");

                // ── PASSE 3 : énumération des services BYD (pour proxy futur) ──────────
                sb.append("=== [3] Services BYD accessibles via shell ===\n");
                AdbShellResponse rSvc = dadb.shell(
                        "service list 2>/dev/null | grep -i 'byd\\|auto\\|vehicle\\|car' | head -20");
                sb.append(rSvc.getAllOutput().isEmpty() ? "(aucun service BYD trouvé)\n" : rSvc.getAllOutput());

                // Vérifier si /proc ou /sys expose des données véhicule
                AdbShellResponse rSys = dadb.shell(
                        "ls /sys/class/byd* /proc/byd* /data/system/byd* 2>/dev/null | head -10");
                if (!rSys.getAllOutput().trim().isEmpty()) {
                    sb.append("Fichiers système BYD :\n").append(rSys.getAllOutput().trim()).append("\n");
                }
                sb.append("\n");

                // ── État final des permissions (dump brut + grep large) ───────────────
                // Le format BYD ROM peut différer du AOSP standard — on dump la section
                // "declared permissions" + "install permissions" + "runtime permissions"
                AdbShellResponse rFinal = dadb.shell(
                        "dumpsys package " + pkg + " 2>/dev/null | grep -iE 'bydauto|BYDAUTO|requested perm|install perm|runtime perm|grantedPermissions' | head -40");
                sb.append("=== Permissions actuelles (dump brut) ===\n");
                String finalOut = rFinal.getAllOutput().trim();
                if (finalOut.isEmpty()) {
                    // Fallback : dump complet de la section permissions
                    AdbShellResponse rFull = dadb.shell(
                            "dumpsys package " + pkg + " 2>/dev/null | grep -A2 -E 'permission|Permission' | grep -iE 'byd|granted|denied' | head -30");
                    finalOut = rFull.getAllOutput().trim();
                }
                sb.append(finalOut.isEmpty() ? "(aucune entrée — vérifier APK installé)" : finalOut).append("\n");

                AppLogger.log(TAG, "ADB local terminé ✓");
                callback.onSuccess(sb.toString());
                }

            } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                AppLogger.e(TAG, "Échec ADB local", e);
                AppLogger.log(TAG, "ADB local ERREUR — " + msg);
                callback.onError(msg);
            }
        }, "adb-local-thread").start();
    }

    // ── Helper privé — connexion dadb (clé déjà autorisée, pas de popup) ───────────

    /** Verrou pour la génération des clés : évite le TOCTOU si deux méthodes ADB sont appelées
     *  simultanément au premier lancement (avant que les fichiers .key/.pub existent). */
    private static final Object sKeyLock = new Object();

    private static Dadb connect(Context context) throws Exception {
        File privateKey = new File(context.getFilesDir(), "adb.key");
        File publicKey  = new File(context.getFilesDir(), "adb.pub");
        AdbKeyPair keyPair;
        synchronized (sKeyLock) {
            if (!privateKey.exists() || !publicKey.exists()) {
                AdbKeyPair.generate(privateKey, publicKey);
            }
            keyPair = AdbKeyPair.read(privateKey, publicKey);
        }
        return Dadb.create("localhost", ADB_PORT, keyPair);
    }

    // ── Grant SYSTEM_ALERT_WINDOW via appops ─────────────────────────────────────
    /**
     * Accorde l'AppOp SYSTEM_ALERT_WINDOW au package courant via le shell ADB local.
     *
     * Sur Android 10+ une app non-system ne bénéficie pas de cet AppOp même si
     * SYSTEM_ALERT_WINDOW est dans le manifest et l'APK est signé platform.
     * La commande "appops set <pkg> SYSTEM_ALERT_WINDOW allow" (uid shell = 2000)
     * est suffisante pour que Settings.canDrawOverlays() renvoie true sans redémarrage.
     *
     * Callback appelé sur le thread background dadb — penser à poster sur le main thread
     * si on veut modifier l'UI suite au succès.
     */
    public static void grantOverlayPermission(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    String cmd = "appops set " + context.getPackageName()
                            + " SYSTEM_ALERT_WINDOW allow";
                    AdbShellResponse r = dadb.shell(cmd + " 2>&1");
                    AppLogger.i(TAG, "grantOverlayPermission → " + cmd
                            + " → '" + r.getAllOutput().trim() + "'");
                    callback.onSuccess(r.getAllOutput().trim());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "grantOverlayPermission ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-overlay-grant").start();
    }

    // ── Freedom : démarrage automatique ─────────────────────────────────────
    /**
     * Configure Freedom (com.xdja.clusterdemo) en mode "全屏导航" (plein écran), puis le
     * démarre via ADB shell.
     *
     * Mécanisme : Freedom persiste son mode navigation dans
     *   /sdcard/Android/data/com.xdja.clusterdemo/data/properties.xml
     * sous la clé "navigationType" (int) : 0=全屏, 1=小屏, 2=关闭.
     * La valeur par défaut (fichier absent) est 0 = 全屏.
     *
     * Séquence :
     *   1. force-stop Freedom (s'il tourne avec une mauvaise config, il enverrait sendInfo(18))
     *   2. supprimer properties.xml → reset vers les défauts (navigationType=0 = 全屏)
     *   3. am start Freedom → démarre avec 全屏导航, envoie sendInfo(16) de son côté
     *
     * La callback est appelée sur un thread ADB (background).
     */
    public static void startFreedom(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    // 1. Force-stop Freedom pour qu'il relise ses préférences au prochain démarrage
                    dadb.shell("am force-stop com.xdja.clusterdemo 2>&1");
                    AppLogger.i(TAG, "startFreedom : force-stop Freedom");
                    Thread.sleep(500);

                    // 2. Supprimer le fichier de préférences → Freedom démarrera avec navigationType=0 (全屏)
                    dadb.shell("rm -f /sdcard/Android/data/com.xdja.clusterdemo/data/properties.xml 2>&1");
                    AppLogger.i(TAG, "startFreedom : properties.xml supprimé (reset → 全屏导航)");

                    // 3. Démarrer Freedom
                    AppLogger.i(TAG, "startFreedom : démarrage via am start");
                    String startOut = safeOut(dadb.shell(
                            "am start -n com.xdja.clusterdemo/.activities.MainActivity 2>&1"
                    ).getAllOutput()).trim();
                    AppLogger.i(TAG, "startFreedom am start → " + startOut);
                    callback.onSuccess(startOut);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "startFreedom ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-start-freedom").start();
    }

    // ── TEST 10 : Test de restauration du cluster ──────────────────────────────
    /**
     * Active le cluster en mode présentation (sendInfo 30 + 16 uniquement).
     *
     *   1. sendInfo(1000, 30) — taille 12.3" TOUJOURS : seul mode où l'écran ADAS n'est pas étiré
     *   2. sendInfo(1000, 16) — Qt standby → libère le display pour la projection
     *
     * Ne contient PAS sendInfo(18) ni sendInfo(0) qui sont des commandes de restauration.
     */
    public static void activateClusterDisplay(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = AppLogger.startTiming();
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    sb.append("── sendInfo(1000, 30) = 12.3\" (ADAS non étiré) ──\n");
                    AdbShellResponse r30 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    sb.append(r30.getAllOutput().trim()).append("\n");
                    Thread.sleep(1000);

                    sb.append("\n── sendInfo(1000, 16) = Qt standby ──\n");
                    AdbShellResponse r16 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 16 s16 \"\" 2>&1");
                    sb.append(r16.getAllOutput().trim()).append("\n");

                    AppLogger.endTiming(TAG, t0, "activateClusterDisplay terminé");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "activateClusterDisplay ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-activate-cluster-thread").start();
    }

    /**
     * TEST 10 — Séquence activation + restauration cluster (Seal EU)
     *
     * Séquence :
     *   1. sendInfo(1000, 30) — Seal EU screen size (CONFIRMÉ 16/04/2026)
     *   2. attente 1s
     *   3. sendInfo(1000, 16) — Qt standby
     *   4. attente 2s
     *   5. sendInfo(1000, 18) — fermer projection (投屏关闭)
     *   6. attente 1s
     *   7. sendInfo(1000,  0) — rafraîchir flux Qt
     *   8. Logcat AutoContainer
     */
    public static void runDisplayOneLaunch(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = AppLogger.startTiming();
                AppLogger.i(TAG, "runDisplayOneLaunch démarré [" + Thread.currentThread().getName() + "]");
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();
                    dadb.shell("logcat -c 2>&1");

                    // ── 1. sendInfo(30) — Seal EU screen size ─────────────────
                    sb.append("── sendInfo(1000, 30) = Seal EU screen size (12.3\") ──\n");
                    AdbShellResponse rSend30 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    sb.append(rSend30.getAllOutput().trim()).append("\n");
                    Thread.sleep(1000);

                    // ── 2. sendInfo(16) — Qt standby ─────────────────────────
                    sb.append("\n── sendInfo(1000, 16) = Qt standby ──\n");
                    AdbShellResponse rSend16 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 16 s16 \"\" 2>&1");
                    sb.append(rSend16.getAllOutput().trim()).append("\n");
                    Thread.sleep(2000);

                    // ── 3. sendInfo(18) — fermer projection ──────────────────
                    sb.append("\n── sendInfo(1000, 18) = fermer projection (投屏关闭) ──\n");
                    AdbShellResponse rSend18 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 18 s16 \"\" 2>&1");
                    sb.append(rSend18.getAllOutput().trim()).append("\n");
                    Thread.sleep(1000);

                    // ── 4. sendInfo(0) — rafraîchir flux Qt ──────────────────
                    sb.append("\n── sendInfo(1000, 0) = rafraîchir flux Qt ──\n");
                    AdbShellResponse rSend0 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append(rSend0.getAllOutput().trim()).append("\n");
                    Thread.sleep(500);

                    // ── 5. Logcat ─────────────────────────────────────────────
                    sb.append("\n── Logcat (AutoContainer) ──\n");
                    AdbShellResponse rLog = dadb.shell(
                        "logcat -d 2>&1 | grep -iE 'AutoContainer|sendInfo' | tail -20");
                    sb.append(rLog.getAllOutput().trim().isEmpty() ? "(aucune entrée)" : rLog.getAllOutput().trim()).append("\n");

                    AppLogger.endTiming(TAG, t0, "runDisplayOneLaunch terminé");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "runDisplayOneLaunch ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-display1-thread").start();
    }


    /**
     * Restaure l'affichage BYD natif sur le cluster.
     *
     * com.byd.automap N'EST PAS INSTALLÉ sur la BYD Seal EU — on ne peut pas utiliser
     * la séquence Freedom (am start automap).
     *
     * FIX Seal EU :
     *   1. Trouver le taskId de notre app sur display <displayId>
     *   2. am task remove <taskId>  → libère la surface (sans tuer le processus entier)
     *   3. sendInfo(1000, 0)        → Qt reprend le contrôle de la surface
     *
     * @param displayId  ID du display cluster (1 sur DiLink 3.0)
     */
    public static void restoreBydOnCluster(final Context context,
            final String targetPackage, // nullable : package à force-stoper avant la restauration
            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                AppLogger.log(TAG, "Restauration BYD cluster"
                        + (targetPackage != null ? " (cible=" + targetPackage + ")" : ""));
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // 0. Force-stop du package cible AVANT sendInfo(18).
                    // Sans ça, la task de l'app (lancée via trampoline sur display 1) reste
                    // enregistrée dans ActivityManager : quand sendInfo(18) libère la surface
                    // Qt, Android relocalise la task orpheline sur display 0 → l'app apparaît
                    // sur l'écran principal du tablet.
                    if (targetPackage != null && !targetPackage.isEmpty()) {
                        dadb.shell("am force-stop " + targetPackage + " 2>&1");
                        sb.append("force-stop ").append(targetPackage).append("\n");
                        Thread.sleep(500);
                    }

                    // 1. Force-stop Freedom (com.xdja.clusterdemo).
                    //    Freedom est démarré automatiquement au lancement de notre app (v1.86).
                    //    S'il tourne encore, il re-prend le display immédiatement après sendInfo(18)
                    //    et annule la restauration. On doit l'arrêter d'abord.
                    dadb.shell("am force-stop com.xdja.clusterdemo 2>&1");
                    sb.append("force-stop Freedom\n");
                    Thread.sleep(500);

                    AdbShellResponse rStop = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 18 s16 \"\" 2>&1");
                    sb.append("sendInfo(18) : ").append(rStop.getAllOutput().trim()).append("\n");
                    Thread.sleep(1000);

                    AdbShellResponse rRestore = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append("sendInfo(0)  : ").append(rRestore.getAllOutput().trim()).append("\n");

                    AppLogger.log(TAG, "restoreBydOnCluster -> OK");
                    callback.onSuccess("BYD restauré \u2713\n" + sb);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "restoreBydOnCluster ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-restore-thread").start();
    }

    /**
     * Cluster d'origine — remet le cluster Qt dans la taille d'écran configurée par l'utilisateur.
     *
     * Séquence :
     *   1. sendInfo(1000, screenSizeCmd) — passer Qt dans la bonne résolution
     *   2. sendInfo(1000, 18)            — fermer la projection (投屏关闭)
     *   3. sendInfo(1000,  0)            — rafraîchir le flux Qt
     *
     * @param screenSizeCmd  code taille : 29=8.8", 30=12.3" (Seal EU), 31=10.25"
     */
    public static void restoreOriginCluster(final Context context, final int screenSizeCmd,
            final String targetPackage, // nullable : package à force-stoper avant la restauration
            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                AppLogger.log(TAG, "restoreOriginCluster screenSize=" + screenSizeCmd
                        + (targetPackage != null ? " cible=" + targetPackage : ""));
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // Force-stop du package cible avant la restauration (même raison que
                    // restoreBydOnCluster : éviter la relocalisation de la task sur display 0).
                    if (targetPackage != null && !targetPackage.isEmpty()) {
                        dadb.shell("am force-stop " + targetPackage + " 2>&1");
                        sb.append("force-stop ").append(targetPackage).append("\n");
                        Thread.sleep(500);
                    }

                    AdbShellResponse rSize = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 " + screenSizeCmd + " s16 \"\" 2>&1");
                    sb.append("sendInfo(").append(screenSizeCmd).append(") : ");
                    sb.append(rSize.getAllOutput().trim()).append("\n");

                    AdbShellResponse rStop = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 18 s16 \"\" 2>&1");
                    sb.append("sendInfo(18) : ").append(rStop.getAllOutput().trim()).append("\n");

                    AdbShellResponse rRefresh = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append("sendInfo(0)  : ").append(rRefresh.getAllOutput().trim()).append("\n");

                    AppLogger.log(TAG, "restoreOriginCluster -> OK");
                    callback.onSuccess("Cluster d'origine restauré \u2713\n" + sb);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "restoreOriginCluster ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-origin-cluster-thread").start();
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // sendInfo ADB relay — contourne la SecurityException (uid=10100 non listé dans whitelist JSON)
    // dm-verity empêche de patcher /system/etc/container_comm_cfg.json sur ce hardware.
    // uid=2000 (shell ADB) passe le checkSignatures() dans AutoContainerService.
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Envoie sendInfo(type, infoInt, infoStr) au service AutoContainer via ADB shell relay.
     *
     * Équivalent de : service call AutoContainer 2 i32 <type> i32 <infoInt> s16 "<infoStr>"
     * uid=2000 (shell) passe le checkSignatures → pas de SecurityException.
     *
     * La callback est appelée depuis un thread background — utiliser runOnUiThread si nécessaire.
     */
    public static void sendInfo(final Context context,
                                final int type, final int infoInt, final String infoStr,
                                final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    String safeStr = (infoStr != null ? infoStr : "").replace("\"", "\\\"");
                    String cmd = "service call AutoContainer 2 i32 " + type
                               + " i32 " + infoInt + " s16 \"" + safeStr + "\" 2>&1";
                    AppLogger.log(TAG, "sendInfo ADB: " + cmd);
                    AdbShellResponse r = dadb.shell(cmd);
                    String out = r.getAllOutput().trim();
                    AppLogger.log(TAG, "sendInfo ADB(" + type + "," + infoInt + ") → " + out);
                    if (callback != null) callback.onSuccess(out);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "sendInfo ADB ERREUR", e);
                    if (callback != null) callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-sendinfo-thread").start();
    }

    // ── Diagnostic : signature + permissions effectivement accordées ──────────

    /**
     * Dump l'état réel des signatures et permissions pour notre app via ADB
     * (uid=2000, vue système). Permet de répondre à la question :
     * "L'APK est-il vraiment signé avec la même clé que la ROM ?"
     *
     * Sortie loggée (AppLogger INFO, tag "SigDump") :
     *   - ro.build.tags / ro.build.version.security_patch
     *   - dumpsys package com.byd.myapp | grep -E "Signature|signatures|version"
     *   - dumpsys package com.xdja.containerservice | grep -E "Signature|signatures"
     *   - pm dump com.byd.myapp | grep -E "INTERNAL_SYSTEM_WINDOW|MANAGE_ACTIVITY_STACKS|INJECT_EVENTS"
     *   - dumpsys package com.byd.myapp | grep -A 1 "install permissions:"
     *   - id (uid courant du shell)
     */
    public static void dumpSignatureAndPermissions(final Context context) {
        new Thread(new Runnable() {
            @Override public void run() {
                final String dTag = "SigDump";
                try (Dadb dadb = connect(context)) {
                    String pkg = context.getPackageName();

                    AppLogger.i(dTag, "=== Build & shell uid ===");
                    AppLogger.i(dTag, "id: " + dadb.shell("id 2>&1").getAllOutput().trim());
                    AppLogger.i(dTag, "build.tags: " + dadb.shell(
                            "getprop ro.build.tags 2>&1").getAllOutput().trim());
                    AppLogger.i(dTag, "build.fingerprint: " + dadb.shell(
                            "getprop ro.build.fingerprint 2>&1").getAllOutput().trim());

                    AppLogger.i(dTag, "=== Notre APK (" + pkg + ") signature & version ===");
                    String ourSig = dadb.shell(
                            "dumpsys package " + pkg
                            + " | grep -E 'versionCode|versionName|signatures' "
                            + "| head -10 2>&1").getAllOutput().trim();
                    for (String line : ourSig.split("\n")) AppLogger.i(dTag, "  " + line);

                    AppLogger.i(dTag, "=== ROM/AutoContainer signature (com.xdja.containerservice) ===");
                    String romSig = dadb.shell(
                            "dumpsys package com.xdja.containerservice "
                            + "| grep -E 'signatures|sharedUser' | head -5 2>&1").getAllOutput().trim();
                    for (String line : romSig.split("\n")) AppLogger.i(dTag, "  " + line);

                    AppLogger.i(dTag, "=== Permissions accordées à notre app ===");
                    String perms = dadb.shell(
                            "dumpsys package " + pkg
                            + " | grep -E "
                            + "'INTERNAL_SYSTEM_WINDOW|MANAGE_ACTIVITY_STACKS|INJECT_EVENTS|"
                            + "BYDAUTO_SPEED|BYDAUTO_GEARBOX|granted=true|granted=false' "
                            + "| head -30 2>&1").getAllOutput().trim();
                    for (String line : perms.split("\n")) AppLogger.i(dTag, "  " + line);

                    AppLogger.i(dTag, "=== Appels signature checks (test direct) ===");
                    // Tente un am start --display 1 minimal pour confirmer le verdict
                    AppLogger.i(dTag, "uid=2000 am start --display 1 (notre activity, dry run):");
                    String testLaunch = dadb.shell(
                            "am start-activity -W --display 1 --windowingMode 5 "
                            + "-n " + pkg + "/.dashboard.ClusterTrampolineActivity 2>&1 "
                            + "| head -20").getAllOutput().trim();
                    for (String line : testLaunch.split("\n")) AppLogger.i(dTag, "  " + line);

                    AppLogger.i(dTag, "=== FIN dump ===");
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(dTag, "dumpSignatureAndPermissions ERREUR", e);
                }
            }
        }, "adb-sigdump-thread").start();
    }

    // ── DIAG v1.74 : SUPPRIMÉ (v1.75.1) ──
    // La méthode dumpClusterRoutingState() effectuait un brute-force sendInfo(1000, N)
    // pour identifier le routing display Freedom. Inutile désormais : la vraie cause
    // (OWN_CONTENT_ONLY sur le VirtualDisplay créé par AutoDisplayService) est
    // identifiée et corrigée par v1.75 (ClusterSurfaceProbe).
    // Suppression demandée par l'utilisateur pour éviter tout impact sur la voiture.

    // ── TEST 12 : Sonde taille display cluster + essais cmd 29/30/31 + wm size ──

    /**
     * Teste les différentes approches pour corriger la résolution du display cluster.
     *
     * Le VirtualDisplay d'AutoDisplayService est créé en 1920×1080 (valeurs par défaut
     * dans le code décompilé de com.xdja.containerservice), mais le panel physique est
     * ~1920×480 (ratio ~4:1). Résultat : étirement vertical.
     *
     * Ce test essaie séquentiellement :
     *   1. Dump l'état actuel du display 1 (wm size, dumpsys display)
     *   2. sendInfo(1000, 29) — 切换到8.8寸屏 (pourrait changer la surface Qt)
     *   3. Re-dump le display 1 pour voir si les dimensions ont changé
     *   4. sendInfo(1000, 30) — 切换到12.3寸屏 (rétablit la config d'origine)
     *   5. Essai wm size 1920x480 -d 1 (forcer la résolution logique)
     *   6. Dump post-wm size
     *   7. wm size reset -d 1 (nettoyer)
     *
     * Le résultat est un rapport texte avec les dumps avant/après chaque commande.
     */
    public static void runClusterDisplaySizeTest(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // ── 1. État initial ──────────────────────────────────────
                    sb.append("=== [1] ÉTAT INITIAL DU DISPLAY CLUSTER ===\n");

                    AdbShellResponse rSize = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 : ").append(rSize.getAllOutput().trim()).append("\n");

                    AdbShellResponse rDensity = dadb.shell("wm density -d 1 2>&1");
                    sb.append("wm density -d 1 : ").append(rDensity.getAllOutput().trim()).append("\n");

                    AdbShellResponse rDump = dadb.shell(
                            "dumpsys display 2>/dev/null | grep -A5 'mDisplayId=1' | head -10");
                    String dumpOut = rDump.getAllOutput().trim();
                    sb.append("dumpsys display id=1 :\n").append(
                            dumpOut.isEmpty() ? "  (non trouvé dans dumpsys)" : dumpOut).append("\n");

                    // Surface info via SurfaceFlinger
                    AdbShellResponse rSf = dadb.shell(
                            "dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'fission|virtual|cluster' | head -5");
                    String sfOut = rSf.getAllOutput().trim();
                    if (!sfOut.isEmpty()) {
                        sb.append("SurfaceFlinger :\n").append(sfOut).append("\n");
                    }
                    sb.append("\n");

                    // ── 2. sendInfo(1000, 29) — switch 8.8 pouces ────────────
                    sb.append("=== [2] sendInfo(1000, 29) — 切换到8.8寸屏 ===\n");
                    AdbShellResponse r29 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 29 s16 \"\" 2>&1");
                    sb.append("Résultat : ").append(r29.getAllOutput().trim()).append("\n");

                    // Attendre que Qt applique le changement
                    Thread.sleep(1500);

                    AdbShellResponse rPost29 = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 après cmd=29 : ").append(rPost29.getAllOutput().trim()).append("\n");

                    AdbShellResponse rDump29 = dadb.shell(
                            "dumpsys display 2>/dev/null | grep -A5 'mDisplayId=1' | head -10");
                    String dump29 = rDump29.getAllOutput().trim();
                    sb.append("dumpsys display id=1 après cmd=29 :\n").append(
                            dump29.isEmpty() ? "  (non trouvé)" : dump29).append("\n\n");

                    // ── 3. sendInfo(1000, 30) — Seal EU mode (12.3", mode par défaut Seal EU) ─
                    sb.append("=== [3] sendInfo(1000, 30) — Seal EU (12.3\") — CONFIRMÉ 16/04/2026 ===\n");
                    AdbShellResponse r30 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    sb.append("Résultat : ").append(r30.getAllOutput().trim()).append("\n");
                    Thread.sleep(1500);

                    AdbShellResponse rPost30 = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 après cmd=30 : ").append(rPost30.getAllOutput().trim()).append("\n");
                    sb.append("\n");

                    // ── 4. sendInfo(1000, 31) — switch 10.25 pouces ───────────
                    sb.append("=== [4] sendInfo(1000, 31) — 切换到10.25寸屏 ===\n");
                    AdbShellResponse r31 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 31 s16 \"\" 2>&1");
                    sb.append("Résultat : ").append(r31.getAllOutput().trim()).append("\n");
                    Thread.sleep(1500);

                    AdbShellResponse rPost31 = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 après cmd=31 : ").append(rPost31.getAllOutput().trim()).append("\n");
                    sb.append("\n");

                    // Rétablir 12.3"
                    dadb.shell("service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    Thread.sleep(500);

                    // ── 5. wm size 1920x480 -d 1 (forcer la résolution) ───────
                    sb.append("=== [5] wm size 1920x480 -d 1 ===\n");
                    AdbShellResponse rWm = dadb.shell("wm size 1920x480 -d 1 2>&1");
                    sb.append("Résultat cmd : ").append(rWm.getAllOutput().trim()).append("\n");
                    Thread.sleep(500);

                    AdbShellResponse rPostWm = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 après : ").append(rPostWm.getAllOutput().trim()).append("\n");

                    AdbShellResponse rDumpWm = dadb.shell(
                            "dumpsys display 2>/dev/null | grep -A5 'mDisplayId=1' | head -10");
                    String dumpWm = rDumpWm.getAllOutput().trim();
                    sb.append("dumpsys display id=1 après :\n").append(
                            dumpWm.isEmpty() ? "  (non trouvé)" : dumpWm).append("\n\n");

                    // ── 6. Reset ──────────────────────────────────────────────
                    sb.append("=== [6] wm size reset -d 1 (nettoyage) ===\n");
                    AdbShellResponse rReset = dadb.shell("wm size reset -d 1 2>&1");
                    sb.append("Résultat : ").append(rReset.getAllOutput().trim()).append("\n");

                    AdbShellResponse rFinal = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 final : ").append(rFinal.getAllOutput().trim()).append("\n");

                    AppLogger.log(TAG, "TEST 12 terminé ✓");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "TEST 12 ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-display-size-test").start();
    }

    /**
     * Envoie une commande de changement de taille d'écran Qt vers le cluster.
     *   cmd 29 = 切换到8.8寸屏  (8.8 pouces)
     *   cmd 30 = 切换到12.3寸屏 (12.3 pouces — état par défaut AutoDisplayService)
     *   cmd 31 = 切换到10.25寸屏 (10.25 pouces)
     * Retourne un rapport wm size -d 1 avant/après la commande.
     */
    public static void sendClusterScreenSize(final Context context, final int sizeCmd,
            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    String label = sizeCmd == 29 ? "8.8\"" : sizeCmd == 30 ? "12.3\"" : "10.25\"";
                    sb.append("sendInfo(1000, ").append(sizeCmd).append(") → ").append(label).append("\n\n");

                    AdbShellResponse rBefore = dadb.shell("wm size -d 1 2>&1");
                    sb.append("Avant  : ").append(rBefore.getAllOutput().trim()).append("\n");

                    AdbShellResponse rCmd = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 " + sizeCmd + " s16 \"\" 2>&1");
                    sb.append("Cmd    : ").append(rCmd.getAllOutput().trim()).append("\n");

                    Thread.sleep(1500);

                    AdbShellResponse rAfter = dadb.shell("wm size -d 1 2>&1");
                    sb.append("Après  : ").append(rAfter.getAllOutput().trim()).append("\n");

                    AdbShellResponse rDump = dadb.shell(
                            "dumpsys display 2>/dev/null | grep -A5 'mDisplayId=1' | head -8");
                    String dump = rDump.getAllOutput().trim();
                    if (!dump.isEmpty())
                        sb.append("\ndumpsys display id=1 :\n").append(dump).append("\n");

                    AdbShellResponse rSf = dadb.shell(
                            "dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'fission|virtual' | head -3");
                    String sf = rSf.getAllOutput().trim();
                    if (!sf.isEmpty())
                        sb.append("\nSurfaceFlinger :\n").append(sf).append("\n");

                    AppLogger.log(TAG, "sendClusterScreenSize(" + sizeCmd + ") ✓");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "sendClusterScreenSize(" + sizeCmd + ") ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-screen-size-" + sizeCmd).start();
    }

    /**
     * Restaure la taille d'écran cluster par défaut :
     *   1. sendInfo(1000, 30) — 切换到12.3寸屏 (état Qt par défaut, 1920×1080)
     *   2. wm size reset -d 1 — annule tout override logique Android
     * À utiliser après un essai de cmd 29/31 qui aurait perturbé l'affichage.
     */
    public static void resetClusterDisplaySize(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("🔄 Restauration taille par défaut\n\n");

                    AdbShellResponse r30 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    sb.append("sendInfo(1000,30) 切换到12.3寸屏 : ")
                      .append(r30.getAllOutput().trim()).append("\n");
                    Thread.sleep(500);

                    AdbShellResponse rReset = dadb.shell("wm size reset -d 1 2>&1");
                    sb.append("wm size reset -d 1 : ").append(rReset.getAllOutput().trim()).append("\n");
                    Thread.sleep(300);

                    AdbShellResponse rFinal = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 final : ").append(rFinal.getAllOutput().trim()).append("\n");

                    AppLogger.log(TAG, "resetClusterDisplaySize ✓");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "resetClusterDisplaySize ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-display-reset").start();
    }

    /**
     * Force-stop d'une application via ADB.
     * Appelé quand l'utilisateur tape "✕" dans la liste.
     * Utilise "am force-stop" qui tue le processus entier + libère toutes ses surfaces.
     */
    public static void forceStopApp(final Context context, final String packageName,
            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                AppLogger.log(TAG, "forceStop " + packageName + " ...");
                try (Dadb dadb = connect(context)) {
                    AdbShellResponse r = dadb.shell("am force-stop " + packageName + " 2>&1 && echo STOPPED");
                    String out = r.getAllOutput().trim();
                    AppLogger.log(TAG, "am force-stop " + packageName + " -> " + out);
                    if (callback != null) {
                        if (out.contains("STOPPED") || out.isEmpty()) {
                            callback.onSuccess("force-stop OK");
                        } else {
                            callback.onError(out);
                        }
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "forceStopApp ERREUR", e);
                    if (callback != null) callback.onError(msg);
                }
            }
        }, "adb-forcestop-thread").start();
    }

    /**
     * Lance NOTRE TRAMPOLINE sur display N via ADB shell, avec le package cible en extra.
     *
     * Pourquoi ce chemin (v1.73+) :
     *   - Notre APK n'est PAS signé avec la clé BYD (CN=Android testkey vs CN=auto_api)
     *     → INTERNAL_SYSTEM_WINDOW non accordée
     *     → setLaunchDisplayId(N!=0) toujours refusé depuis uid=10100
     *   - ADB shell (uid=2000) sur cette ROM POSSÈDE INTERNAL_SYSTEM_WINDOW
     *     (présent dans /system/etc/permissions/platform.xml, contexte SELinux shell:s0)
     *   - Le trampoline est exporté → ADB shell peut le lancer
     *   - Une fois sur display 1, le trampoline lance le tiers via Activity.startActivity()
     *     SANS setLaunchDisplayId → la nouvelle task hérite du display source.
     *
     * C'est le pattern qui fonctionnait le 12 avril (BYDDashboardActivity exported=true
     * lancée depuis ADB shell uid=2000 via am start --display 1).
     */
    public static void launchTrampolineViaAdb(final Context context, final String targetPackage,
            final int displayId, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    String pkg = context.getPackageName();
                    // --es target_package <pkg>  → passé à ClusterTrampolineActivity.onCreate()
                    String cmd = "am start --display " + displayId
                            + " --windowingMode 5"
                            + " -n " + pkg + "/.dashboard.ClusterTrampolineActivity"
                            + " --es target_package " + targetPackage
                            + " 2>&1";
                    AppLogger.i(TAG, "ADB launchTrampoline: " + cmd);
                    AdbShellResponse r = dadb.shell(cmd);
                    String out = r.getAllOutput().trim();
                    AppLogger.i(TAG, "ADB launchTrampoline result: " + out);
                    if (out.contains("Error") || out.contains("Exception")
                            || out.contains("Permission Denial")) {
                        callback.onError(out);
                    } else {
                        callback.onSuccess(out);
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "launchTrampolineViaAdb échoué", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-trampoline-thread").start();
    }

    /**
     * Variante de launchTrampolineViaAdb avec des bounds FREEFORM explicites.
     * Permet de positionner une app dans un demi-écran cluster au lancement.
     *
     * @param left   coordonnée gauche (pixels display cluster)
     * @param top    coordonnée haute
     * @param right  coordonnée droite
     * @param bottom coordonnée basse
     */
    public static void launchTrampolineWithBounds(final Context context,
            final String targetPackage, final int displayId,
            final int left, final int top, final int right, final int bottom,
            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    String pkg = context.getPackageName();
                    String cmd = "am start --display " + displayId
                            + " --windowingMode 5"
                            + " --bounds \"" + left + " " + top + " " + right + " " + bottom + "\""
                            + " -n " + pkg + "/.dashboard.ClusterTrampolineActivity"
                            + " --es target_package " + targetPackage
                            + " 2>&1";
                    AppLogger.i(TAG, "ADB launchTrampolineWithBounds: " + cmd);
                    AdbShellResponse r = dadb.shell(cmd);
                    String out = r.getAllOutput().trim();
                    AppLogger.i(TAG, "ADB launchTrampolineWithBounds result: " + out);
                    if (out.contains("Error") || out.contains("Exception")
                            || out.contains("Permission Denial")) {
                        callback.onError(out);
                    } else {
                        callback.onSuccess(out);
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "launchTrampolineWithBounds échoué", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-trampoline-bounds-thread").start();
    }

    /**
     * Redimensionne la task d'un package déjà lancé sur le cluster via "am task resize".
     * Cherche le taskId dans la sortie de "am stack list".
     */
    public static void resizeTask(final Context context, final String packageName,
            final int left, final int top, final int right, final int bottom,
            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                AppLogger.i(TAG, "resizeTask pkg=" + packageName
                        + " bounds=[" + left + "," + top + "," + right + "," + bottom + "]"
                        + " [" + Thread.currentThread().getName() + "]");
                try (Dadb dadb = connect(context)) {
                    // 1. Trouver le taskId du package
                    // am stack list donne : "  taskId=N: <pkg>/<activity>"
                    AppLogger.d(TAG, "resizeTask : am stack list grep " + packageName);
                    AdbShellResponse rList = dadb.shell(
                            "am stack list 2>/dev/null | grep 'taskId=.*" + packageName + "' | head -1");
                    String line = rList.getAllOutput().trim();
                    AppLogger.i(TAG, "resizeTask stack grep → '" + line + "'");
                    if (line.isEmpty()) {
                        // Fallback : dump stack complet pour aider le debug
                        String allStacks = dadb.shell(
                                "am stack list 2>/dev/null | head -40").getAllOutput().trim();
                        AppLogger.w(TAG, "resizeTask : taskId introuvable. Stacks complets:\n" + allStacks);
                        callback.onError("taskId introuvable pour " + packageName);
                        return;
                    }
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("taskId=(\\d+)").matcher(line);
                    if (!m.find()) {
                        AppLogger.e(TAG, "resizeTask : regex taskId=(\\d+) no match sur: '" + line + "'");
                        callback.onError("taskId introuvable pour " + packageName);
                        return;
                    }
                    int taskId = Integer.parseInt(m.group(1));
                    AppLogger.i(TAG, "resizeTask taskId=" + taskId);

                    // 2. Redimensionner
                    String cmd = "am task resize " + taskId
                            + " " + left + " " + top + " " + right + " " + bottom + " 2>&1";
                    AppLogger.i(TAG, "resizeTask cmd: " + cmd);
                    AdbShellResponse rResize = dadb.shell(cmd);
                    String out = rResize.getAllOutput().trim();
                    AppLogger.i(TAG, "resizeTask result → '" + out + "'");
                    if (out.toLowerCase().contains("error") || out.toLowerCase().contains("exception")) {
                        callback.onError(out);
                    } else {
                        callback.onSuccess("taskId=" + taskId + " resize OK\n" + out);
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "resizeTask ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-resize-task-thread").start();
    }

    private static String safeOut(String s) {
        if (s == null) return "(null)";
        s = s.trim();
        return s.isEmpty() ? "(vide)" : s;
    }

}
