#!/usr/bin/env python3
"""Execution-free fidelity and packaging checks for the offline presentation candidate."""
import json
import re
import unittest
import xml.etree.ElementTree as ET

import build_full_app as fullapp
import build_full_dashboard as dashboard
import build_live_cohort_marker as cohort
import build_live_marker as rcc


def factory_sources():
    legacy = (fullapp.LEGACY_ASSETS / "full_app_21f7735c.rcc").read_bytes()
    assert rcc.sha(legacy) == fullapp.LEGACY_SHA
    rows = {row[0]: row for row in rcc.decode(legacy)}
    roots = {}
    for path, (_, digest) in cohort.ORIGINALS.items():
        native = rows[path][5].decode("utf-8")
        native = re.sub(r"\r\n    // DASHCAST_FULL_APP_BEGIN\r\n.*?\r\n    // DASHCAST_FULL_APP_END\r\n", "", native, flags=re.S)
        roots[path] = native.encode("utf-8")
        assert rcc.sha(roots[path]) == digest
    return rows, roots


def candidate():
    manifest = json.loads((fullapp.OUT / fullapp.MANIFEST).read_bytes())
    carrier = (fullapp.OUT / manifest["carrier_filename"]).read_bytes()
    assert rcc.sha(carrier) == manifest["carrier_sha256"]
    return manifest, carrier, {row[0]: row for row in rcc.decode(carrier)}


def owned_loader(root):
    blocks = re.findall(re.escape(fullapp.BEGIN) + r"\r\n(.*?)\r\n    " + re.escape(fullapp.END), root, re.S)
    assert len(blocks) == 1, "Every owned root declaration must be in the one validated replacement"
    return blocks[0]


class FullAppCarrierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.legacy, cls.originals = factory_sources()
        cls.manifest, cls.carrier, cls.rows = candidate()

    def test_all_factory_translations_preserve_bytes_and_metadata(self):
        self.assertEqual(84, len(self.manifest["resources"]))
        for path in self.manifest["resources"]:
            self.assertEqual(self.legacy[path], self.rows[path], path)
        self.assertEqual(87, len(self.rows))

    def test_only_the_existing_presentation_loader_is_replaced(self):
        for path, original in self.originals.items():
            native = self.rows[path][5].decode("utf-8")
            if path == "qml10_25/cluster.qml":
                fragment = owned_loader(native)
                self.assertEqual((fullapp.OWNED / "PresentationLoader.qml.inc").read_text().strip(),
                                 "\n".join(line[4:] for line in fragment.splitlines()).strip())
                old = original.decode("utf-8")
                obj = fullapp.native_id(old, "presentationLoaderId")
                original_loader = old[obj[2]:dashboard.brace_end(old, obj[3])]
                native = re.sub(re.escape(fullapp.BEGIN) + r".*?" + re.escape(fullapp.END),
                                lambda _: original_loader, native, flags=re.S)
            self.assertEqual(original, native.encode("utf-8"), path)
            self.assertEqual(cohort.ORIGINALS[path][1], self.manifest["roots"][path]["original_sha256"])

    def test_no_native_instrument_or_alert_authority_is_added(self):
        manifest = self.manifest
        self.assertFalse(manifest["native_instruments_hidden"])
        self.assertFalse(manifest["native_alerts_hidden"])
        self.assertEqual("none", manifest["adas_control"])
        self.assertEqual({"qml/cluster.qml", "qml10_25/cluster.qml", "dashcast/isolated/FullApp.qml"},
                         set(self.rows) - set(manifest["resources"]))
        fragment = owned_loader(self.rows["qml10_25/cluster.qml"][5].decode("utf-8"))
        child = self.rows["dashcast/isolated/FullApp.qml"][5].decode("utf-8")
        for forbidden in ("Binding", "opacity", "warningLayoutId", "sendPluginMsg", "XMLHttpRequest", "Timer", "AndroidNativeWindowItem"):
            self.assertNotIn(forbidden, fragment + child)
        self.assertEqual(1, child.count('source: "qrc:/' + fullapp.PRESENTATION + '"'))
        self.assertIn('androidFrameState: "UNVERIFIED"', child)

    def test_candidate_has_no_runtime_admission_or_apk_asset(self):
        self.assertEqual(fullapp.VARIANT, self.manifest["variant"])
        self.assertFalse(self.manifest["runtime_activation_allowed"])
        self.assertFalse(self.manifest["vehicle_validated"])
        self.assertIsNone(self.manifest["runtime_operation"])
        self.assertIsNone(self.manifest["minimum_daemon_protocol"])
        self.assertFalse(fullapp.OUT.is_relative_to(fullapp.ROOT / "app"))
        self.assertFalse((fullapp.LEGACY_ASSETS / self.manifest["carrier_filename"]).exists())
        self.assertRegex(self.manifest["carrier_filename"], r"^Di3F_DynastyOceanTranslate_V0001_DashCastIsolated_[0-9a-f]{16}\.rcc$")
        self.assertIn(self.manifest["carrier_sha256"][:16], self.manifest["carrier_filename"])

    def test_geometry_and_on_state_are_not_a_parked_or_pixel_claim(self):
        self.assertEqual({"x": 0, "y": 0, "width": 1920, "height": 720}, self.manifest["application_rect"])
        root = self.rows["qml10_25/cluster.qml"][5].decode("utf-8")
        fragment = owned_loader(root)
        self.assertIn("DataSource.powerGear === 0x03", fragment)
        self.assertIn("DataSource.dilinkName !== 0x02", fragment)
        self.assertNotIn("parked", fragment.lower())
        self.assertNotIn("FrameReady", fragment)

    def test_retired_assets_are_not_replaced(self):
        self.assertEqual(fullapp.LEGACY_SHA, rcc.sha((fullapp.LEGACY_ASSETS / "full_app_21f7735c.rcc").read_bytes()))
        self.assertEqual("8a96299b3acc1cffd2eaf2a57cf94cd2ae26dfd90b5fb6204a0182baea6ac250",
                         rcc.sha((fullapp.LEGACY_ASSETS / "full_dashboard_21f7735c.rcc").read_bytes()))

    def test_offline_and_recovery_labels_have_locale_and_format_parity(self):
        resources = fullapp.ROOT / "app/src/main/res"
        locales = ("values", "values-ar", "values-be", "values-de", "values-en", "values-es",
                   "values-it", "values-kk", "values-pl", "values-ru", "values-tr", "values-uk", "values-uz")
        keys = {"diag_cluster_full_app_prepare", "diag_cluster_full_app_help",
                "diag_cluster_full_app_prepare_confirm", "diag_cluster_full_app_observe_message"}
        reference = {node.attrib["name"]: node for node in ET.parse(resources / "values/dashboard_strings.xml").getroot()}
        for locale in locales:
            translated = {node.attrib["name"]: node for node in ET.parse(resources / locale / "dashboard_strings.xml").getroot()}
            self.assertEqual(set(reference), set(translated), locale)
            for key in keys:
                text = "".join(translated[key].itertext())
                self.assertTrue(text.strip(), (locale, key))
                self.assertEqual(re.findall(r"%(?:\d+\$)?[a-z]", "".join(reference[key].itertext())),
                                 re.findall(r"%(?:\d+\$)?[a-z]", text), (locale, key))

    def test_no_unknown_root_changes_or_declarations_escape_the_fidelity_check(self):
        root = self.rows["qml10_25/cluster.qml"][5].decode("utf-8")
        changed = root.replace("id:rootItemId", "id:rootItemId\n    property int unreviewed: 42", 1)
        original = self.originals["qml10_25/cluster.qml"].decode("utf-8")
        obj = fullapp.native_id(original, "presentationLoaderId")
        restored = re.sub(re.escape(fullapp.BEGIN) + r".*?" + re.escape(fullapp.END),
                         lambda _: original[obj[2]:dashboard.brace_end(original, obj[3])], changed, flags=re.S)
        self.assertNotEqual(cohort.ORIGINALS["qml10_25/cluster.qml"][1], rcc.sha(restored.encode("utf-8")))


if __name__ == "__main__":
    unittest.main()
