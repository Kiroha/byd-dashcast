# DashCast satellite protocol v1

Status: receiver implemented, experimental; no companion Android APK is delivered yet. Vehicle
rendering, AndroidKeyStore TLS handshakes and WebView codec performance still need device testing.
The receiver is disabled by default. This is a local protocol with no cloud relay or discovery.

## Pairing and transport

Open Settings → Satellite device → Pair a device. Import the resulting JSON in the companion:

```json
{
  "version": 1,
  "hosts": ["192.168.43.1"],
  "port": 47832,
  "path": "/satellite/v1",
  "certificateSha256": "<64 lowercase hex characters>",
  "token": "<installation-local 256-bit secret, base64url without padding>"
}
```

Choose the reachable LAN host and connect to `wss://HOST:47832/satellite/v1`. IPv6 hosts need
brackets. Pin the SHA-256 of the **DER certificate**, not SPKI, before sending the token. The
self-signed certificate identifies this installation: a generic trust-all TLS client is not an
acceptable implementation. Keep the token in AndroidKeyStore-backed storage; do not put it in URLs,
logs, analytics, screenshots or bug reports. Revocation rotates the token and closes existing
connections. Reinstallation changes the identity. Pairing is intentionally excluded from backup.

Use a persistent connection with TCP_NODELAY. Bind the companion connection to the local network
when its SIM supplies internet; do not change the default network for Maps/ABRP. Only loopback,
private/link-local IPv4 and private/link-local IPv6 peers are accepted. There is one authenticated
sender, up to two pending handshakes, a five-second authentication deadline, a 64 KiB UTF-8 message
limit, a 100-message/second control limit and bounded signalling output. Binary messages are refused.

First message:

```json
{"type":"hello","version":1,"token":"<paired token>"}
```

Reply:

```json
{"type":"welcome","version":1,"session":"<opaque id>","navigationTimeoutMs":6000,"remoteGuidance":false,"videoTransport":"webrtc"}
```

A busy receiver refuses another sender. Reconnect with backoff and a new WebSocket after failure.
Version mismatch is refused. Unknown message types produce an error; unknown fields are ignored.
Control heartbeat: `{"type":"ping"}` → `{"type":"pong"}`. Pings do **not** keep guidance alive.

## Guidance

The user must explicitly select **Use satellite guidance**. Existing HUD/cluster destination
switches still apply, as do existing platform gates. Local navigation is selected otherwise,
including when the receiver is disabled. Video does not change guidance source selection.

```json
{
  "type":"navigation.update",
  "seq":1,
  "ageMs":0,
  "maneuver":"right",
  "distanceMeters":200,
  "roadName":"Example road",
  "remainingDistanceMeters":2000,
  "remainingTimeSeconds":300,
  "etaHour":14,
  "etaMinute":30
}
```

Required fields: `type`, `seq`, `ageMs`, `maneuver`, `distanceMeters`.

* `seq`: strictly increasing nonnegative signed 64-bit integer for this WebSocket, across updates
  and stop messages. Duplicates cannot refresh liveness. Start over only on a new connection.
* `ageMs`: age of the source observation, 0–1500 ms. Preserve source age through queues. Sending an
  old cached observation with a new sequence and age zero is incorrect.
* `maneuver`: `left`, `right`, `slight_left`, `slight_right`, `sharp_left`, `sharp_right`, `uturn_left`,
  `uturn_right`, `straight`, `destination`, `roundabout_cw`, `roundabout_ccw`. The last two require
  integer `exit` 1–10. Directions mean circulation direction; the receiver maps portable values to
  its existing OEM codes. Never send CAN/SendInfo2 icon numbers.
* `distanceMeters`: integer 0–1,000,000. Optional remaining distance: 0–10,000,000 m; remaining time:
  0–604,800 s. Numeric strings, fractions and booleans are rejected.
* `roadName`: optional string, at most 160 UTF-16 units, no control characters.
* ETA: optional hour 0–23 and minute 0–59, supplied together; null/absent means unknown.

