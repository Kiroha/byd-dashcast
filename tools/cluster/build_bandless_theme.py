#!/usr/bin/env python3
"""Build an offline-only, identity-pinned OEM decoration experiment; execute no QML."""
import argparse
import difflib
import hashlib
import json
import struct
import zlib
from pathlib import Path

import build_live_marker as rcc


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts/cluster_bandless"
STOCK_SHA = "5a0f1c39d0921752e863978d85a9f5bc0e0e75f5bd8b5fefa0b0af10624669c8"
ORIGINALS = {
    "qml/cluster.qml": (
        "theme1", "0fd1a95f4ec754aa29f53aad5ec540a87c07a4e6a725f09471433502284f6fdb"
    ),
    "qml10_25/cluster.qml": (
        "theme2", "62edd3cbdc59196614c6c69bb7dd4463b9f4bea2539e2a5361be443dbefdc82f"
    ),
}
DECORATIONS = (
    ("bgImageContainerId", b"        id: bgImageContainerId\r\n"),
    ("headLineId", b"            id: headLineId\r\n"),
    ("driveRangeInfoBgId", b"                id:driveRangeInfoBgId\r\n"),
)
OPACITY = (
    b"opacity: DataSource.powerGear === DataSource.POWER_GEAR_ON && "
    b"DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? 0 : 1\r\n"
)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def patch_root(path, original):
    if path not in ORIGINALS or sha(original) != ORIGINALS[path][1]:
        raise ValueError(f"Unexpected original OEM root: {path}")
    patched = original
    for _, anchor in DECORATIONS:
        if patched.count(anchor) != 1:
            raise ValueError("OEM decoration anchor is not unique")
        indent = anchor[:len(anchor) - len(anchor.lstrip())]
        patched = patched.replace(anchor, anchor + indent + OPACITY, 1)
    return patched


def build_artifacts(stock, originals):
    if sha(stock) != STOCK_SHA:
        raise ValueError("Unexpected stock translation RCC")
    if set(originals) != set(ORIGINALS):
        raise ValueError("Both exact OEM roots are required")
    rows = rcc.decode(stock)
    if len(rows) != 84 or any(not row[0].startswith("common/language/") for row in rows):
        raise ValueError("Unexpected stock locale inventory")
    manifest = {
        "schema": 1,
        "purpose": "offline-oem-band-decoration-proof",
        "vehicle_validated": False,
        "diag_loadable": False,
        "stock_sha256": STOCK_SHA,
        "opacity_condition": OPACITY.decode("ascii").strip(),
        "modified_ids": [identifier for identifier, _ in DECORATIONS],
        "resources": {},
        "roots": {},
        "preservation": {
            "common_main_included": False,
            "handshake_modified": False,
            "functional_items_modified": False,
            "native_calls_added": False,
            "runtime_admission_modified": False,
            "notes": [
                "ON + Full removes all bgImageContainerId decoration, including terrainImageId.",
                "All OEM indicators, warning objects, their parents and bindings remain.",
                "Outside ON + Full, each added opacity evaluates to the stock default 1.",
                "Readability, projection loss and physical display composition are unvalidated.",
            ],
        },
        "evidence": {
            "firmware_label": "13.1.33.2602030.1",
            "identity_basis": "hash-pinned disk corpus; user confirms same cluster",
            "runtime_identity_remeasured": False,
            "source_analysis": [
                "REA-Cluster-FullSurface-20261020/rea-fullsurface-reused-native-evidence.json",
                "REA-Cluster-FullSurface-20261020/current_background_mask_evidence.json",
                "analysis-rea-20261022/assets-investigation/ADDENDUM_TOP_BOTTOM_OEM_BANDS.txt",
            ],
        },
    }
    files = {}
    for path, country, language, flags, raw, value in rows:
        manifest["resources"][path] = {
            "sha256": sha(value), "stored_sha256": sha(raw),
            "country": country, "language": language, "flags": flags,
        }
    for path in ORIGINALS:
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
        raw = struct.pack(">I", len(patched)) + zlib.compress(patched, 9)
        rows.append((path, 0, 1, 1, raw, patched))
    carrier = rcc.build(rows)
    if len(carrier) >= 4 * 1024 * 1024:
        raise ValueError("Oversized offline carrier")
    files["bandless-offline.rcc"] = carrier
    manifest["carrier_sha256"] = sha(carrier)
    manifest["carrier_size"] = len(carrier)
    files["manifest.json"] = (json.dumps(manifest, indent=2, ensure_ascii=True) + "\n").encode()
    files["SHA256SUMS"] = "".join(
        f"{sha(data)}  {path}\n" for path, data in sorted(files.items())
    ).encode("ascii")
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--roots", type=Path, required=True,
                        help="REA FullSurface directory containing extracted/theme{1,2}")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    originals = {
        path: (args.roots / "extracted" / theme / path).read_bytes()
        for path, (theme, _) in ORIGINALS.items()
    }
    files = build_artifacts(args.stock.read_bytes(), originals)
    if args.check:
        actual_paths = {str(path.relative_to(OUT)) for path in OUT.rglob("*") if path.is_file()}
        if actual_paths != set(files):
            raise ValueError("Offline output inventory differs")
        for name, data in files.items():
            if (OUT / name).read_bytes() != data:
                raise ValueError(f"Offline output differs: {name}")
    else:
        for name, data in files.items():
            target = OUT / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print(f"Offline only: 84 stock locales + 2 roots; SHA256 {sha(files['bandless-offline.rcc'])}")


if __name__ == "__main__":
    main()
