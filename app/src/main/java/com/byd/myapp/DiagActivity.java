package com.byd.myapp;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Surface;
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
 * TEST 2 — Cluster restore (sendInfo 30→sleep6s→16→sleep6s→35)
 * TEST 3 — Cluster display size (cmd 29 / 30 / 31)
 */
public class DiagActivity extends AppCompatActivity {

    // [1] ADB Local
    private TextView tvAdbLocalResult;
    private Button   btnAdbLocal;
    private Button   btnAdbShare;

    // [2] Cluster restore
    private TextView tvDisplay1Result;
    private Button   btnDisplay1;
    private Button   btnDisplay1Share;

    // [3] Propriétés Cluster — display size
    private TextView tvDisplaySizeResult;
    private Button   btnDisplaySize88;       // cmd 29 — 8.8"
    private Button   btnDisplaySize123;      // cmd 30 — 12.3"
    private Button   btnDisplaySize1025;     // cmd 31 — 10.25"
    private Button   btnDisplaySizeRestore;  // restore
    private Button   btnDisplaySizeFull;     // full diagnostic
    private Button   btnDisplaySizeShare;

    // [4] Analyses Système
    private Button   btnDumpSfMirror;
    private TextView tvSfDumpResult;

    // JNI Qt Surface Probe
    private Button   btnTest13;
    private Button   btnJniStartProbe;
    private TextView tvTest13Result;

    // Dumpsys Windows
    private Button   btnDumpsysWindows;
    private TextView tvDumpsysResult;

    // Daemon VD (app_process)
    private Button   btnDaemonVdTest;
    private TextView tvDaemonVdResult;

    // AutoDisplayService
    private Button   btnAutoDisplayStart;
    private Button   btnAutoDisplayStop;
    private TextView tvAutoDisplayResult;

    // [5] Fission via IBinder direct (mécanisme Freedom)
    private Button        btnFissionViaBinder;
    private Button        btnFissionPipeline;
    private Button        btnFissionLaunch;
    private TextView      tvFissionResult;
    private int           mFissionDisplayId = -1;
    private VirtualDisplay mClusterVirtualDisplay = null;

    private Button   btnReSnifferStart;
    private Button   btnReSnifferStop;
    private Button   btnReSnifferSnapshot;
    private Button   btnReSnifferExport;
    private TextView tvReSnifferStatus;
    private java.io.File mReSnifferFile = null;

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

        btnFissionViaBinder = (Button)   findViewById(R.id.btn_fission_via_binder);
        btnFissionPipeline  = (Button)   findViewById(R.id.btn_fission_pipeline);
        btnFissionLaunch    = (Button)   findViewById(R.id.btn_fission_launch);
        tvFissionResult     = (TextView) findViewById(R.id.tv_fission_result);
        tvFissionResult.setTag("Fission Pipeline");
        tvAutoDisplayResult.setTag("AutoDisplayService");

        // JNI Qt Surface Probe
        btnTest13      = (Button)   findViewById(R.id.btn_test_13);
        btnJniStartProbe = (Button) findViewById(R.id.btn_jni_start_probe);
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

        btnFissionViaBinder.setOnClickListener(v -> runFissionViaBinder());
        btnFissionPipeline.setOnClickListener(v -> runFissionPipeline());
        btnFissionLaunch  .setOnClickListener(v -> launchOnFissionDisplay());

