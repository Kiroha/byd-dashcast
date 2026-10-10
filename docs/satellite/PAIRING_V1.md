# Optional local pairing transfer v1

This extension transfers an existing protocol-v1 pairing profile without a file or camera.
Guidance and video still use the unchanged `PROTOCOL_V1.md` WSS contract on port 47832.
Both applications must support this extension; legacy JSON profile import remains available.

## User flow and lifetime

Start the vehicle hotspot and connect the companion. In DashCast, enable the satellite receiver
and select **Pair a device**. It displays a temporary six-digit code and a two-minute countdown.
Choose **Continue on Tbox**, switch to the Tbox interface and select **Pair with DashCast**,
enter the code, and pair. Continue, Back, outside-tap dismissal and switching applications hide
the dialog without cancelling pairing or extending its original two-minute deadline. Reopening
the pairing screen shows the same code and remaining time.
The receiver's foreground service keeps the pairing window available while the user switches
between the two interfaces on the same vehicle screen. The companion tries the current LAN gateway;
a numeric local vehicle address is an optional fallback. The profile is validated and saved in the
companion's existing Keystore-backed store. Normal guidance still requires notification access
and explicit source/output selection.

Generate a new code for each window. The explicit **Cancel** button, successful advanced profile
copy, expiry, receiver disable, token
revocation, receiver service destruction, or authenticated WSS connection closes the endpoint
and pending sockets. App/process restart does not restore a pairing window. Temporary codes stay
only in memory; never place them in logs, saved state, screenshots or notification text. Returning
to the visible pairing screen may show the current code/countdown; leaving it for the Tbox does
not cancel the receiver window. Leaving the companion's active exchange cancels that attempt and
fences late profile writes. Pairing does not select guidance, take over a sender or start capture.

## Password-authenticated key exchange

A six-digit PIN must never directly encrypt the permanent profile: captured ciphertext would
allow offline guessing. Both applications use the pinned Bouncy Castle Java J-PAKE implementation
(`org.bouncycastle:bcprov-jdk15to18:1.86`) with NIST_3072 and SHA-256, including round-three key
confirmation. The PIN is generated uniformly with SecureRandom and is never transmitted.
Only five exchanges may start per pairing window, bounding online guesses independently of
concurrent connections. A new window creates a new PIN.

Each connection creates fresh participant IDs and ephemeral J-PAKE state. Both peers validate
every received proof and the other peer's key-confirmation tag. The server releases the encrypted
profile only after the client has validated the server's confirmation and the server has validated
the client's confirmation. No generic trust-all TLS policy is used.

A session key is derived from J-PAKE keying material using HKDF-SHA-256 with domain separation and
both participant identities. The profile is encrypted with AES-256-GCM, a fresh random 12-byte IV
and a 128-bit authentication tag; context binds it to this exchange. Modified or replayed messages,
wrong PINs and invalid encodings are rejected before storing the profile. The authenticated profile
supplies the complete DER certificate SHA-256 pin used by the ordinary WSS connection before it
sends the permanent token. The guidance protocol's certificate/token requirements remain unchanged.

## Bounded local transport

The temporary endpoint accepts local peers on TCP 47833. It uses length-prefixed JSON frames,
not HTTP: a four-byte unsigned big-endian UTF-8 byte count followed by exactly that many bytes.
Frames are bounded to 24576 bytes before allocation. Reject malformed UTF-8, unsupported versions,
incorrect message order, unexpected fields/participants and noncanonical bounded encodings.
The decrypted profile remains bounded to 16384 bytes and passes the existing strict profile parser.

The companion binds the socket to its selected Wi-Fi/Ethernet Android Network, bypassing proxies
without changing other applications' routing. There is no relay, DNS target, broadcast scan or
persistent discovery service. Network candidates are bounded; IPv4 hotspot gateways are tried
first, with numeric local address entry as fallback. Both ends bound connection/read/exchange time
and close in-progress sockets on cancellation. The receiver has two workers and no request queue.

Exchange order:

1. Client round one, then server round one.
2. Client round two, then server round two.
3. Server round-three confirmation; client validates before sending its round-three confirmation.
4. Server validates the client confirmation, then sends the authenticated encrypted profile.


The JSON schemas are strict (no extra fields):

- `jpake.round1`: `version`, `type`, `id`, `gx1`, `gx2`, `proof1: [gv, r]`, `proof2: [gv, r]`.
- `jpake.round2`: `version`, `type`, `id`, `a`, `proof: [gv, r]`.
- `jpake.round3`: `version`, `type`, `id`, `mac`.
- `pairing.profile`: `version`, `type`, `clientId`, `serverId`, `iv`, `ciphertext`.

Every version is the integer 1. Participant IDs use `client-` or `server-` followed by 32 fresh
lowercase hexadecimal digits. Group elements/proof responses use canonical unsigned lowercase
hexadecimal, with no leading zero except the value zero, and are bounded to the group's p/q ranges.
The confirmation MAC is canonical signed lowercase hexadecimal in the signed 256-bit range used
by Bouncy Castle. IV/ciphertext use canonical unpadded base64url.

Key derivation and authenticated-encryption context:

```text
context = ASCII("DashCast satellite pairing JPAKE v1") || 0x00 || ASCII(clientId) || 0x00 || ASCII(serverId)
IKM = unsigned minimal big-endian bytes of J-PAKE keying material
salt = SHA-256(context)
info = ASCII("DashCast satellite pairing profile AES-256-GCM v1")
AES key = HKDF-SHA-256(IKM, salt, info, 32 bytes)
AES-GCM AAD = context
```

## Compatibility and validation

`pairing-fixtures.json` contains synthetic compatibility data, never live credentials. Both apps
execute it plus full exchanges, wrong-PIN rejection, replay/tampering, malformed frame limits,
attempt limits, expiry, screen switching, cancellation and storage ownership tests. This optional
transfer has its own version and receiver pin; leave the pinned guidance contract unchanged.

Validate the real flow on the Carlinkit Tbox Ultra 1 / Android 15, especially switching between
DashCast and Tbox on the vehicle screen, TetherFuseNet gateway detection, wrong code followed by
retry, and code pairing followed by the normal pinned WSS connection and physical guidance.

The driver reported that in DashCast 1.9.11, opening CarPlay hid the code and returning within
two minutes did not restore it; Satellite could no longer pair. Validate this regression on the
vehicle; automated lifecycle tests do not establish that the OEM screen transition is fixed:

1. Connect the Tbox to the vehicle hotspot, open **Pair a device**, remember the code and select
   **Continue on Tbox**. Open CarPlay/Tbox, return before expiry and verify the same code with a
   lower countdown. Switch back to Satellite and complete pairing without transferring a file.
2. Repeat using Back and the vehicle's normal app-switch controls. Neither action should create
   a new code, reset the countdown or close the endpoint.
3. Verify that **Cancel** immediately invalidates the code, and that an untouched background
   window expires at its original deadline. A fresh code must require a new pairing action.

References:
[Bouncy Castle Java](https://www.bouncycastle.org/download/bouncy-castle-java/),
[J-PAKE participant contract](https://downloads.bouncycastle.org/lts-java/docs/bccore-lts8on-2.73.11-javadoc/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.html),
[HKDF RFC 5869](https://www.rfc-editor.org/rfc/rfc5869).
