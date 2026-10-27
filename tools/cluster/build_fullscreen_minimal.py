#!/usr/bin/env python3
"""Loadable full-screen profile built from the minimal_perf lifecycle (587d72f3), canonical aliases."""
import argparse
import json
import re
import struct
import zlib
from pathlib import Path

import build_full_app as fullapp
import build_live_cohort_marker as cohort
import build_live_marker as rcc
import build_minimal_app as baseline
import build_minimal_perf as perf

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/cluster_live"
CARRIER = "fullscreen_minimal_21f7735c.rcc"
MANIFEST = "fullscreen_minimal_21f7735c_manifest.json"
LARGE_ROOT = perf.LARGE_ROOT
# The Codex ADAS_ACTIVE block stays out: CenterChild destruction is an unresolved hardware blocker.
PATCHES = {name: perf.PATCHES[name] for name in ("DECLARATIONS", "INITIAL")}
BENCH_INPUT = ("// PC bench input only: no Android frame, parked or movement admission.\n"
               "property bool dashcastBenchProjectionActive: false\n")
CONDITION = ("DataSource.naviType === 0x04 && rootItemId.width === 1920 && rootItemId.height === 720 && "
             "DataSource.powerGear === 0x03 && DataSource.playAnim === true")
LIVE_INPUT = ("// Full-screen projection mode only; not an Android-frame, parked or movement signal.\n"
              "readonly property bool dashcastBenchProjectionActive: " + CONDITION + "\n")


def marker(name, edge):
    return "// DASHCAST_FULLSCREEN_MINIMAL_" + name + "_" + edge


def block_text(name):
    text = perf.block_text(name)
    if name == "DECLARATIONS":
        if text.count(BENCH_INPUT) != 1:
            raise ValueError("The minimal_perf synthetic input changed")
        text = text.replace(BENCH_INPUT, LIVE_INPUT, 1)
    return text


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
    if text.count("// DASHCAST_FULLSCREEN_MINIMAL_") != 2 * len(PATCHES):
        raise ValueError("Unexpected owned marker")
    return result


def patch_root(original):
    if rcc.sha(original) != cohort.ORIGINALS[LARGE_ROOT][1]:
        raise ValueError("Unknown factory large root")
    text = original.decode("utf-8")
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


def factory_inputs():
    """Stock locales and both roots, recovered from the retired full-app carrier as the Codex tests do."""
    legacy = (fullapp.LEGACY_ASSETS / "full_app_21f7735c.rcc").read_bytes()
    if rcc.sha(legacy) != fullapp.LEGACY_SHA:
        raise ValueError("Unexpected legacy carrier")
    expected = json.loads((fullapp.LEGACY_ASSETS / "full_app_21f7735c_manifest.json").read_text())["resources"]
    rows = rcc.decode(legacy)
    locales = [row for row in rows if row[0].startswith("common/language/")]
    if len(locales) != 84 or {row[0] for row in locales} != set(expected):
        raise ValueError("Exact stock locale inventory required")
    for path, country, language, flags, raw, value in locales:
        record = expected[path]
        if (record["sha256"], record["stored_sha256"], record["country"], record["language"], record["flags"]) != \
                (rcc.sha(value), rcc.sha(raw), country, language, flags):
            raise ValueError("Stock locale differs: " + path)
    named = {row[0]: row[5] for row in rows}
    roots = {}
    for path, (_, digest) in cohort.ORIGINALS.items():
        native = re.sub(r"\r\n    // DASHCAST_FULL_APP_BEGIN\r\n.*?\r\n    // DASHCAST_FULL_APP_END\r\n", "",
                        named[path].decode("utf-8"), flags=re.S).encode("utf-8")
        if rcc.sha(native) != digest:
            raise ValueError("Factory root cannot be recovered: " + path)
        roots[path] = native
    return locales, roots


def build_artifacts():
    locales, originals = factory_inputs()
    rows = list(locales)
    manifest = {
        "schema": 1, "variant": "fullscreen-minimal-21f7735c", "cohort": "21f7735c",
        "base": "tools/cluster/minimal_perf RootLifecycle (587d72f3) without ADAS_ACTIVE",
        "vehicle_validated": False, "minimum_daemon_protocol": 46, "canonical_root_aliases": True,
        "native_size": [1920, 720], "application_rect": {"x": 0, "y": 0, "width": 1920, "height": 720},
        "activation_input": CONDITION, "native_instruments_hidden": True, "native_alerts_hidden": True,
        "native_pixels": "UNKNOWN", "oem_restoration": "UNKNOWN", "parked_proof": "UNKNOWN",
        "adas_control": "none", "presentation": "factory Loader, source and native consumer unchanged",
        "stock_sha256": cohort.STOCK_SHA, "resources": {}, "roots": {},
        "patches": {name: rcc.sha(block_text(name).encode()) for name in PATCHES},
        "retired_profile_sha256": fullapp.LEGACY_SHA,
    }
    for path, country, language, flags, raw, value in locales:
        manifest["resources"][path] = {"sha256": rcc.sha(value), "stored_sha256": rcc.sha(raw),
                                      "country": country, "language": language, "flags": flags}
    for path, original in originals.items():
        value = patch_root(original) if path == LARGE_ROOT else original
        rows.append((path, 0, 1, 1, struct.pack(">I", len(value)) + zlib.compress(value, 9), value))
        manifest["roots"][path] = {"original_sha256": rcc.sha(original), "patched_sha256": rcc.sha(value),
                                   "source_contract": baseline.source_contract(original)}
    carrier = rcc.build(rows)
    if len(carrier) >= 4 * 1024 * 1024:
        raise ValueError("Carrier exceeds recovery bound")
    manifest.update(carrier_sha256=rcc.sha(carrier), carrier_size=len(carrier))
    return {CARRIER: carrier, MANIFEST: (json.dumps(manifest, indent=2) + "\n").encode()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    artifacts = build_artifacts()
    for name, value in artifacts.items():
        target = OUT / name
        if args.check:
            current = target.read_bytes()
            same = json.loads(current) == json.loads(value) if name.endswith(".json") else current == value
            if not same:
                raise SystemExit("Nonreproducible artifact: " + name)
        else:
            target.write_bytes(value)
        print(name, len(value), rcc.sha(value))


if __name__ == "__main__":
    main()
