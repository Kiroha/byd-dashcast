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

## Build 705: separate selection from actual QML application

**1.9.66-beta / build 705, ProxyDaemon 38** adds a separate fixed
`corner-marker` profile. The previous three packaged RCC files and their hashes
remain unchanged. Operations 15/16/17 are explicit corner start, nonce/round-bound
user observation, and read-only verification; old operations retain their format.
The new profile is Android-IVI only, including recovered schema-3/4 journals.

REA on the exact firmware explains why a selected file can leave the display
unchanged. `msgDataUpdate(0x2d)` calls `updateTranslateRccFromIvi`, which saves
configuration key `0x38` and emits configuration ACK4; it does not directly publish
translation data item 5 (`ev_ffebc743b6936ef3aad0619d083299c1c5b8d7dcaa088a87ae0ed671bbf3807c`,
`ev_62bbb1764331613feb5441c9e3ac6da54372c5d43af45b9e3029f6bc2396256d`).
The complete `SetConfig_STRING` chain saves XML without a renderer callback
(`ev_53eefec58956fc5451986fcf8f5abcb684e15a3e0e1500f1e5c6bb860f75e2a2`,
`ev_505c9bcc2a0d2f654d2f1fe09acf85cf027d7c91acd8766c3d66f91f7e781d28`,
`ev_dac169ea47527fa39847ed5089190aa4a11059fba85424b741de02390ee4c7a3`).
XML publication occurs through `updateThemeRcc` during native initialization or
specific model/debug paths. Those paths do not authorize an arbitrary apply
command. No vehicle action or restart duration is guaranteed here.

Common `main.qml` destroys the old theme, unregisters its resources, registers
translation before the stock theme, then creates the fixed root alias. Native
unregister clears both filenames and the QML component cache
(`ev_07d90cc364d529c6f31e9ccdf73d04b381b4d939f9d654d059e2901cf9c3bc00`,
`ev_cd037c3e5185b2aaa74fa749ff9ccb9b39cce7edffb7b2a73be2140cbec71457`).
Qt appends registered roots and lookup checks the first matching resource. Thus
our two root aliases can be selected in that researched reload sequence; merely
selecting the file does not establish that the sequence ran. ACK2 is an
uncorrelated registration broadcast, not component-creation or pixel proof;
ACK4 can clear the request flag before registration replies. The older theory
that missing ACK2 alone establishes a hot reload is withdrawn.

For a parked vehicle, first resolve any earlier test debt through its existing
recovery flow. Then:

1. Connect protocol 38 explicitly and choose **Tester le chargement RCC (repères
   aux coins)**. Confirm parked/firmware assertions. This sends one existing
   cmd5/mode0 selection request; it does not send an OEM restart or force Full.
2. Read **RCC sélectionné — affichage non confirmé**. Selection persists. This
   new profile has **no automatic 30-second return after load admission**.
   Preflight, copying, hashing, the helper deadline and helper reaping remain
   bounded; errors preserve the journal, carrier and recovery debt without an
   automatic second OEM mutation. Existing profiles retain their recovery timer.
3. A genuine OEM cluster reinitialization through normal vehicle operation may
   be needed to read the stored selection. The app triggers none and guarantees
   no particular action/duration. Reconnecting the daemon does not reinitialize
   the cluster. After any daemon restart reconnect explicitly; no load is replayed.
4. Use **Vérifier les repères RCC**, a separate read-only action. It requires the
   original trusted nonce/profile/hash, unchanged file/inode/parents, a live
   projection reservation, no recovery proof or other helper, and one fresh
   source-0 exact-own-path query. File identity and reservation are checked again
   afterward. Source 1, a foreign/default response, uncertainty or changed file
   cannot open the visual question. No load/default/restart is sent.
5. A successful check offers a 60-second nonce/generation/round-bound popup:
   inspect cyan and magenta corner brackets and readable OEM instruments on the
   physical cluster. No/Unknown sends no observation. Yes records only this
   tester statement; it does not settle a load, erase uncertainty, mark default,
   close recovery debt or admit projection/new tests. Expiry, changed connection,
   an explicit Restore, or daemon restart invalidates the question. A restarted
   daemon requires a new manual verification.
6. Export the normal diagnostic report. Use **Restaurer les ressources RCC BYD**
   separately when returning to OEM resources; **Vérifier le retour BYD** belongs
   only to the preserved-carrier recovery flow.

The corner carrier SHA-256 is
`3f3052c4d79f5c14ed3b708921127ac07f91ac4b03e68c94ea176505b66eb6bb`.
It preserves all 84 stock locale resources and both complete OEM roots apart
from one inserted transparent Item. Eight static 4-pixel outer-edge strokes
have 96/80-pixel arms, z=1, below the researched Center/ADAS/warning/boot layers.
They appear only for strict native ON=3, completed startup `playAnim===true`,
P=1 and speed===0. Unknown values hide them; these conditions do not establish
safe parking. Full/night are not required. No filled panel, text, simulated
values, input handler, timer, alert modification or Common/main override is added.
The original root geometry/scale is preserved; physical crop and legibility
still need vehicle validation.

