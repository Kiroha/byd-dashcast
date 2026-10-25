#!/usr/bin/env python3
"""Build a separate, non-installable existing-Loader lifecycle experiment."""
import argparse
import json
import re
import struct
import zlib
from pathlib import Path

import build_full_dashboard as qml
import build_live_cohort_marker as cohort
import build_live_marker as rcc
import build_minimal_app as baseline

ROOT = Path(__file__).resolve().parents[2]
OWNED = Path(__file__).with_name("minimal_perf")
OUT = ROOT / "artifacts/cluster_minimal_perf_bench"
NAMESPACE = "offline/minimal-perf-bench/"
LARGE_ROOT = "qml10_25/cluster.qml"
MANIFEST = "OFFLINE_ONLY_minimal_perf_manifest.json"
STOCK_REFERENCE = "factory_translation_input.rcc"
VARIANT = "OFFLINE-ONLY-existing-adas-loader-21f7735c-v1"
PATCHES = {"DECLARATIONS": (4, "    id:rootItemId\r\n"),
           "INITIAL": (8, "    Component.onCompleted: {\r\n"),
           "ADAS_ACTIVE": (12, "            id:adasLoaderId\r\n")}


def marker(name, edge):
    return "// DASHCAST_MINIMAL_PERF_" + name + "_" + edge


def block_text(name):
    if name == "DECLARATIONS":
        return (OWNED / "RootLifecycle.qml.inc").read_text().strip()
    if name == "INITIAL":
        return "dashcastBenchConstructed = true;\ndashcastReconcileVisuals();"
    if name == "ADAS_ACTIVE":
        return "active: !rootItemId.dashcastBenchSuspendVisuals"
    raise ValueError("Unknown owned block: " + name)


def marked(name):
    indent = " " * PATCHES[name][0]
    return (indent + marker(name, "BEGIN") + "\r\n" +
            "\r\n".join(indent + line for line in block_text(name).splitlines()) +
            "\r\n" + indent + marker(name, "END") + "\r\n")


def blocks(text):
    result = {}
    for name, (indent, _) in PATCHES.items():
        matches = re.findall(re.escape(marker(name, "BEGIN")) + r"\r\n(.*?)\r\n[ ]*" +
                             re.escape(marker(name, "END")), text, re.S)
        if len(matches) != 1:
            raise ValueError("Exactly one complete owned block required: " + name)
        lines = matches[0].split("\r\n")
        if any(not line.startswith(" " * indent) for line in lines):
            raise ValueError("Unexpected owned block indentation: " + name)
        result[name] = "\n".join(line[indent:] for line in lines)
    if text.count("// DASHCAST_MINIMAL_PERF_") != 6:
        raise ValueError("Unexpected owned marker")
    return result


def loader_object(text):
    matches = [obj for obj in qml.qml_objects(text) if obj[0] == "Loader" and
               re.match(r"Loader\s*\{\s*id:\s*adasLoaderId\b", text[obj[2]:])]
    if len(matches) != 1 or matches[0][1] != 2:
        raise ValueError("The exact existing nested ADAS Loader is required")
    obj = matches[0]
    return text[obj[2]:qml.brace_end(text, obj[3])]


def patch_root(original):
    if rcc.sha(original) != cohort.ORIGINALS[LARGE_ROOT][1]:
        raise ValueError("Unknown factory large root")
    text = original.decode("utf-8")
    if re.search(r"\bactive\s*:", loader_object(text)):
        raise ValueError("Factory Loader already has an active binding")
    for name, (_, anchor) in PATCHES.items():
        matches = list(re.finditer("(?m)^" + re.escape(anchor), text))
        if len(matches) != 1:
            raise ValueError("Unique exact factory anchor required: " + name)
        offset = matches[0].end()
        text = text[:offset] + marked(name) + text[offset:]
    if blocks(text) != {name: block_text(name) for name in PATCHES}:
        raise ValueError("Final owned source differs")
    return text.encode("utf-8")


