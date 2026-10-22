#!/usr/bin/env python3
"""Independent preservation checks for the physical full-surface test carrier."""
import hashlib
import json
import re
import unittest
from pathlib import Path

import build_full_surface_theme as builder
from test_live_marker import members


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/cluster_live"
OUT = ROOT / "artifacts/cluster_full_surface_test"
EXPECTED_SHA = "64a7a752b01e80a822610bf775eb656f79bd3378a6228b6e36c2490932049ddc"
EXPECTED_ROOTS = {
    "qml/cluster.qml": "0fd1a95f4ec754aa29f53aad5ec540a87c07a4e6a725f09471433502284f6fdb",
    "qml10_25/cluster.qml": "62edd3cbdc59196614c6c69bb7dd4463b9f4bea2539e2a5361be443dbefdc82f",
}
ADDED_LINE = re.compile(
    rb"^ +opacity: DataSource\.powerGear === DataSource\.POWER_GEAR_ON && "
    rb"DataSource\.naviType === DataSource\.NAVI_TYPE_FULL_SCREEN && "
    rb"DataSource\.dayNightMode === 0x02 \? 0 : 1\r\n$"
)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class FullSurfaceThemeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.carrier = (ASSETS / "full_surface_test.rcc").read_bytes()
        cls.manifest = json.loads((ASSETS / "full_surface_test_manifest.json").read_text())
        cls.rows = members(cls.carrier)

    def test_closed_identity_and_exact_inventory(self):
        self.assertEqual(EXPECTED_SHA, sha(self.carrier))
        self.assertEqual(EXPECTED_SHA, self.manifest["carrier_sha256"])
        self.assertEqual(86, len(self.rows))
        self.assertEqual(84, len(self.manifest["resources"]))
        self.assertEqual(set(self.manifest["resources"]) | set(EXPECTED_ROOTS), set(self.rows))
        self.assertEqual(self.carrier, (OUT / "full_surface_test.rcc").read_bytes())
        self.assertEqual((ASSETS / "full_surface_test_manifest.json").read_bytes(), (OUT / "manifest.json").read_bytes())
        self.assertNotIn("qml/main.qml", self.rows)
        self.assertNotIn(EXPECTED_SHA, (ASSETS / "manifest.json").read_text())

    def test_all_stock_locale_metadata_and_stored_bytes_are_preserved(self):
        stock_rows = members((ASSETS / "marker.rcc").read_bytes())
        for path, record in self.manifest["resources"].items():
            self.assertTrue(path.startswith("common/language/"))
            self.assertEqual(stock_rows[path], self.rows[path], path)
            country, language, flags, raw, decoded = self.rows[path]
            self.assertEqual((country, language, flags), (record["country"], record["language"], record["flags"]))
            self.assertEqual((sha(raw), sha(decoded)), (record["stored_sha256"], record["sha256"]))

    def test_only_new_background_and_three_decorative_bindings_differ(self):
        block = self.manifest["theme_block"].encode("ascii")
        for path, original_sha in EXPECTED_ROOTS.items():
            patched = self.rows[path][4]
            self.assertEqual(1, patched.count(block))
            without_block = patched.replace(block, b"", 1)
            lines = without_block.splitlines(keepends=True)
            added_indices = [index for index, line in enumerate(lines) if ADDED_LINE.fullmatch(line)]
            self.assertEqual(3, len(added_indices))
            self.assertEqual([b"id: bgImageContainerId", b"id: headLineId", b"id:driveRangeInfoBgId"],
                             [lines[index - 1].strip() for index in added_indices])
            original = b"".join(line for line in lines if not ADDED_LINE.fullmatch(line))
            self.assertEqual(original_sha, sha(original))
            self.assertEqual(original_sha, self.manifest["roots"][path]["original_sha256"])
            self.assertEqual(patched, (OUT / "patched" / path).read_bytes())
            self.assertEqual(patched, builder.patched_root(path, original))
            for bad in [original + b"\n", patched]:
                with self.assertRaises(ValueError):
                    builder.patched_root(path, bad)
            # This exact byte restoration includes OEM warnings, parents, callbacks and transforms.
            self.assertIn(b"WarinLightLayout", original)
            self.assertIn(b"DataSource.lowPowerBatLight", original)
            self.assertIn(b"RECV_MSG_ID_QML_LOAD_COMPLETE", original)

    def test_background_is_bounded_noninteractive_and_night_only(self):
        block = self.manifest["theme_block"].encode("ascii")
        self.assertIn(b"anchors.fill: parent", block)
        self.assertIn(b"z: CustomStyle.bgZ", block)
        self.assertIn(b'color: "#061018"', block)
        self.assertIn(b'text: "DASHCAST TEST"', block)
        self.assertIn(b"DataSource.dayNightMode === 0x02", block)
        self.assertIn(b"DataSource.powerGear === DataSource.POWER_GEAR_ON", block)
        self.assertIn(b"DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN", block)
        self.assertEqual(4, block.count(b"height: 12"))
        for forbidden in [b"MouseArea", b"Timer", b"Animation", b"sendPlugin", b"WarningLight", b"onCompleted", b"source:"]:
            self.assertNotIn(forbidden, block)
        self.assertFalse(self.manifest["vehicle_validated"])
        self.assertEqual(32, self.manifest["minimum_daemon_protocol"])

    def test_review_files_have_matching_checksums(self):
        covered = set()
        for line in (OUT / "SHA256SUMS").read_text().splitlines():
            expected, path = line.split("  ", 1)
            covered.add(path)
            self.assertEqual(expected, sha((OUT / path).read_bytes()))
        actual = {str(path.relative_to(OUT)) for path in OUT.rglob("*") if path.is_file()}
        self.assertEqual(actual, covered | {"SHA256SUMS"})


if __name__ == "__main__":
    unittest.main()
