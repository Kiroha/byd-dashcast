#!/usr/bin/env python3
"""Build a quarantined, non-installable minimal cluster bench RCC, offline only."""
import argparse
import json
import re
import struct
import zlib
from pathlib import Path

import build_full_dashboard as qml
import build_live_cohort_marker as cohort
import build_live_marker as rcc

ROOT = Path(__file__).resolve().parents[2]
OWNED = Path(__file__).with_name("minimal_app")
OUT = ROOT / "artifacts/cluster_minimal_app_bench"
NAMESPACE = "offline/minimal-bench/"
CHILD = NAMESPACE + "MinimalApp.qml"
PRESENTATION = "qml10_25/alwaysDisplay/Presentation.qml"
PRESENTATION_SOURCE = NAMESPACE + "dependencies/FactoryPresentation.qml.source"
PRESENTATION_SHA = "72406fdda39b5eef7242d9da7dfba84a540eb801ed6919719a46c0015bcf33b8"
LEGACY_SHA = "b121e6622f560a04a583c352977813ebc69047635f28cc0dee77ffeec78d007b"
VARIANT = "OFFLINE-ONLY-minimal-1920x720-21f7735c-v1"
MANIFEST = "OFFLINE_ONLY_minimal_1920x720_manifest.json"
STOCK_REFERENCE = "factory_translation_input.rcc"
INITIAL = "dashcastBenchConstructed = true;\ndashcastContainOemChildren();"
PATCHES = ("DECLARATIONS", "INITIAL", "LOADER")


def marker(name, edge):
    return "// DASHCAST_MINIMAL_BENCH_" + name + "_" + edge


def marked(name, text, indent):
    space = " " * indent
    return (marker(name, "BEGIN") + "\r\n" +
            "\r\n".join(space + line for line in text.splitlines()) +
            "\r\n" + space + marker(name, "END"))


def block_text(name):
    if name == "INITIAL":
        return INITIAL
    filename = "RootContainment.qml.inc" if name == "DECLARATIONS" else "PresentationLoader.qml.inc"
    return (OWNED / filename).read_text().strip()


def blocks(root):
    result = {}
    for name in PATCHES:
        matches = re.findall(re.escape(marker(name, "BEGIN")) + r"\r\n(.*?)\r\n[ ]*" +
                             re.escape(marker(name, "END")), root, re.S)
        if len(matches) != 1:
            raise ValueError("Exactly one complete patch required: " + name)
        indent = 8 if name == "INITIAL" else 4
        lines = matches[0].split("\r\n")
        if any(line and not line.startswith(" " * indent) for line in lines):
            raise ValueError("Unexpected patch indentation: " + name)
        result[name] = "\n".join(line[indent:] for line in lines)
    if root.count("// DASHCAST_MINIMAL_BENCH_") != 6:
        raise ValueError("Unexpected owned marker outside the three patches")
    return result


def presentation_object(text):
    matches = [obj for obj in qml.qml_objects(text) if obj[1] == 1 and
               re.match(r"Loader\s*\{\s*id:\s*presentationLoaderId\b", text[obj[2]:])]
    if len(matches) != 1:
        raise ValueError("The exact single root presentation Loader is required")
    obj = matches[0]
    old = text[obj[2]:qml.brace_end(text, obj[3])]
    expected = ('Loader{\r\n        id:presentationLoaderId\r\n'
                '        z:CustomStyle.presentationZ\r\n'
                '        source: DataSource.dilinkName === 0x02?"qrc:/' + PRESENTATION + '":""\r\n'
                '        asynchronous: true\r\n    }')
    if old != expected:
        raise ValueError("The factory presentation Loader changed")
    return obj, old


def patch_root(original):
    text = original.decode("utf-8")
    obj, _ = presentation_object(text)
    text = text[:obj[2]] + marked("LOADER", block_text("LOADER"), 4) + text[qml.brace_end(text, obj[3]):]
    for anchor, name, indent in (("    id:rootItemId\r\n", "DECLARATIONS", 4),
                                 ("    Component.onCompleted: {\r\n", "INITIAL", 8)):
        matches = list(re.finditer("(?m)^" + re.escape(anchor), text))
        if len(matches) != 1:
            raise ValueError("A unique exact root anchor is required: " + name)
        offset = matches[0].end()
        text = text[:offset] + " " * indent + marked(name, block_text(name), indent) + "\r\n" + text[offset:]
    if blocks(text) != {name: block_text(name) for name in PATCHES}:
        raise ValueError("Patch extraction differs from owned sources")
    return text.encode("utf-8")


def restore_root(patched, original):
    """Reverse only the declared changes; any other mutation remains detectable."""
    text = patched.decode("utf-8")
    blocks(text)
    _, loader = presentation_object(original.decode("utf-8"))
    text = re.sub(re.escape(marker("LOADER", "BEGIN")) + r".*?" +
                  re.escape(marker("LOADER", "END")), lambda _: loader, text, flags=re.S)
    for name, indent in (("DECLARATIONS", 4), ("INITIAL", 8)):
        text = re.sub(" " * indent + re.escape(marker(name, "BEGIN")) + r".*?" +
                      re.escape(marker(name, "END")) + r"\r\n", "", text, flags=re.S)
    return text.encode("utf-8")


