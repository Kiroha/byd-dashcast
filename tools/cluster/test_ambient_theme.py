#!/usr/bin/env python3
"""Independent preservation and safety-bound checks for the offline night ambient prototype."""
import hashlib
import json
import re
import struct
import unittest
import zlib
from pathlib import Path

import build_ambient_theme as builder
import build_live_cohort_marker as cohort
from test_live_marker import members

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/cluster_live"
OUT = ROOT / "artifacts/cluster_ambient"
EXPECTED_SHA = "db5c147d2414af8147d2dc04f821ed0eebd5f15bd9082164805c05e3dc280e2e"
ALLOWED_TYPES = {"Item", "Image", "Rectangle", "Gradient", "GradientStop", "SequentialAnimation",
                 "ParallelAnimation", "NumberAnimation", "PauseAnimation"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


class AmbientThemeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.carrier = (OUT / builder.CARRIER).read_bytes()
        cls.manifest = json.loads((OUT / "manifest.json").read_text())
        cls.rows = members(cls.carrier)

    def test_closed_identity_offline_only_and_bounded_size(self):
        self.assertEqual(EXPECTED_SHA, sha(self.carrier))
        self.assertEqual((EXPECTED_SHA, len(self.carrier)),
                         (self.manifest["carrier_sha256"], self.manifest["carrier_size"]))
        self.assertLess(len(self.carrier), 4 * 1024 * 1024)
        self.assertEqual(87, len(self.rows))
        self.assertEqual(set(self.manifest["resources"]) | set(cohort.ORIGINALS) | {builder.GLOW_ALIAS}, set(self.rows))
        self.assertFalse(self.manifest["vehicle_validated"])
        self.assertFalse(self.manifest["diag_loadable"])
        self.assertFalse(self.manifest["runtime_activation_allowed"])
        self.assertNotIn(EXPECTED_SHA, {sha(path.read_bytes()) for path in ASSETS.glob("*.rcc")})
        for path in ASSETS.glob("*.json"):
            self.assertNotIn(EXPECTED_SHA, path.read_text())

    def test_all_84_current_locale_resources_are_byte_identical(self):
        stock = members((ASSETS / "corner_marker_21f7735c.rcc").read_bytes())
        self.assertEqual(84, len(self.manifest["resources"]))
        for path, record in self.manifest["resources"].items():
            self.assertTrue(path.startswith("common/language/"))
            self.assertEqual(stock[path], self.rows[path], path)
            country, language, flags, raw, decoded = self.rows[path]
            self.assertEqual((country, language, flags), (record["country"], record["language"], record["flags"]))
            self.assertEqual((sha(raw), sha(decoded)), (record["stored_sha256"], record["sha256"]))
        self.assertEqual(cohort.STOCK_SHA, self.manifest["stock_sha256"])

    def test_each_root_differs_only_by_the_inserted_block(self):
        for path, (_, original_sha) in cohort.ORIGINALS.items():
            patched = self.rows[path][4]
            block = builder.block(path)
            self.assertEqual(1, patched.count(block))
            self.assertIn(builder.ANCHOR + block, patched)
            original = patched.replace(block, b"", 1)
            self.assertEqual(original_sha, sha(original))
            self.assertEqual(original_sha, self.manifest["roots"][path]["original_sha256"])
            self.assertEqual(patched, builder.patched_root(path, original))
            self.assertEqual(patched, (OUT / "patched" / path).read_bytes())
            for bad in (original + b"\n", patched):
                with self.assertRaises(ValueError):
                    builder.patched_root(path, bad)
            for functional in (b"WarinLightLayout", b"RECV_MSG_ID_QML_LOAD_COMPLETE", b"presentationLoaderId"):
                self.assertIn(functional, original)
        self.assertNotIn("qml/main.qml", self.rows)
        self.assertNotIn("common/main.qml", self.rows)

    def test_block_is_passive_guarded_and_slow(self):
        for path in cohort.ORIGINALS:
            text = builder.block(path).decode("ascii")
            self.assertEqual(ALLOWED_TYPES, set(re.findall(r"\b([A-Z][A-Za-z]+)\s*(?:on\s+\w+\s*)?\{", text)))
            for forbidden in ("MouseArea", "Timer", "Loader", "Connections", "onCompleted", "sendPlugin",
                              "Qt.binding", "function", "WarningLight", "ShaderEffect", "layer."):
                self.assertNotIn(forbidden, text)
            self.assertNotRegex(text, r"DataSource\.\w+\s*=[^=]")
            self.assertEqual(["qrc:/" + builder.GLOW_ALIAS], re.findall(r'source: "([^"]+)"', text))
            self.assertIn("z: CustomStyle.bgZ + 0.5", text)
            self.assertIn("enabled: false", text)
            self.assertIn("visible: " + builder.condition(path), text)
            self.assertIn('String(bgImageId.source) === "' + builder.NIGHT_BACKGROUND[path] + '"', text)
            self.assertIn("/display_always/night/bg.png", builder.condition(path))
            self.assertEqual(2, text.count("running: dashCastAmbientId.visible"))
            self.assertEqual(2, text.count("loops: Animation.Infinite"))
            self.assertGreaterEqual(min(int(value) for value in re.findall(r"duration: (\d+)", text)), 1500)
        breath, streak = builder.BREATH, builder.STREAK
        travel = 2 * streak["fade_ms"] + streak["hold_ms"]
        speed = (streak["to_x"] - builder.GEOMETRY["streak"][0]) * 1000 / travel
        self.assertLessEqual(breath["max_opacity"], 0.8)
        self.assertLessEqual(streak["peak_opacity"], 0.6)
        self.assertGreaterEqual(2 * breath["half_period_ms"], 4000)
        self.assertGreaterEqual(travel + streak["pause_ms"], 4000)
        self.assertLessEqual(speed, 150)
        self.assertEqual(builder.BREATH, self.manifest["animation"]["breath"])
        self.assertEqual(builder.STREAK, self.manifest["animation"]["streak"])

    def test_glow_asset_is_deterministic_soft_and_small(self):
        _, _, flags, raw, glow = self.rows[builder.GLOW_ALIAS]
        self.assertEqual((0, raw), (flags, glow))
        self.assertEqual(glow, builder.glow_png())
        self.assertEqual(glow, (OUT / "assets" / builder.GLOW_ALIAS).read_bytes())
        self.assertEqual(sha(glow), self.manifest["glow_asset"]["sha256"])
        self.assertLess(len(glow), 16 * 1024)
        self.assertEqual(b"\x89PNG\r\n\x1a\n", glow[:8])
        width, height, depth, color = struct.unpack(">IIBB", glow[16:26])
        self.assertEqual((256, 64, 8, 6), (width, height, depth, color))
        self.assertEqual(b"IDAT", glow[37:41])
        pixels = zlib.decompress(glow[41:41 + struct.unpack(">I", glow[33:37])[0]])
        stride = 1 + 4 * width
        self.assertTrue(all(pixels[y * stride] == 0 for y in range(height)))
        alpha = [[pixels[y * stride + 4 + 4 * x] for x in range(width)] for y in range(height)]
        self.assertEqual(builder.GLOW["peak_alpha"], max(max(row) for row in alpha))
        edges = alpha[0] + alpha[-1] + [row[0] for row in alpha] + [row[-1] for row in alpha]
        self.assertEqual(0, max(edges))

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
