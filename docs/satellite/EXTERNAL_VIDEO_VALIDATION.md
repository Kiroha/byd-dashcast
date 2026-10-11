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
```

Test static scenes longer than a minute, changing frames, source Stop/revoke/lock, viewer closure,
cluster projection loss, Wi-Fi loss, fresh consent sessions and guidance alongside video. Physical
video/performance acceptance remains pending; software/emulator tests must not be reported as an
on-vehicle result. The user separately confirmed official Google Maps guidance on October 11, 2026.
