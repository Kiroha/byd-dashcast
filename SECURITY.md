# Security Policy

DashCast / MyBYDApp is an **open-source privileged system launcher** for BYD DiLink car clusters. Because it runs as a platform-privileged component, its security model differs from that of an ordinary Play Store app. This document explains the parts that most often raise questions — especially APK signing and antivirus heuristics — and how to report vulnerabilities.

## Supply chain / signing

### Platform (AOSP test key) signing — by design

Release APKs are signed with the **public AOSP platform test key**:

- **Certificate owner:** `CN=Android, O=Android` (the well-known AOSP test/platform certificate)
- **Signer SHA-1:** `27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA`
- **Signer SHA-256:** `C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8`

This is **intentional and required**. On BYD DiLink head units, the platform certificate is this AOSP test key. Android grants `protectionLevel="signature"` (system-level) permissions only to apps signed with the same certificate as the platform. DashCast needs several of these — for example `BYDAUTO_*` (BYD Auto system APIs), `INJECT_EVENTS`, `ACCESS_SURFACE_FLINGER`, and cluster/HUD projection — to function as a launcher and drive the instrument cluster.

Signing with a private "production" key is **not possible** on this hardware: the signatures would no longer match the platform, every signature-level permission would be denied, and the launcher/cluster/HUD would stop working.

Because this is a **public** key, the signature proves platform-compatibility, **not** authorship — anyone can produce a same-signed build. The project therefore does **not** treat its own signature as a security boundary. In particular, the custom `com.byd.dashcast.permission.DAEMON_READY` signature permission cannot be relied on for isolation; the effective boundary for privileged operations is the daemon's kernel-supplied caller-UID check, not the signature.

### VirusTotal / BitDefender "TestKey" heuristic

Some engines (e.g. BitDefender) flag release APKs as **`Android.Riskware.TestKey.rB`**, typically **1/65** on VirusTotal.

This is a **signing-key heuristic, not a behavioral malware detection.** It fires solely because the APK carries the public AOSP test certificate, regardless of the app's behavior. It is expected to appear on **every** DashCast release, because we cannot stop signing with the platform key without breaking the app. It is not evidence of malicious code.

### Verifying a release APK

Do not rely on the antivirus verdict to authenticate a build. Instead, verify the signer fingerprint:

```
apksigner verify --print-certs app-release.apk
# or
keytool -printcert -jarfile app-release.apk
```

Confirm the printed SHA-1 equals `27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA`. If it does not match, do not install the APK. For maximum assurance, **build from source** (the signing key used is the public AOSP test key; the keystore file itself is not committed to the repository).

## Known: what's in the APK

A DashCast release APK is a **privileged system component**, not a sandboxed consumer app. Installing it grants it powerful capabilities (input injection, SurfaceFlinger/display access, boot receiver, tethering/Wi-Fi config, microphone, network). Treat it accordingly:

- Install release APKs only from the official project releases, and **verify the signer SHA-1** above — or build from source.
- Understand that platform signing is a public-key model on this hardware, so the signature identifies the platform, not a specific author. If you require author-level trust, build from source.
- The app requires **ADB over TCP** to be enabled out-of-band to bootstrap its privileged helper. This is an operational choice made by the installer/OEM, exposes a network-reachable `adbd` on the vehicle while enabled, and should be turned off when not needed. The app itself does not open that port; it only connects to it locally with an app-private, RSA-authenticated key.
- **Never leave a debug build on a vehicle.** Debug and release carry the same platform signature — the BYD permissions depend on it — but the debug variant is also `debuggable`, and the release is not. Combined with the point above, that is the sharp edge: on a car where ADB over TCP is on because DashCast needs it, anyone who can reach that port can attach a debugger to a process holding platform permissions and act with them. Debug builds belong on a bench. The published releases are release builds.

## Privacy

What a diagnostic report collects and where it goes is documented separately, in
[PRIVACY.md](PRIVACY.md).

## Diagnostic artefacts and tester data

DashCast's support flow collects real vehicle diagnostics: application journal, system logcat,
`dumpsys` output and cluster screenshots. These artefacts routinely contain personal data — Wi-Fi
SSIDs and BSSIDs, account names, Bluetooth device names, installed-app inventories, and navigation
destinations. This repository is public, and its history is permanent.

Two rules therefore apply to anything committed here:

- **No third-party identifying data in versioned documentation.** No licence plates, no tester names
  or handles, no cities or countries tied to an individual, no private channel identifiers, and no
  nominative filesystem paths. Where a document needs to distinguish testers, use opaque labels that
  are local to that document ("tester A", "tester B").
- **No raw diagnostic artefact in the working tree of a clone.** Bug-report bundles and captures are
  covered by `.gitignore`, but the rule matters more than the pattern: keep triage artefacts outside
  the repository directory, because a single `git add -A` publishes them irrevocably.

## Reporting a vulnerability

Please report suspected security issues **privately**, not in a public issue, so we can address them before disclosure:

- Use GitHub's **Private vulnerability reporting** (Security tab → "Report a vulnerability") on this repository, **or**
- Open a minimal public issue that says only "requesting a private security contact" without technical detail, and we will provide one.

When reporting, please include: affected version(s), the head-unit model/firmware if relevant, reproduction steps, and the impact you observed. We aim to acknowledge reports within a few days. Because this is a community project for a niche automotive platform, we cannot commit to a formal SLA, but security reports are prioritized above feature work.

**Please do not** post exploit details, extracted secrets, or working attack payloads in public issues before a fix is released.