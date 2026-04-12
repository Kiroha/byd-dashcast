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

                // _COMMON : si dangerous → grant OK ; si signature → même erreur que _GET
                // _GET    : signature confirmés — toujours refusés via pm grant
                String[] commonPerms = {
                    "android.permission.BYDAUTO_SPEED_COMMON",
                    "android.permission.BYDAUTO_ENERGY_COMMON",
                    "android.permission.BYDAUTO_GEARBOX_COMMON",
                };
                String[] getPerms = {
                    "android.permission.BYDAUTO_SPEED_GET",
                    "android.permission.BYDAUTO_ENERGY_GET",
                    "android.permission.BYDAUTO_GEARBOX_GET",
                };
                sb.append("── pm grant _COMMON (test type dangerous vs signature) ──\n");
                for (String perm : commonPerms) {
                    String shortName = perm.replace("android.permission.BYDAUTO_", "");
                    AdbShellResponse r = dadb.shell("pm grant " + pkg + " " + perm + " 2>&1 && echo GRANTED || echo DENIED");
                    String out = r.getAllOutput().trim();
                    sb.append(shortName).append(": ").append(
                        out.contains("GRANTED") ? "OK ✓ (dangerous — accordée)" :
                        out.contains("not a changeable") ? "SIGNATURE — non accordable via pm" :
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
                Log.e(TAG, "Échec ADB local", e);
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

    // ── Activation du cluster (commandes extraites du DEX Freedom v1.9) ──────
    /**
     * Libère le cluster BYD en relançant com.byd.automap sur le display principal.
     * Après ~2 s, le cluster apparaît comme display Presentation Android standard
     * et DashboardDisplayHelper.onDashboardDisplayConnected() se déclenche.
     */
    public static void activateCluster(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AppLogger.log(TAG, "Activation cluster → commandes Freedom...");
                    Dadb dadb = connect(context);
                    // Commandes extraites du DEX de Freedom v1.9
                    dadb.shell("am start-activity -S -W"
                            + " -n \"com.byd.automap/com.byd.automap.activity.StartupActivity\""
                            + " 2>&1");
                    dadb.shell("am start-activity -S -W"
                            + " -n \"com.byd.launchermap/com.byd.automap.activity.MainActivity\""
                            + " 2>&1");
                    // Laisser le système enregistrer le nouveau display
                    Thread.sleep(2500);
                    AdbShellResponse rDisplays = dadb.shell(
                            "dumpsys display 2>/dev/null | grep -E 'mDisplayId|uniqueId' | head -10");
                    dadb.close();
                    AppLogger.log(TAG, "Cluster libéré ✓ — attente détection Android...");
                    callback.onSuccess("Cluster libéré ✓\n" + rDisplays.getAllOutput()
                            + "\n→ Le display apparaît dans 2–3 secondes.");
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Log.e(TAG, "activateCluster ERREUR", e);
                    AppLogger.log(TAG, "activateCluster ERREUR — " + msg);
                    callback.onError(msg);
                }
            }
        }, "adb-activate-thread").start();
    }

    // ── Lancement sur cluster en plein écran ──────────────────────────────────
    /**
     * Lance une app sur le cluster en plein écran via ADB shell.
     * Contourne les problèmes de permission signatureOrPrivileged liés à
     * setLaunchDisplayId() et garantit le mode FULLSCREEN (windowing_mode=1).
     *
     * @param displayId  ID du display cluster (fourni par DashboardDisplayHelper)
     * @param component  Composant flattenToShortString(), ex. "com.pkg/.MainActivity"
     */
    public static void launchOnCluster(final Context context, final int displayId,
            final String component, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Dadb dadb = connect(context);
                    String cmd = "am start-activity -S -W --display " + displayId
                            + " --windowingMode 5"
                            + " -n \"" + component + "\" 2>&1 && echo LAUNCH_OK";
                    AdbShellResponse r = dadb.shell(cmd);
                    dadb.close();
                    boolean ok = r.getAllOutput().contains("LAUNCH_OK");
                    AppLogger.log(TAG, "launchOnCluster " + component
                            + " display=" + displayId + " → " + (ok ? "OK" : "ÉCHEC"));
                    if (ok) callback.onSuccess(r.getAllOutput());
                    else    callback.onError(r.getAllOutput());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Log.e(TAG, "launchOnCluster ERREUR", e);
                    AppLogger.log(TAG, "launchOnCluster ERREUR — " + msg);
                    callback.onError(msg);
                }
            }
        }, "adb-launch-thread").start();
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
                    Log.e(TAG, "runClusterProbe ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-probe-thread").start();
    }

    // ── TEST 7 : Sonde autoservice (android.gui.BYDAutoServer) ───────────────
    /**
     * TEST 7 — RÉSULTATS OBTENUS :
     *   • service call autoservice 1/2/3 → ffffffff (SecurityException — shell non signé XDJA)
     *   • MAIS logcat montre : BYDAutoService::enableDevice + disableDevice appelés → hardware atteint !
     *   • magicwindow = whitelist d'apps chinoises autorisées (hors sujet cluster)
     *   • stop adbd : requires root — ADB TCP non activable par shell standard
     *
     * Active aussi ADB TCP via setprop uniquement (sans stop/start adbd qui requiert root).
     */
    public static void runAutoServiceProbe(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Dadb dadb = connect(context);
                    StringBuilder sb = new StringBuilder();

                    // ── 1. IP WiFi + setprop ADB TCP (sans stop adbd — pas root) ──
                    dadb.shell("setprop service.adb.tcp.port 5555 2>&1");
                    sb.append("── IP WiFi (pour adb connect depuis PC) ──\n");
                    AdbShellResponse rIp = dadb.shell("ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print $2}' | cut -d/ -f1");
                    String wifiIp = rIp.getAllOutput().trim();
                    sb.append(wifiIp.isEmpty() ? "(WiFi non connecté)" : wifiIp).append("\n");
                    sb.append("→ setprop service.adb.tcp.port 5555 positionné\n");
                    sb.append("→ Redémarrez le service ADB manuellement ou reboot adbd\n");
                    sb.append("→ Depuis PC : adb connect ").append(wifiIp).append(":5555\n");
                    sb.append("  puis : adb pull /sdcard/com.byd.car.server.apk\n");

                    // ── 2. Décrire les services Binder ciblés ────────────────────
                    sb.append("\n── service call autoservice 1 (describe) ──\n");
                    AdbShellResponse rDesc = dadb.shell("service call autoservice 1 2>&1");
                    sb.append(rDesc.getAllOutput().trim()).append("\n");

                    sb.append("\n── service call autoservice 2 ──\n");
                    AdbShellResponse rT2 = dadb.shell("service call autoservice 2 2>&1");
                    sb.append(rT2.getAllOutput().trim()).append("\n");

                    // ── 3. Transaction 3 FLAG_ONEWAY — MÊME appel que Freedom ────
                    sb.append("\n── service call autoservice 3 (= appel Freedom) ──\n");
                    sb.append("(NB: ffffffff=SecurityException attendu, mais enableDevice/disableDevice logué)\n");
                    dadb.shell("logcat -c 2>&1"); // vider le logcat avant
                    AdbShellResponse rT3 = dadb.shell("service call autoservice 3 2>&1");
                    sb.append(rT3.getAllOutput().trim()).append("\n");

                    // ── 4. Logcat post-appel ──────────────────────────────────────
                    sb.append("\n── Logcat post-appel ──\n");
                    dadb.shell("sleep 1");
                    AdbShellResponse rLog = dadb.shell(
                        "logcat -d -t 300 2>&1 | grep -iE 'BYDAutoServer|autoservice"
                        + "|IBYDAcquisition|com\\.byd\\.appserver|com\\.byd\\.car\\.server"
                        + "|cluster|xdja' | tail -20");
                    String logOut = rLog.getAllOutput().trim();
                    sb.append(logOut.isEmpty() ? "(aucune entrée)" : logOut).append("\n");

                    // ── 5. dumpsys acquisitionsrv ─────────────────────────────────
                    sb.append("\n── dumpsys acquisitionsrv (head 20) ──\n");
                    AdbShellResponse rAcq = dadb.shell("dumpsys acquisitionsrv 2>&1 | head -20");
                    sb.append(rAcq.getAllOutput().trim().isEmpty() ? "(vide)" : rAcq.getAllOutput().trim()).append("\n");

                    dadb.close();
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Log.e(TAG, "runAutoServiceProbe ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-autoservice-thread").start();
    }

    // ── TEST 8 : Activer la projection cluster via AutoContainerManager ─────────
    /**
     * TEST 8 — Architecture cluster BYD Seal confirmée :
     *
     *   Le cluster a DEUX modes :
     *   • "Simple mode"  : MCU XDJA pilote le cluster directement (Android absent/redémarrage)
     *   • "Normal mode"  : Android pilote le cluster via AutoContainerService → Qt renderer → surface hw
     *     Quand Android est démarré, le cluster est DÉJÀ en Normal mode en permanence.
     *     sendInfo() ne "démarre" pas le cluster — il change la SOURCE de la surface déjà active.
     *
     *   Commandes (from clusterdebug reverse engineering) :
     *   • sendInfo(1000, 16, "") = Qt renderer libère la surface → plein écran projection Android
     *   • sendInfo(1000,  1, "") = déconnecter flux natif Qt (surface disponible pour VirtualDisplay)
     *   • sendInfo(1000,  0, "") = restaurer le rendu Qt BYD natif
     *   • AutoDisplayService crée un VirtualDisplay (flags=PUBLIC|PRESENTATION=11) sur la surface Qt
     *
     *   Risque : TRÈS FAIBLE. Pire cas = cluster revient en Simple mode (comportement normal de boot).
     *   La vraie barrière : whitelist /system/etc/container_comm_cfg.json + vérification signature XDJA.
     *
     * Ce test :
     *   1. Lit container_comm_cfg.json (whitelist — notre package y est ?)
     *   2. service call AutoContainer 2 i32 1000 i32 16 (plein écran projection)
     *   3. Lance AutoDisplayService
     *   4. Attend 2s puis interroge displaysys pour le nouveau VirtualDisplay
     *   5. Restaure BYD natif automatiquement (sendInfo 0)
     */
    public static void runClusterActivation(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Dadb dadb = connect(context);
                    StringBuilder sb = new StringBuilder();

                    // ── 1. Lire la whitelist JSON ─────────────────────────────────
                    sb.append("── /system/etc/container_comm_cfg.json ──\n");
                    AdbShellResponse rJson = dadb.shell("cat /system/etc/container_comm_cfg.json 2>&1");
                    String jsonContent = rJson.getAllOutput().trim();
                    sb.append(jsonContent.isEmpty() ? "(fichier absent ou vide)" : jsonContent).append("\n");

                    // ── 2. SurfaceFlinger baseline (layers cluster avant appel) ──
                    sb.append("\n── SurfaceFlinger layers baseline ──\n");
                    AdbShellResponse rSfPre = dadb.shell("dumpsys SurfaceFlinger 2>&1 | grep -iE 'cluster|qt|projection|xdja|virtual|mirror' | head -15");
                    sb.append(rSfPre.getAllOutput().trim().isEmpty() ? "(aucun layer cluster)" : rSfPre.getAllOutput().trim()).append("\n");

                    // ── 3. sendInfo(1000, 16, "") via service call AutoContainer ─
                    // Transactions AIDL (IAutoContainer) : sendJson=1, sendInfo=2, sendInfo2=3, registerCallback=4
                    // NB: "AutoContainer" est le nom EXACT dans ServiceManager (case-sensitive)
                    dadb.shell("logcat -c 2>&1"); // vider logcat avant appel
                    sb.append("\n── service call AutoContainer 2 i32 1000 i32 16 s16 \"\" ──\n");
                    sb.append("(plein écran projection — observer le cluster)\n");
                    AdbShellResponse rAc16 = dadb.shell("service call AutoContainer 2 i32 1000 i32 16 s16 \"\" 2>&1");
                    sb.append(rAc16.getAllOutput().trim()).append("\n");
                    // Variante sans string si ffffffff (certains shells rejettent s16 vide)
                    if (rAc16.getAllOutput().contains("ffffffff")) {
                        sb.append("→ Variante sans s16 : ");
                        AdbShellResponse rAc16b = dadb.shell("service call AutoContainer 2 i32 1000 i32 16 2>&1");
                        sb.append(rAc16b.getAllOutput().trim()).append("\n");
                    }

                    // ── 4. Démarrer AutoDisplayService ───────────────────────────
                    sb.append("\n── startservice AutoDisplayService ──\n");
                    AdbShellResponse rSvc = dadb.shell("am startservice -n com.xdja.containerservice/.AutoDisplayService 2>&1");
                    sb.append(rSvc.getAllOutput().trim()).append("\n");

                    // ── 5. Attendre 2s puis vérifier les displays ─────────────────
                    dadb.shell("sleep 2");
                    sb.append("\n── Displays après sendInfo+startService ──\n");
                    AdbShellResponse rDisp = dadb.shell("dumpsys display | grep -E 'mDisplayId|uniqueId|name=|type=|state=|FLAG_PRESENTATION|FLAG_PUBLIC' 2>&1 | head -30");
                    sb.append(rDisp.getAllOutput().trim()).append("\n");

                    // ── 6. SurfaceFlinger après appel ────────────────────────────
                    sb.append("\n── SurfaceFlinger layers après ──\n");
                    AdbShellResponse rSfPost = dadb.shell("dumpsys SurfaceFlinger 2>&1 | grep -iE 'cluster|qt|projection|xdja|virtual|mirror' | head -15");
                    sb.append(rSfPost.getAllOutput().trim().isEmpty() ? "(aucun layer cluster)" : rSfPost.getAllOutput().trim()).append("\n");

                    // ── 7. Logcat xdja_AutoDisplayService ────────────────────────
                    sb.append("\n── Logcat AutoContainer/AutoDisplay ──\n");
                    AdbShellResponse rLog = dadb.shell("logcat -d 2>&1 | grep -iE 'xdja_Auto|AutoContainer|AutoDisplay|createVirtual|QtProjection|container_comm|whiteList|sendInfo' | tail -30");
                    String logOut = rLog.getAllOutput().trim();
                    sb.append(logOut.isEmpty() ? "(aucune entrée)" : logOut).append("\n");

                    // ── 8. Essai sendInfo(1000,1) = déconnecter flux natif ────────
                    sb.append("\n── service call AutoContainer 2 i32 1000 i32 1 s16 \"\" ──\n");
                    sb.append("(déconnecter flux natif — observer le cluster)\n");
                    AdbShellResponse rAc1 = dadb.shell("service call AutoContainer 2 i32 1000 i32 1 s16 \"\" 2>&1");
                    sb.append(rAc1.getAllOutput().trim()).append("\n");

                    // ── 9. Displays final ─────────────────────────────────────────
                    dadb.shell("sleep 1");
                    sb.append("\n── Displays final ──\n");
                    AdbShellResponse rDisp2 = dadb.shell("dumpsys display 2>&1 | grep -E 'mDisplayId|uniqueId|name=|type=|FLAG_PRESENTATION' | head -20");
                    sb.append(rDisp2.getAllOutput().trim()).append("\n");

                    // ── 10. Restaurer le cluster BYD ─────────────────────────────
                    // FIX : AutoDisplayService doit être arrêté AVANT sendInfo(0),
                    // sinon il re-pousse immédiatement la commande de projection.
                    sb.append("\n── Arrêt AutoDisplayService (requis avant restauration) ──\n");
                    AdbShellResponse rStop = dadb.shell("am stopservice -n com.xdja.containerservice/.AutoDisplayService 2>&1");
                    sb.append(rStop.getAllOutput().trim()).append("\n");
                    dadb.shell("sleep 1");

                    sb.append("\n── Restauration BYD sendInfo(1000,0) ──\n");
                    AdbShellResponse rRestore = dadb.shell("service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append(rRestore.getAllOutput().trim()).append("\n");
                    // Laisser le temps à Qt de reprendre le rendu
                    dadb.shell("sleep 1");

                    dadb.close();
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Log.e(TAG, "runClusterActivation ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-cluster-activation-thread").start();
    }

    // ── TEST 9 : Détection VirtualDisplay + diagnostic AutoDisplayService ─────
    /**
     * TEST 9 — Comprendre pourquoi le VirtualDisplay du cluster n'apparaît pas dans
     * DisplayManager après sendInfo(1000,16) + startService AutoDisplayService.
     *
     * Théories testées :
     *   A) Le VirtualDisplay n'apparaît qu'avec bindService (pas startService)
     *   B) Il faut déclencher registerCallback (transaction #4) pour que le
     *      service crée le VirtualDisplay
     *   C) Le VirtualDisplay a un type=VIRTUAL mais non lié à DISPLAY_CATEGORY_PRESENTATION
     *   D) Il faut plus de temps (polling 500ms × 10)
     *
     * Ce test :
     *   1. Vide logcat, puis sendInfo(1000,16)
     *   2. Polling dumpsys display toutes les 500ms pendant 5s → cherche type=VIRTUAL
     *   3. Essai transaction #4 (registerCallback simulée) sur AutoContainer
     *   4. nouveau polling 3s
     *   5. dumpsys SurfaceFlinger pour voir les layers cluster
     *   6. pm dump com.xdja.containerservice (état du service)
     *   7. sendInfo(1000,0) restauration
     */
    public static void runVirtualDisplayProbe(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Dadb dadb = connect(context);
                    StringBuilder sb = new StringBuilder();

                    // ── 1. Préparation ───────────────────────────────────────────
                    dadb.shell("logcat -c 2>&1");
                    sb.append("── service list | grep -i auto ──\n");
                    AdbShellResponse rSvcList = dadb.shell("service list 2>&1 | grep -i auto");
                    sb.append(rSvcList.getAllOutput().trim()).append("\n");

                    // ── 2. sendInfo(1000, 16) ────────────────────────────────────
                    sb.append("\n── sendInfo(1000, 16) ──\n");
                    AdbShellResponse rSend = dadb.shell("service call AutoContainer 2 i32 1000 i32 16 s16 \"\" 2>&1");
                    sb.append(rSend.getAllOutput().trim()).append("\n");

                    // ── 3. Polling display toutes les 500ms sur 5s ───────────────
                    sb.append("\n── Polling displays (10 × 500ms) ──\n");
                    for (int i = 1; i <= 10; i++) {
                        dadb.shell("sleep 0.5");
                        AdbShellResponse rPoll = dadb.shell(
                            "dumpsys display 2>&1 | grep -E 'mDisplayId|type=VIRTUAL|FLAG_PRESENTATION|uniqueId'");
                        String pollOut = rPoll.getAllOutput().trim();
                        boolean hasVirtual = pollOut.contains("VIRTUAL") || pollOut.contains("mDisplayId=1");
                        sb.append("[").append(i * 500).append("ms] ")
                          .append(hasVirtual ? "⚡ VIRTUAL TROUVÉ : " + pollOut : "(pas de VIRTUAL)")
                          .append("\n");
                        if (hasVirtual) break;
                    }

                    // ── 4. Essai transaction #4 (registerCallback) ───────────────
                    // Envoyer une transaction vide pour voir si ça déclenche la création
                    sb.append("\n── service call AutoContainer 4 (registerCallback) ──\n");
                    AdbShellResponse rCb = dadb.shell("service call AutoContainer 4 2>&1");
                    sb.append(rCb.getAllOutput().trim()).append("\n");

                    // ── 5. Nouveau polling 3s ─────────────────────────────────────
                    sb.append("\n── Polling post-registerCallback (6 × 500ms) ──\n");
                    for (int i = 1; i <= 6; i++) {
                        dadb.shell("sleep 0.5");
                        AdbShellResponse rPoll2 = dadb.shell(
                            "dumpsys display 2>&1 | grep -E 'mDisplayId|type=VIRTUAL|FLAG_PRESENTATION'");
                        String pOut = rPoll2.getAllOutput().trim();
                        boolean v = pOut.contains("VIRTUAL") || pOut.contains("mDisplayId=1");
                        sb.append("[").append(i * 500).append("ms] ")
                          .append(v ? "⚡ VIRTUAL : " + pOut : "(pas de VIRTUAL)")
                          .append("\n");
                        if (v) break;
                    }

                    // ── 6. SurfaceFlinger layers ──────────────────────────────────
                    sb.append("\n── SurfaceFlinger layers (virtual|cluster|qt|xdja) ──\n");
                    AdbShellResponse rSf = dadb.shell(
                        "dumpsys SurfaceFlinger 2>&1 | grep -iE 'virtual|cluster|qt|xdja|mirror|projection' | head -20");
                    sb.append(rSf.getAllOutput().trim().isEmpty() ? "(aucun)" : rSf.getAllOutput().trim()).append("\n");

                    // ── 7. État service containerservice ─────────────────────────
                    sb.append("\n── pm dump com.xdja.containerservice (head 30) ──\n");
                    AdbShellResponse rPm = dadb.shell("dumpsys activity services com.xdja.containerservice 2>&1 | head -30");
                    sb.append(rPm.getAllOutput().trim()).append("\n");

                    // ── 8. Logcat AutoContainer ───────────────────────────────────
                    sb.append("\n── Logcat AutoContainer (depuis vidage) ──\n");
                    AdbShellResponse rLog = dadb.shell(
                        "logcat -d 2>&1 | grep -iE 'AutoContainer|AutoDisplay|xdja_Auto|VirtualDisplay|createDisplay|sendInfo|whiteList' | tail -25");
                    sb.append(rLog.getAllOutput().trim().isEmpty() ? "(aucune entrée)" : rLog.getAllOutput().trim()).append("\n");

                    // ── 9. Restauration ───────────────────────────────────────────
                    sb.append("\n── Arrêt AutoDisplayService (avant restauration) ──\n");
                    AdbShellResponse rStop9 = dadb.shell("am stopservice -n com.xdja.containerservice/.AutoDisplayService 2>&1");
                    sb.append(rStop9.getAllOutput().trim()).append("\n");
                    dadb.shell("sleep 1");

                    sb.append("\n── Restauration sendInfo(1000,0) ──\n");
                    AdbShellResponse rRestore = dadb.shell("service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append(rRestore.getAllOutput().trim()).append("\n");
                    dadb.shell("sleep 1");

                    dadb.close();
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Log.e(TAG, "runVirtualDisplayProbe ERREUR", e);
                    callback.onError(msg);
                }
            }
        }, "adb-vd-probe-thread").start();
    }

    // ── TEST 10 : Lancement sur display 1 (cluster) via am + setLaunchDisplayId ──
    /**
     * TEST 10 — Lancement BYDDashboardActivity sur display 1 (cluster)
     *
     *   RÉSULTATS TEST 10 PHYSIQUE (11/04/2026) :
     *     - cmd=16 = BONNE commande → Qt standby → display 1 RESTE dans IActivityManager
     *     - Lancement OK (activité visible sur cluster)
     *     - PROBLÈME 1 : --windowingMode 1 (FULLSCREEN) refusé par DiLink 3.0
     *              → petite fenêtre flottante au lieu du plein écran
     *     - PROBLÈME 2 : sendInfo(0) seul ne restaure pas BYD Qt
     *              → l'activité est encore en premier plan sur display 1
     *
     *   FIX 1 (plein écran) :
     *     → windowingMode=5 (FREEFORM) + am task resize <taskId> 0 0 <W> <H>
     *     Technique identique à Byd Dashboard APK v1.10.5 (com.byd.mecanum.dashboard)
     *
     *   FIX 2 (restauration) :
     *     → Lancer automap AVEC --display 1 pour déplacer notre activité
     *     → Puis sendInfo(1000, 0) pour que Qt reprenne le contrôle
     *
     *   Séquence :
     *   1. sendInfo(1000, 16) — Qt standby
     *   2. am start-activity --windowingMode 5 --display 1 BYDDashboardActivity
     *   3. am task resize <taskId> 0 0 <realW> <realH>
     *   4. Restauration : automap --display 1, puis sendInfo(1000, 0)
     */
    public static void runDisplayOneLaunch(final Context context, final Callback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Dadb dadb = connect(context);
                    StringBuilder sb = new StringBuilder();

                    // ── 1. Vérifier les stacks sur display 1 avant ───────────────
                    sb.append("── am stack list | grep displayId (avant) ──\n");
                    AdbShellResponse rStackPre = dadb.shell("am stack list 2>&1 | grep -i 'displayId\\|display_id\\|Stack #' | head -20");
                    sb.append(rStackPre.getAllOutput().trim().isEmpty() ? "(aucun stack)" : rStackPre.getAllOutput().trim()).append("\n");

                    // ── 2. sendInfo(1000, 16) = Qt standby, display 1 reste dans IActivityManager ───────
                    //    NE PAS utiliser cmd=1 : déconnecte Qt entièrement → display 1 disparaît
                    dadb.shell("logcat -c 2>&1");
                    sb.append("\n── sendInfo(1000, 16) = 全屏投屏开启 (Qt standby) ──\n");
                    AdbShellResponse rSend = dadb.shell("service call AutoContainer 2 i32 1000 i32 16 s16 \"\" 2>&1");
                    sb.append(rSend.getAllOutput().trim()).append("\n");
                    dadb.shell("sleep 1");

                    // 3. Taille réelle du display 1 (regex sur mOverrideDisplayInfo)
                    //    Technique identique à Byd Dashboard APK v1.10.5 (com.byd.mecanum.dashboard).
                    AdbShellResponse rDispRaw = dadb.shell(
                        "dumpsys display 2>&1 | grep -A20 'mDisplayId=1' | grep -oE 'real [0-9]+ x [0-9]+' | head -1");
                    String dispLine = rDispRaw.getAllOutput().trim();
                    if (dispLine.isEmpty()) {
                        AdbShellResponse rFallback = dadb.shell(
                            "dumpsys display 2>&1 | grep -oE 'real [0-9]+ x [0-9]+' | tail -1");
                        dispLine = rFallback.getAllOutput().trim();
                    }
                    int dispW = 1920, dispH = 480; // défaut BYD Seal cluster
                    java.util.regex.Matcher mSize = java.util.regex.Pattern
                        .compile("real (\\d+) x (\\d+)").matcher(dispLine);
                    if (mSize.find()) {
                        try { dispW = Integer.parseInt(mSize.group(1)); } catch (Exception ignored2) {}
                        try { dispH = Integer.parseInt(mSize.group(2)); } catch (Exception ignored2) {}
                    }
                    sb.append("\n-- Dimensions display 1 --\n");
                    sb.append(dispLine.isEmpty() ? "(non détecté, fallback " + dispW + "x" + dispH + ")" : dispLine)
                      .append(" retenu : ").append(dispW).append("x").append(dispH).append("\n");

                    // 4. Lancement en mode FREEFORM (5)
                    //    windowingMode=1 (FULLSCREEN) refusé par DiLink 3.0 — petite fenêtre.
                    //    windowingMode=5 (FREEFORM) + am task resize = plein écran Seal EU.
                    dadb.shell("logcat -c 2>&1");
                    sb.append("\n-- am start-activity --windowingMode 5 --display 1 (FREEFORM) --\n");
                    AdbShellResponse rLaunch = dadb.shell(
                        "am start-activity -W --windowingMode 5 --display 1"
                        + " -n com.byd.myapp/.dashboard.BYDDashboardActivity 2>&1");
                    sb.append(rLaunch.getAllOutput().trim()).append("\n");
                    dadb.shell("sleep 1");

                    // 5. Récupérer le taskId de BYDDashboardActivity
                    sb.append("\n-- Task ID BYDDashboard sur display 1 --\n");
                    AdbShellResponse rTask = dadb.shell(
                        "am stack list 2>&1 | grep -B5 'BYDDashboard\\|com.byd.myapp' | grep -iE 'Task id|taskId' | tail -1");
                    sb.append(rTask.getAllOutput().trim().isEmpty() ? "(Task non trouvé)" : rTask.getAllOutput().trim()).append("\n");
                    String taskIdStr = rTask.getAllOutput().trim().replaceAll("[^0-9]", "").trim();

                    if (!taskIdStr.isEmpty()) {
                        // 6. am task resize → plein écran
                        sb.append("\n-- am task resize ").append(taskIdStr)
                          .append(" 0 0 ").append(dispW).append(" ").append(dispH).append(" --\n");
                        AdbShellResponse rResize = dadb.shell(
                            "am task resize " + taskIdStr + " 0 0 " + dispW + " " + dispH + " 2>&1");
                        sb.append(rResize.getAllOutput().trim().isEmpty() ? "(OK)" : rResize.getAllOutput().trim()).append("\n");
                        dadb.shell("sleep 1");
                    } else {
                        sb.append("-> taskId non parsé, resize sauté\n");
                    }

                    // 7. Vérification stacks après resize
                    sb.append("\n-- am stack list après resize --\n");
                    AdbShellResponse rStackPost = dadb.shell(
                        "am stack list 2>&1 | grep -iE 'displayId|Stack #|myapp|mBounds|taskBounds' | head -20");
                    sb.append(rStackPost.getAllOutput().trim()).append("\n");

                    // 8. Logcat
                    dadb.shell("sleep 0.5");
                    sb.append("\n-- Logcat (windowing + myapp) --\n");
                    AdbShellResponse rLog = dadb.shell(
                        "logcat -d 2>&1 | grep -iE 'BYDDashboard|myapp|windowingMode|FREEFORM|resizeTask|addedDisplay' | tail -20");
                    sb.append(rLog.getAllOutput().trim().isEmpty() ? "(aucune entrée)" : rLog.getAllOutput().trim()).append("\n");

                    // 9. Restauration
                    //    Seal EU : com.byd.automap N'EST PAS INSTALLÉ.
                    //    sendInfo(0) seul ne restaure pas si BYDDashboardActivity est encore
                    //    en premier plan sur display 1 (surface occupée).
                    //    FIX : am task remove <taskId> libère la surface, PUIS sendInfo(0).
                    dadb.shell("sleep 1");
                    if (!taskIdStr.isEmpty()) {
                        sb.append("\n-- Restauration : am task remove ").append(taskIdStr).append(" --\n");
                        AdbShellResponse rRemove = dadb.shell(
                            "am task remove " + taskIdStr + " 2>&1 && echo TASK_REMOVED");
                        sb.append(rRemove.getAllOutput().trim()).append("\n");
                        dadb.shell("sleep 1");
                    } else {
                        sb.append("\n-- Restauration : taskId inconnu, am task remove ignoré --\n");
                    }
                    sb.append("\n-- sendInfo(1000, 0) restauration Qt --\n");
                    AdbShellResponse rRestore = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append(rRestore.getAllOutput().trim()).append("\n");
                    dadb.shell("sleep 1");

                    dadb.close();
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Log.e(TAG, "runDisplayOneLaunch ERREUR", e);
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

                    // 3. Restaurer le rendu Qt BYD natif
                    AdbShellResponse rRestore = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append("sendInfo(0) : ").append(rRestore.getAllOutput().trim()).append("\n");
                    dadb.shell("sleep 1");
                    AppLogger.log(TAG, "sendInfo(0) -> " + rRestore.getAllOutput().trim());

                    dadb.close();
                    boolean ok = !taskIdStr.isEmpty() || rRestore.getAllOutput().contains("00000000");
                    AppLogger.log(TAG, "restoreBydOnCluster -> " + (ok ? "OK" : "ÉCHEC"));
                    if (ok) callback.onSuccess("BYD restauré \u2713\n" + sb);
                    else    callback.onError(sb.toString());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Log.e(TAG, "restoreBydOnCluster ERREUR", e);
                    AppLogger.log(TAG, "restoreBydOnCluster ERREUR — " + msg);
                    callback.onError(msg);
                }
            }
        }, "adb-restore-thread").start();
    }
}
