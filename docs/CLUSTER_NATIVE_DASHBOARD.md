# Native DashCast dashboard diagnostic

This is a manual experimental dashboard for the installed `21f7735c…`
libBydCluster / `02dfc7f5…` libBydDataSource cohort, Android 10 / positively
identified DiLink 3. It is not enabled at boot and does not resume a projection
after a process restart. Validation on the car remains outstanding.

Open **Diag → DashCast dashboard**. The main flow reads the current connected
daemon, prepares the dashboard, selects an application, opens it, and records
the user's visual observation. Every write requires an explicit parked and
firmware assertion; the checkbox is not a movement sensor. Preparation and
opening are separated from projection-display discovery. Preparation requires
the pinned API 29 / positive DiLink3 / exact installed cohort and factory selection.
If no projection display exists, it first opens the original BYD projection with
`16 → 3 s → 35`, without changing its screen size or selecting the custom RCC.
Only a freshly observed, unique OEM-owned ON 1920 × 720 projection display admits
custom selection and the later size-forcing command. Unknown/ambiguous geometry,
private or unconfirmed owners and the existing small-panel latch refuse it. The fixed factory Restore button remains outside the scroll
area. Earlier native/RCC diagnostics remain under folded technical details.

## Rendering

The new translation carrier preserves all 42 `.qm` and 42 `.ts` resources,
including their compressed bytes and metadata. It shadows the exact theme2
root and warning layout; the included theme1 root is byte-identical to stock.
There is no Android or WebView vehicle-data feed. Native DataSource bindings,
invalid-value behavior, units, gear, temperature, SOC/ranges, odometer, ADAS,
warning priorities and flash behavior remain in the factory items.

The owned component supplies decoration and a transparent application rectangle
at `(464, 256)`, size `752 × 320`. Native speed and power occupy the left card;
native ADAS remains at the right and native range information at the bottom.
The warning source contains 80 textual declarations, two commented out: all
78 live lamps remain, including three traffic lamps inside their original
container. Direct lamps and that container wrap without reparenting; translation
transforms compensate native coordinates without replacing their bindings.
Unknown states reserve space and blinking does not change layout occupancy.
The warning strip is dark in day and night modes because some OEM artwork and
the Korean seat layout are permanently white.

The owned decoration is used only with `naviType == 4`, `screenSize == 2`, an
actual 1920 × 720 root, a successfully loaded component, fitting instruments
and complete fitting warning geometry. A missing component, unknown geometry
or overflow leaves the factory scene. Outside FULL, the inserted transforms
are identities and native decoration remains. No native lifecycle function or
instrument binding is removed. The carrier builder verifies exact original
hashes and its tests can remove the marked insertions to recover the exact
factory source hashes.

## Ownership, sequencing and recovery

ProxyDaemon protocol 43 adds the closed factory-bootstrap and current-display read; callers cannot choose
arbitrary native commands or asset hashes. The existing owned RCC journal and
nonce protect selection and recovery. After factory bootstrap and observed geometry,
opening uses `31 → 16 → 35`; closing uses
`18 → 0 → 31`. A monotonic 1200 ms interval applies between all native attempts,
including the boundary between separate open, close and Restore requests.
Each helper keeps its short deadline and records its identity before GO.

The dedicated display reader runs only a fixed `dumpsys -t 1 display` read as
shell on API 29, with bounded capture and process cleanup. It reuses the existing
DMS parser and display model. Logical-display counts, IDs and complete base
records must agree; truncation, dump errors or timeout markers remain unavailable
even when dumpsys exits zero. The target must be the single named xdja/fission
display, owned by `com.xdja.containerservice` / UID 1000, non-private and ON.
The central screen and inferred IDs/sizes never serve as projection evidence.
Read-only discovery uses at most six completed probes in a 15-second window,
with at least one second after each completed probe. Cancel/Restore retires future
probes and stops its owned reader; an unreaped reader prevents another read but
never disables the independent native/RCC Restore lane. Normal status polling
reads cached state only. The reader itself makes no AutoContainer call.

