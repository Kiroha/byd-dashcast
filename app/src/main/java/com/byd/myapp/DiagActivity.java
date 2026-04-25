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
        // Tuer les précédents sniffeurs (pour éviter les doublons/fuite mémoire)
        stopSnifferSilently();
        
        android.widget.Toast.makeText(DiagActivity.this, "Sniffeur système démarré (Background)", android.widget.Toast.LENGTH_LONG).show();
        AppLogger.i("DiagSniffer", "Lancement du Sniffeur système...");
        
        java.io.File logFile = new java.io.File(getExternalFilesDir(null), SNIFFER_FILE_NAME);
        String logPath = logFile.getAbsolutePath();
        
        String logcatCmd = "logcat -v threadtime -b all >> " + logPath + " 2>&1";
        String amMonitorCmd = "am monitor >> " + logPath + " 2>&1";
        
        String dumpsysHeaderCmd = "echo '\n--- DUMPSYS SURFACEFLINGER ---' >> " + logPath + " && dumpsys SurfaceFlinger >> " + logPath;
        
        String fullCmd = "echo '--- MAIN SNIFFER STARTED ---' > " + logPath + " && nohup sh -c \"" + logcatCmd + "\" & nohup sh -c \"" + amMonitorCmd + "\" & nohup sh -c \"" + dumpsysHeaderCmd + "\" &";
        AdbLocalClient.executeShell(DiagActivity.this, fullCmd);
    }

    private void stopSnifferSilently() {
        // Enlève uniquement les instances logcat/am monitor liées à la capture
        String killCmd = "ps -A | grep 'logcat -v threadtime -b all' | awk '{print $2}' | xargs kill -9 2>/dev/null; " +
                         "ps -A | grep 'am monitor' | awk '{print $2}' | xargs kill -9 2>/dev/null; " + 
                         "ps -A | grep 'dumpsys SurfaceFlinger' | awk '{print $2}' | xargs kill -9 2>/dev/null";
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
        // /sdcard/mirrordaemon.log — écrit par MirrorDaemon via nohup app_process
        java.io.File logFile = new java.io.File(
                android.os.Environment.getExternalStorageDirectory(), "mirrordaemon.log");
        if (!logFile.exists() || logFile.length() == 0) {
            android.widget.Toast.makeText(this,
                    "mirrordaemon.log introuvable ou vide — daemon jamais lancé ?",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        try {
            // Copier dans un répertoire accessible au FileProvider
            java.io.File dst = new java.io.File(getExternalFilesDir(null), "mirrordaemon.log");
            try (java.io.InputStream in  = new java.io.FileInputStream(logFile);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", dst);
            android.content.Intent shareIntent = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "mirrordaemon.log");
            shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(shareIntent,
                    "Partager mirrordaemon.log"));
        } catch (Exception e) {
            AppLogger.e("DiagDaemon", "exportDaemonLog erreur", e);
            android.widget.Toast.makeText(this, "Erreur : " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
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
