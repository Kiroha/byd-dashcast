#!/usr/bin/env python3
"""Fidelity, quarantine and complete final-resource checks for the perf bench."""
import json
import subprocess
import sys
import unittest

import build_minimal_app as baseline
import build_minimal_perf as perf
import build_live_cohort_marker as cohort
import build_live_marker as rcc
from test_full_app import factory_sources


def candidate(output=perf.OUT):
    manifest = json.loads((output / perf.MANIFEST).read_bytes())
    carrier = (output / manifest["carrier_filename"]).read_bytes()
    if rcc.sha(carrier) != manifest["carrier_sha256"]:
        raise ValueError("Final carrier digest differs")
    rows = rcc.decode(carrier)
    if len({row[0] for row in rows}) != len(rows):
        raise ValueError("Duplicate resource alias")
    return manifest, carrier, {row[0]: row for row in rows}


class PerfSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.legacy, cls.originals = factory_sources()

    def test_complete_source_reversal_and_native_contract_inventory(self):
        original = self.originals[perf.LARGE_ROOT]
        patched = perf.patch_root(original)
        self.assertEqual(original, perf.restore_root(patched))
        self.assertEqual({name: perf.block_text(name) for name in perf.PATCHES},
                         perf.blocks(patched.decode()))
        before, after = map(baseline.source_contract, (original, patched))
        self.assertEqual(before["imports"], after["imports"])
        for key in ("ids", "functions", "properties"):
            self.assertTrue(set(before[key]) <= set(after[key]), key)
        self.assertEqual(1, after["load_complete_count"])
        # The pre-existing Loader body is restored byte-for-byte, not replaced.
        self.assertEqual(perf.loader_object(original.decode()),
                         perf.loader_object(perf.restore_root(patched).decode()))
        self.assertEqual(1, perf.loader_object(patched.decode()).count("active:"))

    def test_invalid_inputs_and_undeclared_changes_fail_closed(self):
        original = self.originals[perf.LARGE_ROOT]
        with self.assertRaisesRegex(ValueError, "factory"):
            perf.patch_root(original + b"mutation")
        patched = perf.patch_root(original)
        self.assertNotEqual(original, perf.restore_root(patched.replace(
            b"RECV_MSG_ID_QML_LOAD_COMPLETE", b"RECV_MSG_ID_OTHER", 1)))
        for value in (patched.replace(perf.marker("INITIAL", "BEGIN").encode(), b"// absent", 1),
                      patched + perf.marker("ADAS_ACTIVE", "BEGIN").encode()):
            with self.assertRaises(ValueError):
                perf.restore_root(value)

    def test_no_new_service_or_explicit_native_lifetime_calls(self):
        added = "\n".join(perf.block_text(name) for name in perf.PATCHES)
        for forbidden in ("Timer", "Connections", ".destroy(", "createObject", "createComponent",
                          "sendPluginMsg", "AndroidNativeWindowItem", "Binding", "powerGear",
                          "gearMode", "naviType", "speed", "presentationLoaderId.source"):
            self.assertNotIn(forbidden, added)
        self.assertIn("dashcastBenchProjectionActive: false", added)
        self.assertIn("finally", added)
        self.assertIn("active: !rootItemId.dashcastBenchSuspendVisuals", added)

    def test_cli_rejects_runtime_output_before_reading_inputs(self):
        result = subprocess.run([sys.executable, str(perf.ROOT / "tools/cluster/build_minimal_perf.py"),
                                 "--stock", "/missing-stock", "--small-root", "/missing-small-root",
                                 "--large-root", "/missing-large-root", "--output", str(perf.ROOT / "app/perf-bench")],
                                capture_output=True, text=True, check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Offline artifacts must not be packaged as runtime assets", result.stderr)


class PerfArtifactTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (perf.OUT / perf.MANIFEST).exists():
            raise unittest.SkipTest("Build the separate offline perf candidate with exact factory inputs first")
        cls.legacy, cls.originals = factory_sources()
        cls.manifest, cls.carrier, cls.rows = candidate()

    def test_all_stored_translation_bytes_and_locale_fields_preserved(self):
        self.assertEqual(86, len(self.rows))
        self.assertEqual(84, len(self.manifest["resources"]))
        for path, record in self.manifest["resources"].items():
            row = self.rows[path]
            self.assertEqual(self.legacy[path], row, path)
            self.assertEqual({"sha256": rcc.sha(row[5]), "stored_sha256": rcc.sha(row[4]),
                              "country": row[1], "language": row[2], "flags": row[3]}, record)

    def test_every_final_patch_and_root_match_owned_and_factory_bytes(self):
        for path, original in self.originals.items():
            value = self.rows[perf.NAMESPACE + path][5]
            self.assertEqual(original, perf.restore_root(value) if path == perf.LARGE_ROOT else value)
            self.assertEqual(cohort.ORIGINALS[path][1], rcc.sha(original))
            self.assertEqual(baseline.source_contract(original),
                             self.manifest["roots"][perf.NAMESPACE + path]["source_contract"])
        actual = perf.blocks(self.rows[perf.NAMESPACE + perf.LARGE_ROOT][5].decode())
        self.assertEqual({name: perf.block_text(name) for name in perf.PATCHES}, actual)
        self.assertEqual({name: rcc.sha(value.encode()) for name, value in actual.items()}, self.manifest["patches"])

    def test_reproducibility_hash_rejection_and_quarantine(self):
        stock = (perf.OUT / perf.STOCK_REFERENCE).read_bytes()
        rebuilt = perf.build_artifacts(stock, self.originals)
        self.assertEqual(self.carrier, rebuilt[self.manifest["carrier_filename"]])
        self.assertEqual(self.manifest, json.loads(rebuilt[perf.MANIFEST]))
        for stock_input, roots in ((stock + b"mutation", self.originals),
                                   (stock, {key: value + b"mutation" for key, value in self.originals.items()})):
            with self.assertRaises(ValueError):
                perf.build_artifacts(stock_input, roots)
        self.assertEqual(set(self.manifest["resources"]) | {perf.NAMESPACE + path for path in self.originals},
                         set(self.rows))
        for path in self.originals:
            self.assertNotIn(path, self.rows)
        self.assertNotIn(baseline.PRESENTATION, self.rows)
        self.assertRegex(self.manifest["carrier_filename"], r"^OFFLINE_ONLY_MinimalPerf_[0-9a-f]{16}\.rcc$")
        self.assertFalse(perf.OUT.is_relative_to(perf.ROOT / "app"))
        for key in ("runtime_activation_allowed", "vehicle_validated", "road_use_admitted", "canonical_root_aliases"):
            self.assertIs(self.manifest[key], False)
        for key in ("native_pixels", "native_contract_semantics", "native_animation_cleanup",
                    "oem_restoration", "parked_proof", "vehicle_performance"):
            self.assertEqual("UNKNOWN", self.manifest[key])
        self.assertEqual("none", self.manifest["adas_control"])


if __name__ == "__main__":
    unittest.main()
