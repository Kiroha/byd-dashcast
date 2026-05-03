package com.byd.myapp.dashboard;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import com.byd.myapp.AdbLocalClient;
import com.byd.myapp.AppLogger;
import android.view.Display;

/**
 * ClusterManager — direct control of the BYD Seal cluster via the Binder service "AutoContainer".
 *
 * ARCHITECTURE (DiLink 3.0 / XDJA) :
 *   • The "AutoContainer" service (android.os.IAutoContainer) is registered in ServiceManager.
 *   • AutoContainerManager (getSystemService("auto_container")) checks the whitelist
 *     /system/etc/container_comm_cfg.json → only "com.xdja.clusterdemo" is allowed.
 *   • BUT: the direct Binder call bypasses this Java check (confirmed TEST 8 — returned 00000000).
 *   • The Binder is accessed via ServiceManager.getService("AutoContainer") through reflection.
 *
 * AIDL IAutoContainer (transactions) :
 *   #1 sendJson(int type, String json)
 *   #2 sendInfo(int type, int infoInt, String infoStr)  ← used here
 *   #3 sendInfo2(int type, byte[] data)
 *   #4 registerCallback(IAutoContainerCallback cb)
 *
 * CLUSTER COMMANDS (type=1000) — CONFIRMED IN CAR (13/04/2026 + 16/04/2026, BYD Seal EU) :
 *
 *   infoInt=30  → 切换到12.3寸屏 = SWITCH TO Seal EU MODE (correct resolution) :
 *                  MUST be sent BEFORE infoInt=16 on Seal EU.
 *                  Fixes the ADAS window bug and UI stretching.
 *                  Full sequence: sendInfo(30) → wait 1s → sendInfo(16) → wait 2s → startActivity.
 *
 *   infoInt=16  → 全屏投屏开启 = ENABLE fullscreen projection :
 *                  Qt enters standby, display 1 remains registered in IActivityManager.
 *                  THIS IS THE CORRECT COMMAND to launch an app on display 1.
 *                  Sequence: sendInfo(30) → sendInfo(16) → wait 2s → startActivity on display 1.
 *
 *   infoInt=18  → 投屏关闭 = CLOSE the projection :
 *                  THIS IS THE CORRECT RESTORE COMMAND (cmd=0 alone is NOT enough).
 *                  Sequence: finishIfActive() → sendInfo(18) → sendInfo(0).
 *
 *   infoInt= 0  → 主机恢复付表视频流 = refresh the Qt video stream.
 *                  Must be called AFTER sendInfo(18) to complete the restore.
 *
 *   infoInt= 1  → disconnects Qt ENTIRELY → MCU takes control (Simple mode)
 *                  display 1 DISAPPEARS from IActivityManager → DO NOT USE to launch apps
 *
 *   infoInt=12  → 显示Adas — NO EFFECT on 2D cluster Seal EU (intended for 3D cluster)
 *   infoInt=13  → 关闭Adas — NO EFFECT on 2D cluster Seal EU (intended for 3D cluster)
 */
public class ClusterManager {

    private static final String TAG = "ClusterManager";

    // Exact name in ServiceManager (case-sensitive, confirmed by `service list`)
    public static final String SERVICE_NAME = "AutoContainer";

    // Parameters sendInfo(type, infoInt, infoStr)
    public static final int CLUSTER_TYPE      = 1000;
    public static final int CMD_PROJECTION_ON   = 16;  // 全屏投屏开启 — ENABLE projection (CONFIRMED 13/04/2026)
    public static final int CMD_STOP_PROJECTION  = 18;  // 投屏关闭 — CLOSE the projection (CONFIRMED 13/04/2026)
    public static final int CMD_RESTORE_NATIVE   = 0;   // 主机恢复付表视频流 — refresh Qt stream (after cmd 18)
    // CMD=1 : disconnects Qt completely — NEVER USE (destroys display 1)
    // Cluster screen size commands (DiLink 3.0/Di4.0) :
    public static final int CMD_SCREEN_SIZE_SEAL_EU  = 30; // 切换到12.3寸屏 — BYD Seal EU (CONFIRMED 16/04/2026)
    public static final int CMD_DI40_MODE            = 35; // Di4.0 mode — triggers VirtualDisplay creation (CONFIRMED 03/05/2026)
    // Timeout waiting for VirtualDisplay after full sendInfo(30→16→35) sequence.
    // Sequence: 2s delay + 6s (30→16) + 6s (16→35) + ~280ms creation = ~14.3s → 18s total with margin.
    // 🚨 VirtualDisplay does NOT exist at boot on Seal EU (confirmed by logcat 03/05/2026).
    private static final long FREEDOM_STARTUP_TIMEOUT_MS = 18000;
    // Polling interval to detect the virtual display
    private static final long POLL_INTERVAL_MS = 500;

