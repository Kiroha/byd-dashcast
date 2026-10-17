# DashCast satellite protocol v1

Status: experimental receiver and guidance companion APKs are available. Vehicle rendering,
AndroidKeyStore TLS handshakes and WebView codec performance still need device testing.
The receiver is disabled by default. This is a local protocol with no cloud relay or discovery.

## Pairing and transport

Open Settings → Satellite device → Pair a device. The optional six-digit-code exchange in
`PAIRING_V1.md` transfers this profile without a file; manual JSON import remains available:

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

Satellite may add optional display metadata to `hello`:

```json
{"type":"hello","version":1,"token":"<paired token>","device":{"id":"12345678-1234-4123-8123-123456789abc","name":"Carlinkit Tbox Ultra"}}
```

`device.id` is a canonical lowercase random UUID v4 generated once per Satellite installation and
stored outside Android backup, never a hardware identifier. `device.name` is the sender-reported
manufacturer/model label: 1–80 UTF-16 code units, at most 160 UTF-8 bytes, trimmed and nonblank,
without control characters, Unicode format characters or surrogate code units. Whitespace within
the label is limited to ordinary spaces, so labels cannot insert additional UI lines.
Absent or malformed metadata is ignored; the connection remains compatible with the original v1
hello and is shown as an unknown device. Receivers without this extension ignore `device`.

Accept and record this metadata only after token authentication. It is a display aid, not a unique
device credential: copies of the same profile share one token and can report the same name or ID.
The receiver distinguishes the current authenticated connection from its last authenticated
connection, records the numeric peer address only as a network location, and clears that history
when credentials are revoked. Sending a pairing profile does not itself establish a connected or
authenticated device. No device names or IDs are added to support logs.

Both applications display a common **DashCast ID**: the first 16 uppercase hexadecimal characters
of `certificateSha256`, grouped as `ABCD-EF01-2345-6789`. This is a visual installation label;
certificate verification always uses the complete 64-character fingerprint. Satellite distinguishes
its saved receiver profile from a connection accepted by that receiver. Token revocation preserves
the receiver certificate ID, while reinstalling DashCast changes it.

Reply:

```json
{"type":"welcome","version":1,"session":"<opaque id>","navigationTimeoutMs":6000,"remoteGuidance":false,"genericRoundabouts":true,"videoTransport":"webrtc"}
```

A busy receiver refuses another sender. Reconnect with backoff and a new WebSocket after failure.
Version mismatch is refused. Unknown message types produce an error; unknown fields are ignored.
Control heartbeat: `{"type":"ping"}` → `{"type":"pong"}`. Pings do **not** keep guidance alive.

`genericRoundabouts` is an additive optional capability, scoped to this authenticated welcome.
Only the literal Boolean `true` enables it. Missing, null, false, strings and numbers mean no
support. Forget the capability on disconnect and every new connection attempt; never copy it
from a saved profile or another session. Existing companions ignore this additional field.

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
* When circulation is recognized but no exit is available, a receiver advertising
  `genericRoundabouts:true` also accepts `roundabout_generic_cw` and `roundabout_generic_ccw`.
  The `exit` field is **forbidden**, including null or zero. These represent circulation only:
  never guess exit 1, exit geometry, handedness from a road name, or an OEM icon. An explicit
  invalid, out-of-range or contradictory exit remains unsupported instead of becoming generic.
  Senders talking to older receivers send `navigation.stop` with the observation's same sequence
  and real source age, clearing the previous arrow until the next supported maneuver. Numbered
  `roundabout_cw`/`roundabout_ccw` still require integer exit 1–10 with unchanged meaning.
* `distanceMeters`: integer 0–1,000,000. Optional remaining distance: 0–10,000,000 m; remaining time:
  0–604,800 s. Numeric strings, fractions and booleans are rejected.
* `roadName`: optional string, at most 160 UTF-16 units, no control characters.
* ETA: optional hour 0–23 and minute 0–59, supplied together; null/absent means unknown.

Publish on changes and refresh a genuinely observed active guidance snapshot at least every two
seconds. If the source is unavailable or the maneuver cannot be recognized, do not invent a turn.
The receiver clears after six seconds including source age, and on disconnect/source change. A
queued snapshot must still meet the 1500 ms source-age budget when dispatched. Native output
acceptance does not prove physical rendering; diagnostics and vehicle observation remain necessary.