        btnTest13.setOnClickListener(v -> runJniSurfaceProbe());
        btnJniStartProbe.setOnClickListener(v -> runStartAndProbe());
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
    // Start containerservice + JNI Probe (combiné)
    // -------------------------------------------------------------------------
    // Exécute une commande shell et retourne le résultat (bloquant, max 5s)
    private String shellSync(String cmd) {
        String[] out = {""};
        java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String r) { out[0] = r.trim(); l.countDown(); }
            @Override public void onError(String e)   { out[0] = "ERR:" + e; l.countDown(); }
        });
        try { l.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return out[0];
    }

    private void runStartAndProbe() {
        tvTest13Result.setText("Diagnostic en cours...");
        btnJniStartProbe.setEnabled(false);
        btnTest13.setEnabled(false);

        new Thread(() -> {
            StringBuilder res = new StringBuilder();

            // 1. Vérifier si Qt / containerservice tournent AVANT de démarrer
            String psXdja = shellSync("ps -A | grep xdja");
            String psQt   = shellSync("ps -A | grep -i 'qt\\|dilink\\|mecanum'");
            res.append("── Processus avant start ──\n");
            res.append("containerservice: ").append(psXdja.isEmpty() ? "❌ absent" : "✅ " + psXdja.replace("\n", " | ")).append("\n");
            res.append("Qt/dilink: ").append(psQt.isEmpty() ? "❌ absent" : "✅ " + psQt.replace("\n", " | ")).append("\n\n");

            // 2. Displays actifs avant
            String dispBefore = shellSync("dumpsys display | grep -E 'mDisplayId|mName|fission|remote_dash'");
            res.append("── Displays avant start ──\n").append(dispBefore.isEmpty() ? "(aucun fission/remote_dashboard)" : dispBefore).append("\n\n");

            runOnUiThread(() -> tvTest13Result.setText(res.toString()));

            // 3. Démarrer AutoDisplayService
            String startResult = shellSync("am startservice com.xdja.containerservice/.AutoDisplayService");
            res.append("── am startservice ──\n").append(startResult).append("\n\n");
            runOnUiThread(() -> tvTest13Result.setText(res.toString()));

            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

            // 4. Vérifier les displays APRÈS
            String dispAfter = shellSync("dumpsys display | grep -E 'mDisplayId|mName|fission|remote_dash'");
            res.append("── Displays après 2s ──\n").append(dispAfter.isEmpty() ? "❌ toujours aucun fission/remote_dashboard\n→ Qt n'a pas enregistré sa Surface (JNI null)" : "✅ " + dispAfter).append("\n\n");

            // 5. JNI probe
            com.xdja.containerservice.ContainerService.ensureLoaded();
            com.xdja.containerservice.QtDisplayInfo[] arr = com.xdja.containerservice.ContainerService.getQtProjectionDispInfoArray();
            com.xdja.containerservice.QtDisplayInfo info  = com.xdja.containerservice.ContainerService.getQtProjectionDispInfo(0);

            res.append("── JNI probe ──\n");
            res.append("JNI chargé: ").append(com.xdja.containerservice.ContainerService.isLoaded).append("\n");
            if (info != null) {
                res.append("✅ Qt(0): ").append(info.toString()).append("\n");
            } else {
                res.append("❌ Qt(0) = null\n");
                if (psXdja.isEmpty()) {
                    res.append("→ CAUSE PROBABLE: containerservice n'était pas lancé\n");
                } else if (psQt.isEmpty()) {
                    res.append("→ CAUSE PROBABLE: Qt/Dilink5 n'est pas actif, Surface non enregistrée\n");
                } else {
                    res.append("→ Qt tourne mais n'a pas encore enregistré sa Surface\n");
                    res.append("  (Surface enregistrée dans le process Qt, pas accessible cross-process)\n");
                }
            }
            if (arr != null) {
                res.append("✅ Array[").append(arr.length).append("]\n");
                for (int i = 0; i < arr.length; i++) {
                    res.append("  [").append(i).append("]: ").append(arr[i]).append("\n");
                }
            } else {
                res.append("❌ Array = null\n");
            }

            AppLogger.log("DiagJNI", res.toString());
            runOnUiThread(() -> {
                tvTest13Result.setText(res.toString());
                btnJniStartProbe.setEnabled(true);
                btnTest13.setEnabled(true);
            });
        }).start();
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
    // FISSION VIA IBINDER DIRECT — Mécanisme Freedom (com.byd.windowmanager)
    // =========================================================================
    // [OBSOLÈTE v0.1.20] — Le broadcast START_CLUSTER_MIRROR n'est pas reçu par Qt.
    // Le flux réel : ClusterDemoProcess (process séparé) obtient la Surface Qt,
    // puis broadcaste ACTION_cluster_demo_process_started → Freedom reçoit l'IBinder.
    //
    // [v0.1.21 — NOUVEAU TEST] : sendInfo via AutoContainerManager Java direct
    // Le log montre que sendInfo via shell → callingUid=2000, Qt refuse le JNI.
    // Via AutoContainerManager.sendInfo() Java → callingUid = uid de notre app.
    // =========================================================================

    private void runFissionViaBinder() {
        if (tvFissionResult == null) return;
        tvFissionResult.setText("sendInfo Java direct...");
        btnFissionViaBinder.setEnabled(false);
        btnFissionLaunch.setEnabled(false);
        mFissionDisplayId = -1;

        new Thread(() -> {
            final StringBuilder res = new StringBuilder();

            // ── Étape 1 : sendInfo via AutoContainerManager Java (pas shell) ──
            // LOG montre : shell → callingUid=2000, Qt refuse JNI.
            // Java direct → callingUid = notre uid app → Qt devrait accepter.
            res.append("── Étape 1 : AutoContainerManager Java direct ──\n");
            runOnUiThread(() -> tvFissionResult.setText(res.toString()));
            try {
                Object acm = getSystemService("auto_container");
                if (acm == null) {
                    acm = getSystemService("Auto_container");
                }
                if (acm == null) {
                    // Fallback via reflection sur AutoContainerManager.getAutoContainerManager()
                    Class<?> cls = Class.forName("android.os.AutoContainerManager");
                    java.lang.reflect.Method initM = cls.getMethod("init", android.content.Context.class);
                    initM.invoke(null, this);
                    java.lang.reflect.Method getM = cls.getMethod("getAutoContainerManager");
                    acm = getM.invoke(null);
                }
                if (acm != null) {
                    // Séquence complète :
                    // sendInfo(30)           ← correctif maison écrans 8.8"/10.25" : bascule cluster
                    //                          en mode 12" (sans bug fenêtre ADAS)
                    // sleep(6s)              ← laisser Qt absorber le changement de mode
                    // sendInfo(16)           ← Qt entre en mode projection plein écran
                    // sleep(6s)              ← délai Freedom (RE onNavigationTypeChanged L2c11)
                    // sendInfo(35)           ← Di4.0 mode → Qt enregistre Surface via JNI
                    java.lang.reflect.Method sendM = acm.getClass()
                            .getMethod("sendInfo", int.class, int.class, String.class);
                    sendM.invoke(acm, 1000, 30, ""); // correctif écrans 8.8"/10.25" — mode cluster 12"
                    res.append("  sendInfo(1000,30) ✅ (workaround ADAS 8.8\"/10.25\")\n");
                    Thread.sleep(6000); // attendre que Qt absorbe le changement de taille
                    sendM.invoke(acm, 1000, 16, ""); // Qt enters full-screen projection mode
                    res.append("  sendInfo(1000,16) ✅\n");
                    Thread.sleep(6000); // Freedom: sleep 6s before sendInfo(35)
                    sendM.invoke(acm, 1000, 35, ""); // Di4.0 mode → Qt registers Surface via JNI
                    res.append("  sendInfo(1000,35) ✅\n");
                    runOnUiThread(() -> tvFissionResult.setText(res.toString()));
                } else {
                    res.append("❌ AutoContainerManager null via getSystemService\n");
                    res.append("→ Fallback shell...\n");
                    runOnUiThread(() -> tvFissionResult.setText(res.toString()));
                    shellSync("service call AutoContainer 2 i32 1000 i32 30 s16 \"\""); // workaround ADAS
                    Thread.sleep(6000);
                    shellSync("service call AutoContainer 2 i32 1000 i32 16 s16 \"\"");
                    Thread.sleep(6000);
                    shellSync("service call AutoContainer 2 i32 1000 i32 35 s16 \"\"");
                    res.append("  shell sendInfo(30→16→35) envoyés\n");
                }
            } catch (Exception e) {
                res.append("⚠ Exception AutoContainerManager: ").append(e.getMessage()).append("\n");
                res.append("→ Fallback shell...\n");
                runOnUiThread(() -> tvFissionResult.setText(res.toString()));
                shellSync("service call AutoContainer 2 i32 1000 i32 30 s16 \"\""); // workaround ADAS
                try { Thread.sleep(6000); } catch (InterruptedException ignored) {}
                shellSync("service call AutoContainer 2 i32 1000 i32 16 s16 \"\"");
                try { Thread.sleep(6000); } catch (InterruptedException ignored) {}
                shellSync("service call AutoContainer 2 i32 1000 i32 35 s16 \"\"");
                res.append("  shell sendInfo(30→16→35) envoyés\n");
            }
            runOnUiThread(() -> tvFissionResult.setText(res.toString()));

            // ── Étape 2 : attendre 5s que Qt prépare son end-point JNI ──
            res.append("\n── Étape 2 : attendre 5s Qt end-point JNI ──\n");
            runOnUiThread(() -> tvFissionResult.setText(res.toString()));
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}

            // ── Étape 3 : démarrer AutoDisplayService ──
            res.append("\n── Étape 3 : am startservice AutoDisplayService ──\n");
            String startResult = shellSync("am startservice com.xdja.containerservice/.AutoDisplayService");
            res.append(startResult.isEmpty() ? "❌ pas de réponse\n" : startResult + "\n");
            runOnUiThread(() -> tvFissionResult.setText(res.toString()));
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

            // ── Étape 4 : vérifier les displays via DisplayManager Java (API 29) ──
            res.append("\n── Étape 4 : displays présents ──\n");
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display[] allDisplays = dm.getDisplays();
            int foundId = -1;
            for (android.view.Display d : allDisplays) {
                String name = d.getName();
                int id = d.getDisplayId();
                res.append("mDisplayId=").append(id).append(" name=").append(name).append("\n");
                if (id != 0 && (name.contains("fission") || name.contains("remote_dashboard")
                        || name.contains("xdja") || name.contains("Virtual"))) {
                    foundId = id;
                }
            }
            // Fallback : tout display non-0 est candidat cluster
            if (foundId == -1 && allDisplays.length > 1) {
                for (android.view.Display d : allDisplays) {
                    if (d.getDisplayId() != 0) { foundId = d.getDisplayId(); break; }
                }
                res.append("⚠ Display non-nommé détecté, id=").append(foundId).append("\n");
            }

            if (foundId > 0) {
                mFissionDisplayId = foundId;
                res.append("✅ Display cluster trouvé ! id=").append(foundId).append("\n");
            } else {
                res.append("❌ Aucun display cluster créé\n");
                res.append("→ sendInfo shell peut nécessiter plus de temps\n");
                res.append("→ Freedom (ClusterDemoProcess) doit être installé\n");
            }

            AppLogger.i("FissionJavaDirect", "foundId=" + foundId);
            final int finalId = foundId;
            runOnUiThread(() -> {
                tvFissionResult.setText(res.toString());
                btnFissionViaBinder.setEnabled(true);
                btnFissionLaunch.setEnabled(finalId > 0);
            });
        }).start();
    }

    // =========================================================================
    // PIPELINE FISSION — Création du VirtualDisplay cluster
    // =========================================================================
    // Séquence découverte par RE de AutoDisplayService (com.xdja.containerservice) :
    //   1. sendInfo(1000,16) → Qt entre en mode projection → enregistre Surface via JNI
    //   2. am startservice AutoDisplayService → onStart() → updateDisplay() →
    //      getQtProjectionDispInfo(0) != null → createVirtualDisplay("fission_testVirtualSurface")
    //   3. am start-activity --display <fission_id> → app visible sur cluster
    //
    // Note: SecondaryDisplayService (Dilink5) ne démarre QUE sur API > 30 (Android 12+).
    // Sur Seal EU (API 29), on lance directement sur fission_testVirtualSurface.
    // =========================================================================

    private void runFissionPipeline() {
        tvFissionResult.setText("Séquence en cours...");
        btnFissionPipeline.setEnabled(false);
        btnFissionLaunch.setEnabled(false);
        mFissionDisplayId = -1;

        new Thread(() -> {
            StringBuilder res = new StringBuilder();

            // ── Étape 0 : état initial displays ────────────────────────────
            String dispBefore = shellSync("dumpsys display | grep -E 'mDisplayId|mName'");
            res.append("── Displays AVANT ──\n").append(dispBefore.isEmpty() ? "(vide)" : dispBefore).append("\n\n");
            runOnUiThread(() -> tvFissionResult.setText(res.toString()));

            // ── Étape 1 : sendInfo(30→sleep6s→16→sleep6s→35) ─────────────────
            // sendInfo(30) = correctif maison écrans 8.8"/10.25" : bascule cluster
            //                en mode 12" (sans bug fenêtre ADAS)
            // sleep(6s)    = laisser Qt absorber le changement de mode taille
            // sendInfo(16) = Qt entre en mode projection plein écran (全屏投屏开启)
            // sleep(6s)    = délai Freedom (RE onNavigationTypeChanged L2c11)
            // sendInfo(35) = Di4.0 mode → Qt enregistre Surface via JNI
            res.append("── sendInfo(1000,30) workaround ADAS 8.8\"/10.25\" ──\n");
            String r30 = shellSync("service call AutoContainer 2 i32 1000 i32 30 s16 \"\"");
            res.append(r30.isEmpty() ? "⚠ pas de réponse AutoContainer" : r30).append("\n\n");
            runOnUiThread(() -> tvFissionResult.setText(res.toString()));

            // sleep(6s) — laisser Qt absorber sendInfo(30)
            try { Thread.sleep(6000); } catch (InterruptedException ignored) {}

            res.append("── sendInfo(1000,16) → Qt projection plein écran ON ──\n");
            String r16 = shellSync("service call AutoContainer 2 i32 1000 i32 16 s16 \"\"");
            res.append(r16.isEmpty() ? "❌ AutoContainer non disponible" : r16).append("\n\n");
            runOnUiThread(() -> tvFissionResult.setText(res.toString()));

            // sleep(6s) — Freedom: délai entre sendInfo(16) et sendInfo(35)
            try { Thread.sleep(6000); } catch (InterruptedException ignored) {}

            res.append("── sendInfo(1000,35) → Di4.0 mode / Qt enregistre Surface JNI ──\n");
            String r35 = shellSync("service call AutoContainer 2 i32 1000 i32 35 s16 \"\"");
            res.append(r35.isEmpty() ? "⚠" : r35).append("\n\n");
            runOnUiThread(() -> tvFissionResult.setText(res.toString()));

            // ── Étape 2 : démarrer AutoDisplayService ──────────────────────
            // onStart() → updateDisplay() → getQtProjectionDispInfo(0)
            // → si non-null → createVirtualDisplay("fission_testVirtualSurface", qtSurface, flags=11)
            res.append("── am startservice AutoDisplayService ──\n");
            String startSvc = shellSync("am startservice com.xdja.containerservice/.AutoDisplayService");
            res.append(startSvc.isEmpty() ? "❌ startservice échoué" : startSvc).append("\n\n");
            runOnUiThread(() -> tvFissionResult.setText(res.toString()));
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

            // ── Étape 4 : vérifier les displays créés via DisplayManager Java ──
            DisplayManager dm2 = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display[] disps = dm2.getDisplays();
            StringBuilder dispAfterSb = new StringBuilder();
            int foundId2 = -1;
            for (android.view.Display d : disps) {
                String n = d.getName(); int id = d.getDisplayId();
                dispAfterSb.append("mDisplayId=").append(id).append(" name=").append(n).append("\n");
                if (id != 0 && (n.contains("fission") || n.contains("remote_dashboard")
                        || n.contains("xdja") || n.contains("Virtual"))) {
                    foundId2 = id;
                }
            }
            if (foundId2 == -1 && disps.length > 1) {
                for (android.view.Display d : disps) {
                    if (d.getDisplayId() != 0) { foundId2 = d.getDisplayId(); break; }
                }
            }
            String dispAfter = dispAfterSb.toString();
            res.append("── Displays APRÈS ──\n").append(dispAfter.isEmpty() ? "(vide)" : dispAfter).append("\n\n");

            if (foundId2 != -1) {
                mFissionDisplayId = foundId2;
                res.append("✅ Display cluster trouvé : displayId=").append(foundId2).append("\n");
                res.append("→ Cliquer \"Lancer Dashboard\" pour lancer sur ce display\n\n");
            } else {
                res.append("❌ Aucun display cluster trouvé\n");
                res.append("→ Vérifier: AutoContainer accessible ? sendInfo(16) reçu par Qt ?\n\n");
            }

            AppLogger.i("FissionPipeline", res.toString());
            final boolean canLaunch = mFissionDisplayId != -1;
            runOnUiThread(() -> {
                tvFissionResult.setText(res.toString());
                btnFissionPipeline.setEnabled(true);
                btnFissionLaunch.setEnabled(canLaunch);
            });
        }).start();
    }

    /**
     * Parse la sortie de "dumpsys display | grep -E 'mDisplayId|mName'"
     * et retourne le displayId du premier display dont le nom contient nameContains.
     * Format attendu (l'ID précède toujours le nom) :
     *   mDisplayId=4
     *   mName=fission_testVirtualSurface
     */
    private int parseDisplayIdByName(String output, String nameContains) {
        int lastId = -1;
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("mDisplayId=")) {
                try {
                    lastId = Integer.parseInt(t.substring("mDisplayId=".length()).trim());
                } catch (NumberFormatException ignored) {}
            } else if (t.startsWith("mName=") && t.contains(nameContains)) {
                return lastId;
            }
        }
        return -1;
    }

    private void launchOnFissionDisplay() {
        if (mFissionDisplayId == -1) {
            tvFissionResult.setText("❌ Pas de display fission. Lancer d'abord \"Créer Fission Display\".");
            return;
        }
        tvFissionResult.setText("Lancement sur display " + mFissionDisplayId + "...");
        btnFissionLaunch.setEnabled(false);

        new Thread(() -> {
            StringBuilder res = new StringBuilder();
            String pkg = getPackageName();
            // --windowingMode 5 = FREEFORM (obligatoire sur DiLink 3.0)
            String cmd = "am start-activity -W --windowingMode 5 --display " + mFissionDisplayId
                    + " " + pkg + "/.dashboard.BYDDashboardActivity";
            res.append("Cmd:\n").append(cmd).append("\n\n");
            String result = shellSync(cmd);
            res.append(result.isEmpty() ? "❌ Pas de réponse" : result);
            AppLogger.i("FissionLaunch", "displayId=" + mFissionDisplayId + " → " + result);
            runOnUiThread(() -> {
                tvFissionResult.setText(res.toString());
                btnFissionLaunch.setEnabled(true);
            });
        }).start();
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
            tvAutoDisplayResult, tvFissionResult, tvReSnifferStatus
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
