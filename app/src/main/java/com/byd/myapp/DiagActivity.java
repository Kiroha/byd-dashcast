package com.byd.myapp;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.content.Intent;

/**
 * DiagActivity — Diagnostic tools and configuration.
 *
 * TEST 1 — Local ADB connection (prerequisite, to run once)
 * TEST 2 — Cluster restore (sendInfo 30→16→18→0)
 * TEST 3 — Cluster display size (cmd 29 / 30 / 31)
 * TEST 4 — Broadcast BOOT_COMPLETED to Freedom BootReceiver (headless, no UI)
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

    // TEST 4 — Broadcast BootReceiver Freedom
    private TextView tvBootReceiverResult;
    private Button   btnBootReceiver;
    private Button   btnBootReceiverShare;
    private Button   btnTestDaemon;
    private Button   btnScanDaemon;
    private Button   btnKillDaemon;
    private Button   btnKillRestartDaemon;
    private TextView tvDaemonScanResult;
    private Button   btnStartSniffer;
    private Button   btnScanSniffer;
    private Button   btnStopSniffer;
    private Button   btnExportSniffer;
    private Button   btnCleanSnifferLogs;
    private TextView tvSnifferScanResult;
    private Button   btnExportDaemonLog;
    private Button   btnDumpSfMirror;
    private TextView tvSfDumpResult;
    private Button   btnCleanDaemonLogs;

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
        btnScanDaemon = (Button) findViewById(R.id.btn_scan_daemon);
        btnKillDaemon = (Button) findViewById(R.id.btn_kill_daemon);
        btnKillRestartDaemon = (Button) findViewById(R.id.btn_kill_restart_daemon);
        tvDaemonScanResult = (TextView) findViewById(R.id.tv_daemon_scan_result);
        btnScanSniffer = (Button) findViewById(R.id.btn_scan_sniffer);
        tvSnifferScanResult = (TextView) findViewById(R.id.tv_sniffer_scan_result);
        btnStartSniffer = (Button) findViewById(R.id.btn_start_sniffer);
        btnStopSniffer = (Button) findViewById(R.id.btn_stop_sniffer);
        btnExportSniffer = (Button) findViewById(R.id.btn_export_sniffer);
        btnCleanSnifferLogs = (Button) findViewById(R.id.btn_clean_sniffer_logs);
        btnExportDaemonLog = (Button) findViewById(R.id.btn_export_daemon_log);
        btnDumpSfMirror = (Button) findViewById(R.id.btn_dump_sf_mirror);
        tvSfDumpResult = (TextView) findViewById(R.id.tv_sf_dump_result);
        btnCleanDaemonLogs = (Button) findViewById(R.id.btn_clean_daemon_logs);

        btnTestDaemon.setOnClickListener(v -> testLaunchFreedomDaemon());
        btnScanDaemon.setOnClickListener(v -> scanDaemon());
        btnKillDaemon.setOnClickListener(v -> killDaemon());
        btnKillRestartDaemon.setOnClickListener(v -> killAndRestartDaemon());
        btnScanSniffer.setOnClickListener(v -> scanSniffer());
        btnStartSniffer.setOnClickListener(v -> startSniffer());
        btnStopSniffer.setOnClickListener(v -> killSnifferWithFeedback());
        btnExportSniffer.setOnClickListener(v -> exportSnifferReport());
        btnCleanSnifferLogs.setOnClickListener(v -> cleanSnifferLogs());
        btnExportDaemonLog.setOnClickListener(v -> exportDaemonLog());
        btnDumpSfMirror.setOnClickListener(v -> dumpSurfaceFlinger());
        btnCleanDaemonLogs.setOnClickListener(v -> cleanDaemonLogs());

        // TEST 1 — Local ADB connection
        btnAdbShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, tvAdbLocalResult.getText().toString());
                startActivity(Intent.createChooser(intent, "Share TEST 1 result"));
            }
        });
        btnAdbLocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnAdbLocal.setEnabled(false);
                tvAdbLocalResult.setText("Connecting to localhost:5555…\n" +
                        "⏳ The popup will appear on this screen — press ALLOW.");
                AppLogger.log("DiagADB", "Starting local ADB connection");
                AdbLocalClient.connectAndGrant(DiagActivity.this,
                        new AdbLocalClient.Callback() {
                    @Override
                    public void onSuccess(final String report) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvAdbLocalResult.setText("✅ Connection established\n\n" + report);
                                btnAdbLocal.setEnabled(true);
                            }
                        });
                    }
                    @Override
                    public void onError(final String error) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                tvAdbLocalResult.setText(
                                        "❌ Failed: " + error + "\n\n" +
                                        "→ Verify that ADB TCP debugging is enabled\n" +
                                        "  in Settings → Developer → USB Debugging\n" +
                                        "  (or Wireless Debugging on this ROM)");
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
                startActivity(Intent.createChooser(intent, "Share TEST 2 result"));
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
                startActivity(Intent.createChooser(intent, "Share TEST 3 result"));
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
                startActivity(Intent.createChooser(intent, "Share TEST 4 result"));
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
        tvDisplaySizeResult.setText("⏳ Restoring default size (cmd 30 + wm reset)…");
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
                + "Waiting 5s for VirtualDisplay creation…");
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

    private static final String SNIFFER_FILE_PREFIX = "BYD_Sniffer_Dump_";
    private java.io.File mCurrentSnifferFile = null;

    /** Generates a timestamped file in the app's external directory. */
    private java.io.File buildSnifferFile() {
        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new java.io.File(dir, SNIFFER_FILE_PREFIX + ts + ".txt");
    }

    /** Finds the most recent sniffer file in the directory (fallback if mCurrentSnifferFile is null). */
    private java.io.File findLatestSnifferFile() {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) return null;
        java.io.File[] files = dir.listFiles(
                f -> f.getName().startsWith(SNIFFER_FILE_PREFIX) && f.getName().endsWith(".txt"));
        if (files == null || files.length == 0) return null;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files[0];
    }

    private void startSniffer() {
        stopSnifferSilently();

        mCurrentSnifferFile = buildSnifferFile();
        java.io.File logFile = mCurrentSnifferFile;
        String p = logFile.getAbsolutePath();

        android.widget.Toast.makeText(DiagActivity.this,
                "Sniffer started → " + logFile.getName(),
                android.widget.Toast.LENGTH_LONG).show();
        AppLogger.i("DiagSniffer", "Starting system Sniffer → " + p);

        // ── Enriched header (synchronous, creates the file) ──────────────────────
        // Clear the logcat buffer first to capture only future events.
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

        // ── Logcat filtered on relevant tags — avoids audio/AAudio flood ──────────
        // *:S = silence all. Then re-enable BYD/display/crash tags.
        String logcatCmd =
            "logcat -v threadtime *:S"
            + " WindowManager:V ActivityManager:V SurfaceFlinger:V"
            + " AutoContainer:V MirrorDaemon:V"
            + " byd:V xdja:V freedom:V cluster:V"
            + " DEBUG:E dalvikvm:W art:W"
            + " >> " + p + " 2>&1";

        // ── Periodic snapshots every 30s ────────────────────────────────
        // \\$ → \$ in the Java string → $ sent to the inner sh (date expands in sh -c)
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
        // Synchronous kill without feedback (called before restarting the sniffer)
        AdbLocalClient.executeShell(DiagActivity.this, AdbLocalClient.SNIFFER_KILL_CMD);
    }

    private void killSnifferWithFeedback() {
        tvSnifferScanResult.setText("Kill sniffer en cours…");
        tvSnifferScanResult.setTextColor(0xFFFFAB40);
        AdbLocalClient.killSniffer(this, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String msg) {
                runOnUiThread(() -> {
                    tvSnifferScanResult.setText(msg);
                    tvSnifferScanResult.setTextColor(0xFF69F0AE);
                    android.widget.Toast.makeText(DiagActivity.this, msg,
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvSnifferScanResult.setText(error);
                    tvSnifferScanResult.setTextColor(0xFFFF5252);
                    android.widget.Toast.makeText(DiagActivity.this, error,
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void stopSniffer() {
        killSnifferWithFeedback();
    }

    private void scanSniffer() {
        tvSnifferScanResult.setText("Scan en cours…");
        tvSnifferScanResult.setTextColor(0xFFAAAAAA);
        AdbLocalClient.scanSniffer(this, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String msg) {
                runOnUiThread(() -> {
                    tvSnifferScanResult.setText(msg);
                    boolean active = msg.contains("Sniffer process detected");
                    tvSnifferScanResult.setTextColor(active ? 0xFF69F0AE : 0xFFAAAAAA);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvSnifferScanResult.setText(error);
                    tvSnifferScanResult.setTextColor(0xFFFF5252);
                });
            }
        });
    }

    private void exportSnifferReport() {
        java.io.File logFile = mCurrentSnifferFile != null ? mCurrentSnifferFile : findLatestSnifferFile();
        if (logFile == null || !logFile.exists() || logFile.length() == 0) {
            android.widget.Toast.makeText(DiagActivity.this,
                    "No report found (start the sniffer first).",
                    android.widget.Toast.LENGTH_SHORT).show();
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
        android.widget.Toast.makeText(this, "Lecture mirrordaemon_latest.log via ADB…",
                android.widget.Toast.LENGTH_SHORT).show();
        AdbLocalClient.readFileViaAdb(this, "/data/local/tmp/mirrordaemon_latest.log",
                "mirrordaemon_latest.log", new AdbLocalClient.ReadFileCallback() {
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
                                "mirrordaemon_latest.log");
                        shareIntent.addFlags(
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(android.content.Intent.createChooser(
                                shareIntent, "Partager mirrordaemon_latest.log"));
                    } catch (Exception e) {
                        AppLogger.e("DiagDaemon", "exportDaemonLog share erreur", e);
                        android.widget.Toast.makeText(DiagActivity.this,
                                "Share error: " + e.getMessage(),
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

    private void dumpSurfaceFlinger() {
        tvSfDumpResult.setText("dumpsys SurfaceFlinger en cours…");
        tvSfDumpResult.setTextColor(0xFFAAAAAA);
        // Filter on our display + layerStack=2 to check if SF knows about the mirror
        String cmd = "dumpsys SurfaceFlinger 2>/dev/null"
                + " | grep -iE 'byd_myapp_mirror|layerStack=2|fission_bg|virtual'";
        AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) {
                runOnUiThread(() -> {
                    String text = report.trim().isEmpty()
                            ? "(no result — mirror not registered in SF)"
                            : report.trim();
                    tvSfDumpResult.setText(text);
                    boolean found = report.contains("byd_myapp_mirror");
                    tvSfDumpResult.setTextColor(found ? 0xFF69F0AE : 0xFFFF5252);
                    AppLogger.i("SFDump", "SF dump :\n" + text);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvSfDumpResult.setText("Error: " + error);
                    tvSfDumpResult.setTextColor(0xFFFF5252);
                });
            }
        });
    }

    private void cleanDaemonLogs() {
        // Suppression via ADB (fichiers dans /data/local/tmp/, inaccessibles depuis l'app)
        AdbLocalClient.executeShell(this,
                "rm -f /data/local/tmp/mirrordaemon_*.log /data/local/tmp/mirrordaemon_latest.log"
                + " && echo cleaned");
        runOnUiThread(() -> {
            tvDaemonScanResult.setText("mirrordaemon_*.log deleted ✓");
            tvDaemonScanResult.setTextColor(0xFF69F0AE);
            android.widget.Toast.makeText(this,
                    "Daemon logs deleted", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void cleanSnifferLogs() {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) {
            android.widget.Toast.makeText(this, "Folder not found", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.io.File[] files = dir.listFiles(
                f -> f.getName().startsWith(SNIFFER_FILE_PREFIX) && f.getName().endsWith(".txt"));
        int count = 0;
        if (files != null) {
            for (java.io.File f : files) {
                if (f.delete()) count++;
            }
        }
        mCurrentSnifferFile = null;
        final int deleted = count;
        runOnUiThread(() -> {
            tvSnifferScanResult.setText(deleted + " Sniffer file(s) deleted ✓");
            tvSnifferScanResult.setTextColor(0xFF69F0AE);
            android.widget.Toast.makeText(this,
                    deleted + " file(s) deleted", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void testLaunchFreedomDaemon() {
        // Non fonctionnel : app_process n'est pas accessible depuis uid=10100,
        // and CommunicationProcessKt belongs to com.byd.windowmanager (WindowManagement),
        // not to our APK. The command fails silently in the background.
        android.widget.Toast.makeText(this,
                "Experimental — not functional\n(app_process inaccessible from uid=10100)",
                android.widget.Toast.LENGTH_LONG).show();
        AppLogger.w("DiagDaemon", "testLaunchFreedomDaemon() — not functional on this ROM (uid=10100 without app_process access)");
    }

    private void scanDaemon() {
        tvDaemonScanResult.setText("Scan en cours…");
        tvDaemonScanResult.setTextColor(0xFFAAAAAA);
        AdbLocalClient.scanMirrorDaemon(this, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String msg) {
                runOnUiThread(() -> {
                    tvDaemonScanResult.setText(msg);
                    boolean multi = msg.matches("(?s).*[2-9] processus.*");
                    tvDaemonScanResult.setTextColor(multi ? 0xFFFF5252 : 0xFF69F0AE);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvDaemonScanResult.setText(error);
                    tvDaemonScanResult.setTextColor(0xFFFF5252);
                });
            }
        });
    }

    private void killDaemon() {
        tvDaemonScanResult.setText("Envoi kill -9 en cours…");
        tvDaemonScanResult.setTextColor(0xFFFFAB40);
        AdbLocalClient.killMirrorDaemon(this, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String msg) {
                runOnUiThread(() -> {
                    tvDaemonScanResult.setText(msg);
                    tvDaemonScanResult.setTextColor(0xFF69F0AE);
                    android.widget.Toast.makeText(DiagActivity.this, msg,
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvDaemonScanResult.setText(error);
                    tvDaemonScanResult.setTextColor(0xFFFF5252);
                    android.widget.Toast.makeText(DiagActivity.this, error,
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void killAndRestartDaemon() {
        tvDaemonScanResult.setText("Kill + restart in progress…");
        tvDaemonScanResult.setTextColor(0xFFFFAB40);
        AdbLocalClient.killMirrorDaemon(this, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String msg) {
                runOnUiThread(() -> tvDaemonScanResult.setText("Kill OK — relancement…"));
                AdbLocalClient.startMirrorDaemon(DiagActivity.this);
                runOnUiThread(() -> {
                    tvDaemonScanResult.setText("MirrorDaemon restarted — check log in 5s");
                    tvDaemonScanResult.setTextColor(0xFF69F0AE);
                    android.widget.Toast.makeText(DiagActivity.this,
                            "MirrorDaemon restarted (clean)", android.widget.Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvDaemonScanResult.setText("Kill failed: " + error);
                    tvDaemonScanResult.setTextColor(0xFFFF5252);
                });
            }
        });
    }
}