The DL3 native cluster path renders generic circulation as native 11/17 with exit 0; it preserves
the actual maneuver distance. No verified generic physical CAN HUD icon exists. A generic frame
does not start CAN, write a guessed physical icon, or enter the OEM AMap bridge (which translates
11/17 to detour CAN icons). If DashCast previously opened CAN guidance, it attempts its existing
narrow CAN clear **before** updating the native cluster. A failed clear stays uncertain and retries
on fresh frames at the existing bounded cadence. This leaves the authenticated session, source
freshness, cluster guidance and later supported turns intact. The AMap stop broadcast is not used
for this transition because it also stops the cluster. Physical coexistence still needs parked
validation on the owner's SEAL, Android 10 / DiLink 3, SX361 firmware; it is not inferred from tests.

Bug reports include a passive `SATELLITE GUIDANCE` summary: current connection/guidance state,
last accepted maneuver category, distance, exit-known flag, aggregate output acceptance and a
fixed local clear reason. The last accepted input is historical after a stop/expiry; acceptance
means at least one selected output accepted the frame, never proof of visible rendering. No
session ID, credential, peer identity, notification or route text is included in this summary.

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

## Optional manual video diagnostics

A receiver supporting schema 1 adds the exact integer `"videoDiagnostics":1` to its authenticated
`welcome`. Missing, null, Boolean, string, fractional or other version values disable this feature.
Companions send **nothing** to unsupported receivers and reset the capability on every connection.
It is never inferred from a saved profile, app version, video readiness or a previous socket.

The user can choose **Send diagnostics to DashCast** in DashSat after a video attempt. This sends
one bounded sender-reported snapshot over the existing pinned, authenticated local WSS connection;
no Telegram/internet access, additional listener, file server or visible video viewer is required.
Guidance, heartbeat, source age and media negotiation remain independent. There is one replaceable
pending snapshot, no automatic export and no retry across a disconnected control generation.

```json
{"type":"diagnostics.video","schema":1,"request":1,"snapshotAgeMs":200,"appVersionCode":12,"state":"waiting_viewer","captureBusy":true,"share":2,"counts":{"offer_sent":1},"recent":[{"event":"offer_sent","cause":"none","share":1,"ageMs":8000}]}
```

`diagnostics-video-schema.json` defines the strict allowlist and `diagnostics-video-fixtures.json`
contains accepted/rejected compatibility cases. The complete UTF-8 message is at most 16,384 bytes.
All fields are required and unknown fields are rejected, including inside history rows and counts.
Integers must be actual JSON integers, never strings, fractions or Booleans. `request` is a local
per-control-connection ordinal from 1 to 1,000,000, strictly increasing across accepted requests;
it is not a device/session/negotiation identifier. `appVersionCode` is an integer from 1 to 1,000,000.
`share` is a process-local sharing-attempt ordinal from 0 to 1,000,000 (0 before the first attempt).
Counts contain up to 32 fixed event keys with positive integer totals up to 1,000,000. If more
than 32 event types occurred, prefer types present in recent history, then enum declaration order.
Recent history has at most 32 rows, newest first, with a fixed event, fixed exception category,
local share ordinal no greater than the snapshot's share, and nondecreasing age in milliseconds.
`snapshotAgeMs` includes local queue delay. `ageMs` is the event age when the snapshot was captured;
report receipt age is tracked independently by the receiver. Ages saturate at 86,400,000 ms and mean
**at least 24 hours** at that limit. Counts, app version and sharing ordinals saturate at 1,000,000;
capped sharing ordinals may merge attempts. No SDP, candidates, tokens, IDs, addresses, exception
messages, notification/route/app content or arbitrary strings are permitted.

After committing the reconstructed typed snapshot to its RAM cache, the receiver sends:

```json
{"type":"diagnostics.video.ack","schema":1,"request":1,"status":"accepted"}
```

`accepted` confirms receipt only, never an image or successful video. Rejections use the same
message with `status:"rejected"`; an unsupported integer schema uses `status:"unsupported"`.
If the request ordinal itself is malformed, rejection uses request 0 and cannot acknowledge a
valid request. Diagnostic rejections do not emit generic `invalid_message` or disturb control,
guidance or video. Payloads between 16 KiB and the global 64 KiB protocol limit are rejected this
way; exceeding the global limit remains a transport failure. Senders accept only a matching ACK
on the same authenticated generation, after actual dispatch and within five seconds of the manual
request. A local queued/sent event without ACK is never displayed as received.