def restore_root(patched):
    text = patched.decode("utf-8")
    blocks(text)
    for name, (indent, _) in PATCHES.items():
        text = re.sub(" " * indent + re.escape(marker(name, "BEGIN")) + r".*?" +
                      re.escape(marker(name, "END")) + r"\r\n", "", text, flags=re.S)
    return text.encode("utf-8")


def build_artifacts(stock, originals):
    if rcc.sha(stock) != cohort.STOCK_SHA:
        raise ValueError("Exact stock translation RCC required")
    if set(originals) != set(cohort.ORIGINALS):
        raise ValueError("Both exact factory roots required")
    for path, original in originals.items():
        if rcc.sha(original) != cohort.ORIGINALS[path][1]:
            raise ValueError("Unknown factory root: " + path)
    rows = rcc.decode(stock)
    if len(rows) != 84 or any(not row[0].startswith("common/language/") for row in rows):
        raise ValueError("Exact stock locale inventory required")
    manifest = {
        "schema": 1, "variant": VARIANT, "runtime_activation_allowed": False,
        "vehicle_validated": False, "road_use_admitted": False,
        "runtime_operation": None, "minimum_daemon_protocol": None,
        "canonical_root_aliases": False, "native_size": [1920, 720],
        "native_pixels": "UNKNOWN", "native_contract_semantics": "UNKNOWN",
        "native_animation_cleanup": "UNKNOWN",
        "oem_restoration": "UNKNOWN", "parked_proof": "UNKNOWN",
        "vehicle_performance": "UNKNOWN", "adas_control": "none",
        "activation_input": "owned synthetic benchProjectionActive; default false",
        "optimization": "existing root adasLoaderId.active only",
        "center_children": "retained; creation, native services and work unchanged",
        "presentation": "factory Loader, source and native consumer ownership unchanged",
        "stock_sha256": cohort.STOCK_SHA, "resources": {}, "roots": {},
        "patches": {name: rcc.sha(block_text(name).encode()) for name in PATCHES},
        "retired_profile_sha256": baseline.LEGACY_SHA,
    }
    for path, country, language, flags, raw, value in rows:
        manifest["resources"][path] = {"sha256": rcc.sha(value), "stored_sha256": rcc.sha(raw),
                                      "country": country, "language": language, "flags": flags}
    for path, original in originals.items():
        value = patch_root(original) if path == LARGE_ROOT else original
        alias = NAMESPACE + path
        rows.append((alias, 0, 1, 1, struct.pack(">I", len(value)) + zlib.compress(value, 9), value))
        manifest["roots"][alias] = {"original_path": path, "original_sha256": rcc.sha(original),
                                    "patched_sha256": rcc.sha(value),
                                    "source_contract": baseline.source_contract(original)}
    carrier = rcc.build(rows)
    if len(carrier) >= 4 * 1024 * 1024:
        raise ValueError("Oversized offline carrier")
    digest = rcc.sha(carrier)
    filename = "OFFLINE_ONLY_MinimalPerf_" + digest[:16] + ".rcc"
    manifest.update(carrier_filename=filename, carrier_size=len(carrier), carrier_sha256=digest)
    return {filename: carrier, MANIFEST: (json.dumps(manifest, indent=2) + "\n").encode(), STOCK_REFERENCE: stock}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--small-root", type=Path, required=True)
    parser.add_argument("--large-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.output.resolve().is_relative_to((ROOT / "app").resolve()):
        raise ValueError("Offline artifacts must not be packaged as runtime assets")
    artifacts = build_artifacts(args.stock.read_bytes(), {
        "qml/cluster.qml": args.small_root.read_bytes(), LARGE_ROOT: args.large_root.read_bytes()})
    if not args.check:
        args.output.mkdir(parents=True, exist_ok=True)
    for name, value in artifacts.items():
        path = args.output / name
        if args.check:
            if path.read_bytes() != value:
                raise SystemExit("Offline artifact is not reproducible: " + name)
        else:
            path.write_bytes(value)
        print(name, len(value), rcc.sha(value))


if __name__ == "__main__":
    main()
