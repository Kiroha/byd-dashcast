#!/usr/bin/env python3
"""Build an offline presentation candidate; never replace the retired APK asset."""
import argparse
import json
import re
import struct
import zlib
from pathlib import Path

import build_full_dashboard as dashboard
import build_live_cohort_marker as cohort
import build_live_marker as rcc

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts/cluster_full_app_candidate"
LEGACY_ASSETS = dashboard.OUT
LEGACY_SHA = "b121e6622f560a04a583c352977813ebc69047635f28cc0dee77ffeec78d007b"
OWNED = Path(__file__).with_name("full_app")
VARIANT = "isolated-full-app-21f7735c-v1"
MANIFEST = "isolated_full_app_21f7735c_manifest.json"
APP_RECT = {"x": 0, "y": 0, "width": 1920, "height": 720}
BEGIN = "// DASHCAST_ISOLATED_FULL_APP_BEGIN"
END = "// DASHCAST_ISOLATED_FULL_APP_END"
PRESENTATION = "qml10_25/alwaysDisplay/Presentation.qml"
PRESENTATION_SHA = "72406fdda39b5eef7242d9da7dfba84a540eb801ed6919719a46c0015bcf33b8"


def native_id(text, key):
    anchor = re.search(r"(?m)^[ \t]*id:[ \t]*" + re.escape(key) + r"\b", text)
    if anchor is None:
        raise ValueError("Exact factory id required: " + key)
    candidates = [obj for obj in dashboard.qml_objects(text)
                  if obj[2] < anchor.start() < dashboard.brace_end(text, obj[3])]
    return max(candidates, key=lambda obj: obj[1])


def patch_root(original):
    text = original.decode("utf-8")
    obj = native_id(text, "presentationLoaderId")
    old = text[obj[2]:dashboard.brace_end(text, obj[3])]
    expected = ('Loader{\r\n        id:presentationLoaderId\r\n'
                '        z:CustomStyle.presentationZ\r\n'
                '        source: DataSource.dilinkName === 0x02?"qrc:/' + PRESENTATION + '":""\r\n'
                '        asynchronous: true\r\n    }')
    if old != expected:
        raise ValueError("The exact single OEM presentation loader is required")
    owned = (OWNED / "PresentationLoader.qml.inc").read_text().strip()
    replacement = BEGIN + "\r\n" + "\r\n".join("    " + line for line in owned.splitlines()) + "\r\n    " + END
    return (text[:obj[2]] + replacement + text[dashboard.brace_end(text, obj[3]):]).encode("utf-8")


def build_artifacts(stock, roots):
    if rcc.sha(stock) != cohort.STOCK_SHA:
        raise ValueError("Exact cohort translation RCC required")
    rows = rcc.decode(stock)
    if len(rows) != 84 or any(not row[0].startswith("common/language/") for row in rows):
        raise ValueError("Exact factory translations required")
    if rcc.sha((roots / "theme2" / PRESENTATION).read_bytes()) != PRESENTATION_SHA:
        raise ValueError("Exact factory Presentation source required; it is not executed or repackaged")
    manifest = {"schema": 2, "variant": VARIANT, "cohort": "21f7735c",
                "runtime_activation_allowed": False, "vehicle_validated": False,
                "runtime_operation": None, "minimum_daemon_protocol": None,
                "native_size": [1920, 720], "application_rect": APP_RECT,
                "native_instruments_hidden": False, "native_alerts_hidden": False,
                "adas_control": "none", "android_frame_state": "UNVERIFIED",
                "stock_sha256": cohort.STOCK_SHA, "resources": {}, "roots": {}, "owned_resources": {},
                "factory_presentation": {"path": PRESENTATION, "sha256": PRESENTATION_SHA},
                "retired_profile": {"id": "full-app-21f7735c", "sha256": LEGACY_SHA,
                                    "purpose": "recovery-only; existing APK asset is unchanged"}}
    for path, country, language, flags, raw, value in rows:
        manifest["resources"][path] = {"sha256": rcc.sha(value), "stored_sha256": rcc.sha(raw),
            "country": country, "language": language, "flags": flags}
    for path, (theme, digest) in cohort.ORIGINALS.items():
        original = (roots / theme / path).read_bytes()
        if rcc.sha(original) != digest:
            raise ValueError("Unknown factory QML: " + path)
        value = patch_root(original) if path == "qml10_25/cluster.qml" else original
        rows.append((path, 0, 1, 1, struct.pack(">I", len(value)) + zlib.compress(value, 9), value))
        manifest["roots"][path] = {"original_sha256": digest, "patched_sha256": rcc.sha(value)}
    value = (OWNED / "FullApp.qml").read_bytes()
    path = "dashcast/isolated/FullApp.qml"
    rows.append((path, 0, 1, 0, value, value))
    manifest["owned_resources"][path] = rcc.sha(value)
    carrier = rcc.build(rows)
    if len(carrier) >= 4 * 1024 * 1024:
        raise ValueError("Carrier exceeds recovery bound")
    digest = rcc.sha(carrier)
    filename = "Di3F_DynastyOceanTranslate_V0001_DashCastIsolated_" + digest[:16] + ".rcc"
    manifest.update(carrier_sha256=digest, carrier_size=len(carrier), carrier_filename=filename)
    return {filename: carrier, MANIFEST: (json.dumps(manifest, indent=2) + "\n").encode()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--roots", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.output.resolve().is_relative_to((ROOT / "app").resolve()):
        raise ValueError("Offline candidates must not be packaged as runtime assets")
    if not args.check:
        args.output.mkdir(parents=True, exist_ok=True)
    for name, value in build_artifacts(args.stock.read_bytes(), args.roots).items():
        target = args.output / name
        if args.check:
            if target.read_bytes() != value:
                raise SystemExit("Carrier is not reproducible: " + name)
        else:
            target.write_bytes(value)
        print(name, len(value), rcc.sha(value))


if __name__ == "__main__":
    main()
