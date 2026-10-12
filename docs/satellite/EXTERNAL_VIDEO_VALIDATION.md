# External video receiver checks

The companion provides user-consented app/display capture and native hardware encoding; the
vehicle receives a single WebRTC track using its installed WebView. It still decodes and composes
the video. Video is experimental until Tbox Ultra 1 / Android 15 and the actual DiLink 3 / Android 10
vehicle pass the companion's external-video checklist and performance comparison.

The bundled receiver keeps one asynchronous stats request in flight. Its independent six-second
watchdog clears a stalled image even when `getStats()` never settles. Only decoded-frame counters
(or actual HTML video decoded-frame counters on older WebViews) establish progress; incoming bytes
and WSS heartbeats never refresh it. Offer/session reset fences old SDP and stats callbacks.

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

Set up while parked. Start the DashSat control connection, make the existing DashCast cluster
projection ready, and choose **Show on cluster**. Then switch the main display to CarPlay/Tbox,
select the source application in DashSat, and choose **Start video sharing**. Grant Android's
capture consent and choose the same application. The native video viewer must remain visible
on the cluster throughout this sequence.

**Preview received map** is a main-display test viewer. Switching that same display to CarPlay
hides it and intentionally closes its WebRTC connection. DashSat then disables Start because
the viewer is no longer available. Test preview using a separate accessible source-device screen,
or use the cluster workflow above. Opening ABRP alone does not grant capture permission or
start video transmission; guidance and video remain separate.

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

Test static scenes longer than a minute, changing frames, source Stop/revoke/lock, viewer closure,
cluster projection loss, Wi-Fi loss, fresh consent sessions and guidance alongside video. Physical
video/performance acceptance remains pending; software/emulator tests must not be reported as an
on-vehicle result. The user separately confirmed official Google Maps guidance on October 11, 2026.
