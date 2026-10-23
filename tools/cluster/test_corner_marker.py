#!/usr/bin/env python3
"""Independent preservation/geometry checks; never execute OEM QML or plugins."""
import hashlib
import json
import unittest
from pathlib import Path

import build_corner_marker as builder
from test_live_marker import members

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/cluster_live"
EXPECTED = "3f3052c4d79f5c14ed3b708921127ac07f91ac4b03e68c94ea176505b66eb6bb"
ORIGINALS = {"qml/cluster.qml": "0fd1a95f4ec754aa29f53aad5ec540a87c07a4e6a725f09471433502284f6fdb",
             "qml10_25/cluster.qml": "62edd3cbdc59196614c6c69bb7dd4463b9f4bea2539e2a5361be443dbefdc82f"}


class CornerMarkerTest(unittest.TestCase):
    def test_closed_carrier_preserves_every_stock_locale_and_both_complete_roots(self):
        data = (ASSETS / "corner_marker.rcc").read_bytes()
        manifest = json.loads((ASSETS / "corner_marker_manifest.json").read_text())
        rows = members(data)
        digest = lambda value: hashlib.sha256(value).hexdigest()
        self.assertEqual(EXPECTED, digest(data))
        self.assertEqual((EXPECTED, len(data)), (manifest["carrier_sha256"], manifest["carrier_size"]))
        self.assertEqual(set(rows), set(manifest["resources"]) | set(ORIGINALS))
        self.assertEqual(84, len(manifest["resources"]))
        old = members((ASSETS / "marker.rcc").read_bytes())
        for path in manifest["resources"]:
            self.assertTrue(path.startswith("common/language/"))
            self.assertEqual(old[path], rows[path], path)
        for path, original_sha in ORIGINALS.items():
            qml = rows[path][4]
            self.assertEqual(1, qml.count(builder.MARKER))
            self.assertEqual(original_sha, digest(qml.replace(builder.MARKER, b"", 1)))
        self.assertFalse(manifest["vehicle_validated"])
        self.assertEqual(38, manifest["minimum_daemon_protocol"])

    def test_only_thin_outer_corner_strokes_and_strict_guard_are_added(self):
        block = builder.MARKER
        self.assertEqual(8, block.count(b"Rectangle {"))
        self.assertEqual(4, block.count(b'color: "#00e5ff"'))
        self.assertEqual(4, block.count(b'color: "#ff40d5"'))
        self.assertEqual(4, block.count(b"width: 96; height: 4"))
        self.assertEqual(4, block.count(b"width: 4; height: 80"))
        self.assertIn(b"z: 1", block)
        self.assertIn(b"DataSource.powerGear === 3 && DataSource.playAnim === true && DataSource.gearMode === 1 && DataSource.speed === 0", block)
        for forbidden in [b"Text", b"Timer", b"MouseArea", b"onCompleted", b"Animation", b"sendPlugin", b"naviType", b"dayNightMode", b"opacity:"]:
            self.assertNotIn(forbidden, block)
        self.assertIn(b"anchors.fill: parent", block)
        self.assertIn(b"parent.width - width", block)
        self.assertIn(b"parent.height - height", block)

    def test_all_existing_carrier_identities_remain_unchanged(self):
        for path, expected in {
            ASSETS / "marker.rcc": "e631fe209209cf2e9c9c93df5106ad267e4f38fa223f4ac8d4f3e660f07b614f",
            ASSETS / "full_surface_test.rcc": "64a7a752b01e80a822610bf775eb656f79bd3378a6228b6e36c2490932049ddc",
            ROOT / "app/src/main/assets/experimental_theme/theme.rcc": "b6d20a0f11cdb224b14311aa77b92e6241cff5c247214cc45e0f55cb06109a55",
        }.items():
            self.assertEqual(expected, hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
