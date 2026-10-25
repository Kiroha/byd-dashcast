#!/usr/bin/env python3
"""Independent whole-resource preservation checks for the observed 21f7735c cohort."""
import hashlib
import json
import unittest
from pathlib import Path

import build_corner_marker as corner
import build_live_cohort_marker as builder
import build_live_marker as rcc
from test_live_marker import members

ASSETS = Path(__file__).resolve().parents[2] / "app/src/main/assets/cluster_live"
EXPECTED = "fe069081f1d5c8374276aad2513b8862de61008f67fe6162fb2a4a351e00b734"
ROOTS = {"qml/cluster.qml": "76a2ad22efe281a6d73362d3a3c4e5f998d33e9dff210934f11e4e6071336fb3",
         "qml10_25/cluster.qml": "8c9cb017658a904dd34e5b2b1ff0654576031ff4f31d0a0dedd259da52a09631"}


class LiveCohortMarkerTest(unittest.TestCase):
    def test_exact_current_locale_payloads_flags_and_metadata_are_preserved(self):
        data = (ASSETS / "corner_marker_21f7735c.rcc").read_bytes()
        manifest = json.loads((ASSETS / "corner_marker_21f7735c_manifest.json").read_text())
        self.assertEqual(EXPECTED, hashlib.sha256(data).hexdigest())
        self.assertEqual((EXPECTED, len(data)), (manifest["carrier_sha256"], manifest["carrier_size"]))
        rows = members(data)
        self.assertEqual(86, len(rows))
        self.assertEqual(set(rows), set(manifest["resources"]) | set(ROOTS))
        self.assertEqual(84, len(manifest["resources"]))
        # Independent pin for all 84 resources normalized from the actual stock RCC.
        # It includes stored bytes, country/language and flags, excluding OEM tree padding.
        translations = rcc.build([row for row in rcc.decode(data) if row[0].startswith("common/language/")])
        self.assertEqual("9700b965312935fd7f3ba6fdc507a966ffa1f7895cd7f7d0bca3e8f824ed4fa2", hashlib.sha256(translations).hexdigest())
        for path, item in manifest["resources"].items():
            country, language, flags, raw, value = rows[path]
            self.assertEqual((country, language, flags), (item["country"], item["language"], item["flags"]))
            self.assertEqual(item["sha256"], hashlib.sha256(value).hexdigest())
            self.assertEqual(item["stored_sha256"], hashlib.sha256(raw).hexdigest())
        legacy = members((ASSETS / "corner_marker.rcc").read_bytes())
        self.assertTrue(all(rows[path][4] != legacy[path][4] for path in manifest["resources"]))
        self.assertFalse(manifest["vehicle_validated"])
        self.assertEqual(39, manifest["minimum_daemon_protocol"])
        self.assertEqual(builder.STOCK_SHA, manifest["stock_sha256"])

    def test_both_complete_current_roots_and_new_adas_messages_remain_byte_identical(self):
        rows = members((ASSETS / "corner_marker_21f7735c.rcc").read_bytes())
        for path, expected in ROOTS.items():
            marked = rows[path][4]
            self.assertEqual(1, marked.count(corner.MARKER))
            original = marked.replace(corner.MARKER, b"", 1)
            self.assertEqual(expected, hashlib.sha256(original).hexdigest())
            for code in [b"0x5B", b"0x5C", b"0x5D", b"0x5E"]:
                self.assertIn(b"case " + code + b":", original)
                self.assertIn(b"TranslateStr.adasTip_" + code, original)
            for functional in [b"WarinLightLayout", b"RECV_MSG_ID_QML_LOAD_COMPLETE", b"sendPluginMsgInt"]:
                self.assertIn(functional, original)
        self.assertNotIn("common/main.qml", rows)

    def test_builder_rejects_other_stock_before_constructing_any_resource(self):
        with self.assertRaises(ValueError):
            builder.build_artifacts(b"other", {})
        data = (ASSETS / "corner_marker_21f7735c.rcc").read_bytes()
        roots = {path: members(data)[path][4].replace(corner.MARKER, b"", 1) for path in ROOTS}
        # Hash checking happens before resource construction; no caller can substitute roots.
        with self.assertRaises(ValueError):
            builder.build_artifacts(data, roots)

    def test_all_four_existing_recovery_carriers_keep_their_exact_hashes(self):
        for path, expected in {
            ASSETS / "marker.rcc": "e631fe209209cf2e9c9c93df5106ad267e4f38fa223f4ac8d4f3e660f07b614f",
            ASSETS / "full_surface_test.rcc": "64a7a752b01e80a822610bf775eb656f79bd3378a6228b6e36c2490932049ddc",
            ASSETS / "corner_marker.rcc": "3f3052c4d79f5c14ed3b708921127ac07f91ac4b03e68c94ea176505b66eb6bb",
            ASSETS.parent / "experimental_theme/theme.rcc": "b6d20a0f11cdb224b14311aa77b92e6241cff5c247214cc45e0f55cb06109a55",
        }.items():
            self.assertEqual(expected, hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
