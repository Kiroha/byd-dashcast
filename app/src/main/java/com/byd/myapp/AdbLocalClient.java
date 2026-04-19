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

    // ── TEST 6 : Sonde le cluster pour identifier le process propriétaire ──────
    /**
     * Interroge le système via ADB pour répondre à la question :
     * "com.byd.automap est-il vraiment le process qui occupe le cluster ?"
     *
     * Commandes exécutées :
     *   1. pm list packages    → confirme qu'automap/launchermap/xdja sont installés
     *   2. dumpsys display     → identifie les displays (id, nom, owner)
     *   3. dumpsys activity    → montre quelle activité est top-of-stack par display
     *   4. ps -A               → process actifs (automap/launchermap/xdja)
     *   5. pm path + cp        → copie les APK clusterdebug / smarttravel sur /sdcard
     */
    public static void runClusterProbe(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // 1. Packages BYD/xdja installés
                    sb.append("── Packages installés (byd/xdja) ──\n");
                    AdbShellResponse r1 = dadb.shell(
                            "pm list packages 2>/dev/null | grep -iE 'byd|xdja|cluster'");
                    String pkgs = r1.getAllOutput().trim();
                    sb.append(pkgs.isEmpty() ? "(aucun trouvé)" : pkgs).append("\n\n");

                    // 2. Services Binder BYD enregistrés dans le système
                    sb.append("── Services Binder (byd/xdja/cluster/window) ──\n");
                    AdbShellResponse r2 = dadb.shell(
                            "service list 2>/dev/null | grep -iE 'byd|xdja|cluster|window|display|instrument|navi'");
                    sb.append(r2.getAllOutput().trim()).append("\n\n");

                    // 3. Activité top-of-stack sur chaque display
                    sb.append("── dumpsys window displays ──\n");
                    AdbShellResponse r3 = dadb.shell(
                            "dumpsys window displays 2>/dev/null"
                            + " | grep -E 'mDisplayId|mBaseDisplayInfo|uniqueId|name=|owner=' | head -20");
                    String acts = r3.getAllOutput().trim();
                    sb.append(acts.isEmpty() ? "(rien trouvé)" : acts).append("\n\n");

                    // 4. Vérification rapide : process BYD actifs
                    sb.append("── Process actifs (byd/xdja) ──\n");
                    AdbShellResponse r4 = dadb.shell(
                            "ps -A 2>/dev/null | grep -iE 'byd|xdja|cluster'");
                    String procs = r4.getAllOutput().trim();
                    sb.append(procs.isEmpty() ? "(aucun)" : procs).append("\n\n");

                    // 4b. État dumpsys de com.byd.car.server
                    sb.append("── com.byd.car.server services ──\n");
                    AdbShellResponse r4b = dadb.shell(
                            "dumpsys activity services com.byd.car.server 2>/dev/null | head -20");
                    sb.append(r4b.getAllOutput().trim()).append("\n\n");

                    // 5. Extraire les APK pertinents vers /sdcard pour analyse
                    sb.append("── Extraction APK → /sdcard ──\n");
                    String[] targets = {
                        "com.byd.clusterdebug",
                        "com.byd.smarttravel",
                        "com.byd.smarttravel2",
                        "com.byd.car.server",
                        "com.xdja.containerservice",
                        "com.xdja.clusterdemo"
                    };
                    for (String pkg : targets) {
                        AdbShellResponse rPath = dadb.shell("pm path " + pkg + " 2>/dev/null");
                        String pathLine = rPath.getAllOutput().trim(); // "package:/data/app/com.byd.../base.apk"
                        if (pathLine.startsWith("package:")) {
                            String apkPath = pathLine.substring("package:".length()).trim();
                            String dest = "/sdcard/" + pkg + ".apk";
                            AdbShellResponse rCp = dadb.shell("cp \"" + apkPath + "\" \"" + dest + "\" 2>&1 && echo COPIED");
                            boolean copied = rCp.getAllOutput().contains("COPIED");
                            sb.append(pkg).append(" → ").append(copied ? dest + " ✓" : "ÉCHEC cp").append("\n");
                        } else {
                            sb.append(pkg).append(" → non installé\n");
                        }
                    }
                    sb.append("\n→ Récupérez les APK avec le gestionnaire de fichiers BYD (/sdcard/)");

                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "runClusterProbe ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-probe-thread").start();
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

    /**
     * TEST 11 — Whitelist AutoContainer : pourquoi com.byd.myapp est rejeté par sendInfo()
     *
     * xdja_AutoContainerService.checkSendPermissionAndAllowType() refuse tout appel
     * dont le callingUid != uid système (1000) ou uid shell (2000), OU qui ne figure pas
     * dans /system/etc/container_comm_cfg.json.
     *
     * Ce test collecte via ADB shell (uid=2000, donc autorisé) :
     *   1. Contenu de container_comm_cfg.json (liste des packages whitelistés)
     *   2. sharedUserId + userId de com.xdja.clusterdemo (le seul whitelisté)
     *   3. sharedUserId + userId de com.byd.myapp (notre app — probablement uid 10100)
     *   4. Vérification si com.byd.myapp peut être ajouté à la whitelist par pm/setprop
     *   5. aapt dump permissions de com.xdja.clusterdemo (permissions déclarées)
     */
    public static void runAutoContainerWhitelistProbe(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                AppLogger.log(TAG, "runAutoContainerWhitelistProbe démarré");
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    sb.append("════ TEST 11 — AutoContainer Whitelist ════\n\n");

                    // 1. container_comm_cfg.json
                    sb.append("── 1. /system/etc/container_comm_cfg.json ──\n");
                    AdbShellResponse rCfg = dadb.shell(
                        "cat /system/etc/container_comm_cfg.json 2>&1 || " +
                        "find /system /vendor -name 'container_comm_cfg*' 2>/dev/null | head -3");
                    sb.append(rCfg.getAllOutput().trim()).append("\n\n");

                    // 2. sharedUserId + uid de com.xdja.clusterdemo
                    sb.append("── 2. com.xdja.clusterdemo — sharedUserId + uid ──\n");
                    AdbShellResponse rDemo = dadb.shell(
                        "dumpsys package com.xdja.clusterdemo 2>&1 | " +
                        "grep -E 'userId|sharedUser|codePath|versionName|declared permissions' | head -10");
                    sb.append(rDemo.getAllOutput().trim()).append("\n");
                    // aapt si dispo
                    AdbShellResponse rAapt = dadb.shell(
                        "PM_PATH=$(pm path com.xdja.clusterdemo 2>/dev/null | cut -d: -f2 | tr -d ' ') && " +
                        "[ -n \"$PM_PATH\" ] && aapt dump permissions \"$PM_PATH\" 2>/dev/null | head -20 " +
                        "|| echo 'aapt non dispo ou chemin vide'");
                    sb.append("\n[aapt permissions clusterdemo]\n");
                    sb.append(rAapt.getAllOutput().trim()).append("\n\n");

                    // 3. com.byd.myapp uid
                    sb.append("── 3. com.byd.myapp — userId ──\n");
                    AdbShellResponse rMyApp = dadb.shell(
                        "dumpsys package com.byd.myapp 2>&1 | " +
                        "grep -E 'userId|sharedUser|codePath|versionName' | head -6");
                    sb.append(rMyApp.getAllOutput().trim()).append("\n\n");

                    // 4. com.byd.clusterdebug uid
                    sb.append("── 4. com.byd.clusterdebug — userId ──\n");
                    AdbShellResponse rDbg = dadb.shell(
                        "dumpsys package com.byd.clusterdebug 2>&1 | " +
                        "grep -E 'userId|sharedUser|codePath|versionName' | head -6");
                    sb.append(rDbg.getAllOutput().trim()).append("\n\n");

                    // 5. Lister tous les packages partageant android.uid.system ou relevant du cluster
                    sb.append("── 5. Packages avec sharedUserId système ou xdja ──\n");
                    AdbShellResponse rShared = dadb.shell(
                        "dumpsys package packages 2>/dev/null | grep -A2 -B2 'sharedUser' | " +
                        "grep -E 'sharedUser=|pkg=.*xdja|pkg=.*cluster' | head -20");
                    sb.append(rShared.getAllOutput().trim()).append("\n\n");

                    AppLogger.log(TAG, "runAutoContainerWhitelistProbe terminé");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "runAutoContainerWhitelistProbe ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-whitelist-thread").start();
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
                    if (out.contains("STOPPED") || out.isEmpty()) {
                        callback.onSuccess("force-stop OK");
                    } else {
                        callback.onError(out);
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "forceStopApp ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-forcestop-thread").start();
    }

    // ── TEST 13 : Commandes ADAS cluster (cmd 32 / 33) ────────────────────────

    /**
     * Envoie une commande ADAS via sendInfo(1000, cmd) et retourne le résultat.
     *
     *   cmd 32 = 3d adas自刷新开启 — auto-refresh 3D ADAS ON
     *   cmd 33 = 3d adas自刷新关闭 — auto-refresh 3D ADAS OFF
     *
     * Retourne un rapport avec le résultat parcel + logcat post-commande.
     */
    public static void runAdasCommand(final Context context, final int adasCmd,
            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = AppLogger.startTiming();
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    String label = adasCmd == 32 ? "3d adas自刷新开启 (auto-refresh ON)"
                                 : "3d adas自刷新关闭 (auto-refresh OFF)";
                    sb.append("── sendInfo(1000, ").append(adasCmd)
                      .append(") — ").append(label).append(" ──\n");

                    dadb.shell("logcat -c 2>&1");

                    AdbShellResponse rCmd = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 " + adasCmd + " s16 \"\" 2>&1");
                    String parcel = rCmd.getAllOutput().trim();
                    sb.append("Parcel : ").append(parcel).append("\n");

                    // Un parcel "Result: Parcel(00000000    '....'" = succès (rien à retourner)
                    boolean ok = parcel.contains("00000000") || parcel.isEmpty();
                    sb.append("État   : ").append(ok ? "✅ OK" : "⚠ Résultat inattendu").append("\n");

                    Thread.sleep(800);

                    // Logcat post-commande pour voir si Qt réagit
                    AdbShellResponse rLog = dadb.shell(
                        "logcat -d 2>&1 | grep -iE 'AutoContainer|ADAS|adas|sendInfo' | tail -10");
                    String log = rLog.getAllOutput().trim();
                    if (!log.isEmpty()) {
                        sb.append("\n── Logcat AutoContainer/ADAS ──\n").append(log).append("\n");
                    }

                    AppLogger.endTiming(TAG, t0, "runAdasCommand(" + adasCmd + ") terminé");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "runAdasCommand(" + adasCmd + ") ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-adas-cmd-thread").start();
    }

    // ── TEST 14 : Masquage fenêtre ADAS — service "auto" (BYD VHAL privé) ─────

    /**
     * Sous-test A : Liste les services Binder dont le nom contient "auto" ou "byd".
     * Permet de vérifier que le service "auto" (BYD VHAL privé) est accessible via
     * ADB et de trouver le nom exact à utiliser dans service call.
     */
    public static void runAutoServiceList(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = AppLogger.startTiming();
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    sb.append("── service list | grep -iE 'auto|byd|cluster|adas' ──\n");
                    AdbShellResponse rList = dadb.shell(
                        "service list 2>&1 | grep -iE 'auto|byd|cluster|adas|ADAS'");
                    String list = rList.getAllOutput().trim();
                    sb.append(list.isEmpty() ? "(aucun résultat)" : list).append("\n");

                    AppLogger.endTiming(TAG, t0, "runAutoServiceList terminé");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "runAutoServiceList ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-autolist-thread").start();
    }

    /**
     * Sous-test A2 : Tente d'appeler service call auto N i32 1038 i32 944767020 i32 0
     * pour codes de transaction N = 1 à 6.
     *
     * Contexte RE :
     *   Dans byd_dashboard/c0/d.java case default :
     *     getSystemService("auto").setInt(1038, 944767020, 0)
     *   → 1038 = type (int), 944767020 = 0x385B9B2C (propriété VHAL masquage ADAS),
     *     0 = valeur (0 = masqué)
     *   La commande inverse devrait être setInt(1038, 944767020, 1) pour ré-afficher.
     *
     * @param showAdas true = ré-afficher (valeur 1), false = masquer (valeur 0)
     */
    public static void runAutoServiceCall(final Context context, final boolean showAdas,
            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = AppLogger.startTiming();
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    int val = showAdas ? 1 : 0;
                    String action = showAdas ? "ré-afficher" : "masquer";
                    sb.append("── service call auto N i32 1038 i32 944767020 i32 ").append(val)
                      .append(" (").append(action).append(" ADAS) ──\n\n");

                    // Essayer codes de transaction 1 à 6 (setInt inconnu)
                    for (int n = 1; n <= 6; n++) {
                        String cmd = "service call auto " + n
                                + " i32 1038 i32 944767020 i32 " + val + " 2>&1";
                        AdbShellResponse r = dadb.shell(cmd);
                        String out = r.getAllOutput().trim();
                        sb.append("code ").append(n).append(" → ").append(out).append("\n");
                        Thread.sleep(300);
                    }

                    AppLogger.endTiming(TAG, t0, "runAutoServiceCall terminé");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "runAutoServiceCall ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-autocall-thread").start();
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

    // ── DIAGNOSTIC v1.77 : capture A+B — transition OFF→ON + args sendInfo ──
    /**
     * Capture passive (read-only) ENRICHIE :
     *   - SNAPSHOT AVANT  : dumpsys display id=1 + flags + owner, SF layers fission,
     *                       window displays, dumpsys activity service AutoContainer
     *   - logcat -c
     *   - attente waitSecs s : l'utilisateur fait la TRANSITION Freedom OFF→ON
     *   - LOGCAT élargi : tags BYD/XDJA + Binder + ServiceManager + tags génériques
     *     'I am_proc_*' / 'IAutoContainer' avec -B2 -A8 pour récupérer les args
     *     `sendInfo type=… infoInt=…` autour des checkSendPermissionAndAllowType
     *   - SNAPSHOT APRÈS : mêmes dumpsys → diff visible
     *   - dumpsys activity service AutoContainer après pour voir les bindings actifs
     *
     * Aucune commande sendInfo / am start / service call n'est envoyée → strictement
     * passif (lecture seule).
     */
    public static void captureFreedomActivation(final Context context, final int waitSecs,
            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("⏱ Fenêtre de capture : ").append(waitSecs)
                      .append("s — pendant ce temps, fais la transition Freedom OFF→ON\n\n");

                    // ── 1. SNAPSHOT AVANT ────────────────────────────────────
                    sb.append("============ SNAPSHOT AVANT ============\n");
                    appendDumpsysSnapshot(dadb, sb);

                    sb.append("── dumpsys activity service AutoContainer ──\n");
                    AdbShellResponse rSvcA = dadb.shell(
                            "dumpsys activity service AutoContainer 2>/dev/null "
                            + "| head -80");
                    sb.append(safeOut(rSvcA.getAllOutput())).append("\n");

                    sb.append("── ps Freedom (com.xdja.clusterdemo) ──\n");
                    AdbShellResponse rPsA = dadb.shell(
                            "ps -A 2>/dev/null | grep -E 'clusterdemo|containerservice|"
                            + "windowmanages' | head -10");
                    sb.append(safeOut(rPsA.getAllOutput())).append("\n");

                    // ── 2. CLEAR LOGCAT ──────────────────────────────────────
                    AppLogger.i(TAG, "captureFreedomActivation: logcat -c puis attente "
                            + waitSecs + "s");
                    dadb.shell("logcat -c 2>&1");

                    // ── 3. ATTENTE — l'utilisateur active Freedom ────────────
                    Thread.sleep(waitSecs * 1000L);
                    AppLogger.i(TAG, "captureFreedomActivation: récupération du buffer + dumpsys après");

                    // ── 4. LOGCAT FILTRÉ ÉLARGI (tags + contexte ±) ──────────
                    sb.append("============ LOGCAT (").append(waitSecs).append("s) ============\n");
                    AdbShellResponse rLog = dadb.shell(
                            "logcat -d 2>&1 "
                            + "| grep -iE -B2 -A8 "
                            + "'xdja_AutoContainerService|AutoDisplayService|FissionWindow|"
                            + "fission_|setLaunchDisplay|launchDisplayId|VirtualDisplayAdapter|"
                            + "AppDisplayManager|createVirtualDisplay|VIRTUAL_DISPLAY_FLAG|"
                            + "IAutoContainer|sendInfo|sendJson|ClusterDemo|clusterdemo|"
                            + "windowmanages|am_proc_start|am_create_task|am_focused_activity|"
                            + "Binder.*AutoContainer|ServiceManager.*AutoContainer' "
                            + "| head -1500");
                    sb.append(safeOut(rLog.getAllOutput())).append("\n");

                    // ── 5. SNAPSHOT APRÈS ────────────────────────────────────
                    sb.append("============ SNAPSHOT APRÈS ============\n");
                    appendDumpsysSnapshot(dadb, sb);

                    sb.append("── dumpsys activity service AutoContainer (APRÈS) ──\n");
                    AdbShellResponse rSvcB = dadb.shell(
                            "dumpsys activity service AutoContainer 2>/dev/null "
                            + "| head -80");
                    sb.append(safeOut(rSvcB.getAllOutput())).append("\n");

                    sb.append("── ps Freedom (com.xdja.clusterdemo) (APRÈS) ──\n");
                    AdbShellResponse rPsB = dadb.shell(
                            "ps -A 2>/dev/null | grep -E 'clusterdemo|containerservice|"
                            + "windowmanages' | head -10");
                    sb.append(safeOut(rPsB.getAllOutput())).append("\n");

                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "captureFreedomActivation ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-freedom-capture").start();
    }

    /** Snapshots dumpsys partagés AVANT/APRÈS. Lecture seule. */
    private static void appendDumpsysSnapshot(Dadb dadb, StringBuilder sb) throws Exception {
        sb.append("── dumpsys display (id=1, flags, owner) ──\n");
        AdbShellResponse r1 = dadb.shell(
                "dumpsys display 2>/dev/null "
                + "| grep -E 'mDisplayId=1|mBaseDisplayInfo|uniqueId|name=|owner|FLAG_|state=' "
                + "| head -30");
        sb.append(safeOut(r1.getAllOutput())).append("\n");

        sb.append("── SurfaceFlinger : layers fission/cluster ──\n");
        AdbShellResponse r2 = dadb.shell(
                "dumpsys SurfaceFlinger --list 2>/dev/null "
                + "| grep -iE 'fission|cluster|virtual' | head -20");
        sb.append(safeOut(r2.getAllOutput())).append("\n");

        sb.append("── dumpsys window displays (focus, owner) ──\n");
        AdbShellResponse r3 = dadb.shell(
                "dumpsys window displays 2>/dev/null "
                + "| grep -E 'Display:|mDisplayId|mFocused|mOwnerUid|mPrivate|mType' "
                + "| head -40");
        sb.append(safeOut(r3.getAllOutput())).append("\n");
    }

    private static String safeOut(String s) {
        if (s == null) return "(null)";
        s = s.trim();
        return s.isEmpty() ? "(vide)" : s;
    }

    // ── TEST 16 : reproduire la séquence Freedom pour créer le display 1 ────

    /**
     * TEST 16 — Bootstrap actif du cluster sans Freedom.
     *
     * Reproduit la séquence observée dans le log Freedom v1.77 (20:57:24) :
     *   1. sendInfo(1000, 30) — Seal EU screen size
     *   2. sendInfo(1000, 16) — Qt standby (réveille la Surface Qt natif)
     *   3. am startservice -n com.xdja.containerservice/.AutoDisplayService
     *      → AutoDisplayService.onStart() poste MSG=1 → updateDisplay() dans
     *        son HandlerThread (uid 1000) → ContainerService.getQtProjectionDispInfo(0)
     *        retourne la Surface (cette fois valide car appel depuis uid 1000)
     *      → DisplayManager.createVirtualDisplay(...) crée display id=1
     *
     * Pré-requis :
     *   - Freedom doit être DÉSACTIVÉ avant le test (sinon on ne sait pas si le display
     *     vient de nous ou de lui).
     *
     * Critère de succès :
     *   - "fission_bg_xdjaVirtualSurface" présent dans le SNAPSHOT APRÈS
     *     ET absent dans le SNAPSHOT AVANT.
     *
     * Strictement actif côté shell (uid=2000), pas de modification système.
     */
    public static void bootstrapClusterDisplay(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("🧪 TEST 16 — Bootstrap actif du cluster (sans Freedom)\n");
                    sb.append("Pré-requis : Freedom OFF avant ce test.\n\n");

                    // ── 1. SNAPSHOT AVANT ────────────────────────────────────
                    sb.append("============ SNAPSHOT AVANT ============\n");
                    appendDumpsysSnapshot(dadb, sb);

                    // ── 2. CLEAR LOGCAT pour ne capter que notre tentative ───
                    dadb.shell("logcat -c 2>&1");

                    // ── 3. SÉQUENCE BOOTSTRAP ────────────────────────────────
                    sb.append("============ SÉQUENCE BOOTSTRAP ============\n");

                    sb.append("── [1/3] sendInfo(1000, 30) — Seal EU screen size ──\n");
                    AdbShellResponse r1 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    sb.append(safeOut(r1.getAllOutput())).append("\n");
                    Thread.sleep(500);

                    sb.append("── [2/3] sendInfo(1000, 16) — Qt standby (réveille Surface) ──\n");
                    AdbShellResponse r2 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 16 s16 \"\" 2>&1");
                    sb.append(safeOut(r2.getAllOutput())).append("\n");
                    Thread.sleep(1500);

                    sb.append("── [3/3] startservice AutoDisplayService → updateDisplay() ──\n");
                    AdbShellResponse r3 = dadb.shell(
                            "am startservice -n "
                            + "com.xdja.containerservice/.AutoDisplayService 2>&1");
                    sb.append(safeOut(r3.getAllOutput())).append("\n");

                    // ── 4. ATTENTE — création du display côté system ─────────
                    Thread.sleep(3000);

                    // ── 5. LOGCAT CIBLÉ ──────────────────────────────────────
                    sb.append("\n============ LOGCAT (5s) ============\n");
                    AdbShellResponse rLog = dadb.shell(
                            "logcat -d 2>&1 "
                            + "| grep -iE -B1 -A3 "
                            + "'xdja_AutoContainerService|xdja_AutoDisplayService|"
                            + "xdja_ContainerServiceJni|getQtProjectionDispInfo|"
                            + "createVirtualDisplay|fission_|FissionWindow|"
                            + "DisplayManagerService.*Display device added|"
                            + "ssc_skip startServiceLocked.*containerservice' "
                            + "| head -300");
                    sb.append(safeOut(rLog.getAllOutput())).append("\n");

                    // ── 6. SNAPSHOT APRÈS ────────────────────────────────────
                    sb.append("============ SNAPSHOT APRÈS ============\n");
                    appendDumpsysSnapshot(dadb, sb);

                    // ── 7. VERDICT RAPIDE ────────────────────────────────────
                    sb.append("============ VERDICT ============\n");
                    AdbShellResponse rV = dadb.shell(
                            "dumpsys display 2>/dev/null "
                            + "| grep -c 'fission_bg_xdjaVirtualSurface' || echo 0");
                    String count = safeOut(rV.getAllOutput());
                    sb.append("Occurrences 'fission_bg_xdjaVirtualSurface' : ").append(count).append("\n");
                    if (count.startsWith("0")) {
                        sb.append("❌ ÉCHEC : display 1 non créé. Voir LOGCAT ci-dessus.\n");
                    } else {
                        sb.append("✅ SUCCÈS : display 1 présent dans DisplayManager !\n");
                        sb.append("→ Tester maintenant un launchOnDisplay(1) depuis l'UI.\n");
                    }

                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "bootstrapClusterDisplay ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-bootstrap-cluster").start();
    }

    // ── TEST 17 : Lancement d'une app sur display 1 avec Freedom actif ───────

    /**
     * TEST 17 — Vérifie que notre trampoline peut placer une app tierce sur
     * display 1 quand Freedom est en cours d'exécution (= display 1 déjà créé).
     *
     * Séquence :
     *   1. Vérifie que display 1 existe (Freedom doit être ON)
     *   2. sendInfo(1000, 30) + sendInfo(1000, 16) → Qt standby
     *   3. am start --display 1 ClusterTrampolineActivity --es target_package <pkg>
     *   4. Attente 3s + logcat
     *   5. Verdict : app visible sur display 1 (dumpsys window displays) ?
     *
     * La callback est appelée sur le thread ADB (background).
     */
    public static void launchOnFreedomDisplay(final Context context,
            final String targetPackage, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("🧪 TEST 17 — Lancement sur display 1 (Freedom ON)\n");
                    sb.append("Cible : ").append(targetPackage).append("\n\n");

                    // ── 1. PRÉ-REQUIS : display 1 présent ? ────────────────
                    sb.append("============ PRÉ-REQUIS ============\n");
                    AdbShellResponse rChk = dadb.shell(
                            "dumpsys display 2>/dev/null"
                            + " | grep -c 'fission_bg_xdjaVirtualSurface' || echo 0");
                    String cnt = safeOut(rChk.getAllOutput());
                    boolean display1Exists = !cnt.trim().equals("0");
                    sb.append("Display 1 (fission_bg_xdjaVirtualSurface) : ")
                      .append(display1Exists ? "✅ PRÉSENT\n" : "❌ ABSENT — Freedom OFF ?\n");
                    if (!display1Exists) {
                        sb.append("\n⚠ Prérequis non rempli : démarrez Freedom d'abord.\n");
                        callback.onSuccess(sb.toString());
                        return;
                    }

                    // ── 2. SNAPSHOT AVANT ──────────────────────────────────
                    sb.append("\n============ SNAPSHOT AVANT ============\n");
                    appendDumpsysSnapshot(dadb, sb);

                    // ── 3. ACTIVATION QT STANDBY ──────────────────────────
                    sb.append("\n============ ACTIVATION QT STANDBY ============\n");
                    sb.append("── sendInfo(1000, 30) — Seal EU screen size ──\n");
                    AdbShellResponse r30 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    sb.append("Result: ").append(safeOut(r30.getAllOutput())).append("\n");
                    Thread.sleep(500);

                    sb.append("── sendInfo(1000, 16) — Qt standby ──\n");
                    AdbShellResponse r16 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 16 s16 \"\" 2>&1");
                    sb.append("Result: ").append(safeOut(r16.getAllOutput())).append("\n");
                    Thread.sleep(1500);

                    // ── 4. LANCEMENT TRAMPOLINE SUR DISPLAY 1 ──────────────
                    sb.append("\n============ LANCEMENT TRAMPOLINE ============\n");
                    String trampolineCmd = "am start --display 1 --windowingMode 5"
                            + " -n com.byd.myapp/.dashboard.ClusterTrampolineActivity"
                            + " --es target_package " + targetPackage + " 2>&1";
                    sb.append("Cmd : ").append(trampolineCmd).append("\n");
                    AdbShellResponse rLaunch = dadb.shell(trampolineCmd);
                    sb.append("Result: ").append(safeOut(rLaunch.getAllOutput())).append("\n");

                    // ── 5. ATTENTE + LOGCAT ────────────────────────────────
                    Thread.sleep(3000);
                    sb.append("\n============ LOGCAT (3s post-lancement) ============\n");
                    AdbShellResponse rLog = dadb.shell(
                            "logcat -d -t 80 2>/dev/null | grep -E '"
                            + "ClusterTrampolineActivity"
                            + "|ActivityManager.*display=1"
                            + "|ActivityManager.*" + targetPackage
                            + "|START.*display"
                            + "|xdja_Auto"
                            + "' | tail -30");
                    sb.append(safeOut(rLog.getAllOutput())).append("\n");

                    // ── 6. SNAPSHOT APRÈS ─────────────────────────────────
                    sb.append("\n============ SNAPSHOT APRÈS ============\n");
                    appendDumpsysSnapshot(dadb, sb);

                    // ── 7. VERDICT ─────────────────────────────────────────
                    sb.append("\n============ VERDICT ============\n");
                    AdbShellResponse rW = dadb.shell(
                            "dumpsys window displays 2>/dev/null | grep -A 30 'mDisplayId=1'");
                    String windowDump = safeOut(rW.getAllOutput());
                    sb.append("── window display=1 ──\n").append(windowDump).append("\n");

                    boolean trampolineOk = windowDump.contains("ClusterTrampolineActivity");
                    boolean targetOk     = windowDump.contains(targetPackage);
                    if (targetOk) {
                        sb.append("✅ SUCCÈS TOTAL : ").append(targetPackage)
                          .append(" visible sur display 1 !\n");
                    } else if (trampolineOk) {
                        sb.append("⚠ PARTIEL : trampoline présent sur display 1,\n")
                          .append("  mais ").append(targetPackage)
                          .append(" non encore détecté (timing ?).\n");
                    } else {
                        sb.append("❌ ÉCHEC : ni le trampoline ni ").append(targetPackage)
                          .append(" détectés sur display 1.\n");
                    }

                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "launchOnFreedomDisplay ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-freedom-launch").start();
    }

}
