package com.byd.myapp;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.content.Intent;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;

/**
 * DiagActivity — Diagnostic tools and configuration.
 *
 * TEST 1 — Local ADB connection (prerequisite, to run once)
 * TEST 2 — Cluster restore (sendInfo 30→16→18→0)
 * TEST 3 — Cluster display size (cmd 29 / 30 / 31)
 */
public class DiagActivity extends AppCompatActivity {

    // TEST 1 — Local ADB connection
    private TextView tvAdbLocalResult;
    private Button   btnAdbLocal;
    private Button   btnAdbShare;

    // TEST 2 — Cluster restore
    private TextView tvDisplay1Result;
    private Button   btnDisplay1;
    private Button   btnDisplay1Share;

    // TEST 3 — Cluster display size
    private TextView tvDisplaySizeResult;
    private Button   btnDisplaySize88;       // cmd 29 — 8.8"
    private Button   btnDisplaySize123;      // cmd 30 — 12.3"
    private Button   btnDisplaySize1025;     // cmd 31 — 10.25"
    private Button   btnDisplaySizeRestore;  // restore
    private Button   btnDisplaySizeFull;     // full diagnostic
    private Button   btnDisplaySizeShare;

    private Button   btnDumpSfMirror;
    private TextView tvSfDumpResult;

    // TEST 13 — JNI Qt Surface
    private Button   btnTest13;
    private TextView tvTest13Result;

    // TEST 15
    private Button   btnDumpsysWindows;
    private TextView tvDumpsysResult;

    // TEST 16
    private Button btnDaemonVdTest;
    private TextView tvDaemonVdResult;

    // SNIFFER RE
    private Button   btnReSnifferStart;
    private Button   btnReSnifferStop;
    private Button   btnReSnifferSnapshot;
    private Button   btnReSnifferExport;
    private TextView tvReSnifferStatus;
    private java.io.File mReSnifferFile = null;

    // AutoDisplayService
    private Button   btnAutoDisplayStart;
    private Button   btnAutoDisplayStop;
    private TextView tvAutoDisplayResult;

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
        tvAdbLocalResult.setTag("ADB Local");
        btnAdbLocal           = (Button)   findViewById(R.id.btn_adb_local);
        btnAdbShare           = (Button)   findViewById(R.id.btn_adb_share);

        tvDisplay1Result      = (TextView) findViewById(R.id.tv_display1_result);
        tvDisplay1Result.setTag("Display Info");
        btnDisplay1           = (Button)   findViewById(R.id.btn_display1);
        btnDisplay1Share      = (Button)   findViewById(R.id.btn_display1_share);

        tvDisplaySizeResult    = (TextView) findViewById(R.id.tv_display_size_result);
        tvDisplaySizeResult.setTag("Display Size");
        btnDisplaySize88       = (Button)   findViewById(R.id.btn_display_size_88);
        btnDisplaySize123      = (Button)   findViewById(R.id.btn_display_size_123);
        btnDisplaySize1025     = (Button)   findViewById(R.id.btn_display_size_1025);
        btnDisplaySizeRestore  = (Button)   findViewById(R.id.btn_display_size_restore);
        btnDisplaySizeFull     = (Button)   findViewById(R.id.btn_display_size_full);
        btnDisplaySizeShare    = (Button)   findViewById(R.id.btn_display_size_share);

        btnDumpSfMirror = (Button) findViewById(R.id.btn_dump_sf_mirror);
        tvSfDumpResult = (TextView) findViewById(R.id.tv_sf_dump_result);
        tvSfDumpResult.setTag("SurfaceFlinger Dump");
        btnAutoDisplayStart  = (Button)   findViewById(R.id.btn_auto_display_start);
        btnAutoDisplayStop   = (Button)   findViewById(R.id.btn_auto_display_stop);
        tvAutoDisplayResult  = (TextView) findViewById(R.id.tv_auto_display_result);
        tvAutoDisplayResult.setTag("AutoDisplayService");

