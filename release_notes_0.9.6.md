# DashCast v0.9.6-alpha — Settings icons, the actual root cause

Tiny pre-release. The previous tint-related fixes (0.9.3 → 0.9.5) were treating the wrong symptom: even with no tint at all, the leading icons in `Comportement` and `À propos` showed up as **tiny white dots** instead of glyphs.

## Root cause

On this BYD Android 10 runtime, `LayoutInflater` extracts `android:layout_width` and `android:layout_height` from the **View tag itself**, not from the applied `<style>`. The `Widget.DashCast.ListItem.Leading` style declared `layout_width="28dp"` and `layout_height="28dp"`, but they were silently dropped. The ImageView therefore fell back to a near-zero size, which is why the vector rendered as a 1–2 px white dot.

This is a well-known Android quirk on older / less standard runtimes: layout params **must** be on the View tag, not in a style.

## Fix

For every leading `<ImageView>` in `activity_settings.xml` (7 occurrences across the cluster type, overscan, behavior and about cards, plus the science pre-release row):

- Inlined `android:layout_width="28dp"`.
- Inlined `android:layout_height="28dp"`.
- Inlined `android:layout_marginEnd="16dp"`.

The style is still applied for the non-layout attributes (`layout_gravity`, `scaleType`).

The `@color/md_on_surface_variant` tint from 0.9.5 is kept (it works correctly once the ImageView actually has a real size).

## Version

- `versionCode`: **125 → 126**
- `versionName`: **0.9.5 → 0.9.6**

## What did not change

Everything else: no logic change, no API change, no widget IDs touched, all 12 locales intact, OTA / Vérifier behaviour identical to 0.9.4-0.9.5, Welcome screen identical.

## Test plan

Open **Settings**:

- **Comportement** must show `restart_alt`, `autorenew`, `filter_list` as proper 28dp glyphs (light gray on dark surface).
- **À propos** must show `info_outline`, `system_update`, `code`, `science` plus the trailing `open_in_new` chevron.
- The +/- icons on the overscan sliders were already correctly sized in previous releases (explicit `24dp` width/height on the View tag), they should remain visible.

If the icons are now clearly visible, we can finally close the icon saga and move to the next M3 phase.
