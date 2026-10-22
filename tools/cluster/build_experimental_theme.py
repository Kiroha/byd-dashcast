#!/usr/bin/env python3
"""Build the local-only fixture using standard Qt rcc; no vehicle transport."""
import argparse
import hashlib
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/experimental_theme"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rcc", default="rcc", help="Qt 5 rcc executable")
    args = parser.parse_args()
    subprocess.run([args.rcc, "--binary", "--format-version", "1", "--no-compress",
                    "--output", "theme.rcc", "theme.qrc"], cwd=ASSETS, check=True)
    data = (ASSETS / "theme.rcc").read_bytes()
    assert data[:8] == b"qres\x00\x00\x00\x01", "Expected RCC format 1"
    manifest = "schema=1\nversion=0.1.0\nentry=:/dashcast_experimental/Main.qml\n"
    for filename in ("Main.qml", "theme.qrc", "theme.rcc"):
        payload = (ASSETS / filename).read_bytes()
        manifest += f"{filename}.size={len(payload)}\n{filename}.sha256={hashlib.sha256(payload).hexdigest()}\n"
    (ASSETS / "manifest.properties").write_text(manifest, encoding="ascii")
    print(f"Built {len(data)} bytes; local fixture only, no runtime/OEM validation")


if __name__ == "__main__":
    main()
