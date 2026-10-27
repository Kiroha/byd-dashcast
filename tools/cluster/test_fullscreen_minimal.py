#!/usr/bin/env python3
"""Fidelity checks for the loadable profile derived from the minimal_perf lifecycle."""
import json
import unittest

import build_fullscreen_minimal as fullscreen
import build_live_cohort_marker as cohort
import build_live_marker as rcc
import build_minimal_app as baseline
import build_minimal_perf as perf


class FullscreenMinimalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.locales, cls.originals = fullscreen.factory_inputs()
        cls.artifacts = fullscreen.build_artifacts()
        cls.carrier = cls.artifacts[fullscreen.CARRIER]
        cls.manifest = json.loads(cls.artifacts[fullscreen.MANIFEST])
        cls.rows = {row[0]: row for row in rcc.decode(cls.carrier)}

    def test_committed_artifacts_are_reproducible(self):
        self.assertEqual(self.carrier, (fullscreen.OUT / fullscreen.CARRIER).read_bytes())
        self.assertEqual(self.manifest, json.loads((fullscreen.OUT / fullscreen.MANIFEST).read_text()))
        self.assertEqual(rcc.sha(self.carrier), self.manifest["carrier_sha256"])
        self.assertLess(len(self.carrier), 4 * 1024 * 1024)

    def test_canonical_roots_and_unchanged_stock_resources(self):
        self.assertEqual(86, len(self.rows))
        self.assertEqual(set(cohort.ORIGINALS), {path for path in self.rows if not path.startswith("common/language/")})
        for path, *record in self.locales:
            self.assertEqual(tuple(record), self.rows[path][1:])
        self.assertEqual(self.originals["qml/cluster.qml"], self.rows["qml/cluster.qml"][5])

    def test_root_patch_is_exactly_reversible_and_keeps_the_native_contract(self):
        original = self.originals[fullscreen.LARGE_ROOT]
        patched = self.rows[fullscreen.LARGE_ROOT][5]
        self.assertEqual(original, fullscreen.restore_root(patched))
        before, after = map(baseline.source_contract, (original, patched))
        self.assertEqual(before["imports"], after["imports"])
        for key in ("ids", "functions", "properties"):
            self.assertTrue(set(before[key]) <= set(after[key]), key)
        self.assertEqual(1, after["load_complete_count"])
        self.assertEqual(perf.loader_object(original.decode()), perf.loader_object(patched.decode()))

    def test_codex_lifecycle_is_reused_with_only_the_activation_input_changed(self):
        for name in fullscreen.PATCHES:
            ours, codex = fullscreen.block_text(name).splitlines(), perf.block_text(name).splitlines()
            changed = [(a, b) for a, b in zip(ours, codex) if a != b]
            self.assertEqual(len(codex), len(ours))
            self.assertEqual(2 if name == "DECLARATIONS" else 0, len(changed), name)
        self.assertNotIn("ADAS_ACTIVE", fullscreen.PATCHES)
        self.assertIn("readonly property bool dashcastBenchProjectionActive: " + fullscreen.CONDITION,
                      fullscreen.block_text("DECLARATIONS"))

    def test_no_new_service_or_native_lifetime_calls(self):
        added = "\n".join(fullscreen.block_text(name) for name in fullscreen.PATCHES)
        for forbidden in ("Timer", "Connections", ".destroy(", "createObject", "createComponent",
                          "sendPluginMsg", "AndroidNativeWindowItem", "Binding", "presentationLoaderId.source",
                          "opacity:", "adasLoaderId"):
            self.assertNotIn(forbidden, added)

    def test_unknown_or_mutated_inputs_fail_closed(self):
        original = self.originals[fullscreen.LARGE_ROOT]
        with self.assertRaisesRegex(ValueError, "factory"):
            fullscreen.patch_root(original + b"mutation")
        patched = fullscreen.patch_root(original)
        with self.assertRaises(ValueError):
            fullscreen.restore_root(patched + fullscreen.marker("INITIAL", "BEGIN").encode())


if __name__ == "__main__":
    unittest.main()
