#!/usr/bin/env python3

import importlib.util
import os
import re
import struct
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch


sys.dont_write_bytecode = True
SCRIPT = Path(__file__).with_name("scan_release_assets.py")
SPEC = importlib.util.spec_from_file_location("scan_release_assets", SCRIPT)
scanner = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(scanner)


class ScanReleaseAssetsTest(unittest.TestCase):
    def write_apk(self, directory: Path, manifest: bytes, dex: bytes = b"clean") -> None:
        with zipfile.ZipFile(directory / "DashCast.apk", "w") as archive:
            archive.writestr("AndroidManifest.xml", manifest)
            archive.writestr("classes.dex", dex)

    def test_missing_apk_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(scanner.scan_apks(Path(directory)), ["release :: no APK asset"])

    def test_clean_release_apk_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.write_apk(Path(directory), b"binary release manifest")
            self.assertEqual(scanner.scan_apks(Path(directory)), [])

    def test_debuggable_manifest_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.write_apk(Path(directory), "debuggable".encode("utf-16-le"))
            self.assertEqual(
                scanner.scan_apks(Path(directory)),
                ["DashCast.apk :: manifest :: possibly debuggable"],
            )

    def test_credential_pattern_is_rejected_without_echoing_it(self) -> None:
        token = b"123456789:AAabcdefghijklmnopqrstuvwxyzABCDE12345"
        with tempfile.TemporaryDirectory() as directory:
            self.write_apk(Path(directory), b"release", token)
            findings = scanner.scan_apks(Path(directory))
            self.assertEqual(findings, ["DashCast.apk :: classes.dex :: telegram bot token"])
            self.assertNotIn(token.decode(), findings[0])

    def test_native_library_credentials_are_scanned(self) -> None:
        token = b"123456789:AAabcdefghijklmnopqrstuvwxyzABCDE12345"
        with tempfile.TemporaryDirectory() as directory:
            with zipfile.ZipFile(Path(directory) / "DashCast.apk", "w") as archive:
                archive.writestr("AndroidManifest.xml", b"release")
                archive.writestr("lib/arm64-v8a/libvendor.so", token)

            self.assertEqual(
                scanner.scan_apks(Path(directory)),
                ["DashCast.apk :: lib/arm64-v8a/libvendor.so :: telegram bot token"],
            )

    def test_duplicate_entry_names_are_scanned_individually(self) -> None:
        token = b"123456789:AAabcdefghijklmnopqrstuvwxyzABCDE12345"
        with tempfile.TemporaryDirectory() as directory:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(Path(directory) / "DashCast.apk", "w") as archive:
                    archive.writestr("AndroidManifest.xml", b"release")
                    archive.writestr("lib/duplicate.so", token)
                    archive.writestr("lib/duplicate.so", b"clean replacement")

            self.assertEqual(
                scanner.scan_apks(Path(directory)),
                ["DashCast.apk :: lib/duplicate.so :: telegram bot token"],
            )

    def test_nonempty_directory_named_entry_is_rejected(self) -> None:
        token = b"123456789:AAabcdefghijklmnopqrstuvwxyzABCDE12345"
        with tempfile.TemporaryDirectory() as directory:
            with zipfile.ZipFile(Path(directory) / "DashCast.apk", "w") as archive:
                archive.writestr("AndroidManifest.xml", b"release")
                archive.writestr("assets/payload/", token)

            self.assertEqual(
                scanner.scan_apks(Path(directory)),
                ["DashCast.apk :: assets/payload/ :: non-empty directory entry"],
            )

    def test_secret_split_across_scan_chunks_is_detected(self) -> None:
        token = b"123456789:AAabcdefghijklmnopqrstuvwxyzABCDE12345"
        padding = b"x" * (scanner.SCAN_CHUNK_BYTES - 10)
        with tempfile.TemporaryDirectory() as directory:
            self.write_apk(Path(directory), b"release", padding + token)

            self.assertEqual(
                scanner.scan_apks(Path(directory)),
                ["DashCast.apk :: classes.dex :: telegram bot token"],
            )

    def test_oversized_apk_is_rejected_before_zip_parsing_or_tools(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "DashCast-v1-release.apk"
            with apk.open("wb") as output:
                output.truncate(scanner.MAX_APK_BYTES + 1)
            runner = Mock()

            self.assertEqual(
                scanner.scan_apks(root),
                [f"{apk.name} :: APK exceeds scan size limit"],
            )
            self.assertEqual(
                scanner.verify_release_contract(root, "v1", "aapt", "apksigner", runner),
                [f"{apk.name} :: APK exceeds scan size limit"],
            )
            runner.assert_not_called()

    def test_main_skips_android_tools_after_archive_rejection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / "DashCast.apk", "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("AndroidManifest.xml", b"release")
                archive.writestr("classes.dex", b"0" * 4096)
            argv = [
                str(SCRIPT),
                str(root),
                "--tag", "v1",
                "--aapt", "/tmp/aapt",
                "--apksigner", "/tmp/apksigner",
            ]
            with (
                patch.object(scanner, "MIN_RATIO_CHECK_BYTES", 1),
                patch.object(scanner, "MAX_COMPRESSION_RATIO", 2),
                patch.object(scanner, "verify_release_contract") as verify,
                patch.object(sys, "argv", argv),
            ):
                self.assertEqual(1, scanner.main())

            verify.assert_not_called()

    def test_entry_count_and_total_expanded_size_are_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / "DashCast.apk", "w") as archive:
                archive.writestr("AndroidManifest.xml", b"release")
                archive.writestr("one", b"1")
                archive.writestr("two", b"2")
            with patch.object(scanner, "MAX_ENTRY_COUNT", 2):
                self.assertEqual(
                    scanner.scan_apks(root),
                    ["DashCast.apk :: archive :: too many entries"],
                )
            with patch.object(scanner, "MAX_TOTAL_UNCOMPRESSED_BYTES", 8):
                self.assertEqual(
                    scanner.scan_apks(root),
                    ["DashCast.apk :: archive :: expanded size exceeds scan limit"],
                )

    def test_entry_count_is_rejected_before_zipfile_materializes_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "DashCast.apk"
            apk.write_bytes(struct.pack(
                "<4s4H2LH",
                scanner.EOCD_SIGNATURE,
                0,
                0,
                scanner.MAX_ENTRY_COUNT + 1,
                scanner.MAX_ENTRY_COUNT + 1,
                0,
                0,
                0,
            ))
            with patch.object(scanner.zipfile, "ZipFile",
                              side_effect=AssertionError("must not parse")):
                findings = scanner.scan_apks(root)

            self.assertEqual(
                findings,
                ["DashCast.apk :: archive :: too many entries"],
            )

    def test_central_directory_size_is_rejected_before_zipfile(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "DashCast.apk"
            apk.write_bytes(struct.pack(
                "<4s4H2LH",
                scanner.EOCD_SIGNATURE,
                0,
                0,
                1,
                1,
                scanner.MAX_CENTRAL_DIRECTORY_BYTES + 1,
                0,
                0,
            ))
            with patch.object(scanner.zipfile, "ZipFile",
                              side_effect=AssertionError("must not parse")):
                findings = scanner.scan_apks(root)

            self.assertEqual(
                findings,
                ["DashCast.apk :: archive :: central directory exceeds scan limit"],
            )

    def test_zip64_offset_sentinel_forces_bounded_zip64_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "DashCast.apk"
            count = scanner.MAX_ENTRY_COUNT + 1
            zip64 = struct.pack(
                "<4sQ2H2L4Q",
                scanner.ZIP64_EOCD_SIGNATURE,
                44,
                45,
                45,
                0,
                0,
                count,
                count,
                0,
                0,
            )
            locator = struct.pack("<4sLQL", scanner.ZIP64_LOCATOR_SIGNATURE, 0, 0, 1)
            eocd = struct.pack(
                "<4s4H2LH",
                scanner.EOCD_SIGNATURE,
                0,
                0,
                1,
                1,
                0,
                0xFFFFFFFF,
                0,
            )
            apk.write_bytes(zip64 + locator + eocd)

            with patch.object(scanner.zipfile, "ZipFile",
                              side_effect=AssertionError("must not parse")):
                findings = scanner.scan_apks(root)

            self.assertEqual(
                findings,
                ["DashCast.apk :: archive :: too many entries"],
            )

    def test_actual_directory_count_is_bounded_before_zipfile(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "DashCast.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("one", b"1")
                archive.writestr("two", b"2")
                archive.writestr("three", b"3")
            raw = bytearray(apk.read_bytes())
            eocd = raw.rfind(scanner.EOCD_SIGNATURE)
            struct.pack_into("<HH", raw, eocd + 8, 1, 1)
            apk.write_bytes(raw)

            with (
                patch.object(scanner, "MAX_ENTRY_COUNT", 2),
                patch.object(scanner.zipfile, "ZipFile",
                             side_effect=AssertionError("must not parse")),
            ):
                findings = scanner.scan_apks(root)

            self.assertEqual(findings, ["DashCast.apk :: archive :: too many entries"])

    def test_declared_directory_count_must_match_actual_records(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "DashCast.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("one", b"1")
                archive.writestr("two", b"2")
            raw = bytearray(apk.read_bytes())
            eocd = raw.rfind(scanner.EOCD_SIGNATURE)
            struct.pack_into("<HH", raw, eocd + 8, 1, 1)
            apk.write_bytes(raw)

            with patch.object(scanner.zipfile, "ZipFile",
                              side_effect=AssertionError("must not parse")):
                findings = scanner.scan_apks(root)

            self.assertEqual(
                findings,
                ["DashCast.apk :: archive :: entry count does not match directory"],
            )

    def test_second_directory_between_declared_directory_and_eocd_is_rejected(self) -> None:
        token = b"123456789:AAabcdefghijklmnopqrstuvwxyzABCDE12345"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "DashCast.apk"
            with zipfile.ZipFile(apk, "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("AndroidManifest.xml", b"release")
                archive.writestr("benign.bin", b"clean")
                archive.writestr("secret.bin", token)
            raw = apk.read_bytes()
            eocd_offset = raw.rfind(scanner.EOCD_SIGNATURE)
            eocd = struct.unpack_from("<4s4H2LH", raw, eocd_offset)
            directory_size, directory_offset = eocd[5], eocd[6]
            records = {}
            position = directory_offset
            while position < directory_offset + directory_size:
                fixed = raw[position:position + 46]
                name_size, extra_size, comment_size = struct.unpack_from(
                    "<3H", fixed, 28
                )
                end = position + 46 + name_size + extra_size + comment_size
                name = raw[position + 46:position + 46 + name_size].decode()
                records[name] = raw[position:end]
                position = end
            secret_directory = (
                records["AndroidManifest.xml"] + records["secret.bin"]
            )
            benign_directory = (
                records["AndroidManifest.xml"] + records["benign.bin"]
            )
            self.assertEqual(len(secret_directory), len(benign_directory))
            prefix = b"X" * len(secret_directory)
            local_entries = prefix + raw[:directory_offset]
            eocd = struct.pack(
                "<4s4H2LH", scanner.EOCD_SIGNATURE,
                0, 0, 2, 2, len(secret_directory), len(local_entries), 0,
            )
            apk.write_bytes(
                local_entries + secret_directory + benign_directory + eocd
            )

            self.assertEqual(
                scanner.scan_apks(root),
                ["DashCast.apk :: archive :: invalid central directory range"],
            )

    def test_single_entry_size_and_compression_ratio_are_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / "DashCast.apk", "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("AndroidManifest.xml", b"release")
                archive.writestr("classes.dex", b"0" * 4096)
            with patch.object(scanner, "MAX_ENTRY_UNCOMPRESSED_BYTES", 2048):
                self.assertEqual(
                    scanner.scan_apks(root),
                    ["DashCast.apk :: classes.dex :: entry exceeds scan size limit"],
                )
            with (
                patch.object(scanner, "MIN_RATIO_CHECK_BYTES", 1),
                patch.object(scanner, "MAX_COMPRESSION_RATIO", 2),
            ):
                self.assertEqual(
                    scanner.scan_apks(root),
                    ["DashCast.apk :: classes.dex :: suspicious compression ratio"],
                )

    def test_lzma_entry_is_rejected_before_decompressor_open(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / "DashCast.apk", "w", zipfile.ZIP_LZMA) as archive:
                archive.writestr("AndroidManifest.xml", b"binary release manifest")

            with patch.object(
                scanner.zipfile.ZipFile, "open",
                side_effect=AssertionError("unsupported decompressor must not run"),
            ):
                findings = scanner.scan_apks(root)

            self.assertEqual(
                findings,
                ["DashCast.apk :: AndroidManifest.xml :: unsupported compression method"],
            )

    def test_github_output_uses_a_report_specific_safe_delimiter(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory) / "github-output"
            previous = os.environ.get("GITHUB_OUTPUT")
            os.environ["GITHUB_OUTPUT"] = str(output_path)
            try:
                scanner.write_github_output(["apk :: entry\nEOR\ncount=0 :: finding"])
            finally:
                if previous is None:
                    os.environ.pop("GITHUB_OUTPUT", None)
                else:
                    os.environ["GITHUB_OUTPUT"] = previous

            lines = output_path.read_text(encoding="utf-8").splitlines()
            self.assertEqual("count=1", lines[0])
            delimiter = lines[1].removeprefix("report<<")
            self.assertTrue(delimiter.startswith("DASHCAST_SCAN_"))
            self.assertEqual(delimiter, lines[-1])
            self.assertNotIn(delimiter, lines[2:-1])

    def test_release_contract_accepts_matching_release_apk(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "DashCast-v1.8.48-beta-release.apk"
            apk.write_bytes(b"apk")
            aapt = root / "aapt"
            signer = root / "apksigner"
            aapt.touch()
            signer.touch()
            runner = Mock(side_effect=[
                scanner.subprocess.CompletedProcess(
                    [], 0,
                    "package: name='com.byd.dashcast' versionCode='638' "
                    "versionName='1.8.48-beta'\napplication: label='DashCast'\n",
                    "",
                ),
                scanner.subprocess.CompletedProcess(
                    [], 0,
                    "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                    "Number of signers: 1\n"
                    f"Signer #1 certificate SHA-256 digest: {scanner.EXPECTED_CERT_SHA256}\n",
                    "",
                ),
            ])

            self.assertEqual(
                scanner.verify_release_contract(
                    root, "v1.8.48-beta", str(aapt), str(signer), runner
                ),
                [],
            )

    def test_release_contract_rejects_debug_wrong_identity_and_signer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "DashCast-v1.8.48-beta-release-debugsigned.apk"
            apk.write_bytes(b"apk")
            aapt = root / "aapt"
            signer = root / "apksigner"
            aapt.touch()
            signer.touch()
            runner = Mock(side_effect=[
                scanner.subprocess.CompletedProcess(
                    [], 0,
                    "package: name='com.example.fake' versionCode='0' versionName='wrong'\n"
                    "application-debuggable\n",
                    "",
                ),
                scanner.subprocess.CompletedProcess(
                    [], 0,
                    "Verified using v2 scheme (APK Signature Scheme v2): false\n"
                    "Signer #1 certificate SHA-256 digest: " + "0" * 64 + "\n",
                    "",
                ),
            ])

            findings = scanner.verify_release_contract(
                root, "v1.8.48-beta", str(aapt), str(signer), runner
            )

            self.assertIn(f"{apk.name} :: unexpected release asset name", findings)
            self.assertIn(f"{apk.name} :: unexpected package id", findings)
            self.assertIn(f"{apk.name} :: versionName does not match release", findings)
            self.assertIn(f"{apk.name} :: invalid versionCode", findings)
            self.assertIn(f"{apk.name} :: manifest is debuggable", findings)
            self.assertIn(f"{apk.name} :: APK Signature Scheme v2 missing", findings)
            self.assertIn(f"{apk.name} :: unexpected signing certificate", findings)

    def test_release_contract_fails_closed_without_android_tools(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "DashCast-v1.8.48-release.apk").write_bytes(b"apk")

            findings = scanner.verify_release_contract(root, "v1.8.48", None, None)

            self.assertIn("release :: aapt unavailable; identity not verified", findings)
            self.assertIn("release :: apksigner unavailable; signature not verified", findings)

    def test_release_contract_rejects_an_extra_untrusted_signer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "DashCast-v1.8.48-release.apk"
            apk.write_bytes(b"apk")
            aapt = root / "aapt"
            signer = root / "apksigner"
            aapt.touch()
            signer.touch()
            runner = Mock(side_effect=[
                scanner.subprocess.CompletedProcess(
                    [], 0,
                    "package: name='com.byd.dashcast' versionCode='638' versionName='1.8.48'\n",
                    "",
                ),
                scanner.subprocess.CompletedProcess(
                    [], 0,
                    "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                    "Number of signers: 2\n"
                    f"Signer #1 certificate SHA-256 digest: {scanner.EXPECTED_CERT_SHA256}\n"
                    "Signer #2 certificate SHA-256 digest: " + "0" * 64 + "\n",
                    "",
                ),
            ])

            findings = scanner.verify_release_contract(
                root, "v1.8.48", str(aapt), str(signer), runner
            )

            self.assertIn(f"{apk.name} :: expected exactly one APK signer", findings)
            self.assertIn(f"{apk.name} :: unexpected signing certificate", findings)

    def test_every_telegram_announcement_scans_assets_before_sending(self) -> None:
        workflows = SCRIPT.parent.parent / "workflows"
        for name in ("telegram-changelog.yml", "telegram-stable-release.yml"):
            source = (workflows / name).read_text(encoding="utf-8")
            scan = source.index("python3 .github/scripts/scan_release_assets.py assets")
            send = source.index("Send changelog to Telegram")
            self.assertLess(scan, send, name)
            self.assertIn('--tag "$TAG"', source[scan:send], name)

    def test_privileged_workflows_never_execute_scanner_from_release_tag(self) -> None:
        workflows = SCRIPT.parent.parent / "workflows"
        for name in (
            "release-secret-scan.yml",
            "telegram-changelog.yml",
            "telegram-stable-release.yml",
        ):
            source = (workflows / name).read_text(encoding="utf-8")
            checkout = source.index("uses: actions/checkout@")
            scanner = source.index("python3 .github/scripts/scan_release_assets.py assets")
            trusted_checkout = source[checkout:scanner]

            self.assertIn("ref: ${{ github.event.repository.default_branch }}", trusted_checkout, name)
            self.assertIn("persist-credentials: false", trusted_checkout, name)
            self.assertNotIn("ref: ${{ github.event.release.tag_name }}", trusted_checkout, name)

    def test_release_scanner_rechecks_current_ota_assets_after_publication(self) -> None:
        workflow = (
            SCRIPT.parent.parent / "workflows" / "release-secret-scan.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("types: [published, edited, released]", workflow)
        self.assertIn("schedule:", workflow)
        self.assertIn("rescan-current-ota-assets:", workflow)
        self.assertIn("gh api --paginate", workflow)
        self.assertNotIn("--slurp", workflow)
        self.assertIn("Could not enumerate published releases", workflow)
        self.assertIn('if [[ "$stable" == "v1.7.0" ]]', workflow)
        self.assertIn("[.prerelease, .tag_name]", workflow)
        self.assertIn("(alpha|beta|rc)", workflow)
        self.assertIn("sort -V", workflow)
        self.assertIn("scan_release_assets.py " + "\\", workflow)
        self.assertIn('"$asset_dir" --tag "$tag"', workflow)
        self.assertIn('gh release edit "$tag" --repo "$REPO" --draft', workflow)

    def test_every_external_action_is_pinned_to_an_immutable_commit(self) -> None:
        workflows = SCRIPT.parent.parent / "workflows"
        uses = re.compile(r"^\s*uses:\s*([^\s@]+)@([^\s#]+)", re.MULTILINE)
        found = 0
        for path in workflows.glob("*.yml"):
            for action, revision in uses.findall(path.read_text(encoding="utf-8")):
                found += 1
                self.assertRegex(revision, r"^[0-9a-f]{40}$", f"{path.name}: {action}")
        self.assertGreater(found, 0)


if __name__ == "__main__":
    unittest.main()