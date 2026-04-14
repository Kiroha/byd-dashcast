package com.byd.myapp;

import android.content.Context;
import android.util.Log;

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

                AdbKeyPair keyPair;
                if (privateKey.exists() && publicKey.exists()) {
                    keyPair = AdbKeyPair.read(privateKey, publicKey);
                    AppLogger.log(TAG, "Clé ADB existante rechargée");
                } else {
                    AdbKeyPair.generate(privateKey, publicKey);
                    keyPair = AdbKeyPair.read(privateKey, publicKey);
                    AppLogger.log(TAG, "Nouvelle clé ADB générée → popup attendu");
                }

                AppLogger.log(TAG, "Connexion dadb → localhost:" + ADB_PORT + " …");
                Dadb dadb = Dadb.create("localhost", ADB_PORT, keyPair);
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

                dadb.close();
                AppLogger.log(TAG, "ADB local terminé ✓");
                callback.onSuccess(sb.toString());

            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                AppLogger.e(TAG, "Échec ADB local", e);
                AppLogger.log(TAG, "ADB local ERREUR — " + msg);
                callback.onError(msg);
            }
        }, "adb-local-thread").start();
    }

    // ── Helper privé — connexion dadb (clé déjà autorisée, pas de popup) ───────────
    private static Dadb connect(Context context) throws Exception {
        File privateKey = new File(context.getFilesDir(), "adb.key");
        File publicKey  = new File(context.getFilesDir(), "adb.pub");
        AdbKeyPair keyPair;
        if (privateKey.exists() && publicKey.exists()) {
            keyPair = AdbKeyPair.read(privateKey, publicKey);
        } else {
            AdbKeyPair.generate(privateKey, publicKey);
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
                try {
                    Dadb dadb = connect(context);
                    String cmd = "appops set " + context.getPackageName()
                            + " SYSTEM_ALERT_WINDOW allow";
                    AdbShellResponse r = dadb.shell(cmd + " 2>&1");
                    dadb.close();
                    AppLogger.i(TAG, "grantOverlayPermission → " + cmd
                            + " → '" + r.getAllOutput().trim() + "'");
                    callback.onSuccess(r.getAllOutput().trim());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "grantOverlayPermission ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-overlay-grant").start();
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
                try {
                    Dadb dadb = connect(context);
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

                    dadb.close();
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "runClusterProbe ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-probe-thread").start();
    }

    // ── TEST 10 : Test de restauration du cluster ──────────────────────────────
    /**
     * TEST 10 — Séquence complète activation + restauration cluster
     *
     * Séquence testée (identique au flow bouton "Envoyer app") :
     *   1. [Avant] am stack list display 1
     *   2. sendInfo(1000, 16) — Qt standby
     *   3. [Stack display 1 après activation]
     *   4. sendInfo(1000, 18) — fermer projection (投屏关闭)
     *   5. sendInfo(1000,  0) — rafraîchir flux Qt
     *   6. [Après] am stack list display 1
     *   7. Logcat AutoContainer
     */
    public static void runDisplayOneLaunch(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = AppLogger.startTiming();
                AppLogger.i(TAG, "runDisplayOneLaunch démarré [" + Thread.currentThread().getName() + "]");
                try {
                    Dadb dadb = connect(context);
                    StringBuilder sb = new StringBuilder();

                    // sendInfo via "service call AutoContainer" par ADB shell (uid=2000).
                    // POURQUOI ADB et pas ClusterManager directement :
                    //   xdja_AutoContainerService.checkSendPermissionAndAllowType() vérifie
                    //   le package name de l'appelant. Notre package com.byd.myapp n'est pas
                    //   dans /system/etc/container_comm_cfg.json → rejet "Not allowed package".
                    //   L'ADB shell (uid=2000) est autorisé sans vérification de package.

                    // 1. Stacks avant
                    sb.append("── [Avant] am stack list (display 1) ──\n");
                    AdbShellResponse rPre = dadb.shell(
                        "am stack list 2>&1 | grep -iE 'displayId=1|myapp|BYDDashboard' | head -15");
                    sb.append(rPre.getAllOutput().trim().isEmpty() ? "(aucun stack display 1)" : rPre.getAllOutput().trim()).append("\n");

                    // 2. sendInfo(53) — ADAS 2D toggle AVANT Qt standby [FIX v1.27]
                    // Cmd 53 = "2D ADAS切換" : masque l'overlay ADAS sur cluster 2D Seal EU
                    // pendant que la transition vers le mode projection a lieu.
                    // Si cmd 53 n'a pas d'effet sur cette ROM → résultat nul (parcel vide OK).
                    dadb.shell("logcat -c 2>&1");

                    // 2. sendInfo(16) — Qt standby via ADB shell
                    sb.append("\n── sendInfo(1000, 16) = Qt standby ──\n");
                    AdbShellResponse rSend16 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 16 s16 \"\" 2>&1");
                    sb.append(rSend16.getAllOutput().trim()).append("\n");
                    Thread.sleep(2000);

                    // 3. Supprimer les tasks TIERCES sur display 1 (PAS notre propre app).
                    // Bug précédent : la fenêtre ±8 lignes autour de "displayId=1" capturait
                    // notre propre tâche (com.byd.myapp) présente dans le stack list complet.
                    // Fix : exclure explicitement "com.byd.myapp" + afficher le stack brut pour debug.
                    sb.append("\n── am stack list (display 1) — brut ──\n");
                    AdbShellResponse rStack = dadb.shell("am stack list 2>&1");
                    String stkOutput = rStack.getAllOutput();
                    java.util.Set<String> tasksOnD1 = new java.util.LinkedHashSet<>();
                    String[] stkLines = stkOutput.split("\\r?\\n");
                    for (int si = 0; si < stkLines.length; si++) {
                        if (!stkLines[si].contains("displayId=1")) continue;
                        int lo = Math.max(0, si - 8), hi = Math.min(stkLines.length - 1, si + 8);
                        for (int sj = lo; sj <= hi; sj++) {
                            String lj = stkLines[sj];
                            // Exclure notre propre app — elle est sur display 0
                            if (lj.contains("com.byd.myapp")) continue;
                            String[] triggers = {"taskId=", "Task id #", "Task id#", "task #", "taskid="};
                            for (String tr : triggers) {
                                int idx = lj.indexOf(tr);
                                if (idx < 0) { String lc = lj.toLowerCase(); idx = lc.indexOf(tr.toLowerCase()); }
                                if (idx >= 0) {
                                    String after = lj.substring(idx + tr.length()).trim();
                                    StringBuilder num = new StringBuilder();
                                    for (int c = 0; c < after.length() && Character.isDigit(after.charAt(c)); c++)
                                        num.append(after.charAt(c));
                                    if (num.length() > 0) { tasksOnD1.add(num.toString()); break; }
                                }
                            }
                        }
                    }
                    // Afficher le stack brut (filtré display 1) pour diagnostic
                    boolean d1Found = false;
                    for (String l : stkLines) {
                        if (l.contains("displayId=1") || (d1Found && (l.contains("taskId=") || l.contains("bounds=")))) {
                            sb.append(l).append("\n");
                            d1Found = l.contains("displayId=1");
                        }
                    }
                    if (sb.toString().endsWith("── am stack list (display 1) — brut ──\n")) {
                        sb.append("(aucun stack displayId=1 trouvé)\n");
                    }
                    if (!tasksOnD1.isEmpty()) {
                        sb.append("→ Tasks à retirer : " + tasksOnD1 + "\n");
                        for (String tid : tasksOnD1) {
                            dadb.shell("am task remove " + tid + " 2>&1");
                        }
                        Thread.sleep(1000);
                    } else {
                        sb.append("(aucun task tiers sur display 1)\n");
                    }

                    // 5. sendInfo(1000, 18) — FERMER la projection (投屏关闭)
                    sb.append("\n── sendInfo(1000, 18) = fermer projection (投屏关闭) ──\n");
                    AdbShellResponse rSend18 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 18 s16 \"\" 2>&1");
                    sb.append(rSend18.getAllOutput().trim()).append("\n");
                    Thread.sleep(1000);

                    // 5b. sendInfo(1000, 0) — rafraîchir flux vidéo Qt
                    sb.append("\n── sendInfo(1000, 0) = rafraîchir flux Qt ──\n");
                    AdbShellResponse rSend0 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append(rSend0.getAllOutput().trim()).append("\n");
                    Thread.sleep(500);

                    // 5. Stacks après
                    sb.append("\n── [Après] am stack list (display 1) ──\n");
                    AdbShellResponse rPost = dadb.shell(
                        "am stack list 2>&1 | grep -iE 'displayId=1|myapp|BYDDashboard' | head -10");
                    sb.append(rPost.getAllOutput().trim().isEmpty() ? "(aucun stack display 1 ✓)" : rPost.getAllOutput().trim()).append("\n");

                    // 6. Logcat
                    sb.append("\n── Logcat (AutoContainer + myapp) ──\n");
                    AdbShellResponse rLog = dadb.shell(
                        "logcat -d 2>&1 | grep -iE 'AutoContainer|sendInfo|BYDDashboard|myapp' | tail -15");
                    sb.append(rLog.getAllOutput().trim().isEmpty() ? "(aucune entrée)" : rLog.getAllOutput().trim()).append("\n");

                    dadb.close();
                    AppLogger.endTiming(TAG, t0, "runDisplayOneLaunch terminé");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "runDisplayOneLaunch ERREUR : " + msg);
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
    public static void restoreBydOnCluster(final Context context, final int displayId,
            final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AppLogger.log(TAG, "Restauration BYD display=" + displayId + " ...");
                    Dadb dadb = connect(context);
                    StringBuilder sb = new StringBuilder();

                    // 1. Trouver le taskId de notre app sur le display cluster
                    AdbShellResponse rTask = dadb.shell(
                        "am stack list 2>&1 | grep -B5 'com.byd.myapp' | grep -iE 'Task id|taskId' | tail -1");
                    String taskIdStr = rTask.getAllOutput().trim().replaceAll("[^0-9]", "").trim();
                    AppLogger.log(TAG, "taskId com.byd.myapp : '" + taskIdStr + "'");

                    if (!taskIdStr.isEmpty()) {
                        // 2. Retirer la task de display 1 sans tuer le processus
                        AdbShellResponse rRemove = dadb.shell(
                            "am task remove " + taskIdStr + " 2>&1 && echo TASK_REMOVED");
                        sb.append(rRemove.getAllOutput().trim()).append("\n");
                        dadb.shell("sleep 1");
                        AppLogger.log(TAG, "am task remove " + taskIdStr + " -> " + rRemove.getAllOutput().trim());
                    } else {
                        sb.append("(taskId com.byd.myapp non trouvé — am task remove ignoré)\n");
                        AppLogger.log(TAG, "taskId non trouvé — am task remove ignoré");
                    }

                    // 3. Restaurer le rendu Qt BYD natif (séquence correcte : cmd=18 puis cmd=0)
                    AdbShellResponse rStop = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 18 s16 \"\" 2>&1");
                    sb.append("sendInfo(18) : ").append(rStop.getAllOutput().trim()).append("\n");
                    dadb.shell("sleep 1");
                    AdbShellResponse rRestore = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append("sendInfo(0)  : ").append(rRestore.getAllOutput().trim()).append("\n");
                    dadb.shell("sleep 1");
                    AppLogger.log(TAG, "sendInfo(18+0) -> " + rRestore.getAllOutput().trim());

                    dadb.close();
                    boolean ok = !taskIdStr.isEmpty() || rRestore.getAllOutput().contains("00000000");
                    AppLogger.log(TAG, "restoreBydOnCluster -> " + (ok ? "OK" : "ÉCHEC"));
                    if (ok) callback.onSuccess("BYD restauré \u2713\n" + sb);
                    else    callback.onError(sb.toString());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "restoreBydOnCluster ERREUR", e);
                    AppLogger.log(TAG, "restoreBydOnCluster ERREUR — " + msg);
                    callback.onError(msg);
                }
            }
        }, "adb-restore-thread").start();
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
                try {
                    AppLogger.log(TAG, "runAutoContainerWhitelistProbe démarré");
                    Dadb dadb = connect(context);
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

                    dadb.close();
                    AppLogger.log(TAG, "runAutoContainerWhitelistProbe terminé");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
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
                try {
                    Dadb dadb = connect(context);
                    String safeStr = (infoStr != null ? infoStr : "").replace("\"", "\\\"");
                    String cmd = "service call AutoContainer 2 i32 " + type
                               + " i32 " + infoInt + " s16 \"" + safeStr + "\" 2>&1";
                    AppLogger.log(TAG, "sendInfo ADB: " + cmd);
                    String out;
                    try {
                        AdbShellResponse r = dadb.shell(cmd);
                        out = r.getAllOutput().trim();
                    } finally {
                        dadb.close();
                    }
                    AppLogger.log(TAG, "sendInfo ADB(" + type + "," + infoInt + ") → " + out);
                    if (callback != null) callback.onSuccess(out);
                } catch (Exception e) {
                    AppLogger.e(TAG, "sendInfo ADB ERREUR", e);
                    if (callback != null) callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, "adb-sendinfo-thread").start();
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
                try {
                    AppLogger.log(TAG, "forceStop " + packageName + " ...");
                    Dadb dadb = connect(context);
                    AdbShellResponse r = dadb.shell("am force-stop " + packageName + " 2>&1 && echo STOPPED");
                    dadb.close();
                    String out = r.getAllOutput().trim();
                    AppLogger.log(TAG, "am force-stop " + packageName + " -> " + out);
                    if (out.contains("STOPPED") || out.isEmpty()) {
                        callback.onSuccess("force-stop OK");
                    } else {
                        callback.onError(out);
                    }
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "forceStopApp ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-forcestop-thread").start();
    }
}
