# Cluster slot Surface lifecycle

Layout slots belong to `SurfaceDaemon`, the persistent shell daemon. `ProxyDaemon`
remains the command executor. Each slot retains its cluster overlay and its own
VirtualDisplay; this change does not alter OEM calls, display selection, geometry,
creation flags, Binder transaction layouts, or process owner tokens.

Previously the holder callbacks only satisfied an initial latch. A destroyed holder
left the VirtualDisplay targeting the old Surface, a recreated holder was never
bound to it, and `QUERY_SLOT` still returned the display ID merely because the
VirtualDisplay object existed. This could leave a black or stale slot reported as
available. This source defect is independent of OEM pixel production and does not
explain every black-screen report.

`SlotSurfaceLifecycle` now tracks the current holder generation, its availability,
the bound generation, display identity and final closure. Holder callbacks, display
creation, `setSurface`, resize, display disposal and overlay removal share the
existing main-looper lane. Binder/death-recipient threads can invalidate a slot
immediately and queue disposal there. State monitors cover only short in-memory
updates; they never contain a framework call, a dispatcher or a latch wait.

- Initial callbacks are accepted before a slot is published. Creation takes the
  newest valid holder and reconciles its generation before publishing the display.
- `surfaceDestroyed` invalidates readiness first, then detaches the holder with
  `VirtualDisplay.setSurface(null)`. A valid recreation binds the same display and
  retains its ID; no replacement display, retry loop or extra thread is created.
- A failed bind/detach closes the slot, releases its VirtualDisplay and removes its
  overlay. Subsequent callbacks are inert. The holder owns its Surface throughout;
  slot teardown never calls `Surface.release()`.
- Pending requests have an identity separate from committed slots. Explicit
  release, layout deactivation, replacement and creation timeout also cancel a
  pending request. A late local display is disposed instead of being published.
  An old callback or owner-death recipient cannot remove a newer request.
- Resize and rollback share the same lifecycle lane. A rollback restores both
  VirtualDisplay and overlay dimensions; a failed rollback closes the slot.
  Resize generations prevent a late rollback from overriding a newer request.

`QUERY_SLOT` keeps its existing integer wire format. It returns a positive ID only
for a current, valid holder bound to a valid display with that ID. It returns `-1`
while detached, invalid, closing or absent. This is a configured-resource check at
the time of the call. It establishes neither fresh pixels, successful app drawing,
OEM visibility, movement state, nor hardware compatibility.

The native `AutoContainerNative` getter is not used: the studied implementation
admits numeric caller UIDs at most 1000, which excludes the shell daemon UID 2000.
No native library, OEM permission, vehicle restriction or transport is modified.

Validation includes controlled JVM tests for callback/create/release races,
recreation, failure and owner isolation, and API-29 Robolectric tests that invoke
the actual daemon holder callbacks, registry, query transaction and resize handler.
These checks do not validate BYD compositor behavior. A parked hardware check must
confirm initial drawing, holder loss/recreation, stable display ID, resize and
teardown while preserving OEM alerts and controls. Logs distinguish `holder
generation=… bound (resource only)` from `detached/unavailable`; neither marker
claims a received or displayed frame.