        // TEST 13
        btnTest13      = (Button)   findViewById(R.id.btn_test_13);
        tvTest13Result = (TextView) findViewById(R.id.tv_test_13_result);
        tvTest13Result.setTag("JNI Qt Surface Probe");

        // TEST 15
        btnDumpsysWindows = (Button) findViewById(R.id.btn_dumpsys_windows);
        tvDumpsysResult   = (TextView) findViewById(R.id.tv_dumpsys_result);
        tvDumpsysResult.setTag("Dumpsys Windows");

        // TEST 16
        btnDaemonVdTest = (Button) findViewById(R.id.btn_daemon_vd_test);
        tvDaemonVdResult = (TextView) findViewById(R.id.tv_daemon_vd_result);
        tvDaemonVdResult.setTag("Daemon VD");

        // SNIFFER RE
        btnReSnifferStart    = (Button)   findViewById(R.id.btn_re_sniffer_start);
        btnReSnifferStop     = (Button)   findViewById(R.id.btn_re_sniffer_stop);
        btnReSnifferSnapshot = (Button)   findViewById(R.id.btn_re_sniffer_snapshot);
        btnReSnifferExport   = (Button)   findViewById(R.id.btn_re_sniffer_export);
        tvReSnifferStatus    = (TextView) findViewById(R.id.tv_re_sniffer_status);
        tvReSnifferStatus.setTag("RE Sniffer");

        btnReSnifferStart   .setOnClickListener(v -> startReSniffer());
        btnReSnifferStop    .setOnClickListener(v -> stopReSniffer());
        btnReSnifferSnapshot.setOnClickListener(v -> snapshotReSniffer());
        btnReSnifferExport  .setOnClickListener(v -> exportReSniffer());

        setupShareOnLongClick();

        btnDumpSfMirror.setOnClickListener(v -> dumpSurfaceFlinger());
        btnAutoDisplayStart.setOnClickListener(v -> startAutoDisplayService());
        btnAutoDisplayStop .setOnClickListener(v -> stopAutoDisplayService());

        btnTest13.setOnClickListener(v -> runJniSurfaceProbe());
        btnDumpsysWindows.setOnClickListener(v -> runDumpsysWindows());
        btnDaemonVdTest.setOnClickListener(v -> runDaemonVdTest());

