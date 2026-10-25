#!/usr/bin/env python3
"""Execution-free regression checks for the exact native dashboard carrier."""
import json
import re
import unittest
from pathlib import Path

import build_full_dashboard as dashboard
import build_live_marker as rcc


class NativeDashboardCarrierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.carrier = (dashboard.OUT / "full_dashboard_21f7735c.rcc").read_bytes()
        cls.manifest = json.loads((dashboard.OUT / "full_dashboard_21f7735c_manifest.json").read_bytes())
        cls.rows = {row[0]: row for row in rcc.decode(cls.carrier)}

    def test_all_locale_bytes_and_metadata_preserved(self):
        resources = self.manifest["resources"]
        self.assertEqual(84, len(resources))
        self.assertEqual(42, sum(name.endswith(".qm") for name in resources))
        self.assertEqual(42, sum(name.endswith(".ts") for name in resources))
        for path, expected in resources.items():
            _, country, language, flags, stored, value = self.rows[path]
            self.assertEqual((expected["country"], expected["language"], expected["flags"]), (country, language, flags))
            self.assertEqual(expected["stored_sha256"], rcc.sha(stored))
            self.assertEqual(expected["sha256"], rcc.sha(value))

    def test_factory_source_is_exact_after_removing_owned_insertions(self):
        for path, identity in self.manifest["roots"].items():
            text = self.rows[path][5].decode("utf-8")
            native = re.sub(r"\r\n    // DASHCAST_NATIVE_DASHBOARD_BEGIN\r\n.*?\r\n    // DASHCAST_NATIVE_DASHBOARD_END\r\n", "", text, flags=re.S)
            native = native.replace('import "qrc:/dashcast/DashboardLayout.js" as DashCastLayout\r\n', "")
            self.assertEqual(identity["original_sha256"], rcc.sha(native.encode("utf-8")), path)
        theme1 = self.manifest["roots"]["qml/cluster.qml"]
        self.assertEqual(theme1["original_sha256"], theme1["patched_sha256"])

    def test_all_live_telltales_and_nested_group_remain(self):
        text = self.rows[dashboard.WARNING_PATH][5].decode("utf-8")
        active = [item for item in dashboard.qml_objects(text) if item[0] == "WarningLight"]
        self.assertEqual(78, len(active))
        self.assertEqual(3, sum(item[1] == 2 for item in active))
        self.assertEqual(76, len(self.manifest["telltale_ids"]))
        self.assertTrue("id: koreaSeatLayout" in text)
        self.assertTrue("anchors.left: seatBeltLightIcon.right" in text)
        self.assertTrue("anchors.verticalCenter: seatBeltLightIcon.verticalCenter" in text)
        self.assertEqual(76, text.count("transform: Translate"))
        self.assertNotIn("mapToItem", text)
        self.assertNotIn("parent =", text)
        # Explicit comments retain the two dormant factory declarations.
        self.assertIn("/*    WarningLight", text)
        self.assertIn("/*WarningLight", text)

    def test_fail_closed_guard_and_original_telemetry_units(self):
        root = self.rows["qml10_25/cluster.qml"][5].decode("utf-8")
        self.assertIn("dashcastComponentReady && warningLayoutId.dashcastLayoutReady", root)
        self.assertIn("DataSource.powerGear === 0x03 && DataSource.playAnim === true", root)
        self.assertIn("alwyasDisplayId.visible && warningLayoutId.visible", root)
        for binding in ("text: DataSource.speed", "DataSource.speed === -1", "DataSource.speedUnit === 2",
                        "text: DataSource.power", "DataSource.power === 1001", "DataSource.powerUnit === 2",
                        "text: DataSource.outsideTemp"):
            self.assertIn(binding, root)
        self.assertNotIn("Date.now()", root)
        self.assertNotIn("WebView", root)

    def test_hash_and_app_rectangle_match_decoration(self):
        self.assertEqual(self.manifest["carrier_sha256"], rcc.sha(self.carrier))
        self.assertEqual(dashboard.APP_RECT, self.manifest["application_rect"])
        self.assertEqual(42, self.manifest["minimum_daemon_protocol"])
        self.assertFalse(self.manifest["vehicle_validated"])
        qml = self.rows["dashcast/DashCastDashboard.qml"][5].decode()
        self.assertNotIn("DataSource", qml.split("Item {", 1)[1])
        self.assertNotIn("anchors.fill", qml)

    def test_lexer_excludes_comments_and_strings(self):
        source = 'Item { /* WarningLight { id: no } */ Item { WarningLight { id: yes } } // WarningLight{}\nproperty string ignored: "WarningLight { }" }'
        lights = [obj for obj in dashboard.qml_objects(source) if obj[0] == "WarningLight"]
        self.assertEqual(1, len(lights))
        self.assertEqual(2, lights[0][1])


if __name__ == "__main__": unittest.main()
