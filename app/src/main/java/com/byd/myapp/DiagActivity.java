package com.byd.myapp;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.AsyncTask;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import android.content.Intent;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * DiagActivity — Écran de diagnostic de compatibilité.
 *
 * Teste deux mécanismes pour envoyer une app sur l'écran dashboard :
 *
 *  1. Presentation API (méthode propre)
 *     → DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)
 *     → Si au moins 1 display trouvé, notre app fonctionne nativement.
 *
 *  2. ADB TCP (méthode Freedom)
 *     → Connexion sur 127.0.0.1:5555 (daemon ADB local)
 *     → Si joignable, le mécanisme ADB shell est disponible.
 *
 *  3. setLaunchDisplayId via réflexion
 *     → Vérifie que la méthode @hide existe dans ActivityOptions à l'exécution.
 */
public class DiagActivity extends AppCompatActivity {

    private TextView tvPresentationResult;
    private TextView tvReflectionResult;
    private TextView tvAdbResult;
    private TextView tvLaunchResult;
    private TextView tvConclusion;
    private Button   btnRunDiag;

    // TEST 5
    private TextView tvAdbLocalResult;
    private Button   btnAdbLocal;
    private Button   btnAdbShare;

    // TEST 6
    private TextView tvClusterProbeResult;
    private Button   btnClusterProbe;
    private Button   btnClusterProbeShare;

    // TEST 10
    private TextView tvDisplay1Result;
    private Button   btnDisplay1;
    private Button   btnDisplay1Share;

    // TEST 11
    private TextView tvWhitelistResult;
    private Button   btnWhitelist;
    private Button   btnWhitelistShare;

    // TEST 12
    private TextView tvDisplaySizeResult;
    private Button   btnDisplaySize;
    private Button   btnDisplaySizeShare;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");

        tvPresentationResult = (TextView) findViewById(R.id.tv_presentation_result);
        tvReflectionResult   = (TextView) findViewById(R.id.tv_reflection_result);
        tvAdbResult          = (TextView) findViewById(R.id.tv_adb_result);
        tvLaunchResult       = (TextView) findViewById(R.id.tv_launch_result);
        tvConclusion         = (TextView) findViewById(R.id.tv_conclusion);
        btnRunDiag           = (Button)   findViewById(R.id.btn_run_diag);
        tvAdbLocalResult      = (TextView) findViewById(R.id.tv_adb_local_result);
        btnAdbLocal           = (Button)   findViewById(R.id.btn_adb_local);
        btnAdbShare           = (Button)   findViewById(R.id.btn_adb_share);
        tvClusterProbeResult  = (TextView) findViewById(R.id.tv_cluster_probe_result);
        btnClusterProbe       = (Button)   findViewById(R.id.btn_cluster_probe);
        btnClusterProbeShare  = (Button)   findViewById(R.id.btn_cluster_probe_share);

        tvDisplay1Result      = (TextView) findViewById(R.id.tv_display1_result);
        btnDisplay1           = (Button)   findViewById(R.id.btn_display1);
        btnDisplay1Share      = (Button)   findViewById(R.id.btn_display1_share);

        tvWhitelistResult      = (TextView) findViewById(R.id.tv_whitelist_result);
        btnWhitelist           = (Button)   findViewById(R.id.btn_whitelist);
        btnWhitelistShare      = (Button)   findViewById(R.id.btn_whitelist_share);

        tvDisplaySizeResult    = (TextView) findViewById(R.id.tv_display_size_result);
        btnDisplaySize         = (Button)   findViewById(R.id.btn_display_size);
        btnDisplaySizeShare    = (Button)   findViewById(R.id.btn_display_size_share);

        btnAdbShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(android.content.Intent.EXTRA_TEXT, tvAdbLocalResult.getText().toString());
                startActivity(android.content.Intent.createChooser(intent, "Partager résultat TEST 5"));
            }
        });
        btnAdbLocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAdbLocal.setEnabled(false);
                tvAdbLocalResult.setText("Connexion à localhost:5555…\n" +
                        "⏳ Le popup va apparaître sur cet écran — appuyez AUTORISER.");
                AppLogger.log("DiagADB", "Lancement connexion ADB locale");

                AdbLocalClient.connectAndGrant(DiagActivity.this,
                        new AdbLocalClient.Callback() {
                    @Override
                    public void onSuccess(final String report) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvAdbLocalResult.setText("✅ Connexion établie\n\n" + report);
                                btnAdbLocal.setEnabled(true);
                            }
                        });
                    }
                    @Override
                    public void onError(final String error) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvAdbLocalResult.setText(
                                        "❌ Échec : " + error + "\n\n" +
                                        "→ Vérifiez que le débogage ADB TCP est activé\n" +
                                        "  dans Paramètres → Développeur → Débogage USB\n" +
                                        "  (ou Débogage sans fil sur cette ROM)");
                                btnAdbLocal.setEnabled(true);
                            }
                        });
                    }
                });
            }
        });

        btnRunDiag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiagnostic();
            }
        });

        // TEST 6 — Sonder le cluster via ADB
        btnClusterProbeShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(android.content.Intent.EXTRA_TEXT,
                        tvClusterProbeResult.getText().toString());
                startActivity(android.content.Intent.createChooser(intent, "Partager résultat TEST 6"));
            }
        });

        btnClusterProbe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runClusterProbe();
            }
        });

        // TEST 10 — Lancement sur display 1 (cluster)
        btnDisplay1Share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(android.content.Intent.EXTRA_TEXT,
                        tvDisplay1Result.getText().toString());
                startActivity(android.content.Intent.createChooser(intent, "Partager résultat TEST 10"));
            }
        });

        btnDisplay1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDisplayOneLaunch();
            }
        });

        // TEST 11 — AutoContainer Whitelist
        btnWhitelistShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(android.content.Intent.EXTRA_TEXT,
                        tvWhitelistResult.getText().toString());
                startActivity(android.content.Intent.createChooser(intent, "Partager résultat TEST 11"));
            }
        });

        btnWhitelist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAutoContainerWhitelistProbe();
            }
        });

        // TEST 12 — Taille display cluster
        btnDisplaySizeShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(android.content.Intent.EXTRA_TEXT,
                        tvDisplaySizeResult.getText().toString());
                startActivity(android.content.Intent.createChooser(intent, "Partager résultat TEST 12"));
            }
        });

        btnDisplaySize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runClusterDisplaySizeTest();
            }
        });
    }

    // -------------------------------------------------------------------------
    // TEST 12 : Taille display cluster — cmd 29/30/31 + wm size
    // -------------------------------------------------------------------------

    private void runClusterDisplaySizeTest() {
        btnDisplaySize.setEnabled(false);
        tvDisplaySizeResult.setText("⏳ Test dimensions display cluster en cours…\n"
                + "Essai cmd=29 / cmd=30 / cmd=31 / wm size…\n"
                + "(~8 secondes)");
        AppLogger.log("DiagDisplaySize", "Lancement TEST 12");

        AdbLocalClient.runClusterDisplaySizeTest(DiagActivity.this, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvDisplaySizeResult.setBackgroundColor(0xFF1A2A1A);
                        tvDisplaySizeResult.setText(report);
                        btnDisplaySize.setEnabled(true);
                        AppLogger.log("DiagDisplaySize", report);
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvDisplaySizeResult.setBackgroundColor(0xFF2A1A1A);
                        tvDisplaySizeResult.setText("❌ " + error
                                + "\n\n→ Lancez d'abord TEST 5 pour autoriser la connexion ADB.");
                        btnDisplaySize.setEnabled(true);
                        AppLogger.log("DiagDisplaySize", "ERREUR: " + error);
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // TEST 11 : AutoContainer Whitelist — sharedUserId + container_comm_cfg
    // -------------------------------------------------------------------------

    private void runAutoContainerWhitelistProbe() {
        btnWhitelist.setEnabled(false);
        tvWhitelistResult.setText("⏳ Analyse whitelist AutoContainer…");
        AppLogger.log("DiagWhitelist", "Whitelist probe démarré");

        AdbLocalClient.runAutoContainerWhitelistProbe(DiagActivity.this, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvWhitelistResult.setBackgroundColor(0xFF1A1A2A);
                        tvWhitelistResult.setText(report);
                        btnWhitelist.setEnabled(true);
                        AppLogger.log("DiagWhitelist", report);
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvWhitelistResult.setBackgroundColor(0xFF2A1A1A);
                        tvWhitelistResult.setText("❌ " + error
                                + "\n\n→ Lancez d'abord TEST 5 pour autoriser la connexion ADB.");
                        btnWhitelist.setEnabled(true);
                        AppLogger.log("DiagWhitelist", "ERREUR: " + error);
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // TEST 10 : Lancement sur display 1 (cluster) — insight WindowManagement
    // -------------------------------------------------------------------------

    private void runDisplayOneLaunch() {
        btnDisplay1.setEnabled(false);
        tvDisplay1Result.setText("⏳ Lancement display 1…");
        AppLogger.log("DiagDisplay1", "Lancement display 1 démarré");

        AdbLocalClient.runDisplayOneLaunch(DiagActivity.this, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        boolean ok = report.contains("Starting:") && !report.contains("Error");
                        tvDisplay1Result.setBackgroundColor(ok ? 0xFF1A2A1A : 0xFF1A1A2A);
                        tvDisplay1Result.setText(report);
                        btnDisplay1.setEnabled(true);
                        AppLogger.log("DiagDisplay1", report);
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvDisplay1Result.setBackgroundColor(0xFF2A1A1A);
                        tvDisplay1Result.setText("\u274C " + error
                                + "\n\n\u2192 Lancez d'abord TEST 5 pour autoriser la connexion ADB.");
                        btnDisplay1.setEnabled(true);
                        AppLogger.log("DiagDisplay1", "ERREUR: " + error);
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // TEST 6 : Sonder le cluster via ADB
    // -------------------------------------------------------------------------

    private void runClusterProbe() {
        btnClusterProbe.setEnabled(false);
        tvClusterProbeResult.setText("⏳ Connexion ADB…");
        AppLogger.log("DiagCluster", "Sondage cluster démarré");

        AdbLocalClient.runClusterProbe(DiagActivity.this, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvClusterProbeResult.setBackgroundColor(0xFF1A2A1A);
                        tvClusterProbeResult.setText(report);
                        btnClusterProbe.setEnabled(true);
                        AppLogger.log("DiagCluster", report);
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvClusterProbeResult.setBackgroundColor(0xFF2A1A1A);
                        tvClusterProbeResult.setText("❌ " + error
                                + "\n\n→ Lancez d'abord TEST 5 pour autoriser la connexion ADB.");
                        btnClusterProbe.setEnabled(true);
                        AppLogger.log("DiagCluster", "ERREUR: " + error);
                    }
                });
            }
        });
    }

    private void runDiagnostic() {
        btnRunDiag.setEnabled(false);
        tvPresentationResult.setText("Test en cours…");
        tvReflectionResult.setText("Test en cours…");
        tvAdbResult.setText("Test en cours…");
        tvLaunchResult.setText("En attente…");
        tvConclusion.setText("");

        AppLogger.log("Diag", "Démarrage diagnostic");

        // Tests 1 et 2 sont synchrones (pas de réseau)
        final boolean presentationOk = testPresentationDisplay();
        final boolean reflectionOk   = testReflection();
        final int     displayId      = getFirstPresentationDisplayId();

        String presMsg = presentationOk
                ? "✅ " + getPresentationDisplayCount() + " display(s) Presentation détecté(s)"
                : "❌ Aucun display Presentation — le cluster n'est pas exposé";
        updateResultView(tvPresentationResult, presentationOk, presMsg);
        AppLogger.log("Diag", "Test 1 — Presentation: " + (presentationOk ? "OK id=" + displayId : "KO"));

        String reflMsg = reflectionOk
                ? "✅ ActivityOptions.setLaunchDisplayId() disponible"
                : "❌ setLaunchDisplayId introuvable dans ActivityOptions";
        updateResultView(tvReflectionResult, reflectionOk, reflMsg);
        AppLogger.log("Diag", "Test 2 — Réflexion: " + (reflectionOk ? "OK" : "KO"));

        // Tests 3 (réseau) + 4 (lancement effectif) asynchrones
        new DiagTask(presentationOk, reflectionOk, displayId).execute();
    }

    // -------------------------------------------------------------------------
    // Test 1 : Presentation display
    // -------------------------------------------------------------------------

    private boolean testPresentationDisplay() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return displays != null && displays.length > 0;
    }

    private int getPresentationDisplayCount() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return displays == null ? 0 : displays.length;
    }

    private int getFirstPresentationDisplayId() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return (displays != null && displays.length > 0) ? displays[0].getDisplayId() : -1;
    }

    // -------------------------------------------------------------------------
    // Test 2 : Réflexion setLaunchDisplayId
    // -------------------------------------------------------------------------

    private boolean testReflection() {
        try {
            Method m = android.app.ActivityOptions.class
                    .getDeclaredMethod("setLaunchDisplayId", int.class);
            return m != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Tests 3 (ADB TCP) + 4 (lancement effectif)
    // -------------------------------------------------------------------------

    private class DiagTask extends AsyncTask<Void, Void, Boolean> {

        private final boolean mPresentationOk;
        private final boolean mReflectionOk;
        private final int     mDisplayId;

        DiagTask(boolean presentationOk, boolean reflectionOk, int displayId) {
            mPresentationOk = presentationOk;
            mReflectionOk   = reflectionOk;
            mDisplayId      = displayId;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            // Port 5555 = adbd TCP sur la tablette (port 5037 = serveur ADB du PC hôte, hors-sujet ici)
            return isPortOpen("127.0.0.1", 5555, 1000);
        }

        @Override
        protected void onPostExecute(Boolean adbOk) {
            updateResultView(tvAdbResult, adbOk,
                    adbOk
                            ? "✅ Daemon ADB local accessible (port 5555)"
                            : "❌ ADB TCP non disponible — activer 'Débogage sans fil'");
            AppLogger.log("Diag", "Test 3 — ADB TCP: " + (adbOk ? "accessible" : "non disponible"));

            // Test 4 : lancement effectif (onPostExecute = thread UI → startActivity valide)
            String launchResult = testRealLaunch(mPresentationOk, mReflectionOk, mDisplayId);
            updateResultView(tvLaunchResult, launchResult.startsWith("✅"), launchResult);

            buildConclusion(mPresentationOk, mReflectionOk, adbOk, launchResult);
            btnRunDiag.setEnabled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Test 4 : lancement effectif
    // -------------------------------------------------------------------------

    private String testRealLaunch(boolean presentationOk, boolean reflectionOk, int displayId) {
        if (!presentationOk || !reflectionOk) {
            return "⚪ Non applicable — tests 1 ou 2 ont échoué";
        }
        if (displayId < 0) {
            return "⚪ Non applicable — aucun display cluster détecté";
        }
        try {
            Intent intent = getPackageManager()
                    .getLaunchIntentForPackage("com.android.settings");
            if (intent == null) {
                intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);

            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            Method m = android.app.ActivityOptions.class
                    .getDeclaredMethod("setLaunchDisplayId", int.class);
            m.setAccessible(true);
            m.invoke(opts, displayId);

            startActivity(intent, opts.toBundle());
            AppLogger.log("DiagTest4", "✅ Lancement réel réussi — display " + displayId);
            return "✅ Lancement réussi sur display " + displayId
                    + "\nSettings apparaît sur le cluster — mécanisme fonctionnel";
        } catch (SecurityException e) {
            AppLogger.log("DiagTest4", "SecurityException: " + e.getMessage());
            return "❌ SecurityException — vérifier platform.keystore\n" + e.getMessage();
        } catch (android.content.ActivityNotFoundException e) {
            AppLogger.log("DiagTest4", "ActivityNotFoundException: " + e.getMessage());
            return "⚠ ActivityNotFoundException\n" + e.getMessage();
        } catch (NoSuchMethodException e) {
            AppLogger.log("DiagTest4", "setLaunchDisplayId introuvable");
            return "❌ setLaunchDisplayId introuvable (cohérent avec test 2 KO)";
        } catch (Exception e) {
            AppLogger.log("DiagTest4", e.getClass().getSimpleName() + ": " + e.getMessage());
            return "❌ " + e.getClass().getSimpleName() + "\n" + e.getMessage();
        }
    }

    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Conclusion
    // -------------------------------------------------------------------------

    private void buildConclusion(boolean presentationOk,
                                  boolean reflectionOk,
                                  boolean adbOk,
                                  String launchResult) {
        StringBuilder sb = new StringBuilder();
        AppLogger.log("Diag", "Conclusion — pres=" + presentationOk
                + " refl=" + reflectionOk + " adb=" + adbOk);

        if (presentationOk && reflectionOk) {
            sb.append("MODE RECOMMANDÉ : Presentation API\n\n");
            sb.append("Le cluster est exposé comme display Android standard.\n");
            sb.append("Notre app peut envoyer n'importe quelle application\n");
            sb.append("directement via setLaunchDisplayId (réflexion).\n");
            sb.append("Aucun prérequis ADB nécessaire.");
            if (launchResult.startsWith("✅")) {
                sb.append("\n\n").append(launchResult);
            } else if (!launchResult.startsWith("⚪")) {
                sb.append("\n\n⚠ Test lancement effectif :\n").append(launchResult);
            }
            tvConclusion.setBackgroundColor(0xFF1B5E20);

        } else if (presentationOk && !reflectionOk) {
            sb.append("MODE PARTIEL : Presentation OK, réflexion KO\n\n");
            sb.append("Le display est visible mais setLaunchDisplayId est\n");
            sb.append("indisponible. Seule la classe Presentation (android.app)\n");
            sb.append("peut afficher du contenu sur le cluster.\n");
            sb.append("Les apps tierces ne peuvent pas être envoyées directement.");
            tvConclusion.setBackgroundColor(0xFFE65100);

        } else if (!presentationOk && adbOk) {
            sb.append("MODE REQUIS : ADB TCP (comme Freedom)\n\n");
            sb.append("Le cluster n'est pas exposé comme display Presentation.\n");
            sb.append("Le daemon ADB local est accessible : il est possible\n");
            sb.append("d'utiliser la même approche que Freedom pour lancer des apps\n");
            sb.append("via 'am start-activity --display <id>'.\n\n");
            sb.append("→ Intégrer AdbClient dans notre app.\n");
            sb.append("→ Guider l'utilisateur pour activer ADB sans fil.");
            tvConclusion.setBackgroundColor(0xFF0D47A1);

        } else {
            sb.append("INCOMPATIBLE\n\n");
            sb.append("Aucun des deux mécanismes n'est disponible :\n");
            sb.append("• Pas de display Presentation détecté\n");
            sb.append("• ADB TCP non accessible\n\n");
            sb.append("Vérifiez :\n");
            sb.append("• Que l'app est signée avec platform.keystore\n");
            sb.append("• Que le véhicule est démarré (display cluster actif)\n");
            sb.append("• Ou activez 'Débogage sans fil' dans les paramètres dev.");
            tvConclusion.setBackgroundColor(0xFFB71C1C);
        }

        tvConclusion.setText(sb.toString());
    }

    // -------------------------------------------------------------------------
    // Helpers UI
    // -------------------------------------------------------------------------

    private void updateResultView(TextView tv, boolean success, String message) {
        tv.setText(message);
        tv.setBackgroundColor(success ? 0xFF2E7D32 : 0xFFC62828);
        tv.setTextColor(0xFFFFFFFF);
    }
}
