package com.byd.dashcast.proxy.daemon

import android.os.IBinder

/**
 * ProxyDaemonContract — wire protocol constants shared between the daemon ([ProxyDaemonMain]) and
 * the app-side client ([com.byd.dashcast.proxy.ProxyClient]).
 *
 * Separating the contract from the implementation ensures the client does not depend on the
 * daemon's entry-point class. Add a new transaction code here (and nowhere else) when extending
 * the protocol. Never remove or renumber existing codes — old daemons are still in the field until
 * the app kills them.
 *
 * Transaction numbering starts at [IBinder.FIRST_CALL_TRANSACTION] (= 1) and is dense. Each code
 * maps 1-to-1 to a `case` in `ProxyDaemonMain.ProxyBinder.onTransact`.
 *
 * Kotlin port note: every value below was transposed by script from the Java and the whole set is
 * diffed against the Batch-0 frozen baseline. A silently renumbered code would not fail to compile
 * and would not fail a test — it would make a field daemon answer the wrong verb.
 */
object ProxyDaemonContract {

    // ─── Binder identity ─────────────────────────────────────────────────

    /** AIDL-style descriptor for [android.os.Binder#attachInterface]. */
    const val DESCRIPTOR = "com.byd.dashcast.proxy.daemon.IProxyDaemon"

    // ─── Bootstrap broadcast ──────────────────────────────────────────────

    /** Broadcast action delivered to the app once the daemon is ready. */
    const val ACTION_PROXY_CONNECTED = "com.byd.dashcast.proxy.PROXY_CONNECTED"

    /** Parcelable extra key carrying the daemon's [BinderParcelable]. */
    const val EXTRA_BINDER = "proxy_binder"

    // ─── Transaction codes ────────────────────────────────────────────────

    /** No args → `long` epoch_ms. */
    const val TXN_PING                    = IBinder.FIRST_CALL_TRANSACTION      // 1

    /** No args → `int uid, int pid, String protocolVersion`. */
    const val TXN_WHOAMI                  = IBinder.FIRST_CALL_TRANSACTION + 1  // 2

    /** `String cmd` → `int exitCode, String combinedOutput`. */
    const val TXN_EXEC                    = IBinder.FIRST_CALL_TRANSACTION + 2  // 3

    /** No args → `String pipeSeparatedProbeResults`. Phase 4 feasibility probe. */
    const val TXN_PROBE_PHASE4            = IBinder.FIRST_CALL_TRANSACTION + 3  // 4

    /** `int displayId, int l, int t, int r, int b` → void (or remote exception).
     *  Phase 4a typed verb replacing `wm overscan L,T,R,B -d displayId`. */
    const val TXN_SET_OVERSCAN            = IBinder.FIRST_CALL_TRANSACTION + 4  // 5

    /** `String packageName` → `String spaceSeparatedPids`.
     *  Phase 4b typed verb replacing `pidof <pkg>`. */
    const val TXN_GET_PIDS                = IBinder.FIRST_CALL_TRANSACTION + 5  // 6

    /** `int type, int info, String str` → void (or remote exception).
     *  Phase 4c typed verb replacing `service call AutoContainer 2 …`. */
    const val TXN_AUTOCONTAINER_SEND_INFO = IBinder.FIRST_CALL_TRANSACTION + 6  // 7

    /** `String packageName, int userId` → void (or remote exception).
     *  Phase 4d typed verb replacing `am force-stop <pkg>`. */
    const val TXN_FORCE_STOP_PACKAGE      = IBinder.FIRST_CALL_TRANSACTION + 7  // 8

    /** `String name, int w, int h, int dpi, int flags, Surface surface` → `int displayId`.
     *  Phase 5a: create a VirtualDisplay on the daemon (uid 2000) which holds
     *  `CAPTURE_VIDEO_OUTPUT`. Mirrors OpenBYD 2.0 `launchOnVirtualDisplay`. */
    const val TXN_CREATE_VIRTUAL_DISPLAY  = IBinder.FIRST_CALL_TRANSACTION + 8  // 9

    /** `int displayId` → void. Phase 5a: release a VD created by
     *  [TXN_CREATE_VIRTUAL_DISPLAY]. */
    const val TXN_RELEASE_VIRTUAL_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 9  // 10

    /** `String pkg, String cls, int displayId, int w, int h` → `String log`.
     *  Phase 5b: full OpenBYD launchAndForce sequence. */
    const val TXN_LAUNCH_AND_FORCE        = IBinder.FIRST_CALL_TRANSACTION + 10 // 11

    /** `String pkg, int displayId, int l, int t, int r, int b` → `String log`.
     *  Phase 6: move + resize an existing task in-place (no am start). */
    const val TXN_MOVE_AND_RESIZE         = IBinder.FIRST_CALL_TRANSACTION + 11 // 12

    /** `int displayId` → `String log`.
     *  Phase 6b: destroy every non-fullscreen, non-home stack on a display. */
    const val TXN_CLEAN_FISSION_STACKS    = IBinder.FIRST_CALL_TRANSACTION + 12 // 13

