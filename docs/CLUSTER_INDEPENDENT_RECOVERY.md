# Independent cluster recovery investigation

Evidence checkpoint: 2026-10-25. This is an engineering finding, **not a validated
recovery procedure or permission to load a new cluster theme**.

The investigation combined static REA analysis with narrowly filtered, read-only
observations from the identified vehicle. No reboot, country change, MCU write,
property write or RCC load was performed. The candidate full-screen presentation
remains offline; the retired full-app profile remains recovery-only.

## What Country Code Tool actually does

The supplied `com.byd.countrycodetool` APK performs three distinct operations
after its confirmation dialog:

1. Write and sync `/collect2/countrycode.txt`.
2. Send `TEST_COUNTRY_CODE_SET` through `BYDAutoTestDevice.set` to the MCU-facing
   configuration interface.
3. Schedule `PowerManager.reboot("fission-all")` after 3 seconds.

The integer return from the MCU call is ignored, but an uncaught exception can
prevent the delayed reboot from being scheduled. The reboot API is a separate
operation; the observed physical recovery does **not** establish that changing
the country is necessary. Country/file/MCU writes must not be copied into a
recovery feature.

The exact DEX independently confirms the reboot reason and invocation. The OEM
APK's corresponding callback also contains the same reboot operation. The
supplied package declares only one Activity and no dedicated reboot service or
receiver; its inspected startup does not expose an intent-extra reboot contract.

## Android authorization and host boundary

`PowerManager.reboot(reason)` forwards to
`IPowerManager.reboot(false, reason, true)`. The matching
`PowerManagerService.BinderService` enforces `android.permission.REBOOT` before
entering the shutdown sequence.

The sequence includes Android shutdown notifications, ActivityManager and
PackageManager shutdown, and a radio-shutdown branch for `fission-all`.
`lowLevelReboot` then writes both a host property through
`SystemProperties.setHost("sys.ivi.powerctl", ...)` and the local Android
`sys.powerctl` property. This is broader than a QML reload or app restart.

The installed `svc.jar` provides a CLI branch which passes its reason to the
same Binder API with the same flags. It remains subject to the same permission:
invocation from an ADB shell does not acquire the authority of a system-UID app.
There is no executable reboot command in this document.

Initial read-only permission checkpoint, before the manual app update:

| Caller | Observed identity | REBOOT observation |
| --- | --- | --- |
| Country Code Tool | UID 1000, `android.uid.system`; exact supplied APK hash | Granted |
| DashCast at the initial checkpoint | UID 10081, version **1.9.75-beta / build 715** | Not present in requested/installed permission sections |
| Android shell | UID 2000, `android.uid.shell`; only the shell package in that shared UID | Not present in requested/installed permission sections |

The user subsequently installed **1.9.76-beta / build 716 manually** and
confirmed the normal OEM display. A new read-only observation confirms UID
10081 and APK SHA-256
`1ff58ce9aa840c8aaad0438ef0a3ce2f9b04fdbd4f1dadedd637252a23e51327`, exactly
matching the reviewed published release. **Build 716 is the current installed
application; build 715 above is historical.** The investigator performed no
installation or vehicle write.

A version marker reporting 716, a registered proxy service and a live proxy
process under UID 2000 were also observed. They are operational metadata, **not
an authenticated Binder attestation of the running daemon build**, and do not
prove recovery or full-screen admission. A fresh daemon-version check remains
separate from the application package/hash check.

`cmd package check-permission` is unsupported on this build; its error was not
treated as a grant or denial. The observations above come from the filtered
package/permission records. The Country Code Tool signer is
`efe3ca8ada0d10c655c3df9910ad2ebc121a47d9a6358434eb24074309933efc`, distinct from
DashCast's usual signer. Adding a manifest declaration alone would not establish
an effective grant. Direct host-property writes must not bypass this boundary.

## Conditional native restart path

In the matching `fissiond`, `cell_exit_handler` at `0x10f4f8` handles child exit.
Its reboot branch, outside single-OS mode, obtains the cell2 reboot reason and
compares it against `s_host_reboot_reason` at `0x11b058`. The first table entry is
`reboot,fission-all`. A matching branch requests a host reboot; the alternative
branch restarts only the IVI cell.

The selected firmware's host init definitions place the native Qt cluster consumer outside that
Android cell. This identifies a potential recovery authority which does not
depend on constructing the replacement QML root. It does not prove that a
particular caller can trigger it or that the factory instruments will recover.

The reason reader at `0x1138ec` looks for `persist.sys.boot.reason` in the
persistent-property data file. Its constructor at `0x113cec` selects
`/data/cells/cell2-rw/data/property/persistent_properties`. The writer and
freshness of that exact key remain unresolved.

Do not conflate it with `ShutdownThread`'s **different** property
`persist.sys.rebootreason`. Read-only observations showed `fission-all` in the
latter and `reboot,fission-all` in `ro.boot.bootreason`, while
`persist.sys.boot.reason` was empty in the Android property view. These are
consistent historical boot tags, not proof of the value the host file reader
will obtain during a future exit.

