# Physical full-surface test theme

Diag contains **Test full cluster theme — night + Full (experimental)**. This
requests an actual OEM root-QML reload through the existing ProxyDaemon and RCC
transport. It installs a native dark background and cyan frame in the top and
bottom areas occupied by the OEM decorative bands. Real OEM instruments and
warnings retain their original layout. This is a visible composition experiment,
not the finished concept dashboard or proof of exclusive physical screen control.

## Test procedure

Use a parked vehicle in a suitable test location. Explicitly connect the updated
ProxyDaemon through its existing controls; this variant requires protocol 32.
Manually select Full navigation and night mode before loading the test theme.
The dialog requires your parked assertion; the app does not detect movement.

Press the new theme button and confirm **Load test theme**. If runtime admission
passes, the background becomes dark (`#061018`) with a cyan frame, four corner
marks and `DASHCAST TEST`. The stock instrument information stays above it. The
frame uses the OEM root geometry, including theme-2's existing scale/offset, so
its logical corners are not a claim about the physical panel's exact edges.
Stock alert/ADAS/center layers can still cover parts of the test drawing.

The theme appears only while all three native conditions hold:

```qml
DataSource.powerGear === DataSource.POWER_GEAR_ON &&
DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN &&
DataSource.dayNightMode === 0x02
```

The button does not force those modes. In day mode, non-Full navigation or power
states other than ON, the added background component is invisible and the OEM
decorative objects retain their original opacity. The unchanged parent snapshot
and fade animation can retain an older frame during its 500 ms transition; this
is not a promise of an immediate physical switch. Missing test graphics remain inconclusive.
The dark test background is native QML and does not depend on a projected frame;
it deliberately covers the projected map behind it while the condition holds.

Use **I can see DASHCAST TEST, four cyan canvas corners and readable OEM instruments**
only after observing that result on the physical cluster. The existing marker-observation
button is specific to the older passive-marker experiment and cannot settle this
theme test. ACKs and selection queries never substitute for your observation.

**Restore original cluster selection** remains above the scrolling controls,
independent of Diag's shared busy gate. The existing daemon requests restoration
after 30 seconds. Verify the original physical display, then use its OEM-observed
button. A timeout, file removal or queried default is not visual restoration;
reboot alone is not assured rollback. An unresolved session remains quarantined.

## Fixed content and unchanged boundaries

The additional asset `cluster_live/full_surface_test.rcc` contains exactly 84
unchanged stock locale resources and two identity-pinned OEM roots. Its SHA-256 is
`64a7a752b01e80a822610bf775eb656f79bd3378a6228b6e36c2490932049ddc`.
The original marker asset and hash remain unchanged.

Each root receives one bounded, noninteractive background rectangle at
`CustomStyle.bgZ`, behind functional OEM items. The new rectangle has no timer,
input handler, animation, image asset, simulated instrument values or backend
call. Three decorative objects receive a conditional opacity binding:
`bgImageContainerId`, `headLineId` and `driveRangeInfoBgId`. The first includes
both background and terrain imagery. No warning object is a child of these
decorative objects. Native source/visibility writes, including the 350 ms
day/night timer, cannot overwrite their new opacity bindings.

All other OEM code bytes are preserved, including functional containers, nested
low-battery WarningLight objects, `WarinLightLayout`, gear/mode data, battery,
range, power, speed, ODO, ADAS, Center and their original visibility/animation
rules. The common `main.qml` and Full-switch handshake are not overridden. No
third-party skin or artwork is copied. Night-only operation avoids introducing
a bright background behind OEM icons whose white artwork has not been adapted.

Both experiments share transaction 44, one worker, one durable journal, helper,
carrier ownership checks, watchdog and restore path. Legacy operations 0–4 retain
their protocol-31 wire format. Closed operation 5 starts this fixed theme; closed
operation 6 records its tester observation. Both require protocol 32 before the
app transmits, with no reconnect or replay. Callers cannot supply an asset name,
hash, QML, arbitrary OEM command or recovery policy.

Journal schema 2 stores the compiled variant and exact carrier hash. Schema 1 is
accepted only as the historical marker format without profile/hash fields.
Unknown schemas, profiles or inconsistent hashes remain quarantined. Cleanup
uses the recorded variant's exact hash; a marker observation cannot settle the
theme load. Status and the existing historical report cache include the variant,
expected carrier hash, preflight phase and separate theme observation. That
cache never controls admission or recovery.