    /** `String packageName` → `int taskId` (-1 if not found).
     *  Phase 7: find the task ID hosting a package via ATM reflection, so
     *  the caller can removeTask() before force-stopping (avoids orphan tasks
     *  on display 0 after session teardown). */
    const val TXN_FIND_TASK_FOR_PACKAGE   = IBinder.FIRST_CALL_TRANSACTION + 13 // 14

    /** `int taskId` → void (or remote exception).
     *  Phase 7: remove a task from the recents stack via ATM reflection,
     *  replacing the `am task remove` + `TaskRemover app_process`
     *  chain used by `AdbLocalClient.forceStopApp`. */
    const val TXN_REMOVE_TASK             = IBinder.FIRST_CALL_TRANSACTION + 14 // 15

    // ─── CAN bus write (instrument cluster HUD) ───────────────────────────

    /** `int status` → `int resultCode`.
     *  Set navigation status on the instrument cluster HUD.
     *  status: 2 = NAVI_STATUS_ACTIVE, 4 = NAVI_STATUS_STOPPED.
     *  Calls `BYDAutoInstrumentDevice.set({INSTRUMENT_SEND_NAVI_STATUS`, eventValue)}
     *  via the daemon's permission-bypassed system context. */
    const val TXN_CAN_NAVI_STATUS         = IBinder.FIRST_CALL_TRANSACTION + 15 // 16

    /** `int featureId, int value` → `int resultCode`.
     *  Write an integer value to any CAN instrument feature ID.
     *  featureId is the raw BYD CAN feature constant (see [CanWriteVerbs]).
     *  Returns the SDK result code (0 = INSTRUMENT_COMMAND_SUCCESS). */
    const val TXN_CAN_INSTRUMENT_INT      = IBinder.FIRST_CALL_TRANSACTION + 16 // 17

    /** `int featureId, byte[] data` → `int resultCode`.
     *  Write a byte buffer to any CAN instrument feature ID (e.g. street name UTF-8).
     *  Returns the SDK result code (0 = INSTRUMENT_COMMAND_SUCCESS). */
    const val TXN_CAN_INSTRUMENT_BYTES    = IBinder.FIRST_CALL_TRANSACTION + 17 // 18

    /** `int featureId, int value` → `int resultCode`.
     *  Write an integer value to a CAN *setting* feature ID via
     *  `BYDAutoSettingDevice.set(int[], BYDAutoEventValue)`.
     *  Required for `SET_NAVI_SCREEN_STATUS_SET` (1276174357 → value 3)
     *  which activates the navigation lane on the instrument cluster display;
     *  that feature lives on the SettingDevice, not InstrumentDevice. */
    const val TXN_CAN_SETTING_INT         = IBinder.FIRST_CALL_TRANSACTION + 18 // 19

    /** `int featureId` → `int value`.
     *  Read an integer from a CAN *instrument* feature via
     *  `BYDAutoInstrumentDevice.get(int[])` → `BYDAutoEventValue.intValue`.
     *  In-app reads are rejected by the SDK; only the daemon's privileged context works. */
    const val TXN_CAN_INSTRUMENT_GET      = IBinder.FIRST_CALL_TRANSACTION + 19 // 20

    /** `int featureId` → `int value`.
     *  Read an integer from a CAN *setting* feature via
     *  `BYDAutoSettingDevice.get(int[])` → `BYDAutoEventValue.intValue`.
     *  Used to read e.g. `SET_HUD_MODE_FEEDBACK` while the OEM nav drives the HUD. */
    const val TXN_CAN_SETTING_GET         = IBinder.FIRST_CALL_TRANSACTION + 20 // 21

    /** No args → `String status`.
     *  Register a BYD setting feedback listener inside the daemon (privileged context) to capture
     *  PUSH feedback (the HUD/nav feature values are push-only — not gettable). Idempotent. */
    const val TXN_CAN_LISTEN_START        = IBinder.FIRST_CALL_TRANSACTION + 21 // 22

    /** No args → `String events`.
     *  Drain (return + clear) the push events captured since the last drain. */
    const val TXN_CAN_LISTEN_DRAIN        = IBinder.FIRST_CALL_TRANSACTION + 22 // 23

    /** No args → `String report`.
     *  AAOS-only: probe the automotive display proxy HAL (IAutomotiveDisplayProxyService) from
     *  the daemon (uid 2000) to test whether app windows can be drawn to the cluster panel. */
    const val TXN_AAOS_HAL_PROBE          = IBinder.FIRST_CALL_TRANSACTION + 23 // 24

    /** No args → `void`.
     *  Clear the push-feedback event log + persistent last-known map (fresh, uncontaminated read). */
    const val TXN_CAN_LISTEN_CLEAR        = IBinder.FIRST_CALL_TRANSACTION + 24 // 25

