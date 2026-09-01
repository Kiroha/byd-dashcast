#!/usr/bin/env python3

import argparse
import os
import re
import secrets
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path


PATTERNS = {
    "telegram bot token": re.compile(rb"[0-9]{8,12}:AA[A-Za-z0-9_-]{30,}"),
    "azure SAS signature": re.compile(rb"sig=[A-Za-z0-9%+/=]{40,}"),
    "openai key": re.compile(rb"sk-[A-Za-z0-9]{32,}"),
    "github token": re.compile(rb"gh[pousr]_[A-Za-z0-9]{30,}"),
    "aws access key": re.compile(rb"AKIA[0-9A-Z]{16}"),
    "slack token": re.compile(rb"xox[abprs]-[A-Za-z0-9-]{10,}"),
}

# Minimum byte length accepted by each regex above. Retaining one byte less than the longest
# minimum is sufficient: a match beginning earlier was already complete in the previous window.
PATTERN_MIN_BYTES = {
    "telegram bot token": 41,
    "azure SAS signature": 44,
    "openai key": 35,
    "github token": 34,
    "aws access key": 20,
    "slack token": 15,
}

MAX_APK_BYTES = 256 * 1024 * 1024
MAX_ENTRY_COUNT = 10_000
MAX_CENTRAL_DIRECTORY_BYTES = 64 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 512 * 1024 * 1024
MAX_ENTRY_UNCOMPRESSED_BYTES = 256 * 1024 * 1024
MAX_COMPRESSION_RATIO = 200
MIN_RATIO_CHECK_BYTES = 1024 * 1024
SCAN_CHUNK_BYTES = 64 * 1024
SCAN_OVERLAP_BYTES = max(PATTERN_MIN_BYTES.values()) - 1

EXPECTED_PACKAGE = "com.byd.dashcast"
EXPECTED_CERT_SHA256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"

EOCD_SIGNATURE = b"PK\x05\x06"
ZIP64_EOCD_SIGNATURE = b"PK\x06\x06"
ZIP64_LOCATOR_SIGNATURE = b"PK\x06\x07"
EOCD_MAX_BYTES = 22 + 0xFFFF


def archive_directory_finding(apk: Path) -> str | None:
    """Reject oversized central-directory metadata before ZipFile materializes it."""
    try:
        size = apk.stat().st_size
        if size < 22:
            return "unreadable APK"
        with apk.open("rb") as source:
            tail_size = min(size, EOCD_MAX_BYTES)
            source.seek(size - tail_size)
            tail = source.read(tail_size)
            position = len(tail)
            eocd = None
            while True:
                position = tail.rfind(EOCD_SIGNATURE, 0, position)
                if position < 0:
                    break
                if position + 22 <= len(tail):
                    candidate = struct.unpack_from("<4s4H2LH", tail, position)
                    if position + 22 + candidate[-1] == len(tail):
                        eocd = candidate
                        break
                if position == 0:
                    break
            if eocd is None:
                return "unreadable APK"

            _, disk, directory_disk, disk_entries, entries, directory_size, _, _ = eocd
            if disk != 0 or directory_disk != 0 or disk_entries != entries:
                return "archive :: multi-disk ZIP is unsupported"

            if entries == 0xFFFF or directory_size == 0xFFFFFFFF:
                eocd_offset = size - tail_size + position
                if eocd_offset < 20:
                    return "archive :: malformed ZIP64 directory"
                source.seek(eocd_offset - 20)
                locator = source.read(20)
                if len(locator) != 20:
                    return "archive :: malformed ZIP64 directory"
                locator_sig, zip64_disk, zip64_offset, total_disks = struct.unpack(
                    "<4sLQL", locator
                )
                if (locator_sig != ZIP64_LOCATOR_SIGNATURE or zip64_disk != 0
                        or total_disks != 1 or zip64_offset > size - 56):
                    return "archive :: malformed ZIP64 directory"
                source.seek(zip64_offset)
                record = source.read(56)
                if len(record) != 56:
                    return "archive :: malformed ZIP64 directory"
                values = struct.unpack("<4sQ2H2L4Q", record)
                if values[0] != ZIP64_EOCD_SIGNATURE or values[1] < 44:
                    return "archive :: malformed ZIP64 directory"
                disk, directory_disk = values[4], values[5]
                disk_entries, entries = values[6], values[7]
                directory_size = values[8]
                if disk != 0 or directory_disk != 0 or disk_entries != entries:
                    return "archive :: multi-disk ZIP is unsupported"

            if entries > MAX_ENTRY_COUNT:
                return "archive :: too many entries"
            if directory_size > MAX_CENTRAL_DIRECTORY_BYTES:
                return "archive :: central directory exceeds scan limit"
    except (OSError, OverflowError, struct.error):
        return "unreadable APK"
    return None


