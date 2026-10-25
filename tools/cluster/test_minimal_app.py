#!/usr/bin/env python3
"""Source fidelity and FINAL RCC checks for the quarantined minimal bench."""
import json
import re
import unittest

import build_minimal_app as minimal
import build_full_app as preservation
import build_live_cohort_marker as cohort
import build_live_marker as rcc
from test_full_app import factory_sources


def candidate(output=minimal.OUT):
    manifest = json.loads((output / minimal.MANIFEST).read_bytes())
    carrier = (output / manifest["carrier_filename"]).read_bytes()
    if rcc.sha(carrier) != manifest["carrier_sha256"]:
        raise ValueError("Final carrier digest differs from manifest")
    rows = rcc.decode(carrier)
    if len({row[0] for row in rows}) != len(rows):
        raise ValueError("Duplicate resource alias")
    return manifest, carrier, {row[0]: row for row in rows}


class MinimalSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.legacy, cls.originals = factory_sources()

    def test_exact_source_reversal_covers_every_change(self):
        original = self.originals["qml10_25/cluster.qml"]
        patched = minimal.patch_root(original)
        self.assertEqual(original, minimal.restore_root(patched, original))
        extracted = minimal.blocks(patched.decode("utf-8"))
        self.assertEqual({name: minimal.block_text(name) for name in minimal.PATCHES}, extracted)
        original_contract = minimal.source_contract(original)
        contract = minimal.source_contract(patched)
        self.assertEqual(original_contract["imports"], contract["imports"])
        for key in ("ids", "properties", "functions"):
            self.assertTrue(set(original_contract[key]) <= set(contract[key]), key)
        self.assertEqual(1, contract["load_complete_count"])

    def test_mutations_outside_declared_blocks_are_detected(self):
        original = self.originals["qml10_25/cluster.qml"]
        patched = minimal.patch_root(original)
        mutations = (
            patched.replace(b"id:rootItemId", b"id:rootItemId\r\n    property int escaped: 1", 1),
            patched.replace(b"DataSource.RECV_MSG_ID_QML_LOAD_COMPLETE", b"DataSource.RECV_MSG_ID_DIFFERENT", 1),
            patched.replace(b"import QtQuick 2.15", b"import QtQuick 2.0", 1),
        )
        for mutation in mutations:
            self.assertNotEqual(original, minimal.restore_root(mutation, original))

    def test_missing_duplicate_or_extra_blocks_fail_closed(self):
        original = self.originals["qml10_25/cluster.qml"]
        patched = minimal.patch_root(original).decode("utf-8")
        for mutation in (patched.replace(minimal.marker("INITIAL", "BEGIN"), "// missing", 1),
                         patched + minimal.marker("LOADER", "BEGIN")):
            with self.assertRaises(ValueError):
                minimal.blocks(mutation)

    def test_retired_and_preservation_assets_are_untouched(self):
        self.assertEqual(minimal.LEGACY_SHA, rcc.sha((preservation.LEGACY_ASSETS / "full_app_21f7735c.rcc").read_bytes()))
        manifest = json.loads((preservation.OUT / preservation.MANIFEST).read_bytes())
        self.assertEqual(manifest["carrier_sha256"],
                         rcc.sha((preservation.OUT / manifest["carrier_filename"]).read_bytes()))


class MinimalArtifactTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (minimal.OUT / minimal.MANIFEST).exists():
            raise unittest.SkipTest("Build the offline candidate with hash-matched firmware inputs first")
        cls.legacy, cls.originals = factory_sources()
        cls.manifest, cls.carrier, cls.rows = candidate()

    def test_all_84_translations_preserve_stored_bytes_and_locale_metadata(self):
        self.assertEqual(84, len(self.manifest["resources"]))
        self.assertEqual(88, len(self.rows))
        for path, record in self.manifest["resources"].items():
            row = self.rows[path]
            self.assertEqual(self.legacy[path], row, path)
            self.assertEqual({"sha256": rcc.sha(row[5]), "stored_sha256": rcc.sha(row[4]),
                              "country": row[1], "language": row[2], "flags": row[3]}, record)

    def test_research_aliases_cannot_override_any_factory_root(self):
        self.assertEqual({minimal.NAMESPACE + path for path in cohort.ORIGINALS}, set(self.manifest["roots"]))
        for path in cohort.ORIGINALS:
            self.assertNotIn(path, self.rows)
        self.assertNotIn(minimal.PRESENTATION, self.rows)
        self.assertEqual(set(self.manifest["resources"]) | set(self.manifest["roots"]) |
                         {minimal.CHILD, minimal.PRESENTATION_SOURCE}, set(self.rows))
        self.assertFalse(self.manifest["canonical_root_aliases"])
        self.assertRegex(self.manifest["carrier_filename"], r"^OFFLINE_ONLY_Minimal1920x720_[0-9a-f]{16}\.rcc$")

    def test_final_root_byte_fidelity_and_exact_patch_sources(self):
        for path, original in self.originals.items():
            alias = minimal.NAMESPACE + path
            value = self.rows[alias][5]
            restored = minimal.restore_root(value, original) if path == "qml10_25/cluster.qml" else value
            self.assertEqual(original, restored)
            self.assertEqual(cohort.ORIGINALS[path][1], rcc.sha(restored))
            self.assertEqual(minimal.source_contract(original), self.manifest["roots"][alias]["source_contract"])
            self.assertEqual((0, 1, 1), self.rows[alias][1:4])
        blocks = minimal.blocks(self.rows[minimal.NAMESPACE + "qml10_25/cluster.qml"][5].decode("utf-8"))
        self.assertEqual({name: minimal.block_text(name) for name in minimal.PATCHES}, blocks)
        self.assertEqual({name: rcc.sha(value.encode()) for name, value in blocks.items()}, self.manifest["patches"])

    def test_full_rebuild_from_independently_reconstructed_factory_inputs(self):
        translations = (minimal.OUT / minimal.STOCK_REFERENCE).read_bytes()
        self.assertEqual(cohort.STOCK_SHA, rcc.sha(translations))
        rebuilt = minimal.build_artifacts(translations, self.originals, self.rows[minimal.PRESENTATION_SOURCE][5])
        self.assertEqual(self.carrier, rebuilt[self.manifest["carrier_filename"]])
        self.assertEqual(self.manifest, json.loads(rebuilt[minimal.MANIFEST]))

    def test_hash_mismatched_inputs_are_rejected(self):
        stock = (minimal.OUT / minimal.STOCK_REFERENCE).read_bytes()
        presentation = self.rows[minimal.PRESENTATION_SOURCE][5]
        with self.assertRaisesRegex(ValueError, "translation"):
            minimal.build_artifacts(stock + b"mutation", self.originals, presentation)
        with self.assertRaisesRegex(ValueError, "factory root"):
            minimal.build_artifacts(stock, {key: value + b"mutation" for key, value in self.originals.items()}, presentation)
        with self.assertRaisesRegex(ValueError, "Presentation"):
            minimal.build_artifacts(stock, self.originals, presentation + b"mutation")

    def test_owned_canvas_is_blank_and_single_consumer_is_external(self):
        child = self.rows[minimal.CHILD][5]
        self.assertEqual((minimal.OWNED / "MinimalApp.qml").read_bytes(), child)
        self.assertEqual(minimal.PRESENTATION_SHA, rcc.sha(self.rows[minimal.PRESENTATION_SOURCE][5]))
        text = child.decode()
        for forbidden in ("Text {", "Image {", "Canvas {", "AndroidNativeWindowItem", "Binding", "Timer", "sendPluginMsg"):
            self.assertNotIn(forbidden, text)
        self.assertEqual(1, text.count('source: "qrc:/' + minimal.PRESENTATION + '"'))
        self.assertIn('color: "#000000"', text)
        declarations = minimal.blocks(self.rows[minimal.NAMESPACE + "qml10_25/cluster.qml"][5].decode())["DECLARATIONS"]
        self.assertIn("visible: false", declarations)
        self.assertIn("finally", declarations)
        self.assertNotIn("Binding", declarations)
        self.assertNotIn("Timer", declarations)

    def test_manifest_never_grants_runtime_or_pixel_admission(self):
        for key in ("runtime_activation_allowed", "vehicle_validated", "road_use_admitted"):
            self.assertIs(self.manifest[key], False)
        for key in ("runtime_operation", "minimum_daemon_protocol"):
            self.assertIsNone(self.manifest[key])
        for key in ("native_pixels", "native_contract_semantics", "oem_restoration", "parked_proof"):
            self.assertEqual("UNKNOWN", self.manifest[key])
        self.assertEqual("none", self.manifest["adas_control"])
        self.assertEqual({"x": 0, "y": 0, "width": 1920, "height": 720}, self.manifest["application_rect"])
        self.assertFalse(minimal.OUT.is_relative_to(minimal.ROOT / "app"))


if __name__ == "__main__":
    unittest.main()