    /** `String label` → `void`.
     *  Append a timestamped user ground-truth marker (the maneuver shown on the HUD) to the log,
     *  so a driving capture can correlate the tapped arrow with the CAN events at that instant. */
    const val TXN_CAN_LISTEN_MARK         = IBinder.FIRST_CALL_TRANSACTION + 25 // 26

    /** `int featureId, double value` → `int resultCode`.
     *  Write a DOUBLE value to a CAN *setting* feature via
     *  `BYDAutoSettingDevice.set(int[], BYDAutoEventValue)` with the `doubleValue`
     *  field set. Required for the HUD ANGLE (0x4C10E02C), which the OEM CarSettings app writes
     *  as a double (proven from the OEM HalSetter logcat). Returns the SDK result code. */
    const val TXN_CAN_SETTING_DOUBLE      = IBinder.FIRST_CALL_TRANSACTION + 26 // 27

    /** `String path, long offset, int maxLen` → `byte[] chunk`.
     *  Read up to `maxLen` bytes of `path` at `offset` from inside the daemon
     *  (uid 2000 = shell), which can read `/data/local/tmp` files that SELinux hides from
     *  the app uid. Returns an empty array at EOF. Lets the app pull an arbitrarily large raw
     *  logcat capture in bounded chunks without overflowing a single Binder parcel. */
    const val TXN_READ_FILE_CHUNK         = IBinder.FIRST_CALL_TRANSACTION + 27 // 28

    /** `String packageName` → `int status, int taskId, int displayId`.
     *  Status values are defined by `TaskLocation.Status.wireCode`. Unlike the legacy
     *  task-id lookup, ABSENT and UNKNOWN are distinct so a daemon/reflection failure cannot be
     *  treated as proof that a running navigation task disappeared. */
    const val TXN_FIND_TASK_LOCATION      = IBinder.FIRST_CALL_TRANSACTION + 28 // 29

    /** Ordered list of `CanBatchOperation` records → `int appliedCount`.
        *  The daemon executes records sequentially and stops at the first thrown SDK error or
        *  non-zero native result code. Truthful native-result semantics require protocol v24. */
    const val TXN_CAN_BATCH               = IBinder.FIRST_CALL_TRANSACTION + 29 // 30

    /** `int type, int info, String str` → `int nativeResult`.
     *  Result-preserving AutoContainer call used to distinguish an accepted command (`0`)
     *  from the D50F native rejection (`-1`). */
    const val TXN_AUTOCONTAINER_SEND_INFO_RESULT = IBinder.FIRST_CALL_TRANSACTION + 30 // 31

    /** `nullable String packageName` → `boolean cancelled`.
     *  Stops one package guardian, or all guardians when packageName is null. */
    const val TXN_CANCEL_FISSION_WATCHDOG = IBinder.FIRST_CALL_TRANSACTION + 31 // 32

    /** `int type, byte[] data` → void (or remote exception).
     *  Typed verb for `AutoContainer.sendInfo2(type, data)` (AIDL transaction 3) — the same
     *  binder method the OEM's own navigation app uses to push a serialized `NaviInfo`
     *  FlatBuffer (type=4) to the instrument-cluster HUD. Reaches the same `checkSignatures`
     *  fast-path as [TXN_AUTOCONTAINER_SEND_INFO], so any `type` is accepted from the
     *  daemon's uid, not only the values the OEM's `container_comm_cfg.json` allow-lists. */
    const val TXN_AUTOCONTAINER_SEND_INFO2 = IBinder.FIRST_CALL_TRANSACTION + 32 // 33

    /** No args → `String report` (raw hex + best-effort decode, never throws for "no
     *  service" — that IS the diagnostic answer on non-DL3 platforms).
     *  Read-only probe of the native `FissionHostSvc` display registry
     *  (transaction 101 = `getAutoCarDisplay`), DL3 only. See
     *  [FissionHostSvcVerbs]. */
    const val TXN_FISSION_GET_AUTOCAR_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 33 // 34

    /** No args → `int resultCode`.
     *  Registers the daemon's own callback Binder with `AutoContainer.registerCallback`
     *  (AIDL transaction 4) so a future bug report captures `serviceDied()`/
     *  `receivedJson/Info/Info2()` pushes from the native container service — diagnostic
     *  only, never called before this release. See [Phase4ProcessVerbs]. */
    const val TXN_AUTOCONTAINER_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 34 // 35

    /** No args → void.
     *  Arms a background sampler of the `FissionHostSvc` registry (one sample every ~2s,
     *  logged only on change, hard-capped at 90s so a forgotten trace cannot run forever) so the
     *  app can watch it across a normal projection start/stop cycle. See
     *  [TXN_PROJECTION_TRACE_DRAIN]. */
    const val TXN_PROJECTION_TRACE_START = IBinder.FIRST_CALL_TRANSACTION + 35 // 36

    /** No args → `String report`. Stops the sampler (if still running) and returns every
     *  change recorded since [TXN_PROJECTION_TRACE_START]. */
    const val TXN_PROJECTION_TRACE_DRAIN = IBinder.FIRST_CALL_TRANSACTION + 36 // 37
}