The receiver keeps only one immutable typed snapshot in process RAM, at most 24 hours from receipt.
An ordinary disconnect keeps historical evidence for the next DashCast bug report. Reconnection
with the same authenticated installation metadata retains history; a different/unknown peer,
disabling the receiver, token revocation or process restart clears it. Installation metadata is
sender-reported and is not a separate credential. Cache commits are fenced against replacement,
disable and token rotation. Bug reports label the data sender-reported/manual/historical, give
receipt and capture ages, and distinguish a disconnected/replaced receipt connection. A retained
ACK in DashSat is explicitly the **last transfer**, not a claim about the current cache or image.

## Receiver status in DashCast

The Satellite settings page displays connection and guidance separately, using the
receiver's actual callbacks and the serial guidance writer. A disabled receiver is
grey; startup remains grey until the listener is bound. A bound listener is orange
and waiting, successful WSS authentication is green and connected, and a receiver
startup failure is red and unavailable. An open unauthenticated socket or a saved
pairing profile never establishes the connected state.

Guidance is disabled when satellite input is not selected, waiting when no usable
instruction has been processed, active after a validated fresh instruction passes
the current session/source-revision guard, and expired at the existing six-second
deadline including source age. Active input does not assert successful OEM delivery
or physical rendering. Invalid/replayed messages and discarded queued frames do
not renew availability. Source changes, stops, disconnections and token revocation
discard the previous input state; obsolete transport callbacks are fenced.

Each indicator includes a themed icon and a translated accessible label in all 13
app languages. Its snapshot contains only state enums, without credentials, pairing
codes, notification content, road names or peer identifiers. The page samples current
state every 500 milliseconds only while visible and reads it immediately on entry
and return; leaving the page does not stop the receiver or change protocol behavior.

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


### Optional first-frame diagnostics details

An authenticated `welcome` may additionally advertise `"videoDiagnosticsDetails":1` (an exact
integer), alongside `"videoDiagnostics":1`. A sender MUST omit the entire `details` key unless
both capabilities are exactly integer 1 on its current authenticated control connection.
Reset this capability and every queued request on disconnect/reconnect. The optional extension
keeps diagnostics `schema:1` and the existing ACK unchanged. Legacy snapshots remain valid.
Malformed details receive a diagnostics-only rejected ACK; control and guidance remain independent.
The complete UTF-8 message, including details, remains bounded to 16384 bytes.

`details` has exactly `schema:1` and `attempts` (at most four, newest local attempt first).
Each fixed-field row contains local `attempt` and `share` ordinals, `ageMs`, nullable
`sampleAgeMs`, `ended`, nullable codec (`h264`, `vp8`, `vp9`, `av1`), and nullable counters
`bytesSent`, `packetsSent`, `framesEncoded`, `keyFramesEncoded`, `pliCount`, `nackCount`, `firCount`.
Ordinals are bounded to 1000000; ages saturate at 86400000 ms; counters are integers 0..9000000000000.
Attempt ordinals strictly decrease; row.share cannot exceed snapshot.share; sampleAgeMs cannot
exceed ageMs. Unknown remains null, including every measurement before a first stats sample.
Keyframe count cannot exceed encoded frame count when both are available. Samples are historical,
retain their original timestamp through peer disposal, and do not establish a current receiver image.
Sender outbound RTCP PLI/NACK/FIR counts mean feedback **received**; receiver inbound counts mean
feedback **sent**. No SSRC, codec/track/session/negotiation identity, raw SDP/fmtp, exception text,
address, content or route text is exported. The JSON schema and compatibility fixtures define the
strict field allowlist. This extension never changes capture, decoding, freshness or codec selection.


### Optional actual encoded H264 header observations

An authenticated welcome may advertise exact integer `"videoDiagnosticsH264":1` in addition to
both existing diagnostics capabilities. Only when all three are exactly integer 1 on the current
authenticated connection may a sender use `details.schema:2`. Otherwise it MUST retain the exact
schema-1 details shape (or omit details when unsupported). Reset all capabilities and pending
requests on control replacement. Top-level diagnostics and ACK remain schema 1. Receivers accept
both strict details schemas; malformed extensions reject diagnostics only. The entire message
still fits within 16384 UTF-8 bytes.