def apk_size_finding(apk: Path) -> str | None:
    try:
        return "APK exceeds scan size limit" if apk.stat().st_size > MAX_APK_BYTES else None
    except OSError:
        return "unreadable APK"


def suspicious_compression(entry: zipfile.ZipInfo) -> bool:
    if entry.file_size <= 0:
        return False
    if entry.compress_size <= 0:
        return True
    return (
        entry.file_size >= MIN_RATIO_CHECK_BYTES
        and entry.file_size > entry.compress_size * MAX_COMPRESSION_RATIO
    )


def scan_entry(archive: zipfile.ZipFile, entry: zipfile.ZipInfo) -> tuple[set[str], bool]:
    labels: set[str] = set()
    manifest_debuggable = False
    tail = b""
    read_bytes = 0
    needles = (b"debuggable", "debuggable".encode("utf-16-le"))
    with archive.open(entry) as content:
        while True:
            chunk = content.read(SCAN_CHUNK_BYTES)
            if not chunk:
                break
            read_bytes += len(chunk)
            if read_bytes > entry.file_size or read_bytes > MAX_ENTRY_UNCOMPRESSED_BYTES:
                raise ValueError("entry expanded beyond declared or allowed size")
            window = tail + chunk
            for label, pattern in PATTERNS.items():
                if label not in labels and pattern.search(window):
                    labels.add(label)
            if entry.filename == "AndroidManifest.xml" and any(
                needle in window for needle in needles
            ):
                manifest_debuggable = True
            tail = window[-SCAN_OVERLAP_BYTES:]
    if read_bytes != entry.file_size:
        raise ValueError("entry size does not match central directory")
    return labels, manifest_debuggable


def scan_apks(asset_dir: Path) -> list[str]:
    findings: list[str] = []
    apks = sorted(asset_dir.glob("*.apk"))
    if not apks:
        return ["release :: no APK asset"]

    for apk in apks:
        apk_name = single_line(apk.name)
        size_finding = apk_size_finding(apk)
        if size_finding:
            findings.append(f"{apk_name} :: {size_finding}")
            continue
        directory_finding = archive_directory_finding(apk)
        if directory_finding:
            findings.append(f"{apk_name} :: {directory_finding}")
            continue
        try:
            with zipfile.ZipFile(apk) as archive:
                entries = archive.infolist()
                if len(entries) > MAX_ENTRY_COUNT:
                    findings.append(f"{apk_name} :: archive :: too many entries")
                    continue
                total_uncompressed = sum(
                    entry.file_size
                    for entry in entries
                    if not (
                        entry.is_dir()
                        and entry.file_size == 0
                        and entry.compress_size == 0
                    )
                )
                if total_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES:
                    findings.append(
                        f"{apk_name} :: archive :: expanded size exceeds scan limit"
                    )
                    continue
                manifest_found = False
                for entry in entries:
                    if entry.is_dir():
                        if entry.file_size != 0 or entry.compress_size != 0:
                            findings.append(
                                f"{apk_name} :: {single_line(entry.filename)} :: "
                                "non-empty directory entry"
                            )
                        continue
                    name = entry.filename
                    if name == "AndroidManifest.xml":
                        manifest_found = True
                    if entry.file_size > MAX_ENTRY_UNCOMPRESSED_BYTES:
                        findings.append(
                            f"{apk_name} :: {single_line(name)} :: entry exceeds scan size limit"
                        )
                        continue
                    if suspicious_compression(entry):
                        findings.append(
                            f"{apk_name} :: {single_line(name)} :: suspicious compression ratio"
                        )
                        continue
                    try:
                        labels, manifest_debuggable = scan_entry(archive, entry)
                    except (
                        KeyError,
                        OSError,
                        RuntimeError,
                        ValueError,
                        NotImplementedError,
                        zipfile.BadZipFile,
                    ):
                        findings.append(f"{apk_name} :: {single_line(name)} :: unreadable entry")
                        continue
                    for label in labels:
                        findings.append(f"{apk_name} :: {single_line(name)} :: {label}")

                    if manifest_debuggable:
                        findings.append(f"{apk_name} :: manifest :: possibly debuggable")
                if not manifest_found:
                    findings.append(f"{apk_name} :: manifest :: missing")
        except (OSError, zipfile.BadZipFile):
            findings.append(f"{apk_name} :: unreadable APK")

    return sorted(set(findings))


