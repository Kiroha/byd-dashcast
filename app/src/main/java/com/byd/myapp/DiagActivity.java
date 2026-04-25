package com.byd.myapp;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.content.Intent;

/**
 * DiagActivity — Outils de diagnostic et configuration.
 *
 * TEST 1 — Connexion ADB locale (prérequis, à faire une seule fois)
 * TEST 2 — Restauration cluster (sendInfo 30→16→18→0)
 * TEST 3 — Taille display cluster (cmd 29 / 30 / 31)
 * TEST 4 — Broadcast BOOT_COMPLETED vers BootReceiver Freedom (headless, sans UI)
 */
public class DiagActivity extends AppCompatActivity {

    // TEST 1 — Connexion ADB locale
    private TextView tvAdbLocalResult;
    private Button   btnAdbLocal;
    private Button   btnAdbShare;

    // TEST 2 — Restauration cluster
    private TextView tvDisplay1Result;
    private Button   btnDisplay1;
    private Button   btnDisplay1Share;

    // TEST 3 — Taille display cluster
    private TextView tvDisplaySizeResult;
    private Button   btnDisplaySize88;       // cmd 29 — 8.8"
    private Button   btnDisplaySize123;      // cmd 30 — 12.3"
    private Button   btnDisplaySize1025;     // cmd 31 — 10.25"
    private Button   btnDisplaySizeRestore;  // restauration
    private Button   btnDisplaySizeFull;     // diagnostic complet
    private Button   btnDisplaySizeShare;

    // TEST 4 — Broadcast BootReceiver Freedom
    private TextView tvBootReceiverResult;
    private Button   btnBootReceiver;
    private Button   btnBootReceiverShare;
    private Button   btnTestDaemon;
    private Button   btnStartSniffer;
    private Button   btnStopSniffer;
    private Button   btnExportSniffer;
    private Button   btnExportDaemonLog;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");

        tvAdbLocalResult      = (TextView) findViewById(R.id.tv_adb_local_result);
        btnAdbLocal           = (Button)   findViewById(R.id.btn_adb_local);
        btnAdbShare           = (Button)   findViewById(R.id.btn_adb_share);

        tvDisplay1Result      = (TextView) findViewById(R.id.tv_display1_result);
        btnDisplay1           = (Button)   findViewById(R.id.btn_display1);
        btnDisplay1Share      = (Button)   findViewById(R.id.btn_display1_share);

        tvDisplaySizeResult    = (TextView) findViewById(R.id.tv_display_size_result);
        btnDisplaySize88       = (Button)   findViewById(R.id.btn_display_size_88);
        btnDisplaySize123      = (Button)   findViewById(R.id.btn_display_size_123);
        btnDisplaySize1025     = (Button)   findViewById(R.id.btn_display_size_1025);
        btnDisplaySizeRestore  = (Button)   findViewById(R.id.btn_display_size_restore);
        btnDisplaySizeFull     = (Button)   findViewById(R.id.btn_display_size_full);
        btnDisplaySizeShare    = (Button)   findViewById(R.id.btn_display_size_share);

        tvBootReceiverResult  = (TextView) findViewById(R.id.tv_boot_receiver_result);
        btnBootReceiver       = (Button)   findViewById(R.id.btn_boot_receiver);
        btnBootReceiverShare  = (Button)   findViewById(R.id.btn_boot_receiver_share);
        btnTestDaemon = (Button) findViewById(R.id.btn_test_daemon);
        btnStartSniffer = (Button) findViewById(R.id.btn_start_sniffer);
        btnStopSniffer = (Button) findViewById(R.id.btn_stop_sniffer);
        btnExportSniffer = (Button) findViewById(R.id.btn_export_sniffer);
        btnExportDaemonLog = (Button) findViewById(R.id.btn_export_daemon_log);

        btnTestDaemon.setOnClickListener(v -> testLaunchFreedomDaemon());
        btnStartSniffer.setOnClickListener(v -> startSniffer());
        btnStopSniffer.setOnClickListener(v -> stopSniffer());
        btnExportSniffer.setOnClickListener(v -> exportSnifferReport());
        btnExportDaemonLog.setOnClickListener(v -> exportDaemonLog());

