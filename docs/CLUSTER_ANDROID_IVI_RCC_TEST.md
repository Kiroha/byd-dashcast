# Android IVI RCC diagnostic

Build 702 / 1.9.63-beta introduces ProxyDaemon protocol **35** operations 11/12
(fixed marker/full-surface RCC), and 13 (explicit RCC recovery or reconciliation
of a pre-IPC app debt). It fixes the admission mismatch behind the 23 October
14:52/14:55 reports: the running host renderer is outside the Android shell's
visible process namespace. A missing Android-visible `qtandroidnative` therefore
does not mean the cluster is absent, and those reports contained no OEM RCC load.

The new transport is explicitly `android-ivi`. The previous strict renderer
transport and its schema 1/2 recovery remain separate; no failed strict check
silently selects a different transport. Schema 3 requires the exact transport,
compiled variant and carrier hash. Unknown/corrupt journals block admission.

## Evidence and limits

REA on firmware **13.1.33.2602030.1**, without vehicle execution, establishes:

| Artifact / procedure | Relevant result | Evidence |
| --- | --- | --- |
| `fissiond` / `get_rw_path`, `mount_cell` | Cell2's `/data` is bind-mounted from `/data/cells/cell2-rw/data` | `ev_84e85bbbfb88e0e05b51c1a7ca66ff3ac91b6e0a2e44d271015d85cefd1132c8`, `ev_f3cfc2f3d178dfa9902a0e4ed2bc02ad750708a28a5734182bc25516139be5f0` |
| `vold` / `VolumeManager::start`, `EmulatedVolume::doMount`, `linkPrimary` | Emulated storage uses `/data/media`; primary storage selects a user below `/storage/emulated` | `ev_a8bba86b6716c064ffb5aee5962c53fcaa0b648de1e1253cc7a7909c471a443e`, `ev_85260bafd702750a78ba9e907f68c2da1628cad14ac769f57094cbac780b2600`, `ev_5fd13070aed7b0f593c67fa3458bc01332b440ae53e846c32a17bbb50883eb4e` |
| Firmware `/init.rc` | `/sdcard` links to `/storage/self/primary` | SHA-256 `7d8935dce9a6c4959870e08af3d5288d0817bac61b13bfdd88a4e1932a54b0b1` |
| Native `receiveData2` / RCC handler | Type 8 carries query cmd1/sub4, load cmd5/mode0 and default request cmd5/mode1 | `ev_29b439404d23bd6825872f39cad6d02c5a3e0b2ff680ea9d4d6d967c25245269`, `ev_85fbb537a998174e9feceeb6eeb0eb6465e7563ae6476861af4351d41ebe4f8c` |
| RCC validation / registration | Fixed UI-version prefix, host lookup directory and ACK2 resource path | `ev_af2d52782f3a5a8995068c739ce93f5b43a1ca798a7b0608037b802b3ab761d0`, `ev_92369c32336c977f767125be611820dd850b1d157c28c1766365057ca62a394e` |

Exact extracted `fissiond` SHA-256:
`31d39dfc63ffdae993ae5003fc6d392409a1a4c482609cb36f2fcbd8343fd93e`;
`vold`: `be00feac24e08ad2348dfe90187616a6ce1b81c3241f2f74313a77b1764f4175`.
The canonical 13-record REA mapping bundle has SHA-256
`f103e8d897a8fdce8495ab0a38fd0da018cdc6b2d8663cfd993277e662f147d8`.

The derived Android path is `/sdcard/.cluster/translate/`, only for **primary
user 0** resolving to `/storage/emulated/0`. The host lookup is
`/data/cells/cell2-rw/data/media/0/.cluster/translate/`. Other users and aliases
are refused. These are static mapping proofs; runtime mounts, permissions and
the deployed native binaries are still **UNKNOWN**. A matching UI-version token
is a protocol-contract check, not proof of native binary compatibility.


REA also follows the real `updateTranslateRccFromIvi` body at `0x21b588`
(`ev_62bbb1764331613feb5441c9e3ac6da54372c5d43af45b9e3029f6bc2396256d`):
a valid filename is selected into configuration key `0x38`, followed by
`SendIntMsg(0x10,0x0c)`. This body has no `g_productIndex` gate. The constructor
binds `(Di3F_.*_.*)_Translate(.*).rcc` to its regex member
(`ev_878a42d1d88e481289dc50ccc1b806ff90925e5e2b42f5ec9c4fce387df45825`,
`ev_44bf1071d0ce2f4e0deb6f74411bb19b08f91e08daaf5ccbd365608b6292046b`); the closed
`Di3F_DynastyOceanTranslate_V0001_TranslateDashCast_<nonce>.rcc` names match.
These are static filename/dispatch proofs, not evidence of runtime loading.

