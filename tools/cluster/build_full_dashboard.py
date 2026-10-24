#!/usr/bin/env python3
"""Build the exact 21f7735c native dashboard, without executing any OEM code."""
import argparse
import json
import re
import struct
import zlib
from pathlib import Path

import build_live_cohort_marker as cohort
import build_live_marker as rcc

ROOT = Path(__file__).resolve().parents[2]
OWNED = Path(__file__).with_name("full_dashboard")
OUT = ROOT / "app/src/main/assets/cluster_live"
WARNING_PATH = "qml10_25/WarinLightLayout.qml"
WARNING_SHA = "5cc7de50c28d5c45cacb93dcc9015af214b6e674cd17e08ab0a9e7a44d36dc06"
APP_RECT = {"x": 464, "y": 256, "width": 752, "height": 320}
BEGIN = "// DASHCAST_NATIVE_DASHBOARD_BEGIN"
END = "// DASHCAST_NATIVE_DASHBOARD_END"


def block(text):
    return "\r\n    " + BEGIN + "\r\n" + text.replace("\n", "\r\n") + "\r\n    " + END + "\r\n"


def insert_after(text, anchor, insertion):
    if text.count(anchor) != 1:
        raise ValueError("Factory anchor must be unique: " + anchor)
    return text.replace(anchor, anchor + block(insertion), 1)


def brace_end(text, start):
    """Find a QML object's end while preserving strings and both comment forms."""
    depth, quote, line, comment, escape = 0, None, False, False, False
    i = start
    while i < len(text):
        c, nxt = text[i], text[i:i + 2]
        if line:
            if c == "\n": line = False
        elif comment:
            if nxt == "*/": comment = False; i += 1
        elif quote:
            if escape: escape = False
            elif c == "\\": escape = True
            elif c == quote: quote = None
        elif nxt == "//": line = True; i += 1
        elif nxt == "/*": comment = True; i += 1
        elif c in "\"'": quote = c
        elif c == "{": depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0: return i + 1
        i += 1
    raise ValueError("Unbalanced factory QML")


def qml_objects(text):
    """Lex braces outside comments/strings; report actual QML object depth."""
    masked = list(text)
    quote, line, comment, escape, i = None, False, False, False, 0
    while i < len(text):
        c, nxt = text[i], text[i:i + 2]
        if line:
            if c == "\n": line = False
            else: masked[i] = " "
        elif comment:
            if c not in "\r\n": masked[i] = " "
            if nxt == "*/": masked[i + 1] = " "; comment = False; i += 1
        elif quote:
            if c not in "\r\n": masked[i] = " "
            if escape: escape = False
            elif c == "\\": escape = True
            elif c == quote: quote = None
        elif nxt in ("//", "/*"):
            masked[i] = masked[i + 1] = " "; i += 1
            line, comment = nxt == "//", nxt == "/*"
        elif c in "\"'": quote = c; masked[i] = " "
        i += 1
    clean = "".join(masked)
    depth, previous = 0, None
    result = []
    for token in re.finditer(r"[A-Za-z_]\w*|\{|\}|[^\s]", clean):
        value = token.group()
        if value == "{":
            if previous is not None and re.fullmatch(r"[A-Z]\w*", previous.group()):
                result.append((previous.group(), depth, previous.start(), token.start()))
            depth += 1
        elif value == "}": depth -= 1
        previous = token
    if depth != 0: raise ValueError("Unbalanced factory source")
    return result


def warning_inventory(text):
    records = []
    objects = qml_objects(text)
    all_lights = [item for item in objects if item[0] == "WarningLight"]
    if len(all_lights) != 78 or sum(item[1] == 2 for item in all_lights) != 3:
        raise ValueError("Exact live native light inventory required; commented declarations excluded")
    for kind, depth, start, opening in all_lights:
        if depth != 1: continue
        body = text[start:brace_end(text, opening)]
        ident = re.search(r"(?m)^        id:\s*(\w+)", body)
        if not ident: raise ValueError("Factory warning has no stable id")
        key = ident.group(1)
        # Some status lamps use visible instead of displayState. Preserve that
        # expression rather than treating their default displayState=0 as off.
        has_state = re.search(r"(?m)^        displayState\s*:", body) is not None
        records.append((key, has_state))
    if len(records) != 75 or len({key for key, _ in records}) != 75:
        raise ValueError("Exact 75 direct lights + 3 grouped traffic lights required")
    return records