Reports distinguish send/native receipt, exact selected path, exact-path ACK2
broadcast, and tester-observed graphics. Every bounded valid callback records
arrival elapsed time/stage and ACK2/ACK3/ACK4/selection meaning; output occurs
after sealing the callback lease. Both successful and negative exchanges seal
and recheck final Binder/deadline/invalid/conflicting responses atomically. ACK4
means configuration only. Historical receipts never become admission proof.

Local validation includes deterministic carrier preservation tests and an
optional existing-PyQt5 offscreen check of **only the new owned fragment** with
mock data and a synthetic foreground. It checks both aliases, two dimensions,
corner pixel colors, higher-layer precedence and strict guard failures. It does
not execute OEM QML/imports/plugins or establish vehicle loading/recovery.

## Build 704: negative ACK diagnosis and explicit recovery preparation

**1.9.65-beta / build 704 requires ProxyDaemon 37** for Android-IVI operations.
The 16:45 reports show native result zero followed by ACK3, with the owned
persistent selection still active. Build 703 aborted before its final query and
exported only a generic failure, so **the runtime numeric code is unknown in
those reports**. It is not evidence that every ACK3 is `RESOURCE_IS_SAME`.

ACK3 now retains all five decimal/hex integers, bounded sanitized strings and
known error names. One bounded read-only postquery uses the same pinned bridge;
ACK3 remains a failed mutation even if that query reports default. There is no
retry, second load, reconnect or debt clearing. Eligibility is a separate helper
receipt, sealed only after final Java identity/Binder/deadline checks, callback
validation and selection-conflict checks. A malformed, foreign-UID, conflicting,
expired or dead-identity exchange cannot authorize preparation.

For the existing blocked session, with the vehicle parked:

1. Install build 704 and explicitly reconnect the daemon (protocol 37).
2. Open **Diag → Cluster : projection et RCC** and press
   **Restaurer les ressources RCC BYD**. This obtains a fresh decoded result.
3. Only if the OEM actually reports clean `RESOURCE_IS_SAME` and the exact own
   persisted path, **Préparer le retour BYD après erreur RCC** appears for two
   minutes. Read its popup and confirm parked/firmware assertions.
4. Preparation moves **only the recorded DashCast carrier** into a fresh
   `.dashcast-retired-<nonce>/` directory beside it. The original filename,
   variant, bytes, SHA and inode remain recorded. This is **not restoration**:
   active graphics can stay unchanged. The file is preserved permanently.
5. Manually reinitialize the OEM cluster. No OEM restart is sent by this app;
   reconnecting Android or the daemon does not establish native reinitialization.
   No particular vehicle action or restart duration is proven by static analysis.
6. Press the fixed header action, now **Vérifier le retour BYD**. For a retired
   session it only reads the OEM selection and verifies both absent original
   lookup name and preserved archive identity/hash. A nondefault response keeps
   the session pending. No load/default request is sent in this branch.
7. Only after a fresh default response, answer the automatic OEM observation popup
   from the physical cluster. A new matching positive answer, verified preserved
   file and durable CLEAN journal release the original nonce's operational debt.
   Export the normal diagnostic report after each result.

The native **Tester la projection BYD (avec bandeaux)** control is separate and
remains blocked while RCC recovery is unresolved. Preparation is explicit and
never started by a watchdog, app startup, daemon restart or popup answer.

The manual preparation requires a fresh validated negative receipt with exact
ACK integers **[3, 0, 0x2330, 0, 0]** (error in `ints[2]`, not `ints[1]`),
source **0**, the exact UI contract and own persistent host path. A fresh own-path
query repeats that source/path check before a possible rename, including resumed
intent with an original file still present. Foreign selections are never replaced.

Schema **4** is separate from schema 3 and requires strict retirement directory
metadata, same-filesystem device, distinct directory inode, moved state and the
original carrier generation/hash. Schema 1/2 never becomes an IVI lease; schema 3
cannot carry retirement metadata. Fresh unique directory creation refuses an
existing name; parents/directories are pinned with nonblocking, no-follow FDs.
Directory fsync support is checked before changing a carrier. Every possible
rename is preceded by a successful durable intent checkpoint, then fresh source,
target-absence and reservation checks. Post-rename identity/hash/absence and
both parent fsyncs precede the completion checkpoint. Failed intent, rename,
fsync or completion retains debt. An explicit retry can reconcile a moved inode
without moving twice; startup performs no replay. Incomplete intent cannot use
the verification route to mint recovery proof.

Android's guarded rename is **not an atomic source-generation comparison or
no-overwrite guarantee against another process racing these checks**. Directory
substitution, symlinks, changed inode/hash, reappearing original, unsupported
fsync or unreadable state fail closed; simultaneous writers remain a material
shared-storage limitation. This diagnostic has no new native filesystem helper.
The preserved RCC is never automatically moved back, deleted or reloaded.

REA firmware **13.1.33.2602030.1** supports this conditional preparation:

