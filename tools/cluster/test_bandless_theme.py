#!/usr/bin/env python3
"""Independent carrier and exact-diff checks; no Qt, device, or firmware corpus needed."""
import difflib
import hashlib
import json
import re
import unittest
from pathlib import Path

import build_bandless_theme as builder
from test_live_marker import members


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts/cluster_bandless"
EXPECTED_ROOTS = {
    "qml/cluster.qml": "0fd1a95f4ec754aa29f53aad5ec540a87c07a4e6a725f09471433502284f6fdb",
    "qml10_25/cluster.qml": "62edd3cbdc59196614c6c69bb7dd4463b9f4bea2539e2a5361be443dbefdc82f",
}
ADDED_LINE = re.compile(
    rb"^ +opacity: DataSource\.powerGear === DataSource\.POWER_GEAR_ON && "
    rb"DataSource\.naviType === DataSource\.NAVI_TYPE_FULL_SCREEN \? 0 : 1\r\n$"
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


class BandlessThemeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((OUT / "manifest.json").read_text())
        cls.carrier = (OUT / "bandless-offline.rcc").read_bytes()
        cls.rows = members(cls.carrier)
        cls.originals = {}
        for path in EXPECTED_ROOTS:
            patched = cls.rows[path][4]
            cls.originals[path] = b"".join(
                line for line in patched.splitlines(keepends=True) if not ADDED_LINE.fullmatch(line)
            )

    def test_exact_inventory_and_offline_scope(self):
        self.assertEqual(86, len(self.rows))
        self.assertEqual(84, len(self.manifest["resources"]))
        self.assertEqual(set(self.manifest["resources"]) | set(EXPECTED_ROOTS), set(self.rows))
        self.assertTrue(all(path.startswith("common/language/") for path in self.manifest["resources"]))
        self.assertNotIn("qml/main.qml", self.rows)
        self.assertFalse(self.manifest["vehicle_validated"])
        self.assertFalse(self.manifest["diag_loadable"])
        self.assertEqual(digest(self.carrier), self.manifest["carrier_sha256"])
        self.assertEqual(len(self.carrier), self.manifest["carrier_size"])
        marker = (ROOT / "app/src/main/assets/cluster_live/marker.rcc").read_bytes()
        self.assertNotEqual(digest(marker), digest(self.carrier))

    def test_all_locale_bytes_and_metadata_equal_existing_stock_resources(self):
        marker_rows = members((ROOT / "app/src/main/assets/cluster_live/marker.rcc").read_bytes())
        for path, record in self.manifest["resources"].items():
            row = self.rows[path]
            self.assertEqual(marker_rows[path], row, path)
            self.assertEqual((record["country"], record["language"], record["flags"]), row[:3])
            self.assertEqual(record["stored_sha256"], digest(row[3]))
            self.assertEqual(record["sha256"], digest(row[4]))

    def test_exact_three_insertions_restore_each_original(self):
        expected_anchors = [b"id: bgImageContainerId", b"id: headLineId", b"id:driveRangeInfoBgId"]
        for path, original_sha in EXPECTED_ROOTS.items():
            patched = self.rows[path][4]
            original = self.originals[path]
            self.assertEqual(original_sha, digest(original), path)
            self.assertEqual(patched, (OUT / "patched" / path).read_bytes())
            before, after = original.splitlines(keepends=True), patched.splitlines(keepends=True)
            changes = [operation for operation in difflib.SequenceMatcher(
                a=before, b=after, autojunk=False
            ).get_opcodes() if operation[0] != "equal"]
            self.assertEqual(3, len(changes))
            actual_anchors = []
            for tag, start, end, new_start, new_end in changes:
                self.assertEqual(("insert", start, 1), (tag, end, new_end - new_start))
                self.assertRegex(after[new_start], ADDED_LINE)
                actual_anchors.append(after[new_start - 1].strip())
            self.assertEqual(expected_anchors, actual_anchors)
            self.assertIn(b"WarinLightLayout", original)
            self.assertIn(b"displayState: DataSource.lowPowerBatLight", original)
            self.assertIn(b"RECV_MSG_ID_QML_LOAD_COMPLETE", original)
            record = self.manifest["roots"][path]
            self.assertEqual(original_sha, record["original_sha256"])
            self.assertEqual(digest(patched), record["patched_sha256"])

    def test_unified_diffs_describe_only_the_insertions(self):
        for path in EXPECTED_ROOTS:
            patch = (OUT / "patches" / f"{path}.patch").read_bytes()
            self.assertEqual(digest(patch), self.manifest["roots"][path]["patch_sha256"])
            lines = patch.splitlines(keepends=True)
            added = [line[1:] for line in lines if line.startswith(b"+") and not line.startswith(b"+++")]
            removed = [line for line in lines if line.startswith(b"-") and not line.startswith(b"---")]
            self.assertEqual(3, len(added))
            self.assertFalse(removed)
            self.assertTrue(all(ADDED_LINE.fullmatch(line) for line in added))

    def test_background_and_warning_lifecycle_code_is_unchanged(self):
        for path, original in self.originals.items():
            patched = self.rows[path][4]
            for token in [b"DataSource.", b"WarningLight", b"visible", b"source =", b"onTriggered:"]:
                original_lines = [line for line in original.splitlines() if token in line]
                patched_lines = [line for line in patched.splitlines()
                                 if token in line and not line.lstrip().startswith(b"opacity: DataSource.")]
                self.assertEqual(original_lines, patched_lines, (path, token))
            # The exact pinned stock roots contain no writers that can break the new bindings.
            for identifier in [b"bgImageContainerId", b"headLineId", b"driveRangeInfoBgId"]:
                self.assertNotRegex(original, identifier + rb"\.opacity\s*=")
                self.assertNotRegex(original, rb"target:\s*" + identifier + rb"\b")

    def test_input_identity_rejects_unknown_or_already_patched_roots(self):
        for path in EXPECTED_ROOTS:
            original = self.originals[path]
            self.assertEqual(self.rows[path][4], builder.patch_root(path, original))
            for bad in [original + b"\n", self.rows[path][4], b""]:
                with self.assertRaises(ValueError):
                    builder.patch_root(path, bad)
        with self.assertRaises(ValueError):
            builder.patch_root("qml/main.qml", self.originals["qml/cluster.qml"])

    def test_rejects_unknown_stock(self):
        with self.assertRaisesRegex(ValueError, "stock"):
            builder.build_artifacts(b"qres invalid", self.originals)

    def test_checksums_cover_every_output(self):
        checksums = (OUT / "SHA256SUMS").read_text().splitlines()
        covered = set()
        for line in checksums:
            expected, path = line.split("  ", 1)
            covered.add(path)
            self.assertEqual(expected, digest((OUT / path).read_bytes()))
        actual = {str(path.relative_to(OUT)) for path in OUT.rglob("*") if path.is_file()}
        self.assertEqual(actual, covered | {"SHA256SUMS"})


if __name__ == "__main__":
    unittest.main()
