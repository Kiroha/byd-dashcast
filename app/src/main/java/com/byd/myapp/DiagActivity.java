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
    private Button   btnDisplaySize88;       // cmd 29 — 8.8"
    private Button   btnDisplaySize123;      // cmd 30 — 12.3"
    private Button   btnDisplaySize1025;     // cmd 31 — 10.25"
    private Button   btnDisplaySizeRestore;  // restauration
    private Button   btnDisplaySizeFull;     // diagnostic complet
    private Button   btnDisplaySizeShare;

    // TEST 13 — ADAS cluster
    private TextView tvAdasResult;
    private Button   btnAdas32;    // cmd 32 — 3D ADAS auto-refresh ON
    private Button   btnAdas33;    // cmd 33 — 3D ADAS auto-refresh OFF
    private Button   btnAdasShare;

    // TEST 14 — Masquage fenêtre ADAS (service "auto" BYD VHAL privé)
    private TextView tvAutoResult;
    private Button   btnAutoList;    // A1 : lister services auto/byd
    private Button   btnAutoHide;    // A2 : service call auto N hide (val=0)
    private Button   btnAutoShow;    // A2 : service call auto N show (val=1)
    private Button   btnAutoReflect; // B  : reflection depuis l'app
    private Button   btnAutoShare;

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
        btnDisplaySize88       = (Button)   findViewById(R.id.btn_display_size_88);
        btnDisplaySize123      = (Button)   findViewById(R.id.btn_display_size_123);
        btnDisplaySize1025     = (Button)   findViewById(R.id.btn_display_size_1025);
        btnDisplaySizeRestore  = (Button)   findViewById(R.id.btn_display_size_restore);
        btnDisplaySizeFull     = (Button)   findViewById(R.id.btn_display_size_full);
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

        btnDisplaySize88.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { sendScreenSize(29); }
        });
        btnDisplaySize123.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { sendScreenSize(30); }
        });
        btnDisplaySize1025.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { sendScreenSize(31); }
        });
        btnDisplaySizeRestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { restoreDisplaySize(); }
        });
        btnDisplaySizeFull.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { runClusterDisplaySizeTest(); }
        });

        // TEST 13 — ADAS cluster
        tvAdasResult  = (TextView) findViewById(R.id.tv_adas_result);
        btnAdas32     = (Button)   findViewById(R.id.btn_adas_32);
        btnAdas33     = (Button)   findViewById(R.id.btn_adas_33);
        btnAdasShare  = (Button)   findViewById(R.id.btn_adas_share);

        btnAdas32.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendAdasCommand(32); }
        });
        btnAdas33.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendAdasCommand(33); }
        });
        btnAdasShare.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(android.content.Intent.EXTRA_TEXT, tvAdasResult.getText().toString());
                startActivity(android.content.Intent.createChooser(intent, "Partager résultat TEST 13"));
            }
        });

        // TEST 14 — Masquage fenêtre ADAS (service "auto" BYD VHAL privé)
        tvAutoResult   = (TextView) findViewById(R.id.tv_auto_result);
        btnAutoList    = (Button)   findViewById(R.id.btn_auto_list);
        btnAutoHide    = (Button)   findViewById(R.id.btn_auto_hide);
        btnAutoShow    = (Button)   findViewById(R.id.btn_auto_show);
        btnAutoReflect = (Button)   findViewById(R.id.btn_auto_reflect);
        btnAutoShare   = (Button)   findViewById(R.id.btn_auto_share);

        btnAutoList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runAutoServiceList(); }
        });
        btnAutoHide.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runAutoServiceCall(false); }
        });
        btnAutoShow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runAutoServiceCall(true); }
        });
        btnAutoReflect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runAutoReflect(); }
        });
        btnAutoShare.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(android.content.Intent.EXTRA_TEXT, tvAutoResult.getText().toString());
                startActivity(android.content.Intent.createChooser(intent, "Partager résultat TEST 14"));
            }
        });
    }

    // -------------------------------------------------------------------------
    // TEST 12 : Taille display cluster — helpers
    // -------------------------------------------------------------------------

    private void setDisplaySizeBtnsEnabled(boolean enabled) {
        btnDisplaySize88.setEnabled(enabled);
        btnDisplaySize123.setEnabled(enabled);
        btnDisplaySize1025.setEnabled(enabled);
        btnDisplaySizeRestore.setEnabled(enabled);
        btnDisplaySizeFull.setEnabled(enabled);
    }

    private void sendScreenSize(final int sizeCmd) {
        setDisplaySizeBtnsEnabled(false);
        String label = sizeCmd == 29 ? "8.8\"" : sizeCmd == 30 ? "12.3\"" : "10.25\"";
        tvDisplaySizeResult.setText("⏳ sendInfo(1000, " + sizeCmd + ") → " + label + "…");
        tvDisplaySizeResult.setBackgroundColor(0xFF111A1A);
        AppLogger.log("DiagDisplaySize", "sendClusterScreenSize(" + sizeCmd + ")");

        AdbLocalClient.sendClusterScreenSize(DiagActivity.this, sizeCmd,
                new AdbLocalClient.Callback() {
            @Override public void onSuccess(final String report) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    tvDisplaySizeResult.setBackgroundColor(0xFF1A2A1A);
                    tvDisplaySizeResult.setText(report);
                    setDisplaySizeBtnsEnabled(true);
                    AppLogger.log("DiagDisplaySize", report);
                }});
            }
            @Override public void onError(final String error) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    tvDisplaySizeResult.setBackgroundColor(0xFF2A1A1A);
                    tvDisplaySizeResult.setText("❌ " + error
                            + "\n\n→ Lancez d'abord TEST 5 pour autoriser la connexion ADB.");
                    setDisplaySizeBtnsEnabled(true);
                    AppLogger.log("DiagDisplaySize", "ERREUR: " + error);
                }});
            }
        });
    }

    private void restoreDisplaySize() {
        setDisplaySizeBtnsEnabled(false);
        tvDisplaySizeResult.setText("⏳ Restauration taille par défaut (cmd 30 + wm reset)…");
        tvDisplaySizeResult.setBackgroundColor(0xFF111A1A);
        AppLogger.log("DiagDisplaySize", "resetClusterDisplaySize");

        AdbLocalClient.resetClusterDisplaySize(DiagActivity.this,
                new AdbLocalClient.Callback() {
            @Override public void onSuccess(final String report) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    tvDisplaySizeResult.setBackgroundColor(0xFF1A1A2A);
                    tvDisplaySizeResult.setText(report);
                    setDisplaySizeBtnsEnabled(true);
                    AppLogger.log("DiagDisplaySize", report);
                }});
            }
            @Override public void onError(final String error) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    tvDisplaySizeResult.setBackgroundColor(0xFF2A1A1A);
                    tvDisplaySizeResult.setText("❌ " + error);
                    setDisplaySizeBtnsEnabled(true);
                }});
            }
        });
    }

    private void runClusterDisplaySizeTest() {
        setDisplaySizeBtnsEnabled(false);
        tvDisplaySizeResult.setText("⏳ Diagnostic dimensions cluster…\n"
                + "Essai cmd=29 / cmd=30 / cmd=31 / wm size…\n"
                + "(~8 secondes)");
        tvDisplaySizeResult.setBackgroundColor(0xFF111A1A);
        AppLogger.log("DiagDisplaySize", "Lancement TEST 12 complet");

        AdbLocalClient.runClusterDisplaySizeTest(DiagActivity.this, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvDisplaySizeResult.setBackgroundColor(0xFF1A2A1A);
                        tvDisplaySizeResult.setText(report);
                        setDisplaySizeBtnsEnabled(true);
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
                        setDisplaySizeBtnsEnabled(true);
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
                        // TEST 10 ne lance plus am start — vérifier les réponses parcel AutoContainer.
                        // Une réponse parcel valide contient "00000000" (parcel vide = succès).
                        // S'il n'y a pas d'erreur explicite dans le rapport → succès.
                        boolean ok = !report.contains("Exception")
                                && !report.contains("Error:")
                                && !report.contains("FAILED");
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

    // -------------------------------------------------------------------------
    // TEST 13 : Commandes ADAS cluster
    // -------------------------------------------------------------------------

    private void setAdasBtnsEnabled(boolean enabled) {
        btnAdas32.setEnabled(enabled);
        btnAdas33.setEnabled(enabled);
    }

    private void sendAdasCommand(final int adasCmd) {
        setAdasBtnsEnabled(false);
        String label = adasCmd == 32 ? "3d adas自刷新开启 — auto-refresh ON"
                     : "3d adas自刷新关闭 — auto-refresh OFF";
        tvAdasResult.setText("⏳ sendInfo(1000, " + adasCmd + ") → " + label + "…");
        tvAdasResult.setBackgroundColor(0xFF111A1A);
        AppLogger.log("DiagAdas", "sendAdasCommand(" + adasCmd + ")");

        AdbLocalClient.runAdasCommand(DiagActivity.this, adasCmd,
                new AdbLocalClient.Callback() {
            @Override public void onSuccess(final String report) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    boolean ok = report.contains("✅");
                    tvAdasResult.setBackgroundColor(ok ? 0xFF1A2A1A : 0xFF2A2A1A);
                    tvAdasResult.setText(report);
                    setAdasBtnsEnabled(true);
                    AppLogger.log("DiagAdas", report);
                }});
            }
            @Override public void onError(final String error) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    tvAdasResult.setBackgroundColor(0xFF2A1A1A);
                    tvAdasResult.setText("❌ " + error
                            + "\n\n→ Lancez d'abord TEST 5 pour autoriser la connexion ADB.");
                    setAdasBtnsEnabled(true);
                    AppLogger.log("DiagAdas", "ERREUR: " + error);
                }});
            }
        });
    }

    // -------------------------------------------------------------------------
    // TEST 14 : Masquage fenêtre ADAS — service "auto" BYD VHAL privé
    // -------------------------------------------------------------------------

    private void setAutoBtnsEnabled(boolean enabled) {
        btnAutoList.setEnabled(enabled);
        btnAutoHide.setEnabled(enabled);
        btnAutoShow.setEnabled(enabled);
        btnAutoReflect.setEnabled(enabled);
    }

    private void runAutoServiceList() {
        setAutoBtnsEnabled(false);
        tvAutoResult.setText("⏳ Listage services auto/byd…");
        tvAutoResult.setBackgroundColor(0xFF111A1A);
        AppLogger.log("DiagAuto", "runAutoServiceList()");

        AdbLocalClient.runAutoServiceList(DiagActivity.this, new AdbLocalClient.Callback() {
            @Override public void onSuccess(final String report) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    tvAutoResult.setBackgroundColor(0xFF1A1A2A);
                    tvAutoResult.setText(report);
                    setAutoBtnsEnabled(true);
                    AppLogger.log("DiagAuto", report);
                }});
            }
            @Override public void onError(final String error) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    tvAutoResult.setBackgroundColor(0xFF2A1A1A);
                    tvAutoResult.setText("❌ " + error
                            + "\n\n→ Lancez d'abord TEST 5 pour autoriser la connexion ADB.");
                    setAutoBtnsEnabled(true);
                }});
            }
        });
    }

    private void runAutoServiceCall(final boolean showAdas) {
        setAutoBtnsEnabled(false);
        String action = showAdas ? "ré-afficher" : "masquer";
        tvAutoResult.setText("⏳ service call auto N i32 1038 i32 944767020 i32 "
                + (showAdas ? 1 : 0) + " (" + action + ")…");
        tvAutoResult.setBackgroundColor(0xFF111A1A);
        AppLogger.log("DiagAuto", "runAutoServiceCall(showAdas=" + showAdas + ")");

        AdbLocalClient.runAutoServiceCall(DiagActivity.this, showAdas, new AdbLocalClient.Callback() {
            @Override public void onSuccess(final String report) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    tvAutoResult.setBackgroundColor(0xFF1A2A1A);
                    tvAutoResult.setText(report);
                    setAutoBtnsEnabled(true);
                    AppLogger.log("DiagAuto", report);
                }});
            }
            @Override public void onError(final String error) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    tvAutoResult.setBackgroundColor(0xFF2A1A1A);
                    tvAutoResult.setText("❌ " + error
                            + "\n\n→ Lancez d'abord TEST 5 pour autoriser la connexion ADB.");
                    setAutoBtnsEnabled(true);
                }});
            }
        });
    }

    /** Option B : tentative de reflection depuis notre app (sans ADB). */
    private void runAutoReflect() {
        setAutoBtnsEnabled(false);
        tvAutoResult.setText("⏳ Reflection getSystemService(\"auto\").setInt(1038, 944767020, 0)…");
        tvAutoResult.setBackgroundColor(0xFF111A1A);
        AppLogger.log("DiagAuto", "runAutoReflect()");

        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder sb = new StringBuilder();
                sb.append("── Option B : reflection app ──\n\n");

                // Tentative 1 : getSystemService("auto")
                try {
                    Object autoSvc = getSystemService("auto");
                    sb.append("getSystemService(\"auto\") → ")
                      .append(autoSvc == null ? "null (non dispo)" : autoSvc.getClass().getName())
                      .append("\n");

                    if (autoSvc != null) {
                        java.lang.reflect.Method setInt = autoSvc.getClass()
                                .getMethod("setInt", int.class, int.class, int.class);
                        int result = (int) setInt.invoke(autoSvc, 1038, 944767020, 0);
                        sb.append("setInt(1038, 944767020, 0) → ").append(result).append(" ✅\n");
                    }
                } catch (Exception e1) {
                    sb.append("Erreur getSystemService: ").append(e1).append("\n");
                }

                // Tentative 2 : ServiceManager.getService("auto") via reflection
                try {
                    Class<?> sm = Class.forName("android.os.ServiceManager");
                    android.os.IBinder binder = (android.os.IBinder)
                            sm.getMethod("getService", String.class).invoke(null, "auto");
                    sb.append("\nServiceManager.getService(\"auto\") → ")
                      .append(binder == null ? "null (service introuvable)"
                              : "IBinder[" + binder.getInterfaceDescriptor() + "]")
                      .append("\n");

                    if (binder != null) {
                        // Essayer transact direct comme dans c0/d.java case default
                        // setInt(1038, 944767020, 0) = 3 ints envoyés via transact
                        for (int code = 1; code <= 6; code++) {
                            try {
                                android.os.Parcel req = android.os.Parcel.obtain();
                                android.os.Parcel rep = android.os.Parcel.obtain();
                                req.writeInt(1038);
                                req.writeInt(944767020);
                                req.writeInt(0);
                                boolean ok = binder.transact(code, req, rep, 0);
                                sb.append("transact(").append(code).append(") → ok=")
                                  .append(ok).append("\n");
                                req.recycle(); rep.recycle();
                            } catch (Exception ex) {
                                sb.append("transact(").append(code).append(") → ❌ ").append(ex.getMessage()).append("\n");
                            }
                        }
                    }
                } catch (Exception e2) {
                    sb.append("Erreur ServiceManager: ").append(e2).append("\n");
                }

                runOnUiThread(new Runnable() { @Override public void run() {
                    tvAutoResult.setBackgroundColor(0xFF1A1A2A);
                    tvAutoResult.setText(sb.toString());
                    setAutoBtnsEnabled(true);
                    AppLogger.log("DiagAuto", sb.toString());
                }});
            }
        }, "auto-reflect-thread").start();
    }
}
