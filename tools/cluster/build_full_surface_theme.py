#!/usr/bin/env python3
"""Build the fixed night-only physical cluster proof; never execute target QML."""
import argparse
import difflib
import json
import struct
import zlib
from pathlib import Path

import build_bandless_theme as source


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts/cluster_full_surface_test"
ASSETS = ROOT / "app/src/main/assets/cluster_live"
CONDITION = (
    b"DataSource.powerGear === DataSource.POWER_GEAR_ON && "
    b"DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN && DataSource.dayNightMode === 0x02"
)
OPACITY = b"opacity: " + CONDITION + b" ? 0 : 1\r\n"
THEME = (
    b"\r\n    // DASHCAST_FULL_SURFACE_TEST_BEGIN\r\n"
    b"    Rectangle {\r\n"
    b"        id: dashCastFullSurfaceTestId\r\n"
    b"        anchors.fill: parent\r\n"
    b"        z: CustomStyle.bgZ\r\n"
    b"        visible: " + CONDITION + b"\r\n"
    b"        color: \"#061018\"\r\n"
    b"        border.color: \"#00cddd\"; border.width: 6\r\n"
    b"        Rectangle { x: 0; y: 0; width: 120; height: 12; color: \"#00cddd\" }\r\n"
    b"        Rectangle { x: parent.width - width; y: 0; width: 120; height: 12; color: \"#00cddd\" }\r\n"
    b"        Rectangle { x: 0; y: parent.height - height; width: 120; height: 12; color: \"#00cddd\" }\r\n"
    b"        Rectangle { x: parent.width - width; y: parent.height - height; width: 120; height: 12; color: \"#00cddd\" }\r\n"
    b"        Text { x: 24; y: 318; text: \"DASHCAST TEST\"; color: \"#00cddd\"; font.pixelSize: 28 }\r\n"
    b"    }\r\n"
    b"    // DASHCAST_FULL_SURFACE_TEST_END\r\n"
)


def patched_root(path, original):
    patched = source.patch_root(path, original)
    if patched.count(source.OPACITY) != 3:
        raise ValueError("Unexpected decoration patch")
    patched = patched.replace(source.OPACITY, OPACITY)
    anchor = b"    id:rootItemId\r\n"
    if patched.count(anchor) != 1:
        raise ValueError("Root anchor is not unique")
    return patched.replace(anchor, anchor + THEME, 1)


def build_artifacts(stock, originals):
    # Validate all stock/root identities before producing any output.
    source.build_artifacts(stock, originals)
    rows = source.rcc.decode(stock)
    files = {}
    manifest = {
        "schema": 1, "variant": "full-surface-test",
        "purpose": "night-only-full-surface-physical-proof",
        "vehicle_validated": False, "minimum_daemon_protocol": 32,
        "stock_sha256": source.STOCK_SHA,
        "condition": CONDITION.decode("ascii"), "theme_block": THEME.decode("ascii"),
        "modified_ids": [identifier for identifier, _ in source.DECORATIONS],
        "resources": {}, "roots": {},
    }
    for path, country, language, flags, raw, value in rows:
        manifest["resources"][path] = {
            "sha256": source.sha(value), "stored_sha256": source.sha(raw),
            "country": country, "language": language, "flags": flags,
        }
    for path in source.ORIGINALS:
        original = originals[path]
        patched = patched_root(path, original)
        patch = "".join(difflib.unified_diff(
            original.decode("utf-8").splitlines(keepends=True),
            patched.decode("utf-8").splitlines(keepends=True),
            fromfile=f"original/{path}", tofile=f"patched/{path}",
        )).encode("utf-8")
        files[f"patched/{path}"] = patched
        files[f"patches/{path}.patch"] = patch
        manifest["roots"][path] = {
            "original_sha256": source.sha(original), "patched_sha256": source.sha(patched),
            "patch_sha256": source.sha(patch), "original_size": len(original), "patched_size": len(patched),
        }
        raw = struct.pack(">I", len(patched)) + zlib.compress(patched, 9)
        rows.append((path, 0, 1, 1, raw, patched))
    carrier = source.rcc.build(rows)
    if len(carrier) >= 4 * 1024 * 1024:
        raise ValueError("Oversized carrier")
    manifest["carrier_sha256"] = source.sha(carrier)
    manifest["carrier_size"] = len(carrier)
    files["full_surface_test.rcc"] = carrier
    files["manifest.json"] = (json.dumps(manifest, indent=2, ensure_ascii=True) + "\n").encode()
    files["SHA256SUMS"] = "".join(
        f"{source.sha(data)}  {path}\n" for path, data in sorted(files.items())
    ).encode("ascii")
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--roots", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    originals = {
        path: (args.roots / "extracted" / theme / path).read_bytes()
        for path, (theme, _) in source.ORIGINALS.items()
    }
    files = build_artifacts(args.stock.read_bytes(), originals)
    outputs = {OUT / name: data for name, data in files.items()}
    outputs[ASSETS / "full_surface_test.rcc"] = files["full_surface_test.rcc"]
    outputs[ASSETS / "full_surface_test_manifest.json"] = files["manifest.json"]
    for target, data in outputs.items():
        if args.check:
            if target.read_bytes() != data:
                raise ValueError(f"Output differs: {target}")
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print(f"86 resources; night-only full-surface proof; SHA256 {source.sha(files['full_surface_test.rcc'])}")


if __name__ == "__main__":
    main()
