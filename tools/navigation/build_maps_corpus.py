#!/usr/bin/env python3
"""Build bounded recognition masks and test fixtures from the supplied Maps APK.

Requires apktool-decoded resources, Pillow, and CairoSVG 2.8.2. The APK itself
and decompiled Java remain outside the repository. See the corpus provenance.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

import cairosvg
from PIL import Image, ImageOps

APK_SHA256 = "5fa52cfb6f6dfe10efb0b2961a1569d07efaa9615f2de8df1de80a8dd87e05e5"
# Verified source: zao's SVG/mirroring table, consumed by cbjj -> brgj -> brhk.
# Roundabout angles use the existing unnumbered BYD variants, never an ordinal exit.
SOURCES = [
    ("ic_depart", 11, None, None),
    ("ic_place", 48, None, None),
    ("ic_arrive_right", 48, 48, None),
    ("ic_straight", 11, None, None),
    ("ic_turn_right", 2, 1, None),
    ("ic_turn_slight_right", 5, 3, None),
    # Both variants express the same bearing. Do not make a grey branch decide
    # between nearly identical straight/bearing labels after Android resampling.
    ("ic_merge_slight_right", 5, 3, None),
    ("ic_turn_sharp_right", 8, 7, None),
    ("ic_u_turn", 9, 10, None),
    ("ic_merge_right", 2, 1, None),
    ("ic_merge", 11, None, None),
] + [("ic_roundabout" + suffix, icon, reflected, False) for suffix, icon, reflected in [
    ("", 15, 17), ("_exit", 15, 17), ("_left", 15, 17), ("_right", 16, 18),
    ("_sharp_left", 15, 17), ("_sharp_right", 16, 18),
    ("_slight_left", 15, 17), ("_slight_right", 16, 18), ("_straight", 19, 20),
    ("_u_turn", 15, 17),
]]


def render(source, edge, mirrored, faint=False, opaque=False):
    root = ET.fromstring(source)
    for node in root.iter():
        if node.tag.rsplit("}", 1)[-1] not in {"svg", "g", "path", "rect", "circle", "ellipse", "polygon"}:
            raise ValueError("Unsupported SVG element")
        if node is not root:
            node.set("fill", "white")
            # Android/cairo can round half alpha to either 127 or 128. Model both;
            # never increase the classifier's spatial or confidence tolerances.
            if faint and node.get("opacity") == "0.5":
                node.set("opacity", str(127 / 255))
    png = cairosvg.svg2png(bytestring=ET.tostring(root), output_width=edge, output_height=edge)
    image = Image.open(io.BytesIO(png)).convert("RGBA")
    if mirrored:
        image = ImageOps.mirror(image)
    if opaque:
        image = Image.alpha_composite(Image.new("RGBA", image.size, "black"), image)
    return image


def normalize(image, opaque=False):
    pixels = image.load()
    points = [(x, y) for y in range(image.height) for x in range(image.width)
              if (pixels[x, y][0] >= 100 if opaque else pixels[x, y][3] >= 128)]
    left = min(x for x, _ in points); right = max(x for x, _ in points)
    top = min(y for _, y in points); bottom = max(y for _, y in points)
    width = right - left + 1; height = bottom - top + 1
    foreground = set(points)
    strong = {(x, y) for x, y in points
              if (pixels[x, y][0] >= 192 if opaque else pixels[x, y][3] >= 192)}
    rows = []; strong_rows = []
    for y in range(32):
        row = 0; strong_row = 0
        for x in range(32):
            sx = left + min(width - 1, (2 * x + 1) * width // 64)
            sy = top + min(height - 1, (2 * y + 1) * height // 64)
            if (sx, sy) in foreground:
                row |= 1 << x
            if (sx, sy) in strong:
                strong_row |= 1 << x
        rows.append(row)
        strong_rows.append(strong_row)
    return (width / height, ",".join(format(row, "x") for row in rows),
            ",".join(format(row, "x") for row in strong_rows))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--decoded", type=Path, required=True)
    parser.add_argument("--repo", type=Path, required=True)
    args = parser.parse_args()
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != APK_SHA256:
        raise ValueError("APK provenance mismatch; audit a new version before updating this corpus")
    with zipfile.ZipFile(args.apk) as apk:
        originals = {hashlib.sha256(apk.read(name)).hexdigest(): name
                     for name in apk.namelist() if name.startswith("res/") and name.endswith(".svg")}
    def source_bytes(name):
        source = (args.decoded / "res/raw" / (name + ".svg")).read_bytes()
        digest = hashlib.sha256(source).hexdigest()
        if digest not in originals:
            raise ValueError(f"Decoded SVG differs from the audited APK: {name}")
        return source, digest, originals[digest]
    fixtures = args.repo / "app/src/test/resources/navigation/maps-26.33"
    fixtures.mkdir(parents=True, exist_ok=True)
    references = []
    entries = []
    seen = set()
    for name, icon, reflected_icon, clockwise in SOURCES:
        source, digest, apk_entry = source_bytes(name)
        for mirror in ([False, True] if reflected_icon is not None else [False]):
            label = name + ("_mirrored" if mirror else "")
            output = reflected_icon if mirror else icon
            direction = (not clockwise if mirror else clockwise) if clockwise is not None else None
            fixture = fixtures / (label + ".png")
            render(source, 54, mirror).save(fixture)
            entries.append({"name": label, "source": name + ".svg", "mirrored": mirror,
                            "iconId": output, "clockwise": direction,
                            "sourceSha256": digest, "apkEntry": apk_entry,
                            "pngSha256": hashlib.sha256(fixture.read_bytes()).hexdigest()})
            for edge, resampled in [(54, False), (96, False), (48, True), (72, True), (96, True), (108, True)]:
                for faint, opaque in [(False, False), (True, False), (False, True)]:
                    image = render(source, 54 if resampled else edge, mirror, faint, opaque)
                    if resampled:
                        image = image.resize((edge, edge), Image.Resampling.BILINEAR)
                    aspect, rows, strong_rows = normalize(image, opaque)
                    key = (output, direction, aspect, rows, strong_rows)
                    if key in seen:
                        continue
                    seen.add(key)
                    references.append((label, *key))
    negative = []
    for name in ["da_turn_unknown", "da_turn_ferry", "ferry_train"]:
        source, digest, apk_entry = source_bytes(name)
        fixture = fixtures / (name + ".png")
        render(source, 54, False).save(fixture)
        negative.append({"name": name, "iconId": -1,
                         "sourceSha256": digest, "apkEntry": apk_entry,
                         "pngSha256": hashlib.sha256(fixture.read_bytes()).hexdigest()})
    manifest = {"apkSha256": APK_SHA256, "package": "app.morphe.android.apps.maps",
                "version": "26.33.02.961351034", "renderer": "CairoSVG " + cairosvg.__version__,
                "fixtureEdge": 54, "entries": entries, "negative": negative}
    (fixtures / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    kotlin = ["package com.byd.dashcast.hud", "",
              "/** Generated Maps 26.33 recognition masks; no APK resource IDs or runtime dependency.",
              " * Regenerate with tools/navigation/build_maps_corpus.py after auditing its source.",
              " * Half-alpha and opaque-background variants account for notification rendering.",
              " */", "internal object MapsManeuverReferences {",
              "    data class Reference(val iconId: Int, val clockwise: Boolean?,",
              "                         val aspect: Double, val rows: IntArray, val strongRows: IntArray)", "",
              "    private fun rows(bits: String) = bits.split(',').map { it.toUInt(16).toInt() }.toIntArray()",
              "    private fun reference(icon: Int, clockwise: Boolean?, aspect: Double, bits: String, strong: String) =",
              "        Reference(icon, clockwise, aspect, rows(bits), rows(strong))",
              "", "    val entries: List<Reference> = listOf("]
    for label, icon, clockwise, aspect, rows, strong_rows in references:
        direction = "null" if clockwise is None else str(clockwise).lower()
        kotlin += [f"        // {label}",
                   f'        reference({icon}, {direction}, {aspect:.12f}, "{rows}",',
                   f'            "{strong_rows}"),']
    kotlin += ["    )", "}", ""]
    (args.repo / "app/src/main/java/com/byd/dashcast/hud/MapsManeuverReferences.kt").write_text("\n".join(kotlin))
    print(f"Generated {len(entries)} labelled fixtures, {len(negative)} negatives, {len(references)} bounded masks")


if __name__ == "__main__":
    main()
