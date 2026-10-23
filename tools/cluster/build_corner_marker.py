#!/usr/bin/env python3
"""Build a separate, stock-pinned corner proof without executing OEM QML."""
import argparse
import hashlib
import json
import struct
import zlib
from pathlib import Path

import build_bandless_theme as source

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/cluster_live"
CONDITION = (b"DataSource.powerGear === 3 && DataSource.playAnim === true && "
             b"DataSource.gearMode === 1 && DataSource.speed === 0")
# Transparent parent, thin outer-edge strokes; OEM warning/ADAS/boot layers remain above it.
MARKER = (
    b"\r\n    // DASHCAST_CORNER_MARKER_BEGIN\r\n"
    b"    Item {\r\n"
    b"        id: dashCastCornerMarkerId\r\n"
    b"        anchors.fill: parent\r\n"
    b"        z: 1\r\n"
    b"        visible: " + CONDITION + b"\r\n"
    b"        Rectangle { x: 0; y: 0; width: 96; height: 4; color: \"#00e5ff\" }\r\n"
    b"        Rectangle { x: 0; y: 0; width: 4; height: 80; color: \"#00e5ff\" }\r\n"
    b"        Rectangle { x: parent.width - width; y: 0; width: 96; height: 4; color: \"#ff40d5\" }\r\n"
    b"        Rectangle { x: parent.width - width; y: 0; width: 4; height: 80; color: \"#ff40d5\" }\r\n"
    b"        Rectangle { x: 0; y: parent.height - height; width: 96; height: 4; color: \"#ff40d5\" }\r\n"
    b"        Rectangle { x: 0; y: parent.height - height; width: 4; height: 80; color: \"#ff40d5\" }\r\n"
    b"        Rectangle { x: parent.width - width; y: parent.height - height; width: 96; height: 4; color: \"#00e5ff\" }\r\n"
    b"        Rectangle { x: parent.width - width; y: parent.height - height; width: 4; height: 80; color: \"#00e5ff\" }\r\n"
    b"    }\r\n"
    b"    // DASHCAST_CORNER_MARKER_END\r\n"
)


def build_artifacts(stock, originals):
    source.build_artifacts(stock, originals)  # Reject an unknown stock file or root before producing bytes.
    rows = source.rcc.decode(stock)
    manifest = {"schema": 1, "variant": "corner-marker", "vehicle_validated": False,
                "minimum_daemon_protocol": 38, "stock_sha256": source.STOCK_SHA,
                "condition": CONDITION.decode("ascii"), "marker": MARKER.decode("ascii"),
                "resources": {}, "roots": {}}
    for path, country, language, flags, raw, value in rows:
        manifest["resources"][path] = {"sha256": source.sha(value), "stored_sha256": source.sha(raw),
                                       "country": country, "language": language, "flags": flags}
    for path, original in originals.items():
        anchor = b"    id:rootItemId\r\n"
        if original.count(anchor) != 1:
            raise ValueError("OEM root anchor is not unique")
        marked = original.replace(anchor, anchor + MARKER, 1)
        raw = struct.pack(">I", len(marked)) + zlib.compress(marked, 9)
        rows.append((path, 0, 1, 1, raw, marked))
        manifest["roots"][path] = {"original_sha256": source.sha(original), "marked_sha256": source.sha(marked),
                                    "original_size": len(original), "marked_size": len(marked)}
    carrier = source.rcc.build(rows)
    if len(carrier) >= 4 * 1024 * 1024:
        raise ValueError("Oversized corner marker")
    manifest["carrier_sha256"] = source.sha(carrier)
    manifest["carrier_size"] = len(carrier)
    return {"corner_marker.rcc": carrier,
            "corner_marker_manifest.json": (json.dumps(manifest, indent=2, ensure_ascii=True) + "\n").encode()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--roots", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    originals = {path: (args.roots / "extracted" / theme / path).read_bytes()
                 for path, (theme, _) in source.ORIGINALS.items()}
    for name, data in build_artifacts(args.stock.read_bytes(), originals).items():
        target = ASSETS / name
        if args.check:
            if target.read_bytes() != data:
                raise ValueError(f"Output differs: {target}")
        else:
            target.write_bytes(data)
    print("86 resources; corner marker; SHA256 " + hashlib.sha256((ASSETS / "corner_marker.rcc").read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