def patch_warnings(original):
    text = original.decode("utf-8")
    inventory = warning_inventory(text)
    # Keep the three traffic lamps and their native background as one unit.
    # The other two textual WarningLight declarations are commented out in
    # this exact factory source; they must not become live QML references.
    text = insert_after(text, "    Item{\r\n        x:1039+70\r\n", "        id: dashcastTrafficSignalGroup")
    inventory.append(("dashcastTrafficSignalGroup", False))
    text = text.replace("import QtQuick 2.0\r\n", 'import QtQuick 2.0\r\nimport "qrc:/dashcast/DashboardLayout.js" as DashCastLayout\r\n', 1)
    names = ", ".join(key for key, _ in inventory)
    states = ",\n        ".join(
        f"({key}.displayState !== 0 && {key}.displayState !== 2)" if has_state else f"{key}.visible"
        for key, has_state in inventory)
    properties = f'''    id: dashcastWarningRoot
    property bool dashcastRequested: false
    property bool dashcastMeasure: false
    readonly property var dashcastItems: [{names}]
    readonly property var dashcastBounds: dashcastMeasure ? [
        ''' + ",\n        ".join(f"DashCastLayout.nativeBounds({key})" for key, _ in inventory) + f'''] : []
    readonly property var dashcastStates: [
        {states}]
    readonly property var dashcastPacked: dashcastMeasure ? DashCastLayout.pack(dashcastItems, dashcastBounds, dashcastStates) : ({{ fits: false, positions: [] }})
    readonly property bool dashcastLayoutReady: dashcastPacked.fits
    readonly property bool dashcastActive: dashcastRequested && dashcastLayoutReady'''
    text = insert_after(text, "import com.byd.WarningLight 1.0\r\n\r\nItem {\r\n", properties)
    for index, (key, _) in enumerate(inventory):
        anchor = re.search(r"(?m)^        id:[ \t]*" + re.escape(key) + r"[ \t]*\r?\n", text)
        if anchor is None: raise ValueError("Missing warning anchor: " + key)
        transform = f'''        // Translate leaves native x/y bindings and imperative seat updates intact.
        transform: Translate {{
            x: dashcastWarningRoot.dashcastActive ? dashcastWarningRoot.dashcastPacked.positions[{index}].x - {key}.x - dashcastWarningRoot.dashcastBounds[{index}].x : 0
            y: dashcastWarningRoot.dashcastActive ? dashcastWarningRoot.dashcastPacked.positions[{index}].y - {key}.y - dashcastWarningRoot.dashcastBounds[{index}].y : 0
        }}'''
        if key == "dashcastTrafficSignalGroup":
            text = text.replace(block("        id: dashcastTrafficSignalGroup"),
                                block("        id: dashcastTrafficSignalGroup\n" + transform), 1)
        else:
            text = text[:anchor.end()] + block(transform) + text[anchor.end():]
    return text.encode("utf-8"), inventory


def patch_root(original):
    text = original.decode("utf-8")
    text = insert_after(text, "    id:rootItemId\r\n", '''    // A failed owned component or overflowing telltale row keeps the OEM scene.
    readonly property bool dashcastRequested: DataSource.naviType === 0x04 && DataSource.screenSize === 0x02 && rootItemId.width === 1920 && rootItemId.height === 720 && DataSource.powerGear === 0x03 && DataSource.playAnim === true && alwyasDisplayId.visible && warningLayoutId.visible
    readonly property bool dashcastComponentReady: dashcastDashboardLoader.status === Loader.Ready
    readonly property bool dashcastInstrumentsFit: speedValueId.contentWidth + speedUnitId.contentWidth + 12 <= 368 && powerValueId.contentWidth + powerUnitId.contentWidth + 12 <= 368
    readonly property bool dashcastDashboardReady: dashcastRequested && dashcastComponentReady && warningLayoutId.dashcastLayoutReady && dashcastInstrumentsFit
    Loader {
        id: dashcastDashboardLoader
        active: rootItemId.dashcastRequested
        source: "qrc:/dashcast/DashCastDashboard.qml"
        z: CustomStyle.bgZ
        visible: rootItemId.dashcastDashboardReady
        onLoaded: item.night = Qt.binding(function() { return DataSource.dayNightMode === 0x02 })
    }''')
    for key in ("bgImageContainerId", "headLineId", "driveRangeInfoBgId"):
        text = insert_after(text, f"        id: {key}\r\n" if key == "bgImageContainerId" else
                            f"            id: {key}\r\n" if key == "headLineId" else "                id:driveRangeInfoBgId\r\n",
                            "        opacity: rootItemId.dashcastDashboardReady ? 0 : 1")
    for key, x, y in (("naviSpeedId", 232, 326), ("naviPowerId", 98.5, 446)):
        text = insert_after(text, f"            id: {key}\r\n", f'''            transform: Translate {{
                x: rootItemId.dashcastDashboardReady ? {x} - {key}.x : 0
                y: rootItemId.dashcastDashboardReady ? {y} - {key}.y : 0
            }}''')
    text = insert_after(text, "        id:warningLayoutId\r\n", "        dashcastRequested: rootItemId.dashcastDashboardReady\n        dashcastMeasure: rootItemId.dashcastRequested && rootItemId.dashcastComponentReady")
    return text.encode("utf-8")