def source_contract(source):
    """Source inventory only: visual parent/anchor/native semantics stay unknown."""
    text = source.decode("utf-8")
    return {
        "imports": [line for line in text.splitlines() if line.lstrip("\ufeff").startswith("import ")],
        "ids": re.findall(r"(?m)^\s*id:\s*(\w+)", text),
        "functions": re.findall(r"\bfunction\s+(\w+)\s*\(", text),
        "properties": re.findall(r"(?m)^\s*(?:readonly\s+)?property\s+\w+\s+(\w+)\s*:", text),
        "load_complete_count": text.count("DataSource.sendPluginMsgId(DataSource.RECV_MSG_ID_QML_LOAD_COMPLETE)"),
    }


def build_artifacts(stock, originals, presentation):
    if rcc.sha(stock) != cohort.STOCK_SHA:
        raise ValueError("Exact stock translation RCC required")
    if set(originals) != set(cohort.ORIGINALS):
        raise ValueError("Both exact cohort roots required")
    for path, value in originals.items():
        if rcc.sha(value) != cohort.ORIGINALS[path][1]:
            raise ValueError("Unknown factory root: " + path)
    if rcc.sha(presentation) != PRESENTATION_SHA:
        raise ValueError("Exact factory Presentation source required as inert evidence")
    rows = rcc.decode(stock)
    if len(rows) != 84 or any(not row[0].startswith("common/language/") for row in rows):
        raise ValueError("Exact factory locale inventory required")
    manifest = {
        "schema": 1, "variant": VARIANT, "runtime_activation_allowed": False,
        "vehicle_validated": False, "runtime_operation": None, "minimum_daemon_protocol": None,
        "native_size": [1920, 720], "application_rect": {"x": 0, "y": 0, "width": 1920, "height": 720},
        "qml_visual_intent": "application only; absent/failed content is opaque black",
        "native_pixels": "UNKNOWN", "native_contract_semantics": "UNKNOWN",
        "oem_restoration": "UNKNOWN", "parked_proof": "UNKNOWN", "road_use_admitted": False,
        "adas_control": "none", "canonical_root_aliases": False,
        "stock_sha256": cohort.STOCK_SHA, "resources": {}, "roots": {}, "owned_resources": {},
        "patches": {name: rcc.sha(block_text(name).encode()) for name in PATCHES},
        "factory_presentation": {"runtime_path": PRESENTATION, "evidence_path": PRESENTATION_SOURCE,
                                 "sha256": PRESENTATION_SHA, "executed": False},
        "retired_profile": {"sha256": LEGACY_SHA, "purpose": "recovery-only; never modify or reactivate"},
    }
    for path, country, language, flags, raw, value in rows:
        manifest["resources"][path] = {"sha256": rcc.sha(value), "stored_sha256": rcc.sha(raw),
                                      "country": country, "language": language, "flags": flags}
    for path, original in originals.items():
        value = patch_root(original) if path == "qml10_25/cluster.qml" else original
        alias = NAMESPACE + path
        rows.append((alias, 0, 1, 1, struct.pack(">I", len(value)) + zlib.compress(value, 9), value))
        manifest["roots"][alias] = {"original_path": path, "original_sha256": rcc.sha(original),
                                    "patched_sha256": rcc.sha(value), "source_contract": source_contract(original)}
    for path, value in ((CHILD, (OWNED / "MinimalApp.qml").read_bytes()),
                        (PRESENTATION_SOURCE, presentation)):
        rows.append((path, 0, 1, 0, value, value))
        manifest["owned_resources"][path] = {"sha256": rcc.sha(value), "executed": path == CHILD}
    carrier = rcc.build(rows)
    if len(carrier) >= 4 * 1024 * 1024:
        raise ValueError("Oversized offline carrier")
    digest = rcc.sha(carrier)
    filename = "OFFLINE_ONLY_Minimal1920x720_" + digest[:16] + ".rcc"
    manifest.update(carrier_sha256=digest, carrier_size=len(carrier), carrier_filename=filename)
    return {filename: carrier, MANIFEST: (json.dumps(manifest, indent=2) + "\n").encode(), STOCK_REFERENCE: stock}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--small-root", type=Path, required=True)
    parser.add_argument("--large-root", type=Path, required=True)
    parser.add_argument("--presentation-source", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.output.resolve().is_relative_to((ROOT / "app").resolve()):
        raise ValueError("Offline artifacts must not be packaged as runtime assets")
    artifacts = build_artifacts(args.stock.read_bytes(), {
        "qml/cluster.qml": args.small_root.read_bytes(), "qml10_25/cluster.qml": args.large_root.read_bytes(),
    }, args.presentation_source.read_bytes())
    if not args.check:
        args.output.mkdir(parents=True, exist_ok=True)
    for name, value in artifacts.items():
        target = args.output / name
        if args.check:
            if target.read_bytes() != value:
                raise SystemExit("Offline artifact is not reproducible: " + name)
        else:
            target.write_bytes(value)
        print(name, len(value), rcc.sha(value))


if __name__ == "__main__":
    main()