        // TEST 1 — Connexion ADB locale
        btnAdbShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, tvAdbLocalResult.getText().toString());
                startActivity(Intent.createChooser(intent, "Partager résultat TEST 1"));
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

        // TEST 2 — Restauration cluster
        btnDisplay1Share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, tvDisplay1Result.getText().toString());
                startActivity(Intent.createChooser(intent, "Partager résultat TEST 2"));
            }
        });
        btnDisplay1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDisplayOneLaunch();
            }
        });

        // TEST 3 — Taille display cluster
        btnDisplaySizeShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, tvDisplaySizeResult.getText().toString());
                startActivity(Intent.createChooser(intent, "Partager résultat TEST 3"));
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

        // TEST 4 — Broadcast BootReceiver Freedom
        btnBootReceiverShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, tvBootReceiverResult.getText().toString());
                startActivity(Intent.createChooser(intent, "Partager résultat TEST 4"));
            }
        });
        btnBootReceiver.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runBootReceiverTest();
            }
        });
    }


    // -------------------------------------------------------------------------
    // TEST 3 : Taille display cluster — helpers
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
                            + "\n\n→ Lancez d'abord TEST 1 pour autoriser la connexion ADB.");
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
        AppLogger.log("DiagDisplaySize", "Lancement TEST 3 complet");

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
                                + "\n\n→ Lancez d'abord TEST 1 pour autoriser la connexion ADB.");
                        setDisplaySizeBtnsEnabled(true);
                        AppLogger.log("DiagDisplaySize", "ERREUR: " + error);
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // TEST 2 : Lancement sur display 1 (cluster) — restauration cluster
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
                        // TEST 2 ne lance plus am start — vérifier les réponses parcel AutoContainer.
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
                                + "\n\n\u2192 Lancez d'abord TEST 1 pour autoriser la connexion ADB.");
                        btnDisplay1.setEnabled(true);
                        AppLogger.log("DiagDisplay1", "ERREUR: " + error);
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // TEST 4 : Broadcast BOOT_COMPLETED → BootReceiver Freedom (headless)
    // -------------------------------------------------------------------------

    private void runBootReceiverTest() {
        btnBootReceiver.setEnabled(false);
        tvBootReceiverResult.setText(
                "⏳ Force-stop Freedom puis broadcast BOOT_COMPLETED → BootReceiver…\n"
                + "Attente 5s pour création VirtualDisplay…");
        tvBootReceiverResult.setBackgroundColor(0xFF111A1A);
        AppLogger.log("DiagBootReceiver", "Lancement TEST 4");

        AdbLocalClient.sendBootReceiverBroadcast(DiagActivity.this, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        boolean ok = report.contains("✅");
                        tvBootReceiverResult.setBackgroundColor(ok ? 0xFF1A2A1A : 0xFF2A1A1A);
                        tvBootReceiverResult.setText(report);
                        btnBootReceiver.setEnabled(true);
                        AppLogger.log("DiagBootReceiver", report);
                    }
                });
            }
            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvBootReceiverResult.setBackgroundColor(0xFF2A1A1A);
                        tvBootReceiverResult.setText("❌ " + error
                                + "\n\n→ Lancez d'abord TEST 1 pour autoriser la connexion ADB.");
                        btnBootReceiver.setEnabled(true);
                        AppLogger.log("DiagBootReceiver", "ERREUR: " + error);
                    }
                });
            }
        });
    }

    private static final String SNIFFER_FILE_NAME = "BYD_Sniffer_Dump.txt";

    private void startSniffer() {
        stopSnifferSilently();

        android.widget.Toast.makeText(DiagActivity.this,
                "Sniffeur démarré — logcat filtré + snapshots 30s",
                android.widget.Toast.LENGTH_LONG).show();
        AppLogger.i("DiagSniffer", "Lancement du Sniffeur système...");

        java.io.File logFile = new java.io.File(getExternalFilesDir(null), SNIFFER_FILE_NAME);
        String p = logFile.getAbsolutePath();

        // ── Header enrichi (synchrone, crée le fichier) ──────────────────────
        // On vide d'abord le buffer logcat pour n'avoir que les événements futurs.
        String headerCmd =
            "logcat -c 2>/dev/null"
            + " && echo '=== BYD SNIFFER DUMP ===' > " + p
            + " && date >> " + p
            + " && echo '' >> " + p
            + " && echo '--- ROM ---' >> " + p
            + " && getprop ro.build.fingerprint >> " + p
            + " && getprop ro.build.version.release >> " + p
            + " && echo '' >> " + p
            + " && echo '--- PROCESSUS BYD/XDJA/DAEMON ---' >> " + p
            + " && (ps -A 2>/dev/null | grep -iE 'byd|xdja|freedom|daemon|mirror') >> " + p
            + " && echo '' >> " + p
            + " && echo '--- DISPLAYS (dumpsys display) ---' >> " + p
            + " && (dumpsys display 2>/dev/null | grep -E 'mDisplayId|mName|mState|fission|virtual|cluster' | head -25) >> " + p
            + " && echo '' >> " + p
            + " && echo '--- WINDOWS (dumpsys window displays) ---' >> " + p
            + " && (dumpsys window displays 2>/dev/null | head -50) >> " + p
            + " && echo '' >> " + p
            + " && echo '--- SURFACEFLINGER ---' >> " + p
            + " && (dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'display|fission|layer|cluster|mirror|virtual' | head -30) >> " + p
            + " && echo '' >> " + p
            + " && echo '--- MAIN SNIFFER STARTED ---' >> " + p;

        // ── Logcat filtré sur tags pertinents — évite le flood audio/AAudio ──
        // *:S = silence tout. Ensuite on réactive les tags BYD/display/crash.
        String logcatCmd =
            "logcat -v threadtime *:S"
            + " WindowManager:V ActivityManager:V SurfaceFlinger:V"
            + " AutoContainer:V MirrorDaemon:V"
            + " byd:V xdja:V freedom:V cluster:V"
            + " DEBUG:E dalvikvm:W art:W"
            + " >> " + p + " 2>&1";

        // ── Snapshots périodiques toutes les 30s ──────────────────────────────
        // \\$ → \$ dans la string Java → $ envoyé au sh interne (date se réduit dans sh -c)
        String snapshotCmd =
            "while true; do sleep 30;"
            + " echo '' >> " + p + ";"
            + " echo '--- SNAPSHOT '\\$(date +%H:%M:%S)' ---' >> " + p + ";"
            + " dumpsys display 2>/dev/null | grep -E 'mDisplayId|mState|fission|virtual' | head -10 >> " + p + ";"
            + " dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'display|fission|layer|cluster|mirror' | head -10 >> " + p + ";"
            + " ps -A 2>/dev/null | grep -iE 'byd|xdja|freedom|daemon|mirror' >> " + p + ";"
            + " done";

        String fullCmd = headerCmd
                + " && nohup sh -c \"" + logcatCmd + "\" &"
                + " nohup sh -c \"" + snapshotCmd + "\" &";

        AdbLocalClient.executeShell(DiagActivity.this, fullCmd);
    }

    private void stopSnifferSilently() {
        String killCmd =
            "ps -A | grep 'logcat -v threadtime' | awk '{print $2}' | xargs kill -9 2>/dev/null; "
            + "ps -A | grep 'am monitor' | awk '{print $2}' | xargs kill -9 2>/dev/null; "
            + "ps -A | grep 'dumpsys SurfaceFlinger' | awk '{print $2}' | xargs kill -9 2>/dev/null; "
            + "ps -A | grep 'sleep 30' | awk '{print $2}' | xargs kill -9 2>/dev/null";
        AdbLocalClient.executeShell(DiagActivity.this, killCmd);
    }

    private void stopSniffer() {
        AppLogger.i("DiagSniffer", "Arrêt du Sniffeur demandé");
        stopSnifferSilently();
        android.widget.Toast.makeText(DiagActivity.this, "Sniffeur arrêté (background clean)", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void exportSnifferReport() {
        java.io.File logFile = new java.io.File(getExternalFilesDir(null), SNIFFER_FILE_NAME);
        if (!logFile.exists() || logFile.length() == 0) {
            android.widget.Toast.makeText(DiagActivity.this, "Aucun rapport trouvé.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            android.net.Uri fileUri = androidx.core.content.FileProvider.getUriForFile(DiagActivity.this, getPackageName() + ".fileprovider", logFile);
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(shareIntent, "Partager le Sniffer"));
        } catch (Exception e) {
            AppLogger.e("DiagSniffer", "Erreur export", e);
        }
    }

    private void exportDaemonLog() {
        android.widget.Toast.makeText(this, "Lecture mirrordaemon.log via ADB…",
                android.widget.Toast.LENGTH_SHORT).show();
        AdbLocalClient.readFileViaAdb(this, "/data/local/tmp/mirrordaemon.log",
                "mirrordaemon.log", new AdbLocalClient.ReadFileCallback() {
            @Override
            public void onSuccess(java.io.File localCopy) {
                runOnUiThread(() -> {
                    try {
                        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                                DiagActivity.this,
                                getPackageName() + ".fileprovider", localCopy);
                        android.content.Intent shareIntent = new android.content.Intent(
                                android.content.Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                        shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT,
                                "mirrordaemon.log");
                        shareIntent.addFlags(
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(android.content.Intent.createChooser(
                                shareIntent, "Partager mirrordaemon.log"));
                    } catch (Exception e) {
                        AppLogger.e("DiagDaemon", "exportDaemonLog share erreur", e);
                        android.widget.Toast.makeText(DiagActivity.this,
                                "Erreur partage : " + e.getMessage(),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            }
            @Override
            public void onError(String error) {
                runOnUiThread(() -> android.widget.Toast.makeText(DiagActivity.this,
                        "mirrordaemon.log : " + error,
                        android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    private void testLaunchFreedomDaemon() {
        // Non fonctionnel : app_process n'est pas accessible depuis uid=10100,
        // et CommunicationProcessKt appartient à com.byd.windowmanager (WindowManagement),
        // pas à notre APK. La commande échoue silencieusement en background.
        android.widget.Toast.makeText(this,
                "Expérimental — non fonctionnel\n(app_process inaccessible depuis uid=10100)",
                android.widget.Toast.LENGTH_LONG).show();
        AppLogger.w("DiagDaemon", "testLaunchFreedomDaemon() — non fonctionnel sur cette ROM (uid=10100 sans accès app_process)");
    }
}
