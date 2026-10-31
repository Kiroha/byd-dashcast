#!/usr/bin/env python3
"""Build the offline-only night ambient animation prototype for the 21f7735c cohort; execute no QML."""
import argparse
import difflib
import hashlib
import json
import struct
import zlib
from pathlib import Path

import build_live_cohort_marker as cohort
import build_live_marker as rcc

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts/cluster_ambient"
CARRIER = "ambient-offline.rcc"
MAX_CARRIER = 4 * 1024 * 1024
ANCHOR = b"    id:rootItemId\r\n"
GLOW_ALIAS = "dashcast/ambient/horizon_glow.png"
GLOW = {"width": 256, "height": 64, "rgb": [90, 200, 255], "peak_alpha": 77}
NIGHT_BACKGROUND = {
    "qml/cluster.qml": "qrc:/images/display_always/night/bg.png",
    "qml10_25/cluster.qml": "qrc:/images10_25/display_always/night/bg.png",
}
# Both factory night backgrounds share one horizon glow centred at x=960 with its line at y~254.
GEOMETRY = {"glow": [360, 90, 1200, 280], "streak": [480, 254, 240, 3]}
BREATH = {"min_opacity": 0.3, "max_opacity": 0.75, "half_period_ms": 3500}
STREAK = {"to_x": 1200, "peak_opacity": 0.6, "fade_ms": 1500, "hold_ms": 3000, "pause_ms": 4000}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def glow_png():
    """Deterministic straight-alpha radial glow; transparent on every edge pixel."""
    width, height, peak = GLOW["width"], GLOW["height"], GLOW["peak_alpha"]
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        dy = (y + 0.5 - height / 2) / (height / 2)
        for x in range(width):
            dx = (x + 0.5 - width / 2) / (width / 2)
            r2 = dx * dx + dy * dy
            rows += bytes(GLOW["rgb"]) + bytes([round(peak * (1 - r2) ** 2) if r2 < 1 else 0])

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
            + chunk(b"IEND", b""))


def condition(path):
    return ("DataSource.powerGear === 3 && DataSource.playAnim === true && bgImageId.visible && "
            f'String(bgImageId.source) === "{NIGHT_BACKGROUND[path]}"')


def block(path):
    gx, gy, gw, gh = GEOMETRY["glow"]
    sx, sy, sw, sh = GEOMETRY["streak"]
    low, high, half = BREATH["min_opacity"], BREATH["max_opacity"], BREATH["half_period_ms"]
    peak, fade, hold = STREAK["peak_opacity"], STREAK["fade_ms"], STREAK["hold_ms"]
    lines = [
        "",
        "    // DASHCAST_AMBIENT_BEGIN",
        "    Item {",
        "        id: dashCastAmbientId",
        "        anchors.fill: parent",
        "        z: CustomStyle.bgZ + 0.5",
        "        enabled: false",
        f"        visible: {condition(path)}",
        "        Image {",
        "            id: dashCastAmbientGlowId",
        f"            x: {gx}; y: {gy}; width: {gw}; height: {gh}",
        f'            source: "qrc:/{GLOW_ALIAS}"',
        "            smooth: true",
        f"            opacity: {low}",
        "            SequentialAnimation on opacity {",
        '                objectName: "dashCastAmbientBreathAnim"',
        "                running: dashCastAmbientId.visible",
        "                loops: Animation.Infinite",
        f"                NumberAnimation {{ from: {low}; to: {high}; duration: {half}; easing.type: Easing.InOutSine }}",
        f"                NumberAnimation {{ from: {high}; to: {low}; duration: {half}; easing.type: Easing.InOutSine }}",
        "            }",
        "        }",
        "        Rectangle {",
        "            id: dashCastAmbientStreakId",
        f"            x: {sx}; y: {sy}; width: {sw}; height: {sh}",
        "            opacity: 0",
        "            gradient: Gradient {",
        "                orientation: Gradient.Horizontal",
        '                GradientStop { position: 0.0; color: "#008fe8ff" }',
        '                GradientStop { position: 0.5; color: "#ff8fe8ff" }',
        '                GradientStop { position: 1.0; color: "#008fe8ff" }',
        "            }",
        "        }",
        "        SequentialAnimation {",
        '            objectName: "dashCastAmbientStreakAnim"',
        "            running: dashCastAmbientId.visible",
        "            loops: Animation.Infinite",
        "            ParallelAnimation {",
        '                NumberAnimation { target: dashCastAmbientStreakId; property: "x"; '
        f"from: {sx}; to: {STREAK['to_x']}; duration: {2 * fade + hold}; easing.type: Easing.InOutSine }}",
        "                SequentialAnimation {",
        '                    NumberAnimation { target: dashCastAmbientStreakId; property: "opacity"; '
        f"from: 0; to: {peak}; duration: {fade}; easing.type: Easing.InOutSine }}",
        f"                    PauseAnimation {{ duration: {hold} }}",
        '                    NumberAnimation { target: dashCastAmbientStreakId; property: "opacity"; '
        f"from: {peak}; to: 0; duration: {fade}; easing.type: Easing.InOutSine }}",
        "                }",
        "            }",
        f"            PauseAnimation {{ duration: {STREAK['pause_ms']} }}",
        "        }",
        "    }",
        "    // DASHCAST_AMBIENT_END",
        "",
    ]
    return "\r\n".join(lines).encode("ascii")


