# External video receiver checks

The companion provides user-consented app/display capture and native hardware encoding; the
vehicle receives a single WebRTC track using its installed WebView. It still decodes and composes
the video. Video is experimental until Tbox Ultra 1 / Android 15 and the actual DiLink 3 / Android 10
vehicle pass the companion's external-video checklist and performance comparison.

The bundled receiver samples HTML video counters once per second independently of `getStats()`.
Only one underlying stats operation may remain unresolved per peer. At eight seconds it is marked
timed out in diagnostics, without starting another operation or stopping a healthy decoder.
A failed stats operation is diagnostic evidence; it does not stop progressing DOM counters.
The first image has fifteen seconds after the answer is queued, with a hard thirty-second startup
cap from the offer and the existing eight-second limit for each SDP operation. These budgets are
not renewed by track/connection/ICE/byte events. After actual decoded-frame progress, the existing
six-second stale-image limit applies. Incoming bytes and WSS heartbeats never refresh an image.

DOM quality and WebKit counters have separate baselines captured at each new peer/track. Newly
exposed APIs, decreased raw quality/decoded counters and replacement stats sources rebaseline
without proving progress. A fresh peer's positive stats decode count is valid evidence. Replacement
offers preempt pending SDP, cancel its local deadlines and fence its late failures/completions.
Closing/hiding the viewer releases the peer and all monitoring; no offscreen decoder is retained.

Diagnostics are opt-in: long-press **Preview received map** on the main display to toggle a small
codec/dimensions/FPS/bitrate/drop/decode-time overlay, then open the cluster viewer. Hardware decoder
efficiency is unknown unless the platform reports `powerEfficientDecoder`. This field indicates
power-efficient platform decoding, not a direct GPU load measurement. No SDP, route, addresses or
arbitrary diagnostic strings are displayed or logged.

The cluster viewer continuously checks that its original resolved display is still the activity's
display and that the existing ClusterService projection remains active. Loss closes only this viewer
and its decoder. Main-display preview is independent. No new projection activation, daemon ownership,
OEM restore, CAN/AAOS action, driver restriction bypass or input forwarding is introduced.

## Setup when the Tbox uses the car's main display

Set up while parked. Start the DashSat control connection, select the source application in
DashSat, and choose **Start video sharing**. Grant Android's capture consent and choose the same
application. A visible DashCast viewer is not required to authorize that continuous sharing
session. Leave CarPlay, return to DashCast and open **Preview received map**. Video negotiation
starts when the viewer is ready. **Show on cluster** remains available after the existing cluster
projection is ready; it does not activate a new OEM projection.

Switching the same main display back to CarPlay hides the preview and intentionally releases
its WebRTC connection and decoder. DashSat suspends sending and retains the same valid authorized
capture session, so **Screen sharing active · waiting for DashCast** is expected after returning
to CarPlay. Reopen the viewer to negotiate a new peer within that session. Stop, revocation, lock,
source capture pause/hiding, control loss or fatal failure ends sharing and requires new consent;
there is no unattended recapture. Opening ABRP alone does not grant capture permission or start
video transmission. Guidance and video remain separate.

## Retained video stages and readiness ordering

DashSat 0.9.1-dev and DashCast 1.9.22-beta add bounded process-local stage histories that survive
display switching. In DashSat, read **Recent video activity** or choose **Copy video diagnostics**
after returning to CarPlay. The newest stage appears first, local session numbers separate sharing
attempts, and the copy includes up to 32 retained entries with saturating scalar totals. Receiver
bug reports include a separate **Satellite video** history with readiness, received offers,
queued answers, track/media connection and actual decoded-frame progress.

DashCast 1.9.29-beta also retains the last scalar observation for up to eight receiver attempts
in process memory. Each one-second snapshot records nullable inbound bytes/packets, received,
decoded, key and dropped frames, quality/WebKit counters, a codec whitelist, stats state/ages
and track/connection/decode flags. Missing/invalid values print `?`, never a fabricated zero.
The attempt number is local, unrelated to wire/session/track identities. Native code bounds
fields and types, coalesces pending snapshots to one main-thread delivery and rejects obsolete
WebView/peer attempts. Final snapshots survive stop/CarPlay transitions as historical evidence.
Compare sender-encoded frames with receiver bytes/packets and decoded counters; a timed-out stats
operation leaves RTP reception unknown even when DOM decoding is established. Decoded counters
do not establish physical display, hardware decode or reduced vehicle CPU/GPU use.

These fixed labels, elapsed ages and counts never include payloads, tokens, SDP, routes,
notifications, candidate/device/network identities or arbitrary errors. ICE uses counters only.
Queued receiver replies do not establish transmission, sender encoded frames do not establish
receiver decoding, and prior decoded frames do not establish a currently visible image.

The receiver queues an unavailable-viewer rejection while holding the same hub monitor used
by viewer attachment. A later attachment therefore queues `video.ready` after that rejection.
DashSat also ignores an unscoped legacy `viewer_unavailable` for readiness, retaining it only as
diagnostic evidence. Explicit ready/closed events and the existing negotiation deadlines remain
authoritative. This fixes a real ordering race without claiming it explains every reported
blank map; no wire contract, pinned identity or capture-consent policy changes.

## Viewer bootstrap and old WebView compatibility