## Closed test and recovery

The app requires parked/firmware assertions, API 29, positive DL3 detection,
explicitly connected protocol 35 and an idle projection/ADAS reservation. It
durably writes a negative-only app RCC debt before IPC. The daemon checks mutual
exclusion with native tests, queries, RCC recovery and production mode commands.
No generic command fallback, reconnect, 16/18, CAN frame or automatic test load
is used. The two existing compiled RCC files remain byte-identical.

One bounded child verifies the installed system Java bridge's actual APK, UID
1000, source/splits and exact SHA, pins `AutoContainer` /
`android.os.IAutoContainer`, and accepts only bounded type-8 callbacks from UID
1000 during its lease. No host process is needed or claimed verified. Its native
bridge is reached through the researched system Java service, not directly as
shell UID 2000.

Before staging and again inside the loading child, a fresh query must name the
exact UI contract `Di3F_DynastyOceanTranslate_V0001`, source 1 and default
selection. The daemon writes a private journal before queueing, file creation,
and helper GO. Storage parents are pinned by open directory FDs; `/proc/self/fd`
is used only to anchor operations to those owned Android directory handles.
Final components use `O_NOFOLLOW` and creation uses `O_EXCL`. File size, regular
type, compiled hash, device/inode and parent generations are checked. Emulated
storage UID is not treated as native ownership proof. The loading child compares
the exact staged file device/inode and all parent generations again. Cleanup
rechecks the final named inode immediately before deletion; this is not an
atomic compare-and-unlink operation on shared storage.

Only one load cmd5/mode0 is emitted. Native result, exact own-filename ACK2,
fresh query naming the host path, and the user's visible marker/theme answer are
distinct. Callbacks lack request IDs and remain **uncorrelated broadcast
evidence**; time windows, conflicts, malformed input and overflow are checked.
Native zero or ACK4 alone cannot admit the observation-ready state. A successful
native call plus a fresh exact own-path query and no conflicting/error evidence
can ask for visual observation even without ACK2; `loadSettled` stays false until
an exact ACK2 or a fresh positive marker/theme observation. REA shows that the
hot-reload branch of `translateRccChanged` can omit ACK2. Missing/negative visual
confirmation does not settle the load or permit deletion.

The preload confirmation authorizes one watchdog recovery request after the
observation interval, if the existing session is available. It is a request,
not a 30-second restoration guarantee. The header's **Restore BYD RCC resources**
also remains available and asks for current parked/firmware assertions. Return
18 is a different mode operation and does not restore RCC resources.

Recovery queries before acting, never overwrites an unrelated selection, and
emits at most one default request per explicit recovery. In this firmware,
cmd5/mode1 clears the IVI translation field only while that field is nonempty;
after an OEM cold restart it can return `RESOURCE_IS_SAME` while the custom
selection persists. Without a fresh default query, recovery stays unresolved.
Reboot alone is not a rollback guarantee.

Deletion requires the own filename/inode/hash, settled load, fresh default
selection, visible OEM recovery and no unresolved IPC uncertainty. OEM recovery is confirmed
in a separate popup before closing debt and releasing projection admission.
Timeouts, missing ACK, changed identities, failed journal persistence, failed
cleanup and an unreaped child retain recovery/quarantine. Restart never replays
commands. A trusted idle journal can reconcile a flag committed before any IPC;
that operation durably records the exact nonce and sends no OEM command.

The normal bug-report flow includes bounded cumulative phases, timestamps,
transport/native-identity uncertainty, Java identity proof, queries/ACK outcomes,
helper admission/cleanup, recovery and both app debt flags. Partial helper steps
survive a deadline. Exporting the report itself performs no cluster IPC.

## Parked validation still required

Start with the recommended marker, answer the automatic observation popup from
the physical cluster, request/observe OEM restoration, then send the diagnostic
report with the test time. Only after that should the Full + night theme be
tried. Vehicle loading, all instruments/telltales, actual pixels, recovery and
Telegram reception have not been established by static/JVM verification.