    // ─────────────────────────────────────────────────────────────────────────

    /** Notified when the cluster VirtualDisplay becomes available (or on timeout). */
    public interface DisplayReadyCallback {
        void onDisplayReady(Display display, int displayId);
        void onDisplayTimeout();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Reference to the active DisplayListener during activateClusterDisplay(), so that cancel()
    // can unregister it even if no display ever appeared.
    private DisplayManager.DisplayListener mActiveDisplayListener = null;
    private DisplayManager               mActiveDisplayManager   = null;

    public ClusterManager(Context context) {
        mContext = context.getApplicationContext();
    }


    // ── Activation + waiting for VirtualDisplay ───────────────────────────────

    /**
     * Full activation sequence (CONFIRMED by logcat 03/05/2026, BYD Seal EU DiLink 3.0):
     *
     *   🚨 The VirtualDisplay does NOT exist at boot. It is created by the sequence below.
     *
     *   Fast path (VD already present from a previous session):
     *     sendInfo(30) → 6s → sendInfo(16) → onDisplayReady immediately
     *
     *   Slow path (VD not yet created):
     *     sendInfo(30) → 6s → sendInfo(16) → 6s → sendInfo(35)
     *     → Qt calls getQtProjectionDispInfoNative() → AutoDisplayService.createVirtualDisplay()
     *     → VirtualDisplay "fission_bg_xdjaVirtualSurface" id=1 appears in ~280ms
     *     → DisplayListener / polling fires → onDisplayReady()
     *
     *   Timeout: 18s (2s init + 6s + 6s + margin)
     *
     * The callback is called on the main thread.
     *
     * Details: doc_api/VIRTUALDISPLAY_CREATION_MECHANISM.md
     */
    public void activateClusterDisplay(final boolean freedomJustStarted,
            final DisplayReadyCallback callback) {
        final DisplayManager dm = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);

