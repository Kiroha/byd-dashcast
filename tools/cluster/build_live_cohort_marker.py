#!/usr/bin/env python3
"""Build only the exact 21f7735c cohort; preserve its complete stock resources."""
import argparse
import json
import struct
import zlib
from pathlib import Path

import build_corner_marker as corner
import build_live_marker as rcc

ASSETS = Path(__file__).resolve().parents[2] / "app/src/main/assets/cluster_live"
STOCK_SHA = "1dfee5a0ad746166cb28d79bddfe9352dc510df6cbcd0001f4751af1a6dbf5e4"
ORIGINALS = {
    "qml/cluster.qml": ("theme1", "76a2ad22efe281a6d73362d3a3c4e5f998d33e9dff210934f11e4e6071336fb3"),
    "qml10_25/cluster.qml": ("theme2", "8c9cb017658a904dd34e5b2b1ff0654576031ff4f31d0a0dedd259da52a09631"),
}


def build_artifacts(stock, originals):
    if corner.source.sha(stock) != STOCK_SHA or set(originals) != set(ORIGINALS):
        raise ValueError("Exact stock translation RCC and both cohort roots required")
    rows = rcc.decode(stock)
    if len(rows) != 84 or any(not row[0].startswith("common/language/") for row in rows):
        raise ValueError("Unexpected stock locale inventory")
    manifest = {"schema": 1, "variant": "corner-marker-21f7735c", "vehicle_validated": False,
                "minimum_daemon_protocol": 39, "stock_sha256": STOCK_SHA,
                "condition": corner.CONDITION.decode("ascii"), "resources": {}, "roots": {}}
    for path, country, language, flags, raw, value in rows:
        manifest["resources"][path] = {"sha256": corner.source.sha(value),
            "stored_sha256": corner.source.sha(raw), "country": country, "language": language, "flags": flags}
    for path, original in originals.items():
        if corner.source.sha(original) != ORIGINALS[path][1]:
            raise ValueError(f"Unknown cohort root: {path}")
        anchor = b"    id:rootItemId\r\n"
        if original.count(anchor) != 1:
            raise ValueError("OEM root anchor is not unique")
        marked = original.replace(anchor, anchor + corner.MARKER, 1)
        raw = struct.pack(">I", len(marked)) + zlib.compress(marked, 9)
        rows.append((path, 0, 1, 1, raw, marked))
        manifest["roots"][path] = {"original_sha256": corner.source.sha(original),
            "marked_sha256": corner.source.sha(marked), "original_size": len(original), "marked_size": len(marked)}
    carrier = rcc.build(rows)
    if len(carrier) >= 4 * 1024 * 1024:
        raise ValueError("Oversized cohort marker")
    manifest["carrier_sha256"] = corner.source.sha(carrier)
    manifest["carrier_size"] = len(carrier)
    return {"corner_marker_21f7735c.rcc": carrier,
            "corner_marker_21f7735c_manifest.json": (json.dumps(manifest, indent=2) + "\n").encode()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--roots", type=Path, required=True, help="Directory with extracted/theme{1,2}")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    originals = {path: (args.roots / "extracted" / theme / path).read_bytes()
                 for path, (theme, _) in ORIGINALS.items()}
    for name, data in build_artifacts(args.stock.read_bytes(), originals).items():
        target = ASSETS / name
        if args.check:
            if target.read_bytes() != data:
                raise ValueError(f"Output differs: {target}")
        else:
            target.write_bytes(data)
    print("84 stock resources + 2 complete roots; SHA256 " + corner.source.sha((ASSETS / "corner_marker_21f7735c.rcc").read_bytes()))


if __name__ == "__main__":
    main()