        // TEST 1 — Local ADB connection
        btnAdbShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, tvAdbLocalResult.getText().toString());
                startActivity(Intent.createChooser(intent, getString(R.string.diag_share_result1_btn)));
            }
        });
        btnAdbLocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAdbLocal.setEnabled(false);
                tvAdbLocalResult.setText(getString(R.string.diag_adb_connecting));
                AppLogger.log("DiagADB", "Starting local ADB connection");
                AdbLocalClient.connectAndGrant(DiagActivity.this,
                        new AdbLocalClient.Callback() {
                    @Override
                    public void onSuccess(final String report) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvAdbLocalResult.setText(getString(R.string.diag_adb_connected) + report);
                                btnAdbLocal.setEnabled(true);
                            }
                        });
                    }
                    @Override
                    public void onError(final String error) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvAdbLocalResult.setText(
                                        getString(R.string.diag_adb_failed, error));
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
                startActivity(Intent.createChooser(intent, getString(R.string.diag_share_result2_btn)));
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
                startActivity(Intent.createChooser(intent, getString(R.string.diag_share_result3_btn)));
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
        tvDisplaySizeResult.setText(getString(R.string.diag_size_sending, sizeCmd, label));
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
                            + "\n\n" + getString(R.string.diag_adb_test1_hint));
                    setDisplaySizeBtnsEnabled(true);
                    AppLogger.log("DiagDisplaySize", "ERREUR: " + error);
                }});
            }
        });
    }

    private void restoreDisplaySize() {
        setDisplaySizeBtnsEnabled(false);
        tvDisplaySizeResult.setText(getString(R.string.diag_size_restoring));
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
        tvDisplaySizeResult.setText(getString(R.string.diag_size_full_running));
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
                                + "\n\n" + getString(R.string.diag_adb_test1_hint));
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
        tvDisplay1Result.setText(getString(R.string.diag_launching_display1));
        AppLogger.log("DiagDisplay1", "display 1 launch started");

        AdbLocalClient.runDisplayOneLaunch(DiagActivity.this, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // TEST 2 no longer starts am start — check AutoContainer parcel responses.
                        // A valid parcel response contains "00000000" (empty parcel = success).
                        // If there is no explicit error in the report → success.
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
                        tvDisplay1Result.setText("❌ " + error
                                + "\n\n" + getString(R.string.diag_adb_test1_hint));
                        btnDisplay1.setEnabled(true);
                        AppLogger.log("DiagDisplay1", "ERREUR: " + error);
                    }
                });
            }
        });
    }



    // -------------------------------------------------------------------------
    // AutoDisplayService — start/stop via ADB local
    // Chemin direct : Qt Surface native → createVirtualDisplay (mécanisme Dilink5)
    // -------------------------------------------------------------------------

    private static final String AUTO_DISPLAY_PKG = "com.xdja.containerservice";
    private static final String AUTO_DISPLAY_SVC = AUTO_DISPLAY_PKG + "/.AutoDisplayService";

    private void startAutoDisplayService() {
        tvAutoDisplayResult.setText("Démarrage via ADB...");
        tvAutoDisplayResult.setTextColor(0xFFFFAB40);
        btnAutoDisplayStart.setEnabled(false);

        String cmd = "am startservice " + AUTO_DISPLAY_SVC
                + " 2>&1"
                // Vérifier si le display est apparu après 1s
                + " && sleep 1"
                + " && dumpsys display 2>/dev/null | grep -E 'mDisplayId|mName|mState|virtual|fission' | head -20";

        AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) {
                runOnUiThread(() -> {
                    boolean started = !report.contains("Error") && !report.contains("not found");
                    boolean newDisplay = report.contains("mDisplayId=1")
                            || report.contains("remote_dashboard")
                            || report.contains("fission");
                    String status = started ? "✅ Service démarré\n" : "⚠️ Réponse inattendue\n";
                    status += newDisplay ? "✅ Nouveau display détecté !\n" : "⚠️ Aucun nouveau display (normal si Qt pas prêt)\n";
                    status += "\n" + report.trim();
                    tvAutoDisplayResult.setText(status);
                    tvAutoDisplayResult.setTextColor(newDisplay ? 0xFF69F0AE : 0xFFFFAB40);
                    btnAutoDisplayStart.setEnabled(true);
                    AppLogger.i("AutoDisplay", report);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvAutoDisplayResult.setText("❌ " + error);
                    tvAutoDisplayResult.setTextColor(0xFFFF5252);
                    btnAutoDisplayStart.setEnabled(true);
                });
            }
        });
    }

    private void stopAutoDisplayService() {
        tvAutoDisplayResult.setText("Arrêt via ADB...");
        tvAutoDisplayResult.setTextColor(0xFFFFAB40);
        AdbLocalClient.executeShellWithResult(this,
                "am stopservice " + AUTO_DISPLAY_SVC + " 2>&1",
                new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) {
                runOnUiThread(() -> {
                    tvAutoDisplayResult.setText("STOP: " + report.trim());
                    tvAutoDisplayResult.setTextColor(0xFFFF5252);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvAutoDisplayResult.setText("❌ " + error);
                    tvAutoDisplayResult.setTextColor(0xFFFF5252);
                });
            }
        });
    }

    private void dumpSurfaceFlinger() {
        tvSfDumpResult.setText(getString(R.string.diag_sf_dumping));
        tvSfDumpResult.setTextColor(0xFFAAAAAA);
        // Filter on our display + layerStack=2 to check if SF knows about the mirror
        String cmd = "dumpsys SurfaceFlinger 2>/dev/null"
                + " | grep -iE 'byd_myapp_mirror|layerStack=2|fission_bg|virtual'";
        AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) {
                runOnUiThread(() -> {
                    String text = report.trim().isEmpty()
                            ? getString(R.string.diag_sf_no_result)
                            : report.trim();
                    tvSfDumpResult.setText(text);
                    boolean found = report.contains("byd_myapp_mirror");
                    tvSfDumpResult.setTextColor(found ? 0xFF69F0AE : 0xFFFF5252);
                    AppLogger.i("SFDump", "SF dump :\n" + text);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvSfDumpResult.setText(getString(R.string.diag_sf_error, error));
                    tvSfDumpResult.setTextColor(0xFFFF5252);
                });
            }
        });
    }

    // TEST 13 — JNI Surface Probe
    private void runJniSurfaceProbe() {
        tvTest13Result.setText("Liberating Qt Display (sendInfo 16)...");
        btnTest13.setEnabled(false);
        AppLogger.log("DiagJNI", "Starting TEST 13 JNI Surface Probe");

        // 1. Release display
        AdbLocalClient.sendInfo(this, 1000, 16, "", new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(String ignored) {
                runOnUiThread(() -> tvTest13Result.setText("Display released. Probing JNI..."));
                new Thread(() -> {
                    try {
                        Thread.sleep(1000);
                        com.xdja.containerservice.ContainerService.ensureLoaded();
                        com.xdja.containerservice.QtDisplayInfo[] arr = com.xdja.containerservice.ContainerService.getQtProjectionDispInfoArray();
                        com.xdja.containerservice.QtDisplayInfo info = com.xdja.containerservice.ContainerService.getQtProjectionDispInfo(0);

                        StringBuilder res = new StringBuilder();
                        res.append("JNI LOAD: ").append(com.xdja.containerservice.ContainerService.isLoaded).append("\n");
                        if (info != null) {
                            res.append("✅ SUCCESS Qt(0):\n").append(info.toString()).append("\n");
                        } else {
                            res.append("❌ FAIL Qt(0) returned null\n");
                        }
                        if (arr != null) {
                            res.append("✅ SUCCESS Array Size: ").append(arr.length).append("\n");
                            for (int i = 0; i < arr.length; i++) {
                                res.append(" - [").append(i).append("]: ").append(arr[i]).append("\n");
                            }
                        } else {
                            res.append("❌ FAIL Array returned null\n");
                        }

                        AppLogger.log("DiagJNI", res.toString());
                        runOnUiThread(() -> {
                            tvTest13Result.setText(res.toString());
                            btnTest13.setEnabled(true);
                        });
                    } catch (Exception e) {
                        AppLogger.e("DiagJNI", "Exception", e);
                        runOnUiThread(() -> {
                            tvTest13Result.setText("FATAL: " + e.getMessage());
                            btnTest13.setEnabled(true);
                        });
                    }
                }).start();
            }

            @Override
            public void onError(String e) {
                runOnUiThread(() -> {
                    tvTest13Result.setText("Failed to send 16: " + e);
                    btnTest13.setEnabled(true);
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // TEST 15 : Dumpsys window displays
    // -------------------------------------------------------------------------
    private void runDumpsysWindows() {
        tvDumpsysResult.setText("Execution via adb local...");
        btnDumpsysWindows.setEnabled(false);
        AdbLocalClient.executeShellWithResult(this, "dumpsys window displays", new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(final String report) {
                runOnUiThread(() -> {
                    tvDumpsysResult.setText(report);
                    btnDumpsysWindows.setEnabled(true);
                });
            }

            @Override
            public void onError(final String error) {
                runOnUiThread(() -> {
                    tvDumpsysResult.setText("ERREUR:\n" + error);
                    btnDumpsysWindows.setEnabled(true);
                });
            }
        });
    }

    private static final long DAEMON_TIMEOUT_MS = 15_000;

    private void runDaemonVdTest() {
        tvDaemonVdResult.setText("Lancement du daemon via adb local...");
        btnDaemonVdTest.setEnabled(false);

        // Timeout de sécurité : réactive le bouton si ADB ne répond pas dans les 15s
        android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timeoutAction = () -> {
            tvDaemonVdResult.setText("TIMEOUT : ADB n'a pas répondu dans " + (DAEMON_TIMEOUT_MS / 1000) + "s.");
            btnDaemonVdTest.setEnabled(true);
        };
        timeoutHandler.postDelayed(timeoutAction, DAEMON_TIMEOUT_MS);

        try {
            String apkPath = getPackageManager().getApplicationInfo(getPackageName(), 0).sourceDir;
            String cmd = "app_process -Djava.class.path=" + apkPath + " /system/bin com.byd.myapp.dashboard.DashCastDaemon";
            AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
                @Override
                public void onSuccess(final String report) {
                    timeoutHandler.removeCallbacks(timeoutAction);
                    runOnUiThread(() -> {
                        tvDaemonVdResult.setText("DAEMON OUTPUT: " + report);
                        btnDaemonVdTest.setEnabled(true);
                    });
                }
                @Override
                public void onError(final String error) {
                    timeoutHandler.removeCallbacks(timeoutAction);
                    runOnUiThread(() -> {
                        tvDaemonVdResult.setText("ERREUR: " + error);
                        btnDaemonVdTest.setEnabled(true);
                    });
                }
            });
        } catch (Exception e) {
            timeoutHandler.removeCallbacks(timeoutAction);
            tvDaemonVdResult.setText("Erreur APK path: " + e.getMessage());
            btnDaemonVdTest.setEnabled(true);
        }
    }

    // =========================================================================
    // SNIFFER SYSTÈME — Reverse Engineering
    // =========================================================================
    // Capture logcat + dumpsys périodiques dans un fichier exportable.
    // Conçu pour intercepter TOUT ce qui se passe sur le système BYD
    // sans dépendre de Freedom.
    // =========================================================================

    private static final String RE_SNIFFER_TAG    = ".re_sniffer_run";
    private static final String RE_SNIFFER_PIDS    = ".re_sniffer_pids";
    private static final String RE_SNIFFER_PREFIX  = "BYD_RE_Sniffer_";

    // =========================================================================
    // Partage rapide — appui long sur n'importe quel TextView résultat
    // =========================================================================

    private void setupShareOnLongClick() {
        TextView[] results = {
            tvAdbLocalResult, tvDaemonVdResult, tvDisplaySizeResult,
            tvDisplay1Result, tvDumpsysResult, tvTest13Result, tvSfDumpResult,
            tvAutoDisplayResult, tvReSnifferStatus
        };
        for (TextView tv : results) {
            if (tv == null) continue;
            tv.setLongClickable(true);
            tv.setOnLongClickListener(v -> {
                String text = tv.getText().toString().trim();
                if (text.isEmpty() || text.equals("--")) {
                    android.widget.Toast.makeText(this,
                            "Pas de résultat à partager.",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                shareText(tv.getTag() != null ? tv.getTag().toString() : "DashCast Diag", text);
                return true;
            });
        }
    }

    /**
     * Lance un Intent de partage texte vers Telegram (en priorité) ou
     * n'importe quelle app de messagerie via le sélecteur système.
     */
    private void shareText(String label, String body) {
        String full = "[DashCast / " + label + "]\n" + body;

        // Tenter Telegram en direct
        Intent tg = new Intent(Intent.ACTION_SEND);
        tg.setType("text/plain");
        tg.setPackage("org.telegram.messenger");
        tg.putExtra(Intent.EXTRA_TEXT, full);
        try {
            startActivity(tg);
            return;
        } catch (android.content.ActivityNotFoundException ignored) {}

        // Fallback : sélecteur générique
        Intent generic = new Intent(Intent.ACTION_SEND);
        generic.setType("text/plain");
        generic.putExtra(Intent.EXTRA_TEXT, full);
        startActivity(Intent.createChooser(generic, "Partager résultat"));
    }

    private java.io.File buildReSnifferFile() {
        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new java.io.File(dir, RE_SNIFFER_PREFIX + ts + ".txt");
    }

    private void startReSniffer() {
        killReSnifferProcesses();

        mReSnifferFile = buildReSnifferFile();
        final String p  = mReSnifferFile.getAbsolutePath();
        final String pf = "/data/local/tmp/" + RE_SNIFFER_PIDS;
        AppLogger.i("RESniffer", "Starting RE Sniffer → " + p);

        runOnUiThread(() -> {
            tvReSnifferStatus.setText("Initialisation...");
            tvReSnifferStatus.setTextColor(0xFFFFAB40);
        });

        // ── Étape 1 : header rapide (synchrone via executeShellWithResult) ─────
        // IMPORTANT : on utilise executeShellWithResult pour s'assurer que le fichier
        // existe et que le tag est posé AVANT de lancer les processus background.
        // On évite service list / dumpsys window / dumps broadcasts → trop lents.
        String headerCmd =
            "logcat -c 2>/dev/null"
            + " ; touch /data/local/tmp/" + RE_SNIFFER_TAG
            + " ; echo === BYD RE SNIFFER === > " + p
            + " ; date >> " + p
            + " ; getprop ro.product.model >> " + p
            + " ; getprop ro.build.fingerprint >> " + p
            + " ; echo --- DISPLAYS INITIAL --- >> " + p
            + " ; dumpsys display 2>/dev/null >> " + p
            + " ; echo --- SURFACEFLINGER INITIAL --- >> " + p
            + " ; dumpsys SurfaceFlinger 2>/dev/null >> " + p
            + " ; echo --- PROCESSUS INITIAL --- >> " + p
            + " ; ps -A 2>/dev/null >> " + p
            + " ; echo === LIVE CAPTURE START === >> " + p;

        AdbLocalClient.executeShellWithResult(this, headerCmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                // ── Étape 2 : processus background avec setsid ────────────────
                // setsid crée une nouvelle session → survit à la fermeture de la session ADB.
                // nohup seul ne suffit pas : adbd envoie SIGHUP au groupe de processus.
                // On capture TOUT le logcat (pas de filtre tag) → rien n'est manqué.
                // Les PIDs sont sauvés avec $! pour un kill propre.

                // Snapshot toutes les 10s : pas de simples-quotes dans le corps
                // (la commande est wrappée dans sh -c '...' — les ' casseraient l'arg)
                String snapLoop =
                    "while [ -f /data/local/tmp/" + RE_SNIFFER_TAG + " ]; do sleep 10;"
                    + " echo >> " + p + ";"
                    + " printf \"=== SNAP %s ===\\n\" $(date +%H:%M:%S) >> " + p + ";"
                    + " dumpsys display 2>/dev/null"
                    + "   | grep -E \"mDisplayId|mName|mState|fission|virtual|cluster|layerStack\""
                    + "   >> " + p + ";"
                    + " dumpsys SurfaceFlinger 2>/dev/null"
                    + "   | grep -iE \"display|fission|layer|cluster|mirror|virtual|qt\""
                    + "   | head -30 >> " + p + ";"
                    + " ps -A 2>/dev/null"
                    + "   | grep -iE \"byd|xdja|daemon|dilink|qt|cluster|app_process\""
                    + "   >> " + p + ";"
                    + " done";

                // Reset PID file, lance 3 processus setsid, sauve $! après chaque &
                String bgCmd =
                    "echo > " + pf
                    + " ; setsid sh -c 'logcat -v threadtime >> " + p + " 2>&1'"
                    + "   & echo $! >> " + pf
                    + " ; setsid sh -c '" + snapLoop + "'"
                    + "   & echo $! >> " + pf
                    + " ; setsid sh -c 'logcat -b events -v time >> " + p + " 2>&1'"
                    + "   & echo $! >> " + pf;

                AdbLocalClient.executeShell(DiagActivity.this, bgCmd);

                runOnUiThread(() -> {
                    tvReSnifferStatus.setText("ACTIF → " + mReSnifferFile.getName());
                    tvReSnifferStatus.setTextColor(0xFF69F0AE);
                    android.widget.Toast.makeText(DiagActivity.this,
                            "Sniffer démarré : " + mReSnifferFile.getName(),
                            android.widget.Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onError(String err) {
                runOnUiThread(() -> {
                    tvReSnifferStatus.setText("❌ Échec init: " + err);
                    tvReSnifferStatus.setTextColor(0xFFFF5252);
                });
            }
        });
    }

    /** Tue proprement tous les processus du sniffer via PID file + pkill fallback. */
    private void killReSnifferProcesses() {
        String pidFile = "/data/local/tmp/" + RE_SNIFFER_PIDS;
        String killCmd =
            "rm -f /data/local/tmp/" + RE_SNIFFER_TAG
            + " ; if [ -f " + pidFile + " ]; then"
            + "   while IFS= read -r pid; do"
            + "     [ -n \"$pid\" ] && kill -9 \"$pid\" 2>/dev/null; done < " + pidFile + ";"
            + "   rm -f " + pidFile + ";"
            + " fi"
            + " ; pkill -f " + RE_SNIFFER_PREFIX + " 2>/dev/null; true";
        AdbLocalClient.executeShell(this, killCmd);
    }

    private void stopReSniffer() {
        killReSnifferProcesses();
        final String fileName = mReSnifferFile != null ? mReSnifferFile.getName() : "aucun";
        if (mReSnifferFile != null) {
            AdbLocalClient.executeShell(this,
                    "echo '[RE Sniffer] Stopped.' >> " + mReSnifferFile.getAbsolutePath());
        }
        runOnUiThread(() -> {
            tvReSnifferStatus.setText("Arrêté — fichier : " + fileName);
            tvReSnifferStatus.setTextColor(0xFFFF5252);
            android.widget.Toast.makeText(this, "Sniffer arrêté.", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void snapshotReSniffer() {
        if (mReSnifferFile == null) {
            android.widget.Toast.makeText(this, "Démarrer le sniffer d'abord.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final String p = mReSnifferFile.getAbsolutePath();
        String cmd =
            "echo '' >> " + p
            + " && echo '=== SNAPSHOT MANUEL '$(date +%H:%M:%S)' ===' >> " + p
            + " && echo '--- DISPLAYS ---' >> " + p
            + " && dumpsys display 2>/dev/null >> " + p
            + " && echo '--- WINDOWS ---' >> " + p
            + " && dumpsys window 2>/dev/null >> " + p
            + " && echo '--- SURFACEFLINGER ---' >> " + p
            + " && dumpsys SurfaceFlinger 2>/dev/null >> " + p
            + " && echo '--- PROCESSUS ---' >> " + p
            + " && ps -A >> " + p
            + " && echo '--- BROADCASTS ---' >> " + p
            + " && dumpsys activity broadcasts history 2>/dev/null >> " + p;
        AdbLocalClient.executeShell(this, cmd);
        android.widget.Toast.makeText(this, "Snapshot injecté dans le fichier.", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void exportReSniffer() {
        java.io.File logFile = mReSnifferFile;
        if (logFile == null || !logFile.exists() || logFile.length() == 0) {
            android.widget.Toast.makeText(this, "Aucun fichier sniffer à exporter.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", logFile);
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, logFile.getName());
            shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(shareIntent, "Exporter Sniffer RE"));
        } catch (Exception e) {
            AppLogger.e("RESniffer", "Export erreur", e);
        }
    }
}
