#!/usr/bin/env python3
"""Full-screen map profile for the 21f7735c cohort: hide only OEM decoration, keep every BYD indicator."""
import argparse
import difflib
import hashlib
import json
import struct
import zlib
from pathlib import Path

import build_bandless_theme as bandless
import build_live_cohort_marker as cohort
import build_live_marker as rcc

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts/cluster_fullscreen_instruments"
ASSETS = ROOT / "app/src/main/assets/cluster_live"
CARRIER = "fullscreen-instruments-offline.rcc"
ASSET = "fullscreen_instruments_21f7735c.rcc"
ASSET_MANIFEST = "fullscreen_instruments_21f7735c_manifest.json"
MINIMUM_PROTOCOL = 47
MAX_CARRIER = 4 * 1024 * 1024
DECORATIONS = bandless.DECORATIONS
CONDITION = ("DataSource.naviType === 0x04 && DataSource.screenSize === 0x02 && DataSource.powerGear === 0x03 && "
             "DataSource.playAnim === true && rootItemId.width === 1920 && rootItemId.height === 720")
OPACITY = b"opacity: " + CONDITION.encode("ascii") + b" ? 0 : 1\r\n"
KEPT = {
    "warningLayoutId": "all WarinLightLayout telltales (z 100)",
    "alwyasDisplayId": "gear, drive mode, speed, power, range, ODO and nested low-battery lamps",
    "adasTipItemId": "ADAS text messages (z 111)",
    "adasTextTip3R1V": "ADAS 3R1V text messages (z 111)",
    "adasCarDistanceItemId": "ACC time-gap popup",
    "centerContainerId": "Center warnings and messages",
    "presentationLoaderId": "projected application (z -3), revealed by the transparent decoration",
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def patch_root(path, original):
    if path not in cohort.ORIGINALS or sha(original) != cohort.ORIGINALS[path][1]:
        raise ValueError(f"Unexpected original OEM root: {path}")
    patched = original
    for _, anchor in DECORATIONS:
        if patched.count(anchor) != 1:
            raise ValueError("OEM decoration anchor is not unique")
        indent = anchor[:len(anchor) - len(anchor.lstrip())]
        patched = patched.replace(anchor, anchor + indent + OPACITY, 1)
    return patched


def build_artifacts(stock, originals):
    if sha(stock) != cohort.STOCK_SHA or set(originals) != set(cohort.ORIGINALS):
        raise ValueError("Exact 21f7735c stock translation RCC and both cohort roots required")
    rows = rcc.decode(stock)
    if len(rows) != 84 or any(not row[0].startswith("common/language/") for row in rows):
        raise ValueError("Unexpected stock locale inventory")
    manifest = {
        "schema": 1,
        "variant": "fullscreen-instruments-21f7735c",
        "purpose": "fullscreen-map-with-byd-indicators",
        "cohort": "21f7735c",
        "vehicle_validated": False,
        "minimum_daemon_protocol": MINIMUM_PROTOCOL,
        "application_rect": {"x": 0, "y": 0, "width": 1920, "height": 720},
        "native_instruments_hidden": False,
        "native_alerts_hidden": False,
        "stock_sha256": cohort.STOCK_SHA,
        "condition": CONDITION,
        "modified_ids": [identifier for identifier, _ in DECORATIONS],
        "kept_unchanged": KEPT,
        "preservation": {
            "notes": [
                "Only three decorative objects gain an opacity binding; no warning lamp is inside them.",
                "The OEM writes only their visible property, so its writes cannot restore the opacity.",
                "Outside the strict condition the factor is 1, the stock default; unknown values keep stock.",
                "Projection size, legibility over the map and physical composition are unvalidated.",
            ],
        },
        "resources": {},
        "roots": {},
    }
    files = {}
    for path, country, language, flags, raw, value in rows:
        manifest["resources"][path] = {"sha256": sha(value), "stored_sha256": sha(raw),
                                       "country": country, "language": language, "flags": flags}
    for path in cohort.ORIGINALS:
        original = originals[path]
        patched = patch_root(path, original)
        patch = "".join(difflib.unified_diff(
            original.decode("utf-8").splitlines(keepends=True),
            patched.decode("utf-8").splitlines(keepends=True),
            fromfile=f"original/{path}", tofile=f"patched/{path}",
        )).encode("utf-8")
        files[f"patched/{path}"] = patched
        files[f"patches/{path}.patch"] = patch
        manifest["roots"][path] = {
            "original_sha256": sha(original), "original_size": len(original),
            "patched_sha256": sha(patched), "patched_size": len(patched),
            "patch_sha256": sha(patch), "added_lines": len(DECORATIONS),
        }
        rows.append((path, 0, 1, 1, struct.pack(">I", len(patched)) + zlib.compress(patched, 9), patched))
    carrier = rcc.build(rows)
    if len(carrier) >= MAX_CARRIER:
        raise ValueError("Oversized full-screen carrier")
    if sorted(rcc.decode(carrier)) != sorted(rows):
        raise ValueError("Carrier does not round-trip")
    manifest["carrier_sha256"] = sha(carrier)
    manifest["carrier_size"] = len(carrier)
    files[CARRIER] = carrier
    files["manifest.json"] = (json.dumps(manifest, indent=2, ensure_ascii=True) + "\n").encode()
    files["SHA256SUMS"] = "".join(f"{sha(data)}  {path}\n" for path, data in sorted(files.items())).encode("ascii")
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, required=True, help="21f7735c commonTranslate.rcc")
    parser.add_argument("--roots", type=Path, required=True, help="Directory containing extracted/theme{1,2}")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    originals = {path: (args.roots / "extracted" / theme / path).read_bytes()
                 for path, (theme, _) in cohort.ORIGINALS.items()}
    files = build_artifacts(args.stock.read_bytes(), originals)
    outputs = {OUT / name: data for name, data in files.items()}
    outputs[ASSETS / ASSET] = files[CARRIER]
    outputs[ASSETS / ASSET_MANIFEST] = files["manifest.json"]
    if args.check:
        actual = {path.relative_to(OUT).as_posix() for path in OUT.rglob("*") if path.is_file()}
        if actual != set(files):
            raise ValueError("Output inventory differs")
        for target, data in outputs.items():
            if target.read_bytes() != data:
                raise ValueError(f"Output differs: {target}")
    else:
        for target, data in outputs.items():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print(f"84 stock locales + 2 roots; {len(files[CARRIER])} bytes; SHA256 {sha(files[CARRIER])}")


if __name__ == "__main__":
    main()