Bootstrap native debt is durable before GO, even while `mutated` remains false
because no custom RCC has been selected. Bootstrap-only Restore uses `18 → 0`,
then a fresh factory query and a human observation before CLEAN; it never sends 31
or invents a custom-load recovery acknowledgment. The journal keeps this proof
separate from `iviRecoverySettled`, and reads old 72 fields conservatively without
inventing size admission. A fresh geometry check remains immediately before 31
and before the application slot. A lost or changed display stops later writes.
If a previously admitted full-profile close loses its display before its last 31,
that 31 is refused, native debt remains and factory RCC recovery still proceeds;
software does not claim a completed physical recovery.

A conservative 15.5 s host compatibility interval precedes RCC selection
changes. This is outside the helper deadline. The native theme-path handler's
15-second throttle does not prove the translation selector shares it. Query,
configuration/ACK4, registration/ACK2 and physical observation remain distinct.
An exact owned selection can permit opening before ACK2, since the projection
transition may itself trigger QML registration. A fresh query is required and
preparation must have actually finished without uncertainty.

The application zone uses SurfaceDaemon-owned Fission resources. Its launch is
one transaction on the captured ProxyDaemon Binder, with no retry or reconnect.
The opening ticket binds activation, nonce, diagnostic reservation and endpoint.
Pause, close and Restore revoke it; the worker checks it again around display
discovery, slot query, resize/attach and launch. The dashboard path sends no
ordinary Fission activation/teardown sequence and invokes no automatic ADAS
masking callback. Native ADAS stays available.

This manual experiment has no automatic session expiry; it lasts until the user closes or restores it. Only individual helpers, requests and opening tickets are bounded. Close retains the selected RCC and the diagnostic reservation. Ordinary
projection remains blocked until factory recovery. Restore immediately revokes
opening, submits remote recovery through its independent lane, and starts local
slot teardown in parallel. It never waits for a blocked local VD transaction
before requesting remote recovery. Unacknowledged slot releases retain their
ownership debt and prevent releasing the local reservation.

Native close and RCC helpers have separate durable identities. If close fails,
its native debt stays open while verified factory RCC recovery continues. A
factory selection reply alone cannot produce CLEAN while native debt or an
owned native helper remains. Restore can be repeated manually to resolve the
remaining native close; no automatic replay occurs. A fresh factory query and
the user's observation are still required before deleting only the owned
carrier and closing recovery. A recovered journal never opens the dashboard.

**Rollback to an older APK is not supported or validated for this profile.**
Restore and visually verify the factory profile before any APK replacement, but
this does not establish old-APK compatibility: the retained CLEAN journal still
uses a variant older versions cannot recognize. Do not use the build-711 recovery
ZIP as a compatible rollback or delete the journal to bypass the guard. A
compatible forward recovery build would be required for replacement after this
experiment. Existing historical carriers are retained byte-for-byte.

## Host checks and limits

`tools/cluster/build_full_dashboard.py` deterministically builds the carrier from
the exact factory translation/root sources. `test_full_dashboard.py` verifies
source invariance, the complete native lamp inventory and locale preservation.
`validate_full_dashboard_qt5.py` runs owned decoration/layout and self-owned
lamp mocks under isolated Qt 5.15.14. It syntax-parses the complete QML sources
without instantiating them. Its additional Item/Text instrument fixture uses
simulated DataSource values and units, with no factory lifecycle handlers or
BYD plugins. It checks real text bounds, invalid values, oversized values,
day/night contrast, the transparent application zone, original coordinates
outside FULL, imperative coordinate updates and warning overflow fallback.

These checks establish host syntax and the tested composition properties.
They do not establish the vehicle renderer's behavior, effective permissions,
live telemetry freshness, native debounce delivery, physical pixels or recovery
after a vehicle power cycle. The note supplied by the user is a design lead;
the installed artifact cohort and actual receipts remain authoritative. No
vehicle commands were executed during development.
