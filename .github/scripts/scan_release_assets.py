#!/usr/bin/env python3

import argparse
import os
import re
import secrets
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
    args = parser.parse_args()

    findings = scan_apks(args.asset_dir)
    for finding in findings:
        print(f"FOUND: {finding}")
    write_github_output(findings)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())