The separate external ADB maintenance protocol is under investigation as its
own authorization path. The actual adbd binary was not readable from the current
cell, so forwarding of a requested reason to the required fresh persistent key
has **not** been established. No reset was used to test it. Absence of host Qt
processes or init-service fields in the IVI view is not evidence that they are
stopped.

## Firmware identity established by read-only comparison

The observed vehicle reports Android API 29, incremental build
`eng.build.20260610.082733`, and `ro.build.system.fission_single_os=0`.
The following six installed-file hashes match the statically analysed inputs:

| Installed file | SHA-256 |
| --- | --- |
| `/system/framework/framework.jar` | `b3c14f7ced622c2390890d72d4a87bce9f504f267280ec875b2f029e62c86776` |
| `/system/framework/services.jar` | `0a669bb75d40327258462175a874dc791524524ab59a0028f68e7aafe0bfa187` |
| `/system/framework/svc.jar` | `dc95181109b7cd21f4c775b0530a36a3e01daa23f7c5e8ac3871677092c45f8c` |
| `/system/bin/fissiond` | `31d39dfc63ffdae993ae5003fc6d392409a1a4c482609cb36f2fcbd8343fd93e` |
| `/system/lib64/libBydCluster.so` | `21f7735cad4408f7baf7086b0e0d7b4ed3d1d2818a35bea6b8ad026102d9f4b1` |
| `/system/lib64/libBydDataSource.so` | `02dfc7f5aa046167a3d829c69ef8ee97fd301cf9081450b86366319566c10ae8` |

The installed Country Code Tool APK separately matches the supplied artifact:
`b1a3ebcf46c111d3edf6c1458ab89bbed963873942896b66bd39e153422790ec`.
This ties the findings to this observed cohort, not every DiLink 3 firmware.
Analysis-only APK carriers retain the JAR DEX bytes unchanged; their artificial
manifests do not describe installed packages or permissions.

## Evidence anchors

Complete records are retained locally; raw decompilation and device records are
not published in the repository. These REA IDs identify the relevant findings:

| Finding | REA evidence ID |
| --- | --- |
| Country confirmation and delayed callback | `ev_762f12697335e85306e246c9bf2edc5d27a21f56fb614e07420fb0e0d846df15` |
| Country file plus MCU write | `ev_1a57d6285bf219a0b062475c585218dec1b47b12293047bcf265d2c4f5fbdadc` |
| Binder REBOOT permission | `ev_cf2d050dbc78c5aeb4331fb4f43728a5f6945883fcaf8271dab676ec8bf0b3e8` |
| Host and local property writes | `ev_401c49dabbef95d507fe4ae57d9647b9813caf196f3b1b1c9d441fbc624f209f` |
| CLI forwards to the same Binder API | `ev_dccfd8878d101562bb5e4f890b841ea7fb1739e8ac9dca3fd515d7d9afd2dbf1` |
| Native conditional child-exit handling | `ev_9374f610a45cf50d08cf0c7858b4e46ab6c1bda9b717a7771bc055343d3367aa` |
| Native table identity and reason reader | `ev_9122db4e167e836eb60d0937e3d6c7db460cf5a5b464367a2ea144bc08d46c18`, `ev_6877efb3ecbd10682c82eaef9d03489292895fbc8a8b089f44cea07c84910fbc` |
| Persistent-file path constructor | `ev_b3750d025ff10bb39a94bb7902da160903198aa6a69314e0e45e2273c80a79e7` |

## Next controlled step and remaining gates

The manual update to the reviewed, signed **1.9.76-beta / build 716** and the
user's confirmation of normal BYD instruments complete the application-baseline
step. The exact installed APK hash is confirmed above. This is **not** a recovery
test, a Binder daemon attestation or admission to load a new RCC. The retired
profile's refusal and the offline candidate remain in place. See the
[1.9.76 release notes](releases/1.9.76-beta.md).

Before any separate recovery trial:

- Establish the external caller's actual authority, reason-forwarding contract,
  persistent-key writer and freshness; do not use a permission workaround.
- Preserve the exact owned carrier and recovery journal. A host restart which
  reloads the same bad persistent RCC may repeat the failure. Factory resources
  and OEM indications must remain intact.
- Require a fresh, suitable immobilized test context, one exclusive request and
  a rollback plan independent of the candidate QML and its controller.
- Observe the real consumer's new process generation, factory selection and
  physical instruments. ACK, `Loader.Ready`, an Android reconnect or a boot tag
  alone does not prove recovery.
- Cover root-construction failure, Qt-consumer death, controller termination,
  interruption during recovery, missing/frozen Android frames and loss after a
  first frame. Existing debt must survive uncertainty without automatic retries
  or reactivation of the retired profile.

No reboot button, additional privilege, new vehicle transport or full-screen
admission is introduced by these findings. The geometry and layer changes remain
subject to the [offline prototype's admission limits](cluster-full-app-test.md).