The native viewer displays a video-specific preparation screen before JavaScript is ready.
It advertises `video.ready` only after the bundled script confirms WebRTC support. A missing
callback fails visibly after ten seconds in the foreground; background time does not exhaust
that deadline. Script/provider/renderer failure releases the unusable WebView and shows a
manual retry, Close, and the installed WebView version. Retry creates a new generation so old
bridge callbacks and deadlines cannot revive a failed viewer. Logs contain fixed stage codes
and a numeric provider version, never arbitrary console text, URLs, SDP or notification data.

On October 12, 2026, the exact published 1.9.19-beta HTML reproduced two failures in stock
Android 29 WebView **74.0.3729.185**: nullish-coalescing syntax prevented the entire receiver
script from parsing, and unsupported CSS `inset` placed the waiting text below the viewport.
The installed minified APK's actual viewer stayed open; an ordinary authenticated WSS client
received `welcome` but no `video.ready`. This is an emulator reproduction, not identification
of the vehicle's installed WebView version. The correction uses older supported JavaScript
and explicit `top/right/bottom/left` positioning.

Vehicle comparisons require the same route/scenario, brightness and Overdrive recording settings:
DashCast idle, Maps rendered locally in the vehicle, and Maps running externally with received video.
Measure ten minutes, then thirty minutes of sustained CPU/GPU, heat, screen-to-cluster latency and
Overdrive frame loss. Confirm actual decoder behavior on the installed WebView; hardware codecs used
by other vehicle apps do not establish WebView codec selection. Reduce resolution/FPS if needed and
keep the feature experimental until external video improves vehicle workload without regressions.

Local checks:

```
node --test tools/satellite/test_receiver.mjs
./gradlew :app:testDebugUnitTest --tests 'com.byd.dashcast.satellite.*' :app:lintRelease
JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/sdk \
  python3 .github/scripts/check_satellite_webview.py \
  --serial emulator-5580 --apk path/to/the-signed-release.apk
```

The WebView probe refuses physical devices and non-API-29 emulators. It extracts the exact
bundled HTML from the supplied APK, builds a temporary independent test APK, and uses the
installed real system WebView with matching bridge/network blocking. It verifies script
bootstrap and actual waiting-text geometry, then removes only its own test package. It does
not modify DashCast preferences or prove the optimized native bridge, WSS, capture, decoding
or vehicle output. Use `--expect-broken` with the archived 1.9.19-beta APK to reproduce both
original failures. Separately verify the actual optimized viewer's `video.ready`/`video.closed`
with a synthetic local control client, including background/resume and reconnect.

The corrected optimized viewer passed that separate stock-API-29 check: the client compared the
peer certificate hash with the certificate read locally from the receiver's AndroidKeyStore
before sending `hello`, then observed `welcome`, `video.ready`, background `video.closed`, resume
`video.ready`, and a fresh reconnect. Disabling only the emulator's WebView provider produced a
readable native error/retry screen; restoring it and pressing Try again recovered readiness.
The software GPU fixture reported a Chromium font-raster error and did not paint HTML glyphs
reliably in screenshots. Real DOM geometry and control readiness are established; rendered map
pixels, hardware decoding and vehicle performance remain unverified by this bootstrap check.

Test static scenes longer than a minute, changing frames, source Stop/revoke/lock, viewer closure,
cluster projection loss, Wi-Fi loss, fresh consent sessions and guidance alongside video. Physical
video/performance acceptance remains pending; software/emulator tests must not be reported as an
on-vehicle result. The user separately confirmed official Google Maps guidance on October 11, 2026.

## Manual companion diagnostics without box internet

After a video attempt, switch back to DashSat and select **Send diagnostics to DashCast** while
control remains connected. The hidden receiver service accepts the bounded snapshot; a visible
viewer is not required. Wait for received confirmation, return to DashCast and send its usual bug
report. The `DASHSAT VIDEO` section must identify sender-reported manual historical evidence and
its receipt/capture ages. No Telegram/WhatsApp connection from the box is needed. The snapshot
contains no SDP, tokens, network/peer identity, notifications, route text or arbitrary messages.

The cache holds one immutable snapshot in RAM for up to 24 hours after receipt. Ordinary disconnect
retains it; disable, revoke, restart and different/unknown-peer authentication clear it. The sender's
last ACK is historical, not proof the current receiver cache/image is present. Test malformed and
16–64 KiB snapshots: reject only diagnostics, preserving ping/guidance. The existing global message
limit still applies above 64 KiB. Test unsupported receivers, late/wrong-generation ACKs and LAN
loss without automatic replay. JVM/TLS fixtures establish these boundaries, not real vehicle video.

## Watchdog regression and vehicle retest

The pre-fix implementation returned early while a stats promise was pending, suppressing DOM
fallback sampling; its six-second watchdog could stop before the stats request's eight-second
deadline. Deterministic tests now cover a permanently pending stats operation with progressing
DOM frames for more than twenty seconds, failed/missing stats, no image at startup, six-second
stalls after a first frame, raw counter resets, replacement sources and late SDP/stats callbacks.
Native tests cover strict scalar types/privacy, bounded retention/coalescing and obsolete peer,
WebView, hidden-viewer and stop generations. These establish the software defect and correction,
not whether it caused the reported Tbox black screen.

On the parked vehicle, keep DashSat 0.9.2-dev installed, authorize the selected app, return to
DashCast and leave **Preview received map** visible for at least fifteen seconds. Switch back to
DashSat, send its diagnostics to DashCast and wait for the received confirmation; then return
and submit the normal DashCast bug report. The receiver history must now preserve the relevant
attempt's nullable RTP/decode observations through those foreground switches. A black image still
requires evidence to distinguish missing RTP reception, decoding failure and unavailable counters.