def verify_release_contract(
    asset_dir: Path,
    tag: str | None,
    aapt: str | None,
    apksigner: str | None,
    runner=subprocess.run,
) -> list[str]:
    apks = sorted(asset_dir.glob("*.apk"))
    if len(apks) != 1:
        return [f"release :: expected exactly one APK asset, found {len(apks)}"]

    apk = apks[0]
    size_finding = apk_size_finding(apk)
    if size_finding:
        return [f"{single_line(apk.name)} :: {size_finding}"]
    version = (tag or "").removeprefix("v")
    if not version:
        match = re.fullmatch(r"DashCast-v(.+)-release\.apk", apk.name)
        version = match.group(1) if match else ""
    expected_name = f"DashCast-v{version}-release.apk" if version else ""
    findings: list[str] = []
    if not expected_name or apk.name != expected_name:
        findings.append(f"{single_line(apk.name)} :: unexpected release asset name")

    if not aapt or not Path(aapt).is_file():
        findings.append("release :: aapt unavailable; identity not verified")
    else:
        result = runner(
            [aapt, "dump", "badging", str(apk)],
            capture_output=True,
            text=True,
            check=False,
        )
        badging = result.stdout or ""
        package = re.search(
            r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
            badging,
            re.MULTILINE,
        )
        if result.returncode != 0 or package is None:
            findings.append(f"{single_line(apk.name)} :: unreadable package metadata")
        else:
            package_name, version_code, version_name = package.groups()
            if package_name != EXPECTED_PACKAGE:
                findings.append(f"{single_line(apk.name)} :: unexpected package id")
            if version_name != version:
                findings.append(f"{single_line(apk.name)} :: versionName does not match release")
            if not version_code.isdigit() or int(version_code) <= 0:
                findings.append(f"{single_line(apk.name)} :: invalid versionCode")
        if re.search(r"^application-debuggable(?:'|$)", badging, re.MULTILINE):
            findings.append(f"{single_line(apk.name)} :: manifest is debuggable")

    if not apksigner or not Path(apksigner).is_file():
        findings.append("release :: apksigner unavailable; signature not verified")
    else:
        result = runner(
            [apksigner, "verify", "--verbose", "--print-certs", str(apk)],
            capture_output=True,
            text=True,
            check=False,
        )
        signature = (result.stdout or "") + "\n" + (result.stderr or "")
        if result.returncode != 0:
            findings.append(f"{single_line(apk.name)} :: APK signature verification failed")
        if "Verified using v2 scheme (APK Signature Scheme v2): true" not in signature:
            findings.append(f"{single_line(apk.name)} :: APK Signature Scheme v2 missing")
        signer_count = re.search(r"Number of signers:\s*(\d+)", signature)
        certs = re.findall(
            r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", signature
        )
        if signer_count is None or signer_count.group(1) != "1" or len(certs) != 1:
            findings.append(f"{single_line(apk.name)} :: expected exactly one APK signer")
        if len(certs) != 1 or certs[0].lower() != EXPECTED_CERT_SHA256:
            findings.append(f"{single_line(apk.name)} :: unexpected signing certificate")

    return sorted(set(findings))


def find_android_tool(name: str) -> str | None:
    direct = shutil.which(name)
    if direct:
        return direct
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(variable)
        if not root:
            continue
        candidates = list((Path(root) / "build-tools").glob(f"*/{name}"))
        if candidates:
            return str(max(candidates, key=lambda path: version_key(path.parent.name)))
    return None


def version_key(value: str) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", value))


def single_line(value: str) -> str:
    return re.sub(r"[\x00\r\n]", "?", value)


def write_github_output(findings: list[str]) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        return
    report = "\n".join(findings)
    delimiter = f"DASHCAST_SCAN_{secrets.token_hex(16)}"
    while delimiter in report:
        delimiter = f"DASHCAST_SCAN_{secrets.token_hex(16)}"
    with open(output_path, "a", encoding="utf-8") as output:
        output.write(f"count={len(findings)}\n")
        output.write(f"report<<{delimiter}\n{report}\n{delimiter}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Reject unsafe GitHub release APK assets")
    parser.add_argument("asset_dir", type=Path)
    parser.add_argument("--tag")
    parser.add_argument("--aapt")
    parser.add_argument("--apksigner")
    args = parser.parse_args()

    findings = scan_apks(args.asset_dir)
    # Never hand an already-rejected untrusted archive to native Android tools. Their manifest and
    # signature parsers have no scanner resource budget and may inflate the exact bomb rejected
    # above before this workflow gets a chance to quarantine the release.
    if not findings:
        findings.extend(verify_release_contract(
            args.asset_dir,
            args.tag,
            args.aapt or find_android_tool("aapt"),
            args.apksigner or find_android_tool("apksigner"),
        ))
    findings = sorted(set(findings))
    for finding in findings:
        print(f"FOUND: {finding}")
    write_github_output(findings)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())