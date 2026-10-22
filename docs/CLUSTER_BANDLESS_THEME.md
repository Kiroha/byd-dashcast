# Offline OEM band decoration experiment

This proof removes the fixed OEM **background decoration** above and below the
projection in the identity-matched disk firmware `13.1.33.2602030.1`. It retains
the OEM instruments and warnings at their existing positions. It is a first
composition change, not the finished concept theme or a validated vehicle theme.

The user confirms that the local firmware has the same cluster. The tool pins
the exact local root and translation-resource SHA-256 values; this confirmation
does not replace a measurement of the binaries currently loaded in the vehicle.

## Exact change

Both `qml/cluster.qml` and `qml10_25/cluster.qml` receive three property lines:

```qml
opacity: DataSource.powerGear === DataSource.POWER_GEAR_ON && DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? 0 : 1
```

Only these three decorative objects receive the binding:

| Object | Effect in power ON + Full navigation |
| --- | --- |
| `bgImageContainerId` | Makes its background and terrain images transparent |
| `headLineId` | Removes the decorative horizontal head line |
| `driveRangeInfoBgId` | Removes the background behind the battery/range data |

`bgImageContainerId` contains `bgImageId` and `terrainImageId`, with no warning
objects. Consequently this removes all background imagery in that container in
ON + Full, including the terrain image; it does not crop only the band pixels.

An opacity binding is used because the OEM functions write `visible` and
`source` during theme, day/night and power transitions. Those existing writes,
including the theme-1 day/night timer at 350 ms, remain intact and cannot restore
the opacity of these three objects. The pinned originals contain no competing
opacity writer or animation targeting them. Outside ON + Full, the new factor
is 1, the stock default. No Full mode, animation acknowledgment, permission or
transport is forced by this artifact.

All functional items and their parents remain byte-for-byte unchanged, including
`headInfoId`, `driveRangeInfoContainerId`, the gear and mode information,
`odoItemId`, `naviPowerId`, `naviSpeedId`, nested low-battery `WarningLight`
objects and `WarinLightLayout`. Their original bindings and visibility remain.
`ecoSportModeImgId` is retained because it communicates the selected drive mode.
The common `main.qml` and its Full-animation handshake are absent from the
override and unchanged. No OEM QML component or third-party skin is added.

## Reproduce without a vehicle

From the repository root, using the previously extracted, identity-matched
REA corpus:

```bash
python3 tools/cluster/build_bandless_theme.py \
  --stock '/mnt/d/BYD Extract/REA-Cluster-IVI-RCC-20261022/extracted-current/system/lib64/commonTranslate.rcc' \
  --roots '/mnt/d/BYD Extract/REA-Cluster-FullSurface-20261020'
python3 tools/cluster/build_bandless_theme.py \
  --stock '/mnt/d/BYD Extract/REA-Cluster-IVI-RCC-20261022/extracted-current/system/lib64/commonTranslate.rcc' \
  --roots '/mnt/d/BYD Extract/REA-Cluster-FullSurface-20261020' \
  --check
python3 -m unittest discover -s tools/cluster -p 'test_bandless_theme.py' -v
```

The generator reads its inputs and writes only `artifacts/cluster_bandless/`:

- `bandless-offline.rcc`: the 84 original locale resources and two patched roots.
- `patched/`: the exact roots embedded in the offline RCC.
- `patches/`: reviewable unified diffs, three added lines per root.
- `manifest.json`: original, patched, compressed-resource and carrier hashes,
  metadata, scope and limitations.
- `SHA256SUMS`: hashes of every other generated file.

Unknown inputs and already-patched roots are rejected before output is written.
The OEM roots and source images are never modified. Tests use an independently
implemented RCC reader, compare all 84 locale entries against the existing
stock-preserving marker, verify the exact insertion locations and recover each
original root with its known SHA-256 by removing the three new lines. This is
an offline reconstruction check, not evidence of vehicle restoration.

## Runtime boundary and remaining validation

This carrier is deliberately separate from `app/src/main/assets/cluster_live`.
The marker, its admission hash, the daemon, runtime policy and Diag are unchanged.
The current Diag marker test cannot select this carrier. No installation, RCC
load, Binder/ADB command, CAN/VHAL/SOME/IP action or vehicle communication occurs.
No Android release is required to inspect or reproduce this proof.

Static checks do not prove that Qt loads the result or that physical composition
is correct. No Qt QML linter/runtime is installed in the checked tool locations,
so these checks do not constitute a Qt syntax or OEM DataSource execution test.
The added expression uses existing QML properties and DataSource constants.

The retained OEM text selects dark gray (`#333333`) in day mode and white in
night mode. Arbitrary projected content can make that text unreadable. Before
vehicle use, visual validation must cover light and dark content, day/night
changes, Full/non-Full transitions, power changes, relevant warnings, and an
absent or failed projection. In particular, the patch does not establish a safe
fallback background when the projection disappears while Full remains selected.
Preserving warning code alone is not a claim of readable or safe vehicle output.

The later complete theme must integrate the necessary indications into its own
layout and validate their contrast and warning behavior. This experiment keeps
their OEM layout to make the decorative-background change reviewable in isolation.

## Existing REA evidence

The proof reuses the exported findings rather than repeating native analysis:

- `REA-Cluster-FullSurface-20261020/rea-fullsurface-reused-native-evidence.json`:
  native projection layering and profile/theme selection.
- `REA-Cluster-FullSurface-20261020/current_background_mask_evidence.json`:
  opaque Full-navigation band measurements in both OEM backgrounds.
- `analysis-rea-20261022/assets-investigation/ADDENDUM_TOP_BOTTOM_OEM_BANDS.txt`:
  exact decorative and functional QML IDs and nested warning relationships.

The carrier is a design experiment based on these shipped resources; static REA
findings do not imply that the patched theme has executed on a cluster.
