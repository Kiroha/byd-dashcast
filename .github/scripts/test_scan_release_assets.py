#!/usr/bin/env python3

import importlib.util
import os
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path


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

    def test_every_telegram_announcement_scans_assets_before_sending(self) -> None:
        workflows = SCRIPT.parent.parent / "workflows"
        for name in ("telegram-changelog.yml", "telegram-stable-release.yml"):
            source = (workflows / name).read_text(encoding="utf-8")
            scan = source.index("python3 .github/scripts/scan_release_assets.py assets")
            send = source.index("Send changelog to Telegram")
            self.assertLess(scan, send, name)

    def test_privileged_workflows_never_execute_scanner_from_release_tag(self) -> None:
        workflows = SCRIPT.parent.parent / "workflows"
        for name in (
            "release-secret-scan.yml",
            "telegram-changelog.yml",
            "telegram-stable-release.yml",
        ):
            source = (workflows / name).read_text(encoding="utf-8")
            checkout = source.index("uses: actions/checkout@v4")
            scanner = source.index("python3 .github/scripts/scan_release_assets.py assets")
            trusted_checkout = source[checkout:scanner]

            self.assertIn("ref: ${{ github.event.repository.default_branch }}", trusted_checkout, name)
            self.assertIn("persist-credentials: false", trusted_checkout, name)
            self.assertNotIn("ref: ${{ github.event.release.tag_name }}", trusted_checkout, name)


if __name__ == "__main__":
    unittest.main()