def patched_root(path, original):
    if path not in cohort.ORIGINALS or sha(original) != cohort.ORIGINALS[path][1]:
        raise ValueError(f"Unexpected original OEM root: {path}")
    if original.count(ANCHOR) != 1:
        raise ValueError("OEM root anchor is not unique")
    return original.replace(ANCHOR, ANCHOR + block(path), 1)


def build_artifacts(stock, originals):
    if sha(stock) != cohort.STOCK_SHA or set(originals) != set(cohort.ORIGINALS):
        raise ValueError("Exact 21f7735c stock translation RCC and both cohort roots required")
    rows = rcc.decode(stock)
    if len(rows) != 84 or any(not row[0].startswith("common/language/") for row in rows):
        raise ValueError("Unexpected stock locale inventory")
    glow = glow_png()
    manifest = {
        "schema": 1,
        "variant": "ambient-night-21f7735c",
        "purpose": "offline-night-ambient-animation-prototype",
        "vehicle_validated": False,
        "diag_loadable": False,
        "runtime_activation_allowed": False,
        "stock_sha256": cohort.STOCK_SHA,
        "layer": {
            "z": "CustomStyle.bgZ + 0.5: above the OEM background (-2), below theme-switch (-1) and every functional item",
            "input": False,
            "conditions": {path: condition(path) for path in cohort.ORIGINALS},
        },
        "animation": {"geometry": GEOMETRY, "breath": BREATH, "streak": STREAK},
        "glow_asset": dict(GLOW, alias=GLOW_ALIAS, sha256=sha(glow), size=len(glow)),
        "preservation": {
            "notes": [
                "Shown only in power ON, after the start-up animation, over the exact standard night background.",
                "Navigation, terrain, day, power-on and hidden backgrounds hide the layer and stop both animations.",
                "No input, Timer, Loader, Connections, native call, DataSource write or OEM object change.",
                "Warnings (z 100), ADAS (99), Center (3) and default-z instruments stay above the layer.",
                "Breathing period 7 s and a 3 px streak at about 120 px/s; driving acceptability is unvalidated.",
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
        patched = patched_root(path, original)
        patch = "".join(difflib.unified_diff(
            original.decode("utf-8").splitlines(keepends=True),
            patched.decode("utf-8").splitlines(keepends=True),
            fromfile=f"original/{path}", tofile=f"patched/{path}",
        )).encode("utf-8")
        files[f"patched/{path}"] = patched
        files[f"patches/{path}.patch"] = patch
        manifest["roots"][path] = {
            "original_sha256": sha(original), "original_size": len(original),
            "patched_sha256": sha(patched), "patched_size": len(patched), "patch_sha256": sha(patch),
        }
        rows.append((path, 0, 1, 1, struct.pack(">I", len(patched)) + zlib.compress(patched, 9), patched))
    rows.append((GLOW_ALIAS, 0, 1, 0, glow, glow))
    carrier = rcc.build(rows)
    if len(carrier) >= MAX_CARRIER:
        raise ValueError("Oversized ambient carrier")
    if sorted(rcc.decode(carrier)) != sorted(rows):
        raise ValueError("Carrier does not round-trip")
    manifest["carrier_sha256"] = sha(carrier)
    manifest["carrier_size"] = len(carrier)
    files[CARRIER] = carrier
    files[f"assets/{GLOW_ALIAS}"] = glow
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
    if args.check:
        actual = {str(path.relative_to(OUT)).replace("\\", "/") for path in OUT.rglob("*") if path.is_file()}
        if actual != set(files):
            raise ValueError("Offline output inventory differs")
        for name, data in files.items():
            if (OUT / name).read_bytes() != data:
                raise ValueError(f"Offline output differs: {name}")
    else:
        for name, data in files.items():
            target = OUT / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print(f"Offline only: 84 stock locales + 2 roots + 1 glow; {len(files[CARRIER])} bytes; SHA256 {sha(files[CARRIER])}")


if __name__ == "__main__":
    main()
