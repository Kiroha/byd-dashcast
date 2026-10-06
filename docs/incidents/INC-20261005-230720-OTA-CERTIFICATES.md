# OTA validation stops before installation on Android 10

The report captured on 2026-10-05 at 23:07:20 shows DashCast 1.9.3-beta
(versionCode 642) attempting to update to 1.9.4-beta on DiLink 3.0, Android 10
(API 29). The downloaded APK is rejected during certificate validation. The
installer is never started.

## Report evidence

```text
[23:05:26.643][INFO][UpdateChecker] Update available: 1.9.3-beta+build642 → 1.9.4-beta
[23:05:31.535][INFO][OtaArtifactCleanup] removed 1 completed OTA source file(s)
[23:05:31.536][ERROR][UpdateChecker] OTA download failed [Exception: APK signer mismatch]
[23:05:31.546][ERROR][OTA] error: APK signer mismatch
```

`startDownload` downloads the file, validates it, and only then starts installation.
The exception handler clears the pending OTA state and deletes the downloaded APK
before logging the exception. The cleanup line is a consequence of validation
failure, rather than evidence of a deletion race.

Both APKs downloaded from the published 1.9.3-beta and 1.9.4-beta releases verify
successfully with `apksigner verify --verbose --print-certs`. Both use APK Signature
Scheme v2 and have one signer with the same SHA-256 certificate digest:

```text
c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8
```

The release APKs have not changed signing keys.

## Root cause

`UpdateChecker.validateDownloadedApk` requested only
`PackageManager.GET_SIGNING_CERTIFICATES` when calling `getPackageArchiveInfo`.
In the [Android 10 implementation of PackageManager](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/content/pm/PackageManager.java),
archive parsing collects certificates only when `GET_SIGNATURES` is present.
Requesting the modern flag alone leaves the archive's signing details unavailable.

An independently extracted DiLink 3 framework agrees: `getPackageArchiveInfo`
tests flag `0x40` (`GET_SIGNATURES`) before calling `PackageParser.collectCertificates`.
`PackageParser.generatePackageInfo` leaves `signingInfo` null when those details
have not been collected. That extract is from February 2026; the incident's
firmware build is from June 2026, so it is corroborating evidence from an older
build rather than a dump of the incident's exact firmware.

DashCast passed the missing archive signers to `sameSignerSet`, which correctly
refused them, but reported the generic `APK signer mismatch`. This accounts for
the visible download followed by no installation.

The flag omission was introduced in commit
`1d92fd1a0abb9f1815b4ac2713219003151373f9` (`fix(ota): verify release artifacts
before installation`) on 2026-08-31 and was preserved by the Kotlin port.

## Correction and verification

Archive parsing now requests `GET_SIGNING_CERTIFICATES | GET_SIGNATURES`.
The correction is included in 1.9.5-beta (versionCode 644).
The modern `signingInfo.apkContentsSigners` remains the source for comparison.
Unavailable archive or installed-app certificates produce their own errors.
The SHA-256 check, package and version checks, non-debuggable requirement and
exact current signer comparison remain enforced.

`OtaApkValidationTest` uses an API 29 PackageManager fixture that reproduces the
framework's certificate-collection condition. Before the correction, the matching
signer test fails with `APK signer mismatch`. After the correction, it passes.
Additional cases verify rejection of a different signer, absent archive
certificates, absent installed-app certificates, and modified download bytes.
The modified-file case also checks that rejection occurs before archive parsing.

Offline validation on 2026-10-06 completed successfully:

- 796 JVM tests across 163 suites, with no failures, errors or skips.
- Release lint: 0 issues.
- Release assembly: successful; the resulting APK verifies with Signature Scheme
  v2 and the same certificate digest listed above.
- `graphify update .`: completed using AST extraction.

Vehicle verification remains necessary: install a build containing this correction
manually once, then test OTA installation of a subsequent, newer build. An older
installed build still runs its own broken validation code and cannot obtain the
fix through that failing OTA path. This change does not replace the already
published 1.9.4-beta APK.
