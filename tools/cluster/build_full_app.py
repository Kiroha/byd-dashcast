#!/usr/bin/env python3
"""Build a closed full-app carrier for the exact 21f7735c cohort.

Only source bytes are inspected. No OEM component, supplied script or vehicle
command is executed. ADAS remains owned by the existing projection controller.
"""
import argparse
import json
import re
import struct
import zlib
from pathlib import Path

import build_full_dashboard as dashboard
import build_live_cohort_marker as cohort
import build_live_marker as rcc

OUT = dashboard.OUT
OWNED = Path(__file__).with_name("full_app")
APP_RECT = {"x": 0, "y": 0, "width": 1920, "height": 720}
BEGIN = "// DASHCAST_FULL_APP_BEGIN"
END = "// DASHCAST_FULL_APP_END"
HIDDEN_ITEMS = ("bgImageContainerId", "headLineId", "headInfoId", "warningLayoutId",
                "driveRangeInfoContainerId", "odoItemId", "naviPowerId", "naviSpeedId")


def block(text):
    return "\r\n    " + BEGIN + "\r\n" + text.replace("\n", "\r\n") + "\r\n    " + END + "\r\n"


def insert_id(text, key, insertion):
    anchor = re.search(r"(?m)^\s*id:\s*" + re.escape(key) + r"[ \t]*\r?$", text)
    if anchor is None:
        raise ValueError("Exact factory id required: " + key)
    return text[:anchor.end()] + block(insertion) + text[anchor.end():]


def native_id(text, key):
    anchor = re.search(r"(?m)^[ \t]*id:[ \t]*" + re.escape(key) + r"\b", text)
    if anchor is None:
        raise ValueError("Exact factory id required: " + key)
    candidates = [obj for obj in dashboard.qml_objects(text) if obj[2] < anchor.start() < dashboard.brace_end(text, obj[3])]
    return max(candidates, key=lambda obj: obj[1])


def patch_root(original):
    text = original.decode("utf-8")
    text = insert_id(text, "rootItemId", '''    // Manual demonstration only: native instruments and alerts are hidden.
    readonly property bool dashcastRequested: DataSource.naviType === 0x04 && DataSource.screenSize === 0x02 && rootItemId.width === 1920 && rootItemId.height === 720 && DataSource.powerGear === 0x03 && DataSource.playAnim === true && alwyasDisplayId.visible && warningLayoutId.visible
    readonly property bool dashcastComponentReady: dashcastFullAppLoader.status === Loader.Ready
    readonly property bool dashcastFullAppReady: dashcastRequested && dashcastComponentReady
    Loader {
        id: dashcastFullAppLoader
        active: rootItemId.dashcastRequested
        source: "qrc:/dashcast/FullApp.qml"
        z: CustomStyle.bgZ
        visible: rootItemId.dashcastFullAppReady
    }
    Binding { target: rootItemId.mediaInfoUi; property: "opacity"; value: 0; when: rootItemId.dashcastFullAppReady && rootItemId.mediaInfoUi !== null; restoreMode: Binding.RestoreBindingOrValue }
    Binding { target: rootItemId.navigationUi; property: "opacity"; value: 0; when: rootItemId.dashcastFullAppReady && rootItemId.navigationUi !== null; restoreMode: Binding.RestoreBindingOrValue }''')
    for key in HIDDEN_ITEMS:
        text = insert_id(text, key, "opacity: rootItemId.dashcastFullAppReady ? 0 : 1")
    return text.encode("utf-8")


def build_artifacts(stock, roots):
    if rcc.sha(stock) != cohort.STOCK_SHA:
        raise ValueError("Exact cohort translation RCC required")
    rows = rcc.decode(stock)
    if len(rows) != 84 or any(not row[0].startswith("common/language/") for row in rows):
        raise ValueError("Exact factory translations required")
    manifest = {"schema": 1, "variant": "full-app-21f7735c", "cohort": "21f7735c",
                "vehicle_validated": False, "minimum_daemon_protocol": 44,
                "native_size": [1920, 720], "application_rect": APP_RECT,
                "native_instruments_hidden": True, "native_alerts_hidden": True,
                "adas_control": "existing-projection-plan",
                "stock_sha256": cohort.STOCK_SHA, "resources": {}, "roots": {}, "owned_resources": {}}
    for path, country, language, flags, raw, value in rows:
        manifest["resources"][path] = {"sha256": rcc.sha(value), "stored_sha256": rcc.sha(raw), "country": country, "language": language, "flags": flags}
    sources = dict(cohort.ORIGINALS)
    for path, (theme, digest) in sources.items():
        original = (roots / theme / path).read_bytes()
        if rcc.sha(original) != digest:
            raise ValueError("Unknown factory QML: " + path)
        if path == "qml10_25/cluster.qml":
            value = patch_root(original)
        else:
            value = original
        rows.append((path, 0, 1, 1, struct.pack(">I", len(value)) + zlib.compress(value, 9), value))
        manifest["roots"][path] = {"original_sha256": digest, "patched_sha256": rcc.sha(value)}
    for filename in ("FullApp.qml",):
        value = (OWNED / filename).read_bytes()
        path = "dashcast/" + filename
        rows.append((path, 0, 1, 0, value, value))
        manifest["owned_resources"][path] = rcc.sha(value)
    manifest["hidden_items"] = list(HIDDEN_ITEMS)
    carrier = rcc.build(rows)
    if len(carrier) >= 4 * 1024 * 1024:
        raise ValueError("Carrier exceeds recovery bound")
    manifest["carrier_sha256"] = rcc.sha(carrier)
    manifest["carrier_size"] = len(carrier)
    return {"full_app_21f7735c.rcc": carrier,
            "full_app_21f7735c_manifest.json": (json.dumps(manifest, indent=2) + "\n").encode()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--roots", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for name, value in build_artifacts(args.stock.read_bytes(), args.roots).items():
        target = OUT / name
        if args.check:
            if target.read_bytes() != value:
                raise SystemExit("Carrier is not reproducible: " + name)
        else:
            target.write_bytes(value)
        print(name, len(value), rcc.sha(value))


if __name__ == "__main__":
    main()
