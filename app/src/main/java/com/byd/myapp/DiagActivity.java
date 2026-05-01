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
import android.media.ImageReader;
import android.graphics.PixelFormat;

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

    // TEST 7 — Cluster orientation
    private Button   btnOrientFreezeLandscape;
    private Button   btnOrientFreezePortrait;
    private Button   btnOrientUnfreeze;
    private Button   btnOrientRead;
    private TextView tvOrientationResult;

    // TEST 13 — JNI Qt Surface
    private Button   btnTest13;
    private TextView tvTest13Result;

    // TEST 14
    private Button   btnVdTest;
    private TextView tvVdResult;

    // TEST 15
    private Button   btnDumpsysWindows;
    private TextView tvDumpsysResult;

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

        // TEST 7 — Cluster orientation
        btnOrientFreezeLandscape = (Button)   findViewById(R.id.btn_orient_freeze_landscape);
        btnOrientFreezePortrait  = (Button)   findViewById(R.id.btn_orient_freeze_portrait);
        btnOrientUnfreeze        = (Button)   findViewById(R.id.btn_orient_unfreeze);
        btnOrientRead            = (Button)   findViewById(R.id.btn_orient_read);
        tvOrientationResult      = (TextView) findViewById(R.id.tv_orientation_result);

        // TEST 13
        btnTest13      = (Button)   findViewById(R.id.btn_test_13);
        tvTest13Result = (TextView) findViewById(R.id.tv_test_13_result);

        // TEST 14
        btnVdTest      = (Button)   findViewById(R.id.btn_vd_test);
        tvVdResult     = (TextView) findViewById(R.id.tv_vd_result);

        // TEST 15
        btnDumpsysWindows = (Button) findViewById(R.id.btn_dumpsys_windows);
        tvDumpsysResult   = (TextView) findViewById(R.id.tv_dumpsys_result);

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
        btnOrientFreezeLandscape.setOnClickListener(v -> orientFreezeDisplay(0));
        btnOrientFreezePortrait .setOnClickListener(v -> orientFreezeDisplay(1));
        btnOrientUnfreeze       .setOnClickListener(v -> orientUnfreezeDisplay());
        btnOrientRead           .setOnClickListener(v -> orientReadDisplay());

        btnTest13.setOnClickListener(v -> runJniSurfaceProbe());
        btnVdTest.setOnClickListener(v -> testVirtualDisplayAPI());
        btnDumpsysWindows.setOnClickListener(v -> runDumpsysWindows());

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

        // TEST 4 — Broadcast BootReceiver Freedom
        btnBootReceiverShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, tvBootReceiverResult.getText().toString());
                startActivity(Intent.createChooser(intent, getString(R.string.diag_share_result4_btn)));
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
    // TEST 4 : Broadcast BOOT_COMPLETED → BootReceiver Freedom (headless)
    // -------------------------------------------------------------------------

    private void runBootReceiverTest() {
        btnBootReceiver.setEnabled(false);
        tvBootReceiverResult.setText(getString(R.string.diag_boot_receiver_running));
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
                                + "\n\n" + getString(R.string.diag_adb_test1_hint));
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
                getString(R.string.toast_sniffer_started, logFile.getName()),
                android.widget.Toast.LENGTH_LONG).show();
        AppLogger.i("DiagSniffer", "Starting system Sniffer → " + p);

        // ── Enriched header (synchronous, creates the file) ──────────────────────
        // Clear the logcat buffer first to capture only future events.
        String headerCmd =
            "logcat -c 2>/dev/null && touch /data/local/tmp/.sniffer_run"
            + " && echo '=========================================' > " + p
            + " && echo '===  BYD SNIFFER DUMP (ENHANCED)  ===' >> " + p
            + " && echo '=========================================' >> " + p
            + " && date >> " + p
            + " && echo '' >> " + p
            + " && echo '--- ROM & DEVICE INFO ---' >> " + p
            + " && getprop ro.product.model >> " + p
            + " && getprop ro.build.fingerprint >> " + p
            + " && getprop ro.build.version.release >> " + p
            + " && echo '' >> " + p
            + " && echo '--- BYD STRICT PROPERTIES ---' >> " + p
            + " && getprop | grep -i byd >> " + p
            + " && echo '' >> " + p
            + " && echo '--- PROCESSUS BYD/XDJA/DAEMON/DILINK/QT ---' >> " + p
            + " && (ps -A 2>/dev/null | grep -iE 'byd|xdja|freedom|daemon|mirror|dilink|qt|cluster') >> " + p
            + " && echo '' >> " + p
            + " && echo '--- RELEVANT BYD SERVICES (service list) ---' >> " + p
            + " && (service list 2>/dev/null | grep -iE 'byd|auto|display|window|freedom|xdja|qt|cluster') >> " + p
            + " && echo '' >> " + p
            + " && echo '--- DISPLAYS (dumpsys display) ---' >> " + p
            + " && (dumpsys display 2>/dev/null | grep -A 2 -B 2 -E 'mDisplayId|mName|mState|fission|virtual|cluster|Qt|Screen') >> " + p
            + " && echo '' >> " + p
            + " && echo '--- WINDOWS (dumpsys window) ---' >> " + p
            + " && (dumpsys window 2>/dev/null | grep -iE 'mDisplayId|Window \\{|mSurface|fission|cluster|byd|xdja|qt|container' | head -100) >> " + p
            + " && echo '' >> " + p
            + " && echo '--- SURFACEFLINGER ---' >> " + p
            + " && (dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'display|fission|layer|cluster|mirror|virtual|qt|container' | head -50) >> " + p
            + " && echo '' >> " + p
            + " && echo '--- RECENT INTENTS/BROADCASTS ---' >> " + p
            + " && (dumpsys activity broadcasts history 2>/dev/null | grep -iE 'byd|xdja|freedom|qt') | head -30 >> " + p
            + " && echo '' >> " + p
            + " && echo '--- MAIN SNIFFER STARTED ---' >> " + p;

        // ── Logcat filtered on relevant tags — avoids audio/AAudio flood ──────────
        // *:S = silence all. Then re-enable BYD/display/crash/event tags.
        String logcatCmd =
            "logcat -v threadtime *:S"
            + " WindowManager:V ActivityManager:V SurfaceFlinger:V"
            + " AutoContainer:V MirrorDaemon:V"
            + " byd:V xdja:V freedom:V cluster:V dilink:V diag:V qt:V container:V input:V"
            + " BYD_*:V Qt*:V"
            + " DEBUG:E dalvikvm:W art:W"
            + " >> " + p + " 2>&1";

        // ── Periodic snapshots every 15s ────────────────────────────────
        // \$ → $ sent to the inner sh (date expands in sh -c)
        String snapshotCmd =
            "while [ -f /data/local/tmp/.sniffer_run ]; do sleep 15;"
            + " echo '' >> " + p + ";"
            + " echo '--- SNAPSHOT '\\$(date +%H:%M:%S)' ---' >> " + p + ";"
            + " dumpsys display 2>/dev/null | grep -A 2 -B 2 -E 'mDisplayId|mState|fission|virtual|cluster|qt' | head -15 >> " + p + ";"
            + " dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'display|fission|layer|cluster|mirror|qt' | head -15 >> " + p + ";"
            + " dumpsys activity broadcasts history 2>/dev/null | grep -iE 'byd|xdja|freedom|sendinfo' | head -15 >> " + p + ";"
            + " ps -A 2>/dev/null | grep -iE 'byd|xdja|freedom|daemon|mirror' | wc -l | sed \"s/^/[Process count]: /\" >> " + p + ";"
            + " done";

        // ── Intent & Event Background Sniffing ────────────────────────────
        String eventCmd = "logcat -b events -v time | grep -iE 'byd|xdja|qt|cluster|sendinfo' >> " + p + " 2>/dev/null";
        String inputCmd = "while [ -f /data/local/tmp/.sniffer_run ]; do sleep 5; dumpsys input 2>/dev/null | grep -iE 'FocusedWindow|TouchedWindow|byd|cluster' | head -5 >> " + p + "; done";

        String fullCmd = headerCmd
                + " && nohup sh -c \"" + logcatCmd + "\" &"
                + " nohup sh -c \"" + snapshotCmd + "\" &"
                + " nohup sh -c \"" + eventCmd + "\" &"
                + " nohup sh -c \"" + inputCmd + "\" &";

        AdbLocalClient.executeShell(DiagActivity.this, fullCmd);
    }

    private void stopSnifferSilently() {
        // Synchronous kill without feedback (called before restarting the sniffer)
        AdbLocalClient.executeShell(DiagActivity.this, AdbLocalClient.SNIFFER_KILL_CMD);
    }

    private void killSnifferWithFeedback() {
            tvSnifferScanResult.setText(getString(R.string.diag_sniffer_killing));
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
        tvSnifferScanResult.setText(getString(R.string.diag_scanning));
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
                    getString(R.string.diag_no_sniffer_report),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            android.net.Uri fileUri = androidx.core.content.FileProvider.getUriForFile(DiagActivity.this, getPackageName() + ".fileprovider", logFile);
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.diag_share_sniffer_title)));
        } catch (Exception e) {
            AppLogger.e("DiagSniffer", "Erreur export", e);
        }
    }

    private void exportDaemonLog() {
        android.widget.Toast.makeText(this, getString(R.string.diag_daemon_log_reading),
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
                                shareIntent, getString(R.string.diag_share_daemon_log_title)));
                    } catch (Exception e) {
                        AppLogger.e("DiagDaemon", "exportDaemonLog share erreur", e);
                        android.widget.Toast.makeText(DiagActivity.this,
                                getString(R.string.toast_share_error, e.getMessage()),
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

    private void cleanDaemonLogs() {
        // Suppression via ADB (fichiers dans /data/local/tmp/, inaccessibles depuis l'app)
        AdbLocalClient.executeShell(this,
                "rm -f /data/local/tmp/mirrordaemon_*.log /data/local/tmp/mirrordaemon_latest.log"
                + " && echo cleaned");
        runOnUiThread(() -> {
            tvDaemonScanResult.setText(getString(R.string.diag_daemon_logs_deleted_text));
            tvDaemonScanResult.setTextColor(0xFF69F0AE);
            android.widget.Toast.makeText(this,
                    getString(R.string.toast_daemon_logs_deleted), android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void cleanSnifferLogs() {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) {
            android.widget.Toast.makeText(this, getString(R.string.toast_folder_not_found), android.widget.Toast.LENGTH_SHORT).show();
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
            tvSnifferScanResult.setText(getString(R.string.diag_sniffer_files_deleted, deleted));
            tvSnifferScanResult.setTextColor(0xFF69F0AE);
            android.widget.Toast.makeText(this,
                    getString(R.string.toast_files_deleted, deleted), android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void testLaunchFreedomDaemon() {
        // Non fonctionnel : app_process n'est pas accessible depuis uid=10100,
        // and CommunicationProcessKt belongs to com.byd.windowmanager (WindowManagement),
        // not to our APK. The command fails silently in the background.
        android.widget.Toast.makeText(this,
                getString(R.string.toast_experimental),
                android.widget.Toast.LENGTH_LONG).show();
        AppLogger.w("DiagDaemon", "testLaunchFreedomDaemon() — not functional on this ROM (uid=10100 without app_process access)");
    }

    private void scanDaemon() {
        tvDaemonScanResult.setText(getString(R.string.diag_scanning));
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
        tvDaemonScanResult.setText(getString(R.string.diag_daemon_killing));
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
        tvDaemonScanResult.setText(getString(R.string.diag_kill_restart_in_progress));
        tvDaemonScanResult.setTextColor(0xFFFFAB40);
        AdbLocalClient.killMirrorDaemon(this, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String msg) {
                runOnUiThread(() -> tvDaemonScanResult.setText(getString(R.string.diag_kill_ok_restarting)));
                AdbLocalClient.startMirrorDaemon(DiagActivity.this);
                runOnUiThread(() -> {
                    tvDaemonScanResult.setText(getString(R.string.diag_daemon_restarted));
                    tvDaemonScanResult.setTextColor(0xFF69F0AE);
                    android.widget.Toast.makeText(DiagActivity.this,
                            getString(R.string.toast_daemon_restarted), android.widget.Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvDaemonScanResult.setText(getString(R.string.diag_kill_failed, error));
                    tvDaemonScanResult.setTextColor(0xFFFF5252);
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // TEST 7 — Cluster orientation (freezeDisplayRotation via IWindowManager)
    // NOTE: NO wm size call here — it would corrupt the main screen resolution
    //       on Android 10 (DiLink 3.0) because --display is silently ignored.
    // -------------------------------------------------------------------------

    /**
     * Returns the cluster display ID (first non-default display, fallback = 2).
     */
    private int getClusterDisplayId() {
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm != null) {
            for (android.view.Display d : dm.getDisplays()) {
                if (d.getDisplayId() != 0) return d.getDisplayId();
            }
        }
        return 2;
    }

    /**
     * Freezes the cluster display rotation via IWindowManager.freezeDisplayRotation().
     * rotation = 0 → ROTATION_0 (landscape), 1 → ROTATION_90 (portrait).
     * Does NOT call wm size — main screen resolution is never touched.
     */
    private void orientFreezeDisplay(int rotation) {
        final int displayId = getClusterDisplayId();
        tvOrientationResult.setText("⏳ freezeDisplayRotation(display=" + displayId
                + ", rotation=" + rotation + ")…");
        tvOrientationResult.setTextColor(0xFFFFAB40);
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                android.os.IBinder wmBinder = (android.os.IBinder)
                        smClass.getMethod("getService", String.class).invoke(null, "window");
                Class<?> iwmStub = Class.forName("android.view.IWindowManager$Stub");
                Object iwm = iwmStub.getMethod("asInterface", android.os.IBinder.class)
                        .invoke(null, wmBinder);
                java.lang.reflect.Method freeze = iwm.getClass()
                        .getMethod("freezeDisplayRotation", int.class, int.class);
                freeze.invoke(iwm, displayId, rotation);
                sb.append("✅ freezeDisplayRotation(").append(displayId).append(", ")
                  .append(rotation == 0 ? "LANDSCAPE" : "PORTRAIT").append(") OK\n");
            } catch (Exception e) {
                sb.append("❌ freezeDisplayRotation: ").append(e.getMessage()).append("\n");
            }
            final String result = sb.toString();
            runOnUiThread(() -> {
                tvOrientationResult.setText(result);
                tvOrientationResult.setTextColor(
                        result.contains("✅") ? 0xFF69F0AE : 0xFFFF5252);
            });
        }).start();
    }

    /**
     * Thaws the cluster display rotation via IWindowManager.thawDisplayRotation().
     */
    private void orientUnfreezeDisplay() {
        final int displayId = getClusterDisplayId();
        tvOrientationResult.setText("⏳ thawDisplayRotation(display=" + displayId + ")…");
        tvOrientationResult.setTextColor(0xFFFFAB40);
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                android.os.IBinder wmBinder = (android.os.IBinder)
                        smClass.getMethod("getService", String.class).invoke(null, "window");
                Class<?> iwmStub = Class.forName("android.view.IWindowManager$Stub");
                Object iwm = iwmStub.getMethod("asInterface", android.os.IBinder.class)
                        .invoke(null, wmBinder);
                // Try thawDisplayRotation(int displayId) first (API 30+),
                // fall back to thawRotation() (API 26-29).
                try {
                    java.lang.reflect.Method thaw = iwm.getClass()
                            .getMethod("thawDisplayRotation", int.class);
                    thaw.invoke(iwm, displayId);
                    sb.append("✅ thawDisplayRotation(").append(displayId).append(") OK\n");
                } catch (NoSuchMethodException e2) {
                    java.lang.reflect.Method thaw = iwm.getClass().getMethod("thawRotation");
                    thaw.invoke(iwm);
                    sb.append("✅ thawRotation() OK (fallback API 29)\n");
                }
            } catch (Exception e) {
                sb.append("❌ thawDisplayRotation: ").append(e.getMessage()).append("\n");
            }
            final String result = sb.toString();
            runOnUiThread(() -> {
                tvOrientationResult.setText(result);
                tvOrientationResult.setTextColor(
                        result.contains("✅") ? 0xFF69F0AE : 0xFFFF5252);
            });
        }).start();
    }

    /**
     * Reads current display rotation via ADB (wm rotation -d N or dumpsys display).
     */
    private void orientReadDisplay() {
        final int displayId = getClusterDisplayId();
        tvOrientationResult.setText("⏳ Reading display " + displayId + "…");
        tvOrientationResult.setTextColor(0xFFFFAB40);
        String cmd = "dumpsys display 2>/dev/null"
                + " | grep -E 'mDisplayId|mName|mCurrentOrientation|mRotation|PhysicalDisplayInfo' | head -20";
        AdbLocalClient.executeShellWithResult(this, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String result) {
                runOnUiThread(() -> {
                    tvOrientationResult.setText(result.trim());
                    tvOrientationResult.setTextColor(0xFF69F0AE);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    tvOrientationResult.setText("❌ " + error);
                    tvOrientationResult.setTextColor(0xFFFF5252);
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
    // TEST 14 : VirtualDisplay API
    // -------------------------------------------------------------------------
    private void testVirtualDisplayAPI() {
        tvVdResult.setText("Appel en cours...");
        btnVdTest.setEnabled(false);
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            ImageReader reader = ImageReader.newInstance(1920, 720, PixelFormat.RGBA_8888, 2);
            VirtualDisplay vd = dm.createVirtualDisplay("remote_dashboard", 1920, 720, 320, reader.getSurface(), 320);
            if (vd != null) {
                tvVdResult.setText("SUCCES MYSTERIEUX ! Un VirtualDisplay a pu etre cree par l'app !\nID: " 
                    + vd.getDisplay().getDisplayId() + " Name: " + vd.getDisplay().getName());
                vd.release();
            } else {
                tvVdResult.setText("Echec : createVirtualDisplay a renvoye null.");
            }
        } catch (SecurityException se) {
            tvVdResult.setText("EXCEPTION DE SECURITE (Attendu) :\n" + se.getMessage());
        } catch (Exception e) {
            tvVdResult.setText("ERREUR : " + e.getMessage());
        }
        btnVdTest.setEnabled(true);
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
}
