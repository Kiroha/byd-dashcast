# DashCast v0.9.5-alpha — Settings icons (third and final fix)

Tiny pre-release that fixes one specific regression reported on the car: the leading icons in **Comportement** and **À propos** were still invisible after **v0.9.4-alpha**.

## What was wrong (history)

- **0.9.3**: tint was declared inside each `<vector>` drawable as `android:tint="?attr/colorOnSurfaceVariant"`. Theme-attribute references inside a vector drawable are not honored at runtime on the BYD device → icons rendered transparent.
- **0.9.4**: tint moved to the `<ImageView>` itself, still as `android:tint="?attr/colorOnSurfaceVariant"`. Inlining helps on most Android devices, but on this particular BYD runtime the theme attribute reference **still** does not resolve when set via `android:tint` on an ImageView → icons remain invisible (confirmed on car).

## Final fix (this release)

Replace the theme-attribute reference with a **direct color resource reference**, which the runtime resolves through the normal resource-qualifier system (no theme indirection):

- `android:tint="?attr/colorOnSurfaceVariant"` → `android:tint="@color/md_on_surface_variant"` (8 occurrences in `activity_settings.xml`)
- `android:tint="?attr/colorOnPrimaryContainer"` → `android:tint="@color/md_on_primary_container"` (Welcome hero `ic_cast`)

DayNight is still honored, because `md_on_surface_variant` and `md_on_primary_container` are defined in both `values/colors.xml` (light, `#43474E` / `#001B3F`) and `values-night/colors.xml` (dark, `#C3C6CF` / `#D5E3FF`). The device picks the night variant automatically since night mode is active.

This is the same DayNight behaviour as the theme attribute path, just without the broken indirection.

## What did not change

- No business logic, no API change, no new permissions, no widget IDs renamed.
- All locales, layouts, dialogs, OTA, overscan, behavior toggles unchanged.

## Version

- `versionCode`: **124 → 125**
- `versionName`: **0.9.4 → 0.9.5**

## Test plan

Just open **Settings** and check that the leading icons are visible:

- **Marges d'affichage (Overscan)**: `−` / `+` icons on each slider, leading icon on the visual-mode row.
- **Comportement**: `restart_alt`, `autorenew`, `filter_list`.
- **À propos**: `info_outline`, `system_update`, `code`, `science`, plus the trailing `open_in_new` chevron on the Code source row.

If they're visible, we're good to proceed to the next M3 phase.
