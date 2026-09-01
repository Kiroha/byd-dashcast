#!/usr/bin/env python3

import argparse
import os
import re
import secrets
import shutil
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

EXPECTED_PACKAGE = "com.byd.dashcast"
EXPECTED_CERT_SHA256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"


def scan_apks(asset_dir: Path) -> list[str]:
    findings: list[str] = []
    apks = sorted(asset_dir.glob("*.apk"))
    if not apks:
        return ["release :: no APK asset"]

    for apk in apks:
        apk_name = single_line(apk.name)
        try:
            with zipfile.ZipFile(apk) as archive:
                manifest_found = False
                for entry in archive.infolist():
                    if entry.is_dir():
                        continue
                    name = entry.filename
                    try:
                        with archive.open(entry) as content:
                            blob = content.read()
                    except (KeyError, OSError, RuntimeError, zipfile.BadZipFile):
                        findings.append(f"{apk_name} :: {single_line(name)} :: unreadable entry")
                        continue
                    for label, pattern in PATTERNS.items():
                        if pattern.search(blob):
                            findings.append(f"{apk_name} :: {single_line(name)} :: {label}")

                    if name == "AndroidManifest.xml":
                        manifest_found = True
                        needles = (b"debuggable", "debuggable".encode("utf-16-le"))
                        if any(needle in blob for needle in needles):
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