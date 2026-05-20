# DashCast v0.9.2-alpha — Welcome Screen: Mockup-Accurate Redesign

**Pre-release for in-car testing.** Hotfix on top of **v0.9.1-alpha** to bring the **Welcome screen** in line with the Material 3 mockup defined in `mockups/mockup_m3.html` (screen #1).

The previous v0.9.1 build technically migrated the Welcome screen to M3 widgets, but visually it diverged from the mockup (filled-tonal buttons everywhere, no hero logo, no version subtitle, wrong card tone). This release rebuilds it from the mockup specification verbatim.

> Constraints respected: API 29 max runtime (`targetSdkVersion = 29`), no business logic changed, every existing widget id preserved.

---

## What changed

### Hero logo

- New 96 dp tonal square (`MaterialCardView`, `?attr/colorPrimaryContainer`, 24 dp corner radius, 0 elevation).
- New vector drawable `drawable/ic_cast.xml` — Material Symbols `cast_connected`, tinted `?attr/colorOnPrimaryContainer`, rendered at 56 dp inside the tonal square.

### Typography

- Title "DashCast" — 44 sp, regular weight, `?attr/colorOnSurface`.
- Version subtitle — 14 sp, `?attr/colorPrimary`, 0.18 letter-spacing, text set dynamically in `WelcomeActivity.onCreate` as:
  ```
  BYD CLUSTER MIRROR · v<versionName>
  ```
  (`versionName` is read from `BuildConfig.VERSION_NAME`, so the subtitle stays in sync with every future release).
- Prompt — 16 sp, `?attr/colorOnSurfaceVariant`, centered.

### Card

- Background switched from `?attr/colorSurfaceVariant` to `@color/md_surface_container` (matches the mockup tone exactly).
- 28 dp corner radius.
- Asymmetric padding 56 dp (top/bottom) × 80 dp (left/right), per mockup.
- Elevation 0 (the surface tone is what carries the depth — true M3 styling).

### Language buttons

- All 12 buttons converted from `Widget.DashCast.Button.Tonal` to `Widget.DashCast.Button.Outlined` (pill-shaped, 28 dp corners, 56 dp tall) so they take the discreet, single-emphasis look from the mockup.
- 4 × 3 grid with 14 dp gap (was 8 dp).
- All 12 button ids preserved: `btn_lang_fr`, `btn_lang_en`, `btn_lang_de`, `btn_lang_it`, `btn_lang_tr`, `btn_lang_es`, `btn_lang_ru`, `btn_lang_uk`, `btn_lang_ar`, `btn_lang_uz`, `btn_lang_kk`, `btn_lang_be`.

### Java change

- `WelcomeActivity.java`: added a single block to bind `tv_welcome_subtitle` and set its text to `"BYD CLUSTER MIRROR · v" + BuildConfig.VERSION_NAME`.
- Nothing else touched — same setup-done check, same `setLanguageButton` wiring, same `MainActivity` redirect.

## Compatibility

- `compileSdkVersion 33`, `minSdkVersion 29`, `targetSdkVersion 29` unchanged.
- No new permissions, no new dependencies.
- Settings screen, Main, Diag, SysInfo, Log are **identical to v0.9.1**.

## Testing focus

1. Clear app data (or fresh install) → verify the new Welcome screen renders:
   - Centered card on the dark surface.
   - Tonal blue square with cast icon at the top.
   - "DashCast" title visible and large.
   - Version subtitle reads `BYD CLUSTER MIRROR · v0.9.2`.
   - All 12 outlined pill buttons visible in 4 rows × 3 columns.
2. Tap each language and verify:
   - The chosen locale is applied immediately.
   - `MainActivity` opens and stays on the picked language.
   - Welcome no longer appears on subsequent launches (setup-done flag is persisted).
3. Confirm Settings screen still works exactly as in v0.9.1.

## Build metadata

- `versionCode 121`, `versionName "0.9.2"`.
- APK: `DashCast-v0.9.2-debug.apk`.
- Branch: `alpha/0.9`.

## Roadmap

- After in-car validation of this Welcome redesign, **Phase 4** continues with `activity_main.xml` and `activity_diag.xml`, and the Settings screen will be re-checked against the mockup with the same mockup-fidelity discipline applied here.