- RCC handler mode1 can report SAME when the transient IVI field is empty while
  XML-backed native selection remains custom. `sendIviSetRccRes` DWARF signature
  and its handler caller establish the exact error slot
  (`ev_35665c433f9da36aa9d540692aa5df51bde421280252531360fb64867281a21a`).
- `updateTranslateRccFromXml` clears config key 0x38 and publishes the default
  translation when the selected lookup file is absent
  (`ev_7118ee57e6543755206b6dc0cd793050828313ddb2229173714e6d211aee6fe2`).
  `updateThemeRcc` invokes it before early returns
  (`ev_5d82f7b83d0b07e0ca7acf52fbbff870e4bc16ca508273cb12fe1254193717ee`);
  native self-study completion invokes that update
  (`ev_df74c7983f7115f617e3fa9ebcfdb4f49010fd5741fbef584135339c347c3bbb`).
- Exact QtCore SHA `3b9abb05e6800fc3785f5f2b08d64afbaf0fae197aa700c6b370e806647fbe46`
  retains a private mapping or complete heap data and closes the file after
  registration (`ev_4db25f16b278fc0eb4ff383e138b6510df96062676bddd5fc3cd60208d3ef9ff`).
  Buffer roots retain pointers (`ev_18384f0ff558894ce7edcae4e40c15992b69b56e2ca44c21fb9a5bb55cb30ace`);
  destruction frees/unmaps (`ev_6db2d5e1e51c0a5d7a662c8246caa0455fb69fe3df0bed1396e1ea3ea6da1d59`),
  and unregister matches retained names/roots without reopening the original
  (`ev_a7f7c3ab8f252624237a7e7708e7e034b19698eb813213f76aa8f8534765d391`).

These are static shipped-artifact proofs. Runtime native identity, actual OEM
initialization, graphics, telltales and physical recovery remain unverified.
Reports include schema, exact carrier/archive identity, eligibility, durable
phases, decoded errors, query and observation outcomes without raw-payload
exports or extra IPC during report generation.

## Build 703 regression correction

**1.9.64-beta / build 703 requires ProxyDaemon 36**, using the same operations.
The 15:51, 15:54 and 15:55 reports show one load sent at 15:49:24. Its fresh
query returned source **1**, the exact owned IVI host path, and resource
`DashCast_a`. Build 702 incorrectly required source 0, then rejected the same
owned path during recovery as foreign. Persistent RCC debt consequently blocked
native projection. The native command was not shown to have failed: it was not
sent again in these reports.

REA `getTranslateSource` at `0x188cc0`
(`ev_ec8ea4a138af5ddd4dc19efedd574db39c7bb251149007edfe08ccd61c8fd438`)
sets the source from the native `m_translateRccPath`. The query path is chosen
independently from `m_translatePathFromIvi` when that field is set
(`ev_96c25d56ea8d291732657097204f0a652adde66e8153a40e13e57a8075da1d43`).
Thus source 0 or 1 can accompany our exact IVI path. The matcher still requires
the exact UI version, supported source value and owned host path. This query
proves an IVI selection field, **not registration or rendering**.

For the existing build-702 session, first install build 703 and reconnect the
daemon, then use **Diag → Cluster : projection et RCC → Restaurer les ressources
RCC BYD**. Observe the cluster and answer the OEM restoration popup. Start another
test only after that recovery closes. **Tester la projection BYD (avec bandeaux)**
is the separate native command 16; it retains the top and bottom BYD bars and
does not load an RCC. A blocked request now explains the exact restoration action
in a popup and is included in the diagnostic report. These names are localized
in all 13 app languages.

A recovered trusted schema-3 journal keeps its original nonce, variant, hash,
file device/inode and pinned parent identities. After positive helper reaping,
an exact own-path prequery permits one default request; the mutating helper
checks the unchanged carrier and compiled hash before sending it. A successful
fresh default postquery records an independent **ordered IVI recovery proof**.
Historical load uncertainty remains recorded. Only a fresh positive OEM visual
answer, verified owned cleanup and durable CLEAN journal release operational
debt. A foreign selection, failed/ambiguous response or identity change cannot
mint this proof. An already-default query without a prior proof cannot settle
an unresolved load. A durable proof survives restart and non-OEM cleanup or
journal failures, but still requires a fresh default query and visual answer on
recovery. No load is replayed. Strict-renderer recovery is unchanged.

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
explicitly connected protocol 37 and an idle projection/ADAS reservation. It
durably writes a negative-only app RCC debt before IPC. The daemon checks mutual
exclusion with native tests, queries, RCC recovery and production mode commands.
No generic command fallback, reconnect, 16/18, CAN frame or automatic test load
is used. The two historical compiled RCC files remain byte-identical; build 705 adds a
separate corner profile.

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
an exact ACK2 or a fresh positive marker/theme observation. Missing ACK2 does not identify the apply path; the build-705 native-chain
analysis above supersedes the earlier hot-reload explanation. Missing/negative
visual confirmation does not settle the load or permit deletion.

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

Deletion requires the own filename/inode/hash, fresh default selection and visible
OEM recovery, plus either settled load without IPC uncertainty or the separate
ordered Android IVI recovery proof described above. OEM recovery is confirmed
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