Schema 2 adds required `h264` to each attempt row: at most four observations, oldest first,
containing exactly `ageMs`, `declaredKey`, nullable `width` and `height`, `framing` (`annex_b` or
`unknown`), `inspection` (`complete` or `truncated`), nullable Boolean `sps`, `pps`, `idr`, and
nullable byte integers `profileIdc`, `constraintFlags`, `levelIdc`. Observation age is frozen at
manual snapshot time independently of the RTC statistics sample age, cannot exceed attempt age,
and is nonincreasing through the array. Later observations require declaredKey=true. Dimensions
are the SDK EncodedImage metadata, null or 1..8192; they are NOT parsed SPS dimensions.

The sender samples its first actual H264 encoded output, then up to three later SDK-declared key
outputs. Per attempt it examines no more than 180 callbacks during 12 seconds from the first
output, scans at most 256 KiB total and 96 KiB per selected buffer, and stops without a timer.
The same budget survives encoder replacement or reinitialization; retired or stale callbacks
cannot update evidence. It reads only the encoder buffer's current position..limit synchronously,
without retaining, copying, modifying or exporting media bytes.

`complete` means the bounded inspection covered the EncodedImage window, NOT validation of a
bitstream. Length-prefixed/unknown framing yields null presence. Truncated, short or invalid NAL
headers cannot establish absence. A positive presence means a recognized header was observed;
false is allowed only after complete recognized Annex B inspection. SPS metadata is either three
nulls or three 0..255 bytes with sps=true. It is the first observed fixed SPS header in that buffer,
not a validated SPS, evidence of SPS-to-IDR association, negotiated profile, successful RTP delivery
or receiver decoding. No raw NAL, SPS, codec implementation name or other free-form content is
retained. This diagnostic extension does not alter codec selection, capture, recovery or freshness.


### Optional synthetic video reference test

Exact integer `"videoReferenceTest":1` in the authenticated welcome extends the existing
`videoDiagnostics:1`, `videoDiagnosticsDetails:1`, and `videoDiagnosticsH264:1` contract. All four
must be exact integers on the same control generation. This one capability enables both the
explicit reference offer and diagnostics details schema 3; reset it on reconnect. Missing, boolean,
string, floating-point or unknown values do not enable testing or schema 3.

Normal capture retains the exact legacy `video.offer` shape. A supported reference test adds
`"sourceMode":"reference"` to its offer. No other value or type is accepted. This describes
synthetic pre-encoded media, never an app image, and grants no capture/input permissions.
The viewer visibly labels the test before the first decoded image and while rendering it.
A receiver must never infer hardware decoding, successful display or guidance from this label.

Details schema 3 extends schema 2 with one required `sourceMode` scalar on each attempt:
exactly `capture` or `reference`. The sender fixes it at attempt creation. All previous bounds,
ordering, nullable scalars, byte limits, strict unknown-key rejection and ACK rules remain unchanged.
Without the complete capability contract, capture attempts keep the exact older schema shape;
reference attempts are omitted from older details rather than reported as ordinary capture.
Top-level diagnostics/ACK schema remains 1. A malformed mode rejects only that diagnostics request.

The reference implementation requires fresh Android MediaProjection consent, shares the existing
foreground service, uses no captured pixel data, sends only pre-encoded synthetic IDRs through the
same native WebRTC RTP/DTLS stack, and has no software encoding fallback. Its fixtures are 960×452,
Constrained Baseline level 3.1, include SPS/PPS and an IDR large enough for FU-A. Original platform
texture timestamps are consumed once: timer-forced cached frames are disabled in reference mode.
It permits at most 5 outputs/second and 75 outputs total, with no catch-up. The capture lease ends
no later than 60 seconds after consent or 15 seconds after the first reference negotiation starts,
whichever comes first, even with zero callbacks or no visible viewer. Peer replacement/source
resume never renews either deadline. Completion is not proof of decoding; normal sharing afterward
requires a new consent. Two immutable direct payload buffers remain leased until native release;
when unavailable, frames are dropped. Assets are bounded and hash-validated off callback/main threads.
