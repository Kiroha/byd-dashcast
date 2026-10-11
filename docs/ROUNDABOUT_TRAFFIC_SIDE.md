# Roundabout traffic side

DashCast Settings → Navigation guidance now includes three choices:

- **Direction supplied by navigation**: default for existing installations; retains the source icon and every existing output mapping unchanged. This does not detect the country, traffic side or vehicle steering-wheel position.
- **Right-hand traffic (France)**: counter-clockwise roundabout circulation.
- **Left-hand traffic (United Kingdom)**: clockwise roundabout circulation.

Traffic side and steering-wheel position are independent. Configure the setting while parked. The choice is saved immediately and takes effect on the next fresh guidance update, for local navigation and Satellite alike. Changing it never replays a stored route or renews its freshness timer.

## Rendering contract

`RoundaboutDirectionPolicy` is applied in `HudController.updateNavigation` only after the existing validity/source/destination gates and before output delivery. It creates a separate immutable rendering snapshot; parsers and the Satellite wire protocol retain their original values.

The numbered CAN families 25–34 (CCW) and 35–44 (CW) change by ±10, preserving exit ordinals 1–10. The audited generic pairs 15/16 (left geometry), 18/17 (right geometry), 20/19 (straight), and 22/21 (U-turn) retain their geometry when a traffic side is forced. Generic icons retain exit ordinal zero.

CAN names containing LEFT/RIGHT do not reliably identify circulation. The existing `ClusterNavPusher.toAmapIcon(Int)` mapping intentionally remains unchanged in source mode, including legacy IDs 16/19. The rendering copy carries an optional direction override used by the native NaviInfo and AMap broadcast mapping, so an already matching generic CAN icon still receives the explicitly selected output direction. That field never becomes a Satellite protocol field.

IDs 23/24 have no confirmed circulation pair and are not emitted by the current parsers; they remain untouched. Other turns, arrivals, unknown IDs, distance values, route time, ETA and optional values remain unchanged. Source/session revisions, authentication, input selection, expiry, watchdog and stop semantics are unchanged. The preference observer invalidates notification identities and cancels queued frames without rescanning old notifications.

## Evidence and validation limits

The pair table follows `docs/openbyd-2.5/OPENBYD_2_5_HUD_INTEROP.md:308–314`. Native cluster direction and ordinal mappings are already recorded in `ClusterNavPusher`. The CAN and native/AMap namespaces are distinct, and static/JVM tests do not prove identical physical HUD behavior on every firmware.

Available earlier OEM evidence concerns a SEAL on DiLink 3 / Android 10 / API 29, SX361 firmware `6125f_1for2_USER_SIGN_SX361_202606100404_Q2700`, without a physical HUD. The new traffic-side choice still requires visual validation on the target vehicle, recording model, ROM/firmware, enabled destinations and chosen setting. No new OEM transport, permission or vehicle-control command is introduced.

## Checks

Targeted JVM tests cover default and persisted preferences, unknown-value fallback, observer unregistration, settings selection/restoration, all numbered exits in both directions, generic geometry and ordinal zero, unaffected IDs and raw snapshots, local/Satellite routing, CAN/native/AMap output direction, identical fresh notifications after preference changes and absence of replay/freshness renewal. Independent verification completed successfully:

- 52 focused JVM tests passed.
- Full JVM suite: 998 tests in 187 suites, with zero failures, errors or skips.
- `lintRelease`: zero reported issues.
- Debug and release builds, including release R8, passed. Release checks excluded `:app:copyMappingRelease` to preserve the published mapping archive.
- Independent checks confirmed that the frozen 1.9.14-beta APK, mapping, changelog and checksum manifest remain consistent, and its build-653 mapping archive is unchanged. The frozen 1.9.13-beta artifacts are also unchanged.
- The scanner pinned to `origin/main` accepted the non-debuggable release APK with the usual DashCast signing certificate.
- All five new strings are present in each of the 13 existing language files; XML parsing and `git diff --check` passed.
- `graphify update .` completed; its six existing parse warnings remain unrelated to this change.

The feature commit retained the 1.9.14-beta / versionCode 653 baseline. Those historical feature checks and published artifacts remain unchanged. The publication candidate is now **1.9.15-beta / versionCode 654**, documented in `docs/releases/1.9.15-beta.md`. Independent final-version verification passed 998 JVM tests in 187 suites, release lint with zero issues, debug and signed release assembly including the new build-654 mapping archive, 10 JavaScript receiver scenarios and 35 scanner tests. Its frozen APK and R8 mapping match that build byte for byte, and the trusted default-branch scanner accepts its identity, non-debuggable configuration and usual certificate. The candidate has not yet been published. These checks do not replace validation in the vehicle.

Parked vehicle validation should compare both forced circulation directions and source mode using numbered and generic roundabouts, verify the exit number and distance, then check route stop/expiry and ordinary left/right turns. Settings labels are supplied in all 13 existing resource languages.