Publish on changes and refresh a genuinely observed active guidance snapshot at least every two
seconds. If the source is unavailable or the maneuver cannot be recognized, do not invent a turn.
The receiver clears after six seconds including source age, and on disconnect/source change. A
queued snapshot must still meet the 1500 ms source-age budget when dispatched. Native output
acceptance does not prove physical rendering; diagnostics and vehicle observation remain necessary.

Stop explicitly:

```json
{"type":"navigation.stop","seq":2,"ageMs":0}
```

Errors use `{"type":"error","code":"..."}`. Codes include `guidance_disabled`, `stale_sequence`,
`invalid_message`, `unsupported_message` and `viewer_unavailable`.

## Receive-only video

The user opens **Preview received map** or **Show on cluster**. Cluster projection must already
be ready in DashCast; launching a preview does not activate/change an OEM projection sequence.
The viewer is a separate task and accepts video only. It creates no camera/microphone capture and
requests no capture permission on the vehicle. All capture occurs on the companion device.

When a capable visible viewer becomes available the sender receives `{"type":"video.ready"}`.
When it closes, `{"type":"video.closed"}`. Wait for `video.ready` and offer a **single video track**;
audio and data-channel media sections are rejected. Use a new negotiation ID (1–64 ASCII letters,
digits, underscores or hyphens) on every attempt:

```json
{"type":"video.offer","negotiation":"attempt_1","sdp":"<RTC offer SDP>"}
```

Reply:

```json
{"type":"video.answer","negotiation":"attempt_1","sdp":"<RTC answer SDP>"}
```

Trickle ICE in either direction:

```json
{"type":"video.ice","negotiation":"attempt_1","candidate":"candidate:...","sdpMid":"0","sdpMLineIndex":0}
```

Queue ICE until the corresponding remote description is applied. Candidates are bounded to 128
per negotiation, candidate length 2048, media index 0–8. SDP is bounded to 60,000 UTF-16 units and
the whole message must also meet the UTF-8 limit. The receiver has no STUN/TURN servers; the
companion must likewise use local host candidates and an empty ICE server list. Media uses WebRTC
DTLS-SRTP directly between LAN peers, separate from the ordered control socket. Prefer H.264 if
both endpoints negotiate it; do not assume every vehicle WebView offers a hardware decoder.

```json
{"type":"video.stop","negotiation":"attempt_1"}
```

Viewer errors use `{"type":"video.error","negotiation":"attempt_1","code":"..."}` (for example
`negotiation_failed`, `connection_lost`, `video_timeout`, `playback_failed`, `signalling_overflow`).
After an error/stop use a new negotiation ID. Waiting viewers can accept a new offer without
reopening the Activity. An external reset discards queued old-session signalling. A hidden/closed
viewer releases its connection; a stopped video source clears the displayed image after six
seconds without decoder progress. Keep screen-capture frames flowing even when the map is static.

No remote touch/key injection, audio forwarding, cloud relay, automatic arbitrary-app background
capture or ABRP-specific integration is promised by v1. Application capture and protected-screen
restrictions depend on the companion Android version and app. WebView absence/incompatibility
causes a controlled viewer failure while local navigation and projection remain available.

## Compatibility and validation

`navigation-fixtures.json` contains portable accepted/rejected messages and is exercised by the
receiver tests. Copy/pin these fixtures and this contract in the satellite repository; changing
them requires passing both applications' compatibility tests. Additive optional fields can remain
v1; changed meanings/required fields need a new version. Keep historical protocol tests.

Run receiver checks with:

```sh
rtk proxy ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
rtk proxy node --test tools/satellite/test_receiver.mjs
```

Vehicle gates: disabled receiver preserves local behavior; pairing and revocation work; remote
guidance respects HUD-only/cluster-only/both/off; listener teardown does not clear satellite
guidance; disconnect clears; stale socket cleanup cannot clear a replacement; ignition restart
restores the opted-in receiver; video preview/project/stop release the decoder; test video
resolution/frame rate and latency with Overdrive recording throughout. No measured latency or
vehicle support claim follows from JVM/JavaScript tests.
