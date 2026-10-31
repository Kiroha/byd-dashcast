#!/usr/bin/env python3
"""Independent preservation checks for the offline full-screen map profile that keeps every BYD indicator."""
import hashlib
import json
import re
import unittest
from pathlib import Path

import build_fullscreen_instruments as builder
import build_live_cohort_marker as cohort
from test_live_marker import members

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/cluster_live"
OUT = builder.OUT
EXPECTED_SHA = "f8c0d5df43b207258d470b389648ef400cf2bd179205af39dce4849749c468ba"
ADDED = re.compile(rb"^ +opacity: " + re.escape(builder.CONDITION.encode()) + rb" \? 0 : 1\r\n$")


def sha(data):
    return hashlib.sha256(data).hexdigest()


class FullscreenInstrumentsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.carrier = (OUT / builder.CARRIER).read_bytes()
        cls.manifest = json.loads((OUT / "manifest.json").read_text())
        cls.rows = members(cls.carrier)

    def test_closed_identity_loadable_asset_and_bounded_size(self):
        self.assertEqual(EXPECTED_SHA, sha(self.carrier))
        self.assertEqual((EXPECTED_SHA, len(self.carrier)),
                         (self.manifest["carrier_sha256"], self.manifest["carrier_size"]))
        self.assertLess(len(self.carrier), 4 * 1024 * 1024)
        self.assertEqual(86, len(self.rows))
        self.assertEqual(set(self.manifest["resources"]) | set(cohort.ORIGINALS), set(self.rows))
        self.assertFalse(self.manifest["vehicle_validated"])
        self.assertEqual(builder.MINIMUM_PROTOCOL, self.manifest["minimum_daemon_protocol"])
        self.assertEqual({"x": 0, "y": 0, "width": 1920, "height": 720}, self.manifest["application_rect"])
        self.assertFalse(self.manifest["native_instruments_hidden"])
        self.assertFalse(self.manifest["native_alerts_hidden"])
        self.assertEqual(self.carrier, (ASSETS / builder.ASSET).read_bytes())
        self.assertEqual((OUT / "manifest.json").read_bytes(), (ASSETS / builder.ASSET_MANIFEST).read_bytes())

    def test_all_84_current_locale_resources_are_byte_identical(self):
        stock = members((ASSETS / "corner_marker_21f7735c.rcc").read_bytes())
        for path, record in self.manifest["resources"].items():
            self.assertEqual(stock[path], self.rows[path], path)
            self.assertEqual(record["stored_sha256"], sha(self.rows[path][3]))

    def test_only_three_decorative_opacity_lines_differ(self):
        for path, (_, original_sha) in cohort.ORIGINALS.items():
            patched = self.rows[path][4]
            lines = patched.splitlines(keepends=True)
            added = [index for index, line in enumerate(lines) if ADDED.fullmatch(line)]
            self.assertEqual(["id: bgImageContainerId", "id: headLineId", "id:driveRangeInfoBgId"],
                             [lines[index - 1].strip().decode() for index in added])
            original = b"".join(line for line in lines if not ADDED.fullmatch(line))
            self.assertEqual(original_sha, sha(original))
            self.assertEqual(patched, builder.patch_root(path, original))
            self.assertEqual(patched, (OUT / "patched" / path).read_bytes())
            with self.assertRaises(ValueError):
                builder.patch_root(path, patched)
            text = original.decode("utf-8")
            for identifier in self.manifest["modified_ids"]:
                # A factory opacity writer or animation would silently override the new binding.
                self.assertNotRegex(text, identifier + r"\.opacity\s*=")
                self.assertNotRegex(text, r"target\s*:\s*" + identifier + r"\b")
            for kept in builder.KEPT:
                self.assertIn(f"id:{kept}" if f"id:{kept}" in text else f"id: {kept}", text)

    def test_condition_is_strict_and_full_projection_only(self):
        for term in ("DataSource.naviType === 0x04", "DataSource.screenSize === 0x02", "DataSource.powerGear === 0x03",
                     "DataSource.playAnim === true", "rootItemId.width === 1920", "rootItemId.height === 720"):
            self.assertIn(term, builder.CONDITION)
        self.assertNotIn("||", builder.CONDITION)
        self.assertNotIn("!==", builder.CONDITION)
        self.assertEqual(builder.CONDITION, self.manifest["condition"])

    def test_review_files_have_matching_checksums(self):
        covered = set()
        for line in (OUT / "SHA256SUMS").read_text().splitlines():
            expected, path = line.split("  ", 1)
            covered.add(path)
            self.assertEqual(expected, sha((OUT / path).read_bytes()))
        actual = {path.relative_to(OUT).as_posix() for path in OUT.rglob("*") if path.is_file()}
        self.assertEqual(actual, covered | {"SHA256SUMS"})


if __name__ == "__main__":
    unittest.main()
