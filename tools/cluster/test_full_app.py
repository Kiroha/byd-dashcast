#!/usr/bin/env python3
"""Execution-free checks of the full-app source and resource contract."""
import json
import re
import unittest

import build_full_app as fullapp
import build_full_dashboard as dashboard
import build_live_marker as rcc


class FullAppCarrierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.carrier = (fullapp.OUT / "full_app_21f7735c.rcc").read_bytes()
        cls.manifest = json.loads((fullapp.OUT / "full_app_21f7735c_manifest.json").read_bytes())
        cls.rows = {row[0]: row for row in rcc.decode(cls.carrier)}

    def test_factory_source_and_translation_bytes_remain_recoverable(self):
        self.assertEqual(84, len(self.manifest["resources"]))
        for path, identity in self.manifest["resources"].items():
            _, country, language, flags, stored, value = self.rows[path]
            self.assertEqual((country, language, flags), (identity["country"], identity["language"], identity["flags"]))
            self.assertEqual(rcc.sha(stored), identity["stored_sha256"])
            self.assertEqual(rcc.sha(value), identity["sha256"])
        for path, identity in self.manifest["roots"].items():
            native = self.rows[path][5].decode("utf-8")
            native = re.sub(r"\r\n    // DASHCAST_FULL_APP_BEGIN\r\n.*?\r\n    // DASHCAST_FULL_APP_END\r\n", "", native, flags=re.S)
            native = native.replace('import "qrc:/dashcast/FullAppLayout.js" as DashCastLayout\r\n', "")
            self.assertEqual(identity["original_sha256"], rcc.sha(native.encode("utf-8")), path)

    def test_no_adas_resource_is_patched_and_requested_native_items_are_hidden(self):
        self.assertFalse(any("Adas" in path or "Center" in path or "Warin" in path for path in self.manifest["roots"]))
        root = self.rows["qml10_25/cluster.qml"][5].decode("utf-8")
        for key in fullapp.HIDDEN_ITEMS:
            obj = fullapp.native_id(root, key)
            direct = root[obj[3]:dashboard.brace_end(root, obj[3])]
            marker = direct.index(fullapp.BEGIN)
            owned = direct[marker:direct.index(fullapp.END, marker)]
            self.assertIn("opacity: rootItemId.dashcastFullAppReady ? 0 : 1", owned, key)
        self.assertNotIn("-5000", root)
        self.assertNotIn("XMLHttpRequest", root)
        self.assertNotIn("sealout", root.lower())

    def test_closed_surface_and_manual_demo_contract(self):
        self.assertEqual({"x": 0, "y": 0, "width": 1920, "height": 720}, self.manifest["application_rect"])
        self.assertEqual([1920, 720], self.manifest["native_size"])
        self.assertEqual(44, self.manifest["minimum_daemon_protocol"])
        self.assertTrue(self.manifest["native_instruments_hidden"])
        self.assertTrue(self.manifest["native_alerts_hidden"])
        self.assertEqual(rcc.sha(self.carrier), self.manifest["carrier_sha256"])
        self.assertEqual(list(fullapp.HIDDEN_ITEMS), self.manifest["hidden_items"])
        root = self.rows["qml10_25/cluster.qml"][5].decode("utf-8")
        self.assertIn("dashcastRequested && dashcastComponentReady", root)
        self.assertIn("DataSource.naviType === 0x04 && DataSource.screenSize === 0x02", root)
        self.assertIn("dashcastFullAppLoader.status === Loader.Ready", root)
        self.assertIn("text: DataSource.speed", root)
        self.assertIn("DataSource.speedUnit === 2", root)

    def test_previous_dashboard_carrier_is_not_changed(self):
        old = fullapp.OUT / "full_dashboard_21f7735c.rcc"
        self.assertEqual("8a96299b3acc1cffd2eaf2a57cf94cd2ae26dfd90b5fb6204a0182baea6ac250", rcc.sha(old.read_bytes()))


if __name__ == "__main__":
    unittest.main()