All original runtime binary hashes, Java APK/UID checks, mapped inode/device
checks, process/start/mount identity, source bounds, native callbacks and
selection/recovery prerequisites remain mandatory. The user's confirmation of
matching cluster firmware selects the disk corpus; it does not replace live
identity verification. Unreadable identity still refuses the request. Refusals
now retain the preflight phase, artifact path and actual/expected digest or the
underlying read error. No SX361 hash allowance is inferred from a build string.

The two earlier reports at 19:49/19:58 were generated with beta694. They confirm
a protocol-31 daemon at UID 2000 but contain no cluster-test status/ACK proving
where admission stopped. Their missing early-refusal telemetry was fixed by
beta695's app-private historical cache; the absence of old entries cannot identify
a specific failing prerequisite. A new report after an attempted test preserves
the current refusal without requesting any additional cluster operation.

### Renderer discovery refusals

The old `Expected one readable qtandroidnative renderer; found 0` message conflated
failed `/proc` enumeration, unreadable process names, disappearing processes and
no matching name in the daemon's visible process view. It does not establish that
the renderer is absent or that the RCC was rejected: this phase precedes staging
and every native RCC request.

Renderer discovery now retains enumeration status, read/permission/race counters,
the first bounded error label, and any entry/PID/time limit in the normal status
detail and historical report cache. Only the first NUL-terminated `argv0` is
considered; subsequent command arguments and arbitrary exception messages are
omitted. The scan permits at most 8,192 entries, 4,096 PIDs and 1,025 bytes per PID
under a five-second soft budget, checked between reads. An incomplete enumeration
refuses admission even when one name matched. A read may outlast the soft budget;
no subsequent phase starts after that read returns past the deadline.

The existing exact `qtandroidnative` basename match is unchanged. A unique name
is only a discovery candidate: all original process-generation, mount namespace,
executable/maps inode and artifact hash checks still apply. There is no UID,
namespace, path, alias or permission fallback. Denied or masked processes remain
unknown; readable names do not establish the complete host process inventory.
The `vanished` counter records ENOENT/ESRCH or a missing-file observation during
the scan; it does not distinguish process exit from visibility restrictions.

For the existing refusal, collect **Diag → Exports & OEM collection → Relevés
locaux OEM → Contexte cluster — bibliothèque et processus**. This existing
targeted export retains the bounded process list, exact-name argv0/stat bracket,
maps and namespace link observations; it does not load a theme or activate a
display. Its ZIP is useful even when reads are denied or the export is partial.

## Reproduction and checks

```bash
python3 tools/cluster/build_full_surface_theme.py \
  --stock '/mnt/d/BYD Extract/REA-Cluster-IVI-RCC-20261022/extracted-current/system/lib64/commonTranslate.rcc' \
  --roots '/mnt/d/BYD Extract/REA-Cluster-FullSurface-20261020' \
  --check
python3 -m unittest discover -s tools/cluster -p 'test_*.py' -v
```

The generator rejects unknown stock/root hashes and already-patched roots. It
writes only the two named assets and `artifacts/cluster_full_surface_test/` with
the exact roots, unified patches, manifest and checksums. Original firmware files
remain untouched. Independent resource tests compare all 84 locale entries,
stored/decoded bytes and metadata, and remove the new block/bindings to recover
both original root SHA-256 values. Existing marker checks remain applicable.

An isolated Qt 5.15.19 / PyQt 5.15.11 offscreen harness loaded the exact new block
and tested 75 power/navigation/day-night combinations, plus five transition
captures. The night+Full case showed the dark background and cyan corner pixels;
a synthetic z=100 foreground remained above it. This validates the new QML
fragment with mocked DataSource/CustomStyle and decorative stubs. It does not
execute the full OEM roots/plugins or validate RCC admission, actual warning
visibility, physical composition or vehicle restoration. Harness results are in
`REA-Cluster-Complete-20261022/qt-validation/RESULT.json` in the analysis corpus.

The [earlier offline bandless proof](CLUSTER_BANDLESS_THEME.md) remains a separate
transparent-background artifact and is not the carrier selected by this button.
