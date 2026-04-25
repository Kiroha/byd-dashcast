package com.byd.myapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectOutputStream;
import java.util.HashMap;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdbLocalClient {
    // Limité à 4 threads pour éviter un OutOfMemoryError ou une saturation socket
    // si l'utilisateur martèle l'UI de clics provoquant des commandes lentes ADB.
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(4);

    private static final String TAG = "AdbLocalClient";

    /** Port ADB TCP — identique pour Android 7–10 en mode développeur */
    private static final int ADB_PORT = 5555;

    // -------------------------------------------------------------------------

    /**
     * Exécute une commande shell brute via ADB local (asynchrone).
     */
    public static void executeShell(final Context context, final String command) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    AdbShellResponse r = dadb.shell(command);
                    AppLogger.d(TAG, "executeShell: " + command + " -> " + r.getAllOutput().trim());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "executeShell ERREUR pour: " + command, e);
                }
            }
        });
    }

    public interface Callback {
        /** Appelé sur un thread background quand la connexion + les grants sont terminés. */
        void onSuccess(String report);
        /** Appelé si la connexion échoue (port fermé, timeout, refus…). */
        void onError(String error);
    }

    public interface BitmapCallback {
        void onBitmap(Bitmap bitmap);
        void onError(String error);
    }

    // ── Freedom state ─────────────────────────────────────────────────────────

    /** État de Freedom (com.xdja.clusterdemo) — prérequis à toute diffusion cluster. */
    public enum FreedomStatus {
        /** com.xdja.clusterdemo n'est pas installé sur le système. */
        NOT_INSTALLED,
        /** Installé mais inactif : fission VirtualDisplay absent — display 1 inaccessible. */
        INACTIVE,
        /** Actif en mode 全屏导航 : fission VirtualDisplay présent — display 1 accessible. */
        ACTIVE
    }

    public interface FreedomStateCallback {
        void onResult(FreedomStatus status);
    }

    /**
     * Vérifie l'état de Freedom via ADB (thread background).
     *
     * Deux tests en séquence :
     *   1. pm list packages com.xdja.clusterdemo → NOT_INSTALLED si absent
     *   2. dumpsys display | grep -i fission      → ACTIVE si présent, INACTIVE sinon
     *
     * Rappel : Freedom OFF = display 1 absent de DisplayManager (confirmé 18/04/2026).
     * En cas d'erreur ADB, on retourne INACTIVE (déclenchera startFreedom en fallback).
     */
    public static void startMirrorDaemon(final Context context) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    // Tuer l'ancien daemon si présent (cherche par nice-name)
                    String psOut = safeOut(dadb.shell("ps -A | grep 'byd.mirror.daemon' 2>&1").getAllOutput());
                    if (psOut.contains("byd.mirror.daemon")) {
                        dadb.shell("ps -A | grep 'byd.mirror.daemon' | awk '{print $2}' | xargs kill -9 2>/dev/null");
                        AppLogger.i(TAG, "Ancien MirrorDaemon tué.");
                        Thread.sleep(500);
                    }
                    String apkPath = context.getPackageCodePath();
                    final String logPath = "/data/local/tmp/mirrordaemon.log";
                    // app_process64 (ARM64) + -Xnoimage-dex2oat pour éviter crash AOT au démarrage
                    // Pas de nohup (défaillant sur Android toybox) — & suffit via ADB exec channel
                    // CLASSPATH inline (pas export &&) comme le fait Commander APK
                    String cmd = "CLASSPATH=" + apkPath
                            + " /system/bin/app_process64 -Xnoimage-dex2oat /system/bin"
                            + " --nice-name=byd.mirror.daemon"
                            + " com.byd.myapp.daemon.MirrorDaemon"
                            + " </dev/null >" + logPath + " 2>&1 &";
                    dadb.shell(cmd);
                    AppLogger.i(TAG, "MirrorDaemon lancé via ADB (app_process64).");

                    // Vérification : le process est-il bien vivant après 3s ?
                    Thread.sleep(3000);
                    String psCheck = safeOut(dadb.shell("ps -A | grep 'byd.mirror.daemon' 2>&1").getAllOutput());
                    if (psCheck.contains("byd.mirror.daemon")) {
                        AppLogger.i(TAG, "MirrorDaemon ACTIF ✓  " + psCheck.trim());
                    } else {
                        AppLogger.e(TAG, "MirrorDaemon INTROUVABLE après 3s — lecture log :");
                        String logContent = safeOut(dadb.shell("cat " + logPath + " 2>&1").getAllOutput());
                        AppLogger.e(TAG, "mirrordaemon.log = [" + logContent + "]");
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "Erreur demarrage MirrorDaemon", e);
                }
            }
        });
    }

    public static void checkFreedomState(final Context context, final FreedomStateCallback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    // 1. Freedom installé ?
                    String pkgOut = safeOut(dadb.shell(
                            "pm list packages com.xdja.clusterdemo 2>&1").getAllOutput()).trim();
                    if (!pkgOut.contains("com.xdja.clusterdemo")) {
                        AppLogger.w(TAG, "checkFreedomState: non installé");
                        callback.onResult(FreedomStatus.NOT_INSTALLED);
                        return;
                    }
                    // 2. Freedom tourne-t-il vraiment en mémoire ?
                    //    ATTENTION: on ne vérifie plus "dumpsys display | grep fission" !
                    //    Le VirtualDisplay fission est géré par AutoDisplayService et reste ALWAYS présent
                    //    même quand Freedom est force-stop. On doit vérifier la présence du procesus.
                    String pidOut = safeOut(dadb.shell(
                            "ps -A | grep com.xdja.clusterdemo 2>&1").getAllOutput()).trim();
                    if (!pidOut.isEmpty()) {
                        AppLogger.i(TAG, "checkFreedomState: ACTIVE (process found)");
                        callback.onResult(FreedomStatus.ACTIVE);
                    } else {
                        AppLogger.i(TAG, "checkFreedomState: INACTIVE (processus absent)");
                        callback.onResult(FreedomStatus.INACTIVE);
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "checkFreedomState ERREUR", e);
                    callback.onResult(FreedomStatus.INACTIVE); // fallback → startFreedom tenté
                }
            }
        }); // adb-check-freedom
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
        sExecutor.execute(() -> {
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
        }); // adb-local-thread
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
        sExecutor.execute(new Runnable() {
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
        }); // adb-overlay-grant
    }

    // ── Freedom : démarrage automatique ─────────────────────────────────────
    /**
     * Configure Freedom (com.xdja.clusterdemo) en mode "全屏导航" (plein écran), puis le
     * démarre via ADB shell.
     *
     * Mécanisme : Freedom persiste son mode navigation dans
     *   /sdcard/Android/data/com.xdja.clusterdemo/data/properties.xml
     * sous la clé "navigationType" (int) — HashMap Java sérialisé (ObjectOutputStream).
     * Valeur 1 = 全屏导航 (projection plein écran, confirmé voiture v2.29).
     * Sans fichier (défaut) = navigationType absent → BootReceiver retourne sans créer de VirtualDisplay.
     *
     * Séquence :
     *   1. force-stop Freedom (nettoyage avant redémarrage propre)
     *   2. écrire properties.xml avec navigationType=1 (全屏导航)
     *   3. am broadcast BOOT_COMPLETED → BootReceiver lit le fichier et crée le VirtualDisplay
     *
     * La callback est appelée sur un thread ADB (background).
     */
    public static void startFreedom(final Context context, final Callback callback) {
        startFreedom(context, false, callback);
    }

    /**
     * @param skipDisplayCheck  true si l'appelant sait déjà que le VirtualDisplay fission est absent
     *                          (ex: juste après checkFreedomState → INACTIVE). Évite une 2e connexion
     *                          ADB redondante pour vérifier la même chose.
     */
    public static void startFreedom(final Context context, final boolean skipDisplayCheck,
            final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    // 1. Vérifier si Freedom tourne déjà en mémoire.
                    //    Sauté si skipDisplayCheck=true (l'appelant a déjà confirmé son absence).
                    if (!skipDisplayCheck) {
                        String pidCheck = safeOut(dadb.shell(
                                "ps -A | grep com.xdja.clusterdemo 2>&1").getAllOutput()).trim();
                        if (!pidCheck.isEmpty()) {
                            AppLogger.i(TAG, "startFreedom : Freedom (com.xdja.clusterdemo) déjà actif → pas de redémarrage");
                            callback.onSuccess("Freedom déjà actif");
                            return;
                        }
                    } else {
                        AppLogger.d(TAG, "startFreedom : skip pidCheck (Freedom déjà confirmé inactif)");
                    }

                    // 2. Freedom inactif → force-stop de sécurité avant le démarrage propre
                    dadb.shell("am force-stop com.xdja.clusterdemo 2>&1");
                    AppLogger.i(TAG, "startFreedom : force-stop Freedom");
                    Thread.sleep(500);

                    // 3. Écrire properties.xml avec navigationType=1 (全屏导航)
                    //    IMPORTANT : ne PAS supprimer le fichier — navigationType=0 (défaut sans fichier)
                    //    déclenche le retour immédiat dans BootReceiver.setup() sans créer le VirtualDisplay.
                    //    Le fichier est un HashMap Java sérialisé (ObjectOutputStream).
                    writeNavigationTypeFile(dadb);
                    AppLogger.i(TAG, "startFreedom : properties.xml écrit (navigationType=1 → 全屏导航)");

                    // 4. Démarrer Freedom de manière 100% transparente
                    //    Au lieu d'ouvrir MainActivity et causer un "flash" visuel à l'écran,
                    //    on simule le BOOT_COMPLETED. Freedom n'a besoin que de lancer son BootReceiver
                    //    pour lire notre fichier et établir le pont Binder.
                    AppLogger.i(TAG, "startFreedom : démarrage transparent via am broadcast BOOT_COMPLETED");
                    String startOut = safeOut(dadb.shell(
                            "am broadcast -a android.intent.action.BOOT_COMPLETED -n com.xdja.clusterdemo/com.byd.windowmanager.receivers.BootReceiver 2>&1"
                    ).getAllOutput()).trim();
                    AppLogger.i(TAG, "startFreedom am broadcast → " + startOut);
                    callback.onSuccess(startOut);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "startFreedom ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }); // adb-start-freedom
    }

    /**
     * Écrit /sdcard/Android/data/com.xdja.clusterdemo/data/properties.xml
     * avec navigationType=1 (全屏导航 — projection plein écran).
     *
     * Freedom lit ce fichier via ObjectInputStream → HashMap<String, Object>.
     * BootReceiver.setup() vérifie navigationType > 0 pour déclencher la création
     * du VirtualDisplay. Avec la valeur par défaut 0 (fichier absent), setup() retourne
     * immédiatement sans rien faire.
     */
    private static void writeNavigationTypeFile(Dadb dadb) throws Exception {
        // Sérialiser HashMap {"navigationType": Integer(1)} avec Java ObjectOutputStream
        HashMap<String, Object> prefs = new HashMap<>();
        prefs.put("navigationType", Integer.valueOf(1));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(prefs);
        oos.close();
        byte[] bytes = baos.toByteArray();

        // Encoder en Base64 et écrire via shell ADB (base64 disponible dans Android toybox)
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        String dir  = "/sdcard/Android/data/com.xdja.clusterdemo/data";
        String path = dir + "/properties.xml";
        dadb.shell("mkdir -p " + dir + " 2>&1");
        String writeResult = safeOut(dadb.shell(
                "echo '" + b64 + "' | base64 -d > " + path + " 2>&1"
        ).getAllOutput()).trim();
        AppLogger.i(TAG, "writeNavigationTypeFile → '" + writeResult + "' (" + bytes.length + " bytes)");
    }


    // ── TEST 4 : Broadcast BOOT_COMPLETED vers le BootReceiver de Freedom ──────
    /**
     * Envoie BOOT_COMPLETED directement au BootReceiver de Freedom sans ouvrir son UI.
     *
     * Séquence :
     *   1. am broadcast BOOT_COMPLETED → com.xdja.clusterdemo/.BootReceiver
     *   2. Attendre 5s pour laisser le temps au VirtualDisplay d'apparaître
     *   3. dumpsys display | grep fission → vérifie si le VirtualDisplay cluster est créé
     *
     * Si le VirtualDisplay est créé → startFreedom() peut être remplacé par ce broadcast
     * (headless, pas d'UI Freedom visible).
     */
    public static void sendBootReceiverBroadcast(final Context context, final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                long t0 = AppLogger.startTiming();
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // Snapshot AVANT
                    sb.append("── AVANT broadcast ──\n");
                    String before = safeOut(dadb.shell(
                            "dumpsys display 2>&1 | grep -i fission"
                    ).getAllOutput()).trim();
                    sb.append(before.isEmpty() ? "(aucun display fission)" : before).append("\n\n");
                    AppLogger.i(TAG, "TEST4 avant : " + (before.isEmpty() ? "vide" : before));

                    // Force-stop Freedom au préalable pour repartir d'un état propre
                    sb.append("── Force-stop Freedom ──\n");
                    dadb.shell("am force-stop com.xdja.clusterdemo 2>&1");
                    Thread.sleep(500);
                    sb.append("OK\n\n");

                    // Broadcast BOOT_COMPLETED ciblé
                    sb.append("── am broadcast BOOT_COMPLETED → BootReceiver ──\n");
                    String broadcastOut = safeOut(dadb.shell(
                            "am broadcast -a android.intent.action.BOOT_COMPLETED" +
                            " -n com.xdja.clusterdemo/com.byd.windowmanager.receivers.BootReceiver 2>&1"
                    ).getAllOutput()).trim();
                    sb.append(broadcastOut).append("\n\n");
                    AppLogger.i(TAG, "TEST4 broadcast → " + broadcastOut);

                    // Attendre 5s pour que le VirtualDisplay soit créé
                    sb.append("── Attente 5s (création VirtualDisplay) ──\n");
                    Thread.sleep(5000);

                    // Snapshot APRÈS
                    sb.append("── APRÈS broadcast ──\n");
                    String after = safeOut(dadb.shell(
                            "dumpsys display 2>&1 | grep -i fission"
                    ).getAllOutput()).trim();
                    sb.append(after.isEmpty() ? "(aucun display fission)" : after).append("\n\n");
                    AppLogger.i(TAG, "TEST4 après : " + (after.isEmpty() ? "vide" : after));

                    // Conclusion
                    boolean created = !after.isEmpty() && after.contains("fission");
                    sb.append(created
                            ? "✅ VirtualDisplay créé ! Broadcast seul suffit → Freedom headless possible."
                            : "❌ VirtualDisplay absent. Le BootReceiver seul ne suffit pas.");
                    long ms = System.currentTimeMillis() - t0;
                    sb.append("\n\n(").append(ms).append(" ms)");

                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "sendBootReceiverBroadcast ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }); // adb-test4-boot-receiver
    }


    /**
     * Active le cluster en mode présentation (sendInfo 30 + 16 uniquement).
     *
     *   1. sendInfo(1000, 30) — taille 12.3" TOUJOURS : seul mode où l'écran ADAS n'est pas étiré
     *   2. sendInfo(1000, 16) — Qt standby → libère le display pour la projection
     *
     * Ne contient PAS sendInfo(18) ni sendInfo(0) qui sont des commandes de restauration.
     */
    public static void activateClusterDisplay(final Context context, final Callback callback) {
        checkFreedomState(context, new FreedomStateCallback() {
            @Override public void onResult(FreedomStatus status) {
                if (status == FreedomStatus.INACTIVE) {
                    AppLogger.i(TAG, "activateClusterDisplay : Freedom inactif → démarrage d'abord");
                    startFreedom(context, true, new Callback() {
                        @Override public void onSuccess(String ignored) {
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override public void run() { doActivateClusterDisplayLocked(context, callback); }
                            }, 2000);
                        }
                        @Override public void onError(String err) {
                            doActivateClusterDisplayLocked(context, callback);
                        }
                    });
                } else {
                    doActivateClusterDisplayLocked(context, callback);
                }
            }
        });
    }

    private static void doActivateClusterDisplayLocked(final Context context, final Callback callback) {
        sExecutor.execute(new Runnable() {
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
        }); // adb-activate-cluster-thread
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
        sExecutor.execute(new Runnable() {
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
        }); // adb-display1-thread
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
        sExecutor.execute(new Runnable() {
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
        }); // adb-restore-thread
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
        sExecutor.execute(new Runnable() {
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
        }); // adb-origin-cluster-thread
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
        sExecutor.execute(new Runnable() {
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
        }); // adb-sendinfo-thread
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
        sExecutor.execute(new Runnable() {
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
                            "am start-activity -W --display 1 "
                            + "-n " + pkg + "/.dashboard.ClusterTrampolineActivity 2>&1 "
                            + "| head -20").getAllOutput().trim();
                    for (String line : testLaunch.split("\n")) AppLogger.i(dTag, "  " + line);

                    AppLogger.i(dTag, "=== FIN dump ===");
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(dTag, "dumpSignatureAndPermissions ERREUR", e);
                }
            }
        }); // adb-sigdump-thread
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
        sExecutor.execute(new Runnable() {
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
        }); // adb-display-size-test
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
        sExecutor.execute(new Runnable() {
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
        });
    }

    /**
     * Restaure la taille d'écran cluster par défaut :
     *   1. sendInfo(1000, 30) — 切换到12.3寸屏 (état Qt par défaut, 1920×1080)
     *   2. wm size reset -d 1 — annule tout override logique Android
     * À utiliser après un essai de cmd 29/31 qui aurait perturbé l'affichage.
     */
    public static void resetClusterDisplaySize(final Context context, final Callback callback) {
        sExecutor.execute(new Runnable() {
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
        }); // adb-display-reset
    }

    /**
     * Force-stop d'une application via ADB.
     * Appelé quand l'utilisateur tape "✕" dans la liste.
     * Utilise "am force-stop" qui tue le processus entier + libère toutes ses surfaces.
     */
    public static void forceStopApp(final Context context, final String packageName,
            final Callback callback) {
        sExecutor.execute(new Runnable() {
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
        }); // adb-forcestop-thread
    }

    /**
     * Lance une application tierce via le MirrorDaemon (uid=2000) avec des bounds FREEFORM explicites.
     */
    public static void launchDirectWithBounds(final Context context,
            final String targetPackage, final int displayId,
            final int left, final int top, final int right, final int bottom,
            final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    android.content.pm.PackageManager pm = context.getPackageManager();
                    android.content.Intent li = pm.getLaunchIntentForPackage(targetPackage);
                    if (li == null) {
                        try {
                            android.content.pm.PackageInfo pi = pm.getPackageInfo(targetPackage, android.content.pm.PackageManager.GET_ACTIVITIES);
                            if (pi.activities != null && pi.activities.length > 0) {
                                li = new android.content.Intent();
                                li.setComponent(new android.content.ComponentName(targetPackage, pi.activities[0].name));
                            }
                        } catch (Exception ignored) {}
                    }
                    if (li == null || li.getComponent() == null) {
                        callback.onError("Aucune activité trouvée pour " + targetPackage);
                        return;
                    }
                    
                    AppLogger.i(TAG, "Broadcast daemon_launch_bounds pour " + targetPackage);
                    android.content.Intent intent = new android.content.Intent("com.byd.myapp.MIRROR_DAEMON_LAUNCH");
                    intent.putExtra("pkg", li.getComponent().getPackageName());
                    intent.putExtra("cls", li.getComponent().getClassName());
                    intent.putExtra("displayId", displayId);
                    intent.putExtra("bounds_l", left);
                    intent.putExtra("bounds_t", top);
                    intent.putExtra("bounds_r", right);
                    intent.putExtra("bounds_b", bottom);
                    context.sendBroadcast(intent);
                    
                    callback.onSuccess("Broadcast bounds envoyé au Daemon.");
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "launchDirectWithBounds échoué", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }); // adb-trampoline-bounds-thread
    }

    /**
     * Capture une frame du display cluster via screencap (uid=2000 = accès SurfaceFlinger garanti).
     * Sauvegarde dans le cache externe de l'app ; l'app peut le lire directement (pas de permission
     * READ_EXTERNAL_STORAGE requise pour le répertoire spécifique au package en API 29).
     */
    public static void captureClusterDisplay(final Context context,
            final int displayId, final BitmapCallback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    File cacheDir = context.getExternalCacheDir();
                    if (cacheDir == null) cacheDir = context.getCacheDir();
                    File outFile = new File(cacheDir, "cluster_live.png");
                    // Chemin ADB : /storage/emulated/0 → /sdcard (symlink standard)
                    String remotePath = outFile.getAbsolutePath()
                            .replace("/storage/emulated/0", "/sdcard");
                    dadb.shell("screencap -d " + displayId + " -p " + remotePath);
                    Bitmap bm = BitmapFactory.decodeFile(outFile.getAbsolutePath());
                    if (bm != null) {
                        callback.onBitmap(bm);
                    } else {
                        callback.onError("decodeFile null: " + outFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.w(TAG, "captureClusterDisplay erreur: " + e.getMessage());
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }); // screenshot-mirror-thread
    }

    /**
     * Lit un fichier texte via ADB shell (cat) et le copie dans getExternalFilesDir.
     * Évite le besoin de READ_EXTERNAL_STORAGE pour lire des fichiers dans /sdcard/.
     */
    public interface ReadFileCallback {
        void onSuccess(java.io.File localCopy);
        void onError(String error);
    }

    public static void readFileViaAdb(final Context context, final String remotePath,
                                      final String localName, final ReadFileCallback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    String content = safeOut(
                            dadb.shell("cat " + remotePath + " 2>&1").getAllOutput());
                    if (content.contains("No such file") || content.equals("(vide)")) {
                        callback.onError("Fichier introuvable : " + remotePath);
                        return;
                    }
                    java.io.File dst = new java.io.File(
                            context.getExternalFilesDir(null), localName);
                    try (java.io.FileWriter fw = new java.io.FileWriter(dst, false)) {
                        fw.write(content);
                    }
                    callback.onSuccess(dst);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });
    }

    private static String safeOut(String s) {
        if (s == null) return "(null)";
        s = s.trim();
        return s.isEmpty() ? "(vide)" : s;
    }

}
