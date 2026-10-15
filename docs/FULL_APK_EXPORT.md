# Full local APK export

In **DIAG**, choose **Export ALL accessible APKs — local ZIP volumes**. This action is independent
of the older, targeted **BYD APK Extraction** button and its platform-completion flag. Use it while
parked and keep DashCast in the foreground. It does not infer movement or enforce a parked state.

The fixed progress panel shows discovery stage/current source, discovered/copied/failed counts,
MiB streamed, elapsed time and time since actual byte or stage progress. Discovery starts with an
unknown total. After 15 seconds without progress, the panel says the job may be waiting/stalled.
Cancel takes effect after an outstanding synchronous daemon command/read returns; shell commands
have the existing 30-second server timeout, while a Binder transaction can take longer. Rotating
or reopening DIAG observes the existing process-scoped session. Process termination stops export;
the next launch detects its marker/partial files instead of claiming it resumed successfully.

Discovery combines current-user PackageManager base/split source paths, `pm list users`, each user's
`pm list packages -f -u --user` and `pm path --user`, and recursive APK scans of firmware partitions,
known installation/preload/local-storage roots and eligible mounted filesystems. Disabled packages,
retained package records and inactive factory APKs are included when the effective app/shell access
can discover/read them. Paths are deduplicated by complete path, never by APK basename/package tier.
There is no APK selection list, APK-count budget, payload-size budget, or automatic upload.

Filesystem scans run once per root into a bounded shell inventory text file. The app reads that text
in bounded chunks and removes it afterwards. Timeout can therefore retain partial discoveries;
missing completion markers, command failures, permission errors and unknown inventory lengths are
reported as discovery issues. A child-shell file-size limit bounds each temporary inventory; hitting
it also marks discovery partial. Package output is paged under the daemon's existing output cap.

The effective app and existing ProxyDaemon identities cannot guarantee access to **every APK in
the vehicle**. Protected, encrypted, offline/unmounted or other-namespace locations may remain
unavailable. Private application-data directories are not recursively scanned. Newline-containing
paths are unsupported, and live discovery is not an atomic snapshot. `manifest.json` records build
identifiers, effective daemon UID/protocol, sources/origins, expected and copied lengths, SHA-256,
per-file results and coverage details. Build identifiers do not positively identify a BYD model,
DiLink generation or firmware compatibility. No root or OEM restriction bypass is attempted.

Results are kept below:

```text
/storage/emulated/0/Android/data/com.byd.dashcast/files/apk-exports/apk-export-<timestamp>/
```

This subtree is independent of report cleanup. Each `apks-NNN.zip` is an ordinary standalone ZIP,
usually containing up to 1 GiB of APK payload. A larger single APK occupies its own volume. APK
bytes stream directly from their original files, or through 256 KiB ProxyDaemon reads, into the
ZIP; there is no payload staging copy. A 128 MiB storage reserve is checked before/while writing.

Each finished ZIP has its own `manifest.json`; the session manifest describes the overall result.
**Only entries with status `exported` contain a complete APK.** If a source becomes unreadable or
changes size during its read, its entry may contain truncated bytes with status `failed`; healthy
entries in that same ZIP are preserved. Destination/ZIP failures and cancellation leave the
current file as `.zip.part`; it is never published as a finished volume. Earlier finished ZIPs
remain available. A final `partial` outcome also covers known discovery gaps even if all discovered
sources were copied. Whole-vehicle coverage always remains explicitly unverified.

Use **Local export files** to see the directory and manually share individual finished ZIPs or
the session manifest. For large results, pull the displayed directory with an already-authorized
ADB connection, for example:

```sh
adb pull /storage/emulated/0/Android/data/com.byd.dashcast/files/apk-exports/
```

These files are retained until manually removed or app storage is cleared. Nothing sends them to
the configured bug-report channel. JVM tests and Android compilation cover archive/error handling;
actual file visibility, shell utility support, storage capacity and transfer latency still require
validation on the specific vehicle/ROM.

A separate **Export system files — framework, libraries & configs** button now collects a fixed
firmware subset into `system-exports`, with its own SYSTEM profile and provenance checks. See
[SYSTEM_EXPORT.md](SYSTEM_EXPORT.md). The shared progress/recovery panel labels the active profile
and recovers the latest timestamp across both export roots.
