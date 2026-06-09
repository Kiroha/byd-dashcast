package com.byd.dashcast.proxy.daemon;

import android.os.IBinder;

/**
 * ProxyDaemonContract — wire protocol constants shared between the daemon
 * ({@link ProxyDaemonMain}) and the app-side client ({@link com.byd.dashcast.proxy.ProxyClient}).
 *
 * <p>Separating the contract from the implementation ensures the client does not
 * depend on the daemon's entry-point class. Add a new transaction code here
 * (and nowhere else) when extending the protocol. Never remove or renumber
 * existing codes — old daemons are still in the field until the app kills them.
 *
 * <p>Transaction numbering starts at {@link IBinder#FIRST_CALL_TRANSACTION} (= 1)
 * and is dense. Each code maps 1-to-1 to a {@code case} in
 * {@code ProxyDaemonMain.ProxyBinder.onTransact}.
 */
public final class ProxyDaemonContract {

    private ProxyDaemonContract() {}

    // ─── Binder identity ─────────────────────────────────────────────────

    /** AIDL-style descriptor for {@link android.os.Binder#attachInterface}. */
    public static final String DESCRIPTOR = "com.byd.dashcast.proxy.daemon.IProxyDaemon";

    // ─── Bootstrap broadcast ──────────────────────────────────────────────

    /** Broadcast action delivered to the app once the daemon is ready. */
    public static final String ACTION_PROXY_CONNECTED = "com.byd.dashcast.proxy.PROXY_CONNECTED";

    /** Parcelable extra key carrying the daemon's {@link BinderParcelable}. */
    public static final String EXTRA_BINDER = "proxy_binder";

    // ─── Transaction codes ────────────────────────────────────────────────

    /** No args → {@code long} epoch_ms. */
    public static final int TXN_PING                    = IBinder.FIRST_CALL_TRANSACTION;      // 1

    /** No args → {@code int uid, int pid, String protocolVersion}. */
    public static final int TXN_WHOAMI                  = IBinder.FIRST_CALL_TRANSACTION + 1;  // 2

    /** {@code String cmd} → {@code int exitCode, String combinedOutput}. */
    public static final int TXN_EXEC                    = IBinder.FIRST_CALL_TRANSACTION + 2;  // 3

    /** No args → {@code String pipeSeparatedProbeResults}. Phase 4 feasibility probe. */
    public static final int TXN_PROBE_PHASE4            = IBinder.FIRST_CALL_TRANSACTION + 3;  // 4

    /** {@code int displayId, int l, int t, int r, int b} → void (or remote exception).
     *  Phase 4a typed verb replacing {@code wm overscan L,T,R,B -d displayId}. */
    public static final int TXN_SET_OVERSCAN            = IBinder.FIRST_CALL_TRANSACTION + 4;  // 5

    /** {@code String packageName} → {@code String spaceSeparatedPids}.
     *  Phase 4b typed verb replacing {@code pidof <pkg>}. */
    public static final int TXN_GET_PIDS                = IBinder.FIRST_CALL_TRANSACTION + 5;  // 6

    /** {@code int type, int info, String str} → void (or remote exception).
     *  Phase 4c typed verb replacing {@code service call AutoContainer 2 …}. */
    public static final int TXN_AUTOCONTAINER_SEND_INFO = IBinder.FIRST_CALL_TRANSACTION + 6;  // 7

    /** {@code String packageName, int userId} → void (or remote exception).
     *  Phase 4d typed verb replacing {@code am force-stop <pkg>}. */
    public static final int TXN_FORCE_STOP_PACKAGE      = IBinder.FIRST_CALL_TRANSACTION + 7;  // 8

    /** {@code String name, int w, int h, int dpi, int flags, Surface surface} → {@code int displayId}.
     *  Phase 5a: create a VirtualDisplay on the daemon (uid 2000) which holds
     *  {@code CAPTURE_VIDEO_OUTPUT}. Mirrors OpenBYD 2.0 {@code launchOnVirtualDisplay}. */
    public static final int TXN_CREATE_VIRTUAL_DISPLAY  = IBinder.FIRST_CALL_TRANSACTION + 8;  // 9

    /** {@code int displayId} → void. Phase 5a: release a VD created by
     *  {@link #TXN_CREATE_VIRTUAL_DISPLAY}. */
    public static final int TXN_RELEASE_VIRTUAL_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 9;  // 10

    /** {@code String pkg, String cls, int displayId, int w, int h} → {@code String log}.
     *  Phase 5b: full OpenBYD launchAndForce sequence. */
    public static final int TXN_LAUNCH_AND_FORCE        = IBinder.FIRST_CALL_TRANSACTION + 10; // 11

    /** {@code String pkg, int displayId, int l, int t, int r, int b} → {@code String log}.
     *  Phase 6: move + resize an existing task in-place (no am start). */
    public static final int TXN_MOVE_AND_RESIZE         = IBinder.FIRST_CALL_TRANSACTION + 11; // 12

    /** {@code int displayId} → {@code String log}.
     *  Phase 6b: destroy every non-fullscreen, non-home stack on a display. */
    public static final int TXN_CLEAN_FISSION_STACKS    = IBinder.FIRST_CALL_TRANSACTION + 12; // 13

    /** {@code String packageName} → {@code int taskId} (-1 if not found).
     *  Phase 7: find the task ID hosting a package via ATM reflection, so
     *  the caller can removeTask() before force-stopping (avoids orphan tasks
     *  on display 0 after session teardown). */
    public static final int TXN_FIND_TASK_FOR_PACKAGE   = IBinder.FIRST_CALL_TRANSACTION + 13; // 14

    /** {@code int taskId} → void (or remote exception).
     *  Phase 7: remove a task from the recents stack via ATM reflection,
     *  replacing the {@code am task remove} + {@code TaskRemover app_process}
     *  chain used by {@code AdbLocalClient.forceStopApp}. */
    public static final int TXN_REMOVE_TASK             = IBinder.FIRST_CALL_TRANSACTION + 14; // 15

    // ─── CAN bus write (instrument cluster HUD) ───────────────────────────

    /** {@code int status} → {@code int resultCode}.
     *  Set navigation status on the instrument cluster HUD.
     *  status: 2 = NAVI_STATUS_ACTIVE, 4 = NAVI_STATUS_STOPPED.
     *  Calls {@code BYDAutoInstrumentDevice.set({INSTRUMENT_SEND_NAVI_STATUS}, eventValue)}
     *  via the daemon's permission-bypassed system context. */
    public static final int TXN_CAN_NAVI_STATUS         = IBinder.FIRST_CALL_TRANSACTION + 15; // 16

    /** {@code int featureId, int value} → {@code int resultCode}.
     *  Write an integer value to any CAN instrument feature ID.
     *  featureId is the raw BYD CAN feature constant (see {@link CanWriteVerbs}).
     *  Returns the SDK result code (0 = INSTRUMENT_COMMAND_SUCCESS). */
    public static final int TXN_CAN_INSTRUMENT_INT      = IBinder.FIRST_CALL_TRANSACTION + 16; // 17

    /** {@code int featureId, byte[] data} → {@code int resultCode}.
     *  Write a byte buffer to any CAN instrument feature ID (e.g. street name UTF-8).
     *  Returns the SDK result code (0 = INSTRUMENT_COMMAND_SUCCESS). */
    public static final int TXN_CAN_INSTRUMENT_BYTES    = IBinder.FIRST_CALL_TRANSACTION + 17; // 18
}
