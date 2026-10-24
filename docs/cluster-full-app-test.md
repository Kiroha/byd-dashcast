# Full-screen application experiment

The manual Diag profile `full-app-21f7735c` gives the application the complete
1920 × 720 cluster surface. It hides the native header (including gear), speed,
power, warning lamps (including OK and speed-limit symbols), battery/range footer
and odometer. These instruments and alerts are intentionally absent during this
parked demonstration. The previous dashboard profile and factory assets are kept.

The supplied Portuguese reference confirmed conditional opacity rather than
changing visibility, and selecting factory resources before closing projection.
The implementation uses its own closed RCC; it does not run the supplied scripts,
poll external JSON, move ADAS off-screen, replace vehicle data or modify OEM files.
The unchanged translation payload retains all 84 factory locale resources.

After the application has launched, the existing `ProjectionAdasPlan` performs
ADAS masking through the pinned protocol44 diagnostic endpoint. The endpoint
accepts only its compiled initialization, commands13/12/53 and existing frames.
Hide authority is bound to the current RCC nonce, opening round, diagnostic lease,
resumed application and current native power observations. Closing or Restore
retires hide authority, then delivers the existing complete ADAS restore before
native projection close commands. A delivery receipt does not establish pixels.
Failed or interrupted recovery retains durable debt and blocks a clean session.

In Diag, read compatibility, prepare **Full-screen application**, choose Waze and
open. This mode never starts automatically. An active, uniquely identified native
1920 × 720 display and the exact observed DiLink3/API29 artifact cohort are required.
The loader/geometry guard leaves the original QML presentation outside FULL mode
or when the owned component fails. Hardware validation is still required.

Restore selects the factory profile before closing native projection, then makes
a fresh query. `source=1` means the native Qt path property is empty; it does not
prove resource registration or rendered pixels. A `default/default` configuration
with `source=0` remains pending. It does not authorize another load or a clean state.
For a pending selection with native projection already closed, the separate
**Apply factory selection** button performs one explicit16→35→18→0 transition
(31 only when size admission was previously established), then queries again.
It never repeats the factory selection request or retries automatically. Confirm
the restored factory display before the owned carrier can be retired or removed.
Restore and verify before installing an older APK.

The carrier is reproducible with `tools/cluster/build_full_app.py`. Host checks use
`test_full_app.py` and `validate_full_app_qt5.py`: only owned QML and simulated
primitive fixtures execute; no OEM root, plugin or vehicle command is executed.