        // 1. First check if the cluster VirtualDisplay is already present (DISPLAY_CATEGORY_PRESENTATION)
        //    AutoDisplayService creates it at BOOT → available immediately without waiting.
        Display found = findClusterDisplay(dm);
        if (found != null) {
            // Fast path: VirtualDisplay already present (previous session, not destroyed yet).
            // Send 30→6s→16 to put Qt back in projection mode. VD already exists → immediate callback.
            AppLogger.i(TAG, "VirtualDisplay already present: id=" + found.getDisplayId()
                    + " name=" + found.getName() + " — fast path (30→6s→16)");
            final Display displayFound = found;
            AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_SCREEN_SIZE_SEAL_EU, "",
                new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String out) {
                        AppLogger.i(TAG, "fast path ADB(cmd=30): " + out);
                        mHandler.postDelayed(new Runnable() {
                            @Override public void run() {
                                AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "",
                                    new AdbLocalClient.Callback() {
                                        @Override public void onSuccess(String out2) {
                                            AppLogger.i(TAG, "fast path ADB(cmd=16): " + out2);
                                            mHandler.post(new Runnable() {
                                                @Override public void run() {
                                                    callback.onDisplayReady(displayFound, displayFound.getDisplayId());
                                                }
                                            });
                                        }
                                        @Override public void onError(String err) {
                                            AppLogger.e(TAG, "fast path ADB(cmd=16) ERROR: " + err);
                                            mHandler.post(new Runnable() {
                                                @Override public void run() {
                                                    callback.onDisplayReady(displayFound, displayFound.getDisplayId());
                                                }
                                            });
                                        }
                                    });
                            }
                        }, 6000);
                    }
                    @Override public void onError(String err) {
                        AppLogger.e(TAG, "fast path ADB(cmd=30) ERROR: " + err);
                        AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "",
                            new AdbLocalClient.Callback() {
                                @Override public void onSuccess(String out2) {
                                    AppLogger.i(TAG, "fast path ADB(cmd=16) fallback: " + out2);
                                    mHandler.post(new Runnable() {
                                        @Override public void run() {
                                            callback.onDisplayReady(displayFound, displayFound.getDisplayId());
                                        }
                                    });
                                }
                                @Override public void onError(String err2) {
                                    AppLogger.e(TAG, "fast path ADB(cmd=16) fallback ERROR: " + err2);
                                    mHandler.post(new Runnable() {
                                        @Override public void run() {
                                            callback.onDisplayReady(displayFound, displayFound.getDisplayId());
                                        }
                                    });
                                }
                            });
                    }
                });
            return;
        }

        // Display not found immediately — send full sequence 30→6s→16→6s→35 to create the VirtualDisplay.
        // sendInfo(35) = Di4.0 mode → Qt calls getQtProjectionDispInfoNative() → AutoDisplayService.createVirtualDisplay()
        // VirtualDisplay appears ~280ms after sendInfo(35). DisplayListener + polling will detect it.
        AppLogger.w(TAG, "VirtualDisplay not found — sending full sequence (30→6s→16→6s→35) + polling");

        // Extended timeout: Freedom may take ~5s to create the display on first startup.
        final long timeoutMs = FREEDOM_STARTUP_TIMEOUT_MS;

        // 1. Start AppStartManagement directly
        //    If freedomJustStarted=true: ClusterService already launched it, do not force-stop/restart
        //    (it would be in the process of starting → race condition).
        if (freedomJustStarted) {
            AppLogger.i(TAG, "activateClusterDisplay: Freedom already started by ClusterService — skip startFreedom()");
            // Still send sendInfo(30+16) to release the Qt surface
            mHandler.postDelayed(new Runnable() {
                @Override public void run() { sendActivationSequence(); }
            }, 2000);
        } else {
            
            AppLogger.i(TAG, "Invoking native BYD cluster spawn (com.byd.appstartmanagement)");
            Intent bypassIntent = new Intent();
            bypassIntent.setClassName("com.byd.appstartmanagement", "com.byd.appstartmanagement.frame.AppStartManagement");
            bypassIntent.addFlags(268468224);
            try {
                mContext.startActivity(bypassIntent);
            } catch (Exception e) {
                AppLogger.e(TAG, "Bypass failed", e);
            }
            mHandler.postDelayed(new Runnable() { @Override public void run() { sendActivationSequence(); } }, 2000);
        }

        // Listen for display additions + timeout
        final long[] pollCount = {0};
        final DisplayManager.DisplayListener[] listenerHolder = new DisplayManager.DisplayListener[1];

        listenerHolder[0] = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {
                Display d = dm.getDisplay(displayId);
                AppLogger.i(TAG, "onDisplayAdded id=" + displayId + " display=" + d);
                if (isClusterDisplay(d)) {
                    mHandler.removeCallbacksAndMessages(null);
                    dm.unregisterDisplayListener(listenerHolder[0]);
                    mActiveDisplayListener = null;
                    mActiveDisplayManager  = null;
                    AppLogger.i(TAG, "VirtualDisplay cluster detected: id=" + displayId);
                    callback.onDisplayReady(d, displayId);
                }
            }
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {}
        };
        mActiveDisplayManager  = dm;
        mActiveDisplayListener = listenerHolder[0];
        dm.registerDisplayListener(listenerHolder[0], mHandler);

        // Additional polling: onDisplayAdded is sometimes not triggered for VirtualDisplays
        // created in another process (cross-process binder)
        scheduleDisplayPoll(dm, listenerHolder, callback, pollCount, 0);

        // Global timeout — extended (FREEDOM_STARTUP_TIMEOUT_MS) because Freedom must start
        mHandler.postDelayed(new Runnable() {
            @Override public void run() {
                dm.unregisterDisplayListener(listenerHolder[0]);
                mActiveDisplayListener = null;
                mActiveDisplayManager  = null;
                mHandler.removeCallbacksAndMessages(null);
                AppLogger.w(TAG, "Timeout: cluster VirtualDisplay not detected after "
                        + timeoutMs + "ms");
                callback.onDisplayTimeout();
            }
        }, timeoutMs);
    }

    private void scheduleDisplayPoll(
            final DisplayManager dm,
            final DisplayManager.DisplayListener[] listenerHolder,
            final DisplayReadyCallback callback,
            final long[] pollCount,
            long delayMs) {
        mHandler.postDelayed(new Runnable() {
            @Override public void run() {
                pollCount[0]++;
                if (pollCount[0] * POLL_INTERVAL_MS >= FREEDOM_STARTUP_TIMEOUT_MS) return;

                Display found = findClusterDisplay(dm);
                if (found != null) {
                    mHandler.removeCallbacksAndMessages(null);
                    dm.unregisterDisplayListener(listenerHolder[0]);
                    mActiveDisplayListener = null;
                    mActiveDisplayManager  = null;
                    AppLogger.i(TAG, "VirtualDisplay found by polling: id=" + found.getDisplayId());
                    callback.onDisplayReady(found, found.getDisplayId());
                } else {
                    scheduleDisplayPoll(dm, listenerHolder, callback, pollCount, POLL_INTERVAL_MS);
                }
            }
        }, delayMs == 0 ? POLL_INTERVAL_MS : delayMs);
    }

    // ── Activation sequence sendInfo(30 → 16) ──────────────────────────────

    /**
     * Full activation sequence: sendInfo(30) → 6s → sendInfo(16) → 6s → sendInfo(35).
     * Confirmed sequence from logcat (03/05/2026, BYD Seal EU):
     *   sendInfo(35) triggers Qt JNI → AutoDisplayService.createVirtualDisplay() → VD appears ~280ms later.
     * The DisplayReadyCallback is NOT called here: the DisplayListener / polling handles it.
     */
    private void sendActivationSequence() {
        AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_SCREEN_SIZE_SEAL_EU, "",
            new AdbLocalClient.Callback() {
                @Override public void onSuccess(String out) {
                    AppLogger.i(TAG, "activation ADB(cmd=30): " + out);
                    mHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "",
                                new AdbLocalClient.Callback() {
                                    @Override public void onSuccess(String out2) {
                                        AppLogger.i(TAG, "activation ADB(cmd=16): " + out2);
                                        mHandler.postDelayed(new Runnable() {
                                            @Override public void run() {
                                                AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_DI40_MODE, "",
                                                    new AdbLocalClient.Callback() {
                                                        @Override public void onSuccess(String out3) { AppLogger.i(TAG, "activation ADB(cmd=35): " + out3); }
                                                        @Override public void onError(String err3)   { AppLogger.e(TAG, "activation ADB(cmd=35) ERROR: " + err3); }
                                                    });
                                            }
                                        }, 6000);
                                    }
                                    @Override public void onError(String err) {
                                        AppLogger.e(TAG, "activation ADB(cmd=16) ERROR: " + err);
                                        // Still attempt sendInfo(35) even if cmd=16 failed
                                        mHandler.postDelayed(new Runnable() {
                                            @Override public void run() {
                                                AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_DI40_MODE, "",
                                                    new AdbLocalClient.Callback() {
                                                        @Override public void onSuccess(String out3) { AppLogger.i(TAG, "activation ADB(cmd=35) after err16: " + out3); }
                                                        @Override public void onError(String err3)   { AppLogger.e(TAG, "activation ADB(cmd=35) ERROR: " + err3); }
                                                    });
                                            }
                                        }, 6000);
                                    }
                                });
                        }
                    }, 6000);
                }
                @Override public void onError(String err) {
                    AppLogger.e(TAG, "activation ADB(cmd=30) ERROR: " + err);
                    // On cmd=30 error: still send 16 → 6s → 35
                    AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "",
                        new AdbLocalClient.Callback() {
                            @Override public void onSuccess(String out2) {
                                AppLogger.i(TAG, "activation ADB(cmd=16) after err30: " + out2);
                                mHandler.postDelayed(new Runnable() {
                                    @Override public void run() {
                                        AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_DI40_MODE, "",
                                            new AdbLocalClient.Callback() {
                                                @Override public void onSuccess(String out3) { AppLogger.i(TAG, "activation ADB(cmd=35) after err30: " + out3); }
                                                @Override public void onError(String err3)   { AppLogger.e(TAG, "activation ADB(cmd=35) ERROR: " + err3); }
                                            });
                                    }
                                }, 6000);
                            }
                            @Override public void onError(String err2) { AppLogger.e(TAG, "activation ADB(cmd=16) ERROR after err30: " + err2); }
                        });
                }
            });
    }

    // ── Cluster display detection ─────────────────────────────────────────

    /**
     * Searches for a display that looks like the cluster VirtualDisplay among ALL displays.
     * We look for either PRESENTATION or a non-default VIRTUAL, because the VirtualDisplay
     * from AutoDisplayService can have any category depending on the ROM version.
     */
    private Display findClusterDisplay(DisplayManager dm) {
        // Strategy 1: PRESENTATION category displays
        Display[] presentations = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (presentations != null) {
            for (Display d : presentations) {
                if (d.getDisplayId() != 0) {
                    AppLogger.d(TAG, "PRESENTATION candidate: id=" + d.getDisplayId() + " name=" + d.getName());
                    return d;
                }
            }
        }
        // Strategy 2: any non-default display (id != 0)
        Display[] all = dm.getDisplays();
        if (all != null) {
            for (Display d : all) {
                if (d.getDisplayId() != 0) {
                    AppLogger.d(TAG, "Non-default candidate: id=" + d.getDisplayId() + " name=" + d.getName());
                    return d;
                }
            }
        }
        return null;
    }

    private boolean isClusterDisplay(Display d) {
        // A display is considered cluster if it is not the primary display (id=0)
        return d != null && d.getDisplayId() != 0;
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    /**
     * Cancels all in-progress operations: Handler polls, timeout, and DisplayListener.
     * MUST be called by DashboardDisplayHelper.stop().
     */
    public void cancel() {
        mHandler.removeCallbacksAndMessages(null);
        if (mActiveDisplayManager != null && mActiveDisplayListener != null) {
            mActiveDisplayManager.unregisterDisplayListener(mActiveDisplayListener);
            mActiveDisplayListener = null;
            mActiveDisplayManager  = null;
        }
        AppLogger.d(TAG, "cancel() — Handler and DisplayListener cancelled");
    }
}