def build_artifacts(stock, roots):
    if rcc.sha(stock) != cohort.STOCK_SHA:
        raise ValueError("Exact cohort translation RCC required")
    rows = rcc.decode(stock)
    if len(rows) != 84 or any(not row[0].startswith("common/language/") for row in rows):
        raise ValueError("Exact 42 qm + 42 ts locale resources required")
    manifest = {"schema": 1, "variant": "full-dashboard-21f7735c", "cohort": "21f7735c",
                "vehicle_validated": False, "minimum_daemon_protocol": 42,
                "native_size": [1920, 720], "native_size_command": 31,
                "application_rect": APP_RECT, "stock_sha256": cohort.STOCK_SHA,
                "resources": {}, "roots": {}, "owned_resources": {}, "native_telltales": 78,
                "native_telltale_groups": 76, "factory_commented_telltales": 2}
    for path, country, language, flags, raw, value in rows:
        manifest["resources"][path] = {"sha256": rcc.sha(value), "stored_sha256": rcc.sha(raw),
                                     "country": country, "language": language, "flags": flags}
    sources = dict(cohort.ORIGINALS)
    sources[WARNING_PATH] = ("theme2", WARNING_SHA)
    for path, (theme, digest) in sources.items():
        original = (roots / theme / path).read_bytes()
        if rcc.sha(original) != digest: raise ValueError("Unknown factory QML: " + path)
        if path == "qml10_25/cluster.qml": value = patch_root(original)
        elif path == WARNING_PATH: value, inventory = patch_warnings(original)
        else: value = original  # Unsupported theme1 remains byte-for-byte native.
        raw = struct.pack(">I", len(value)) + zlib.compress(value, 9)
        rows.append((path, 0, 1, 1, raw, value))
        manifest["roots"][path] = {"original_sha256": digest, "patched_sha256": rcc.sha(value)}
    for filename in ("DashCastDashboard.qml", "DashboardLayout.js"):
        value = (OWNED / filename).read_bytes(); path = "dashcast/" + filename
        rows.append((path, 0, 1, 0, value, value))
        manifest["owned_resources"][path] = rcc.sha(value)
    manifest["telltale_ids"] = [key for key, _ in inventory]
    carrier = rcc.build(rows)
    if len(carrier) >= 4 * 1024 * 1024: raise ValueError("Carrier exceeds recovery bound")
    manifest["carrier_sha256"] = rcc.sha(carrier); manifest["carrier_size"] = len(carrier)
    return {"full_dashboard_21f7735c.rcc": carrier,
            "full_dashboard_21f7735c_manifest.json": (json.dumps(manifest, indent=2) + "\n").encode()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--roots", type=Path, required=True, help="Extracted factory directory containing theme1 and theme2")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for name, value in build_artifacts(args.stock.read_bytes(), args.roots).items():
        target = OUT / name
        if args.check:
            if target.read_bytes() != value: raise ValueError("Nonreproducible artifact: " + name)
        else: target.write_bytes(value)
    print("PASS: native dashboard exact 21f7735c; 84 unmodified locale resources; all 78 live native telltales; no OEM execution")


if __name__ == "__main__": main()
