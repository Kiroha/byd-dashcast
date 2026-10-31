#!/usr/bin/env python3
"""Optional PyQt5 check of the owned ambient block under the factory import header.

Only the owned block runs, inside a fixture root with mocked BYD modules. No OEM root,
native plugin, Android connection or vehicle command is loaded or executed.
"""
import argparse
import os
import sys
import tempfile
from pathlib import Path

os.environ["QT_QPA_PLATFORM"] = "offscreen"
os.environ["QT_QUICK_BACKEND"] = "software"
os.environ["QSG_RENDER_LOOP"] = "basic"
sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_ambient_theme as ambient  # noqa: E402
import build_live_marker as rcc  # noqa: E402
from PyQt5.QtCore import QBuffer, QIODevice, QObject, QResource, QUrl, QT_VERSION_STR, pyqtProperty, pyqtSignal  # noqa: E402
from PyQt5.QtGui import QColor, QGuiApplication, QImage, QPainter  # noqa: E402
from PyQt5.QtQml import QQmlComponent, QQmlEngine, qmlRegisterSingletonType, qmlRegisterType  # noqa: E402
from PyQt5.QtQuick import QQuickWindow  # noqa: E402
from PyQt5.QtTest import QTest  # noqa: E402

FRAME_MS = 250
CYCLE_MS = 10_500
STUB_STYLE = (b"pragma Singleton\nimport QtQuick 2.15\nQtObject {\n    readonly property int bgZ: -2\n"
              b"    readonly property int presentationZ: -3\n    readonly property int themeSwithAnimZ: -1\n"
              b"    readonly property int adasZ: 99\n    readonly property int warningLightZ: 100\n}\n")


class MockDataSource(QObject):
    changed = pyqtSignal()

    def __init__(self):
        super().__init__()
        self.values = {"powerGear": 3, "playAnim": True}

    def set(self, key, value):
        self.values[key] = value
        self.changed.emit()

    powerGear = pyqtProperty("QVariant", lambda self: self.values["powerGear"], notify=changed)
    playAnim = pyqtProperty("QVariant", lambda self: self.values["playAnim"], notify=changed)


class Placeholder(QObject):
    pass


class WindowPlaceholder(QObject):
    pass


def png_bytes(image):
    buffer = QBuffer()
    buffer.open(QIODevice.WriteOnly)
    image.save(buffer, "PNG")
    return bytes(buffer.data())


def synthetic_background():
    image = QImage(1920, 720, QImage.Format_RGB32)
    image.fill(QColor("#141821"))
    painter = QPainter(image)
    painter.fillRect(480, 254, 960, 3, QColor("#2c3542"))
    painter.end()
    return png_bytes(image)


def lum(image, x, y):
    color = image.pixelColor(x, y)
    return 0.2126 * color.red() + 0.7152 * color.green() + 0.0722 * color.blue()


def grab(item, wait_ms):
    result = item.grabToImage()
    QTest.qWait(wait_ms)
    image = result.image()
    assert image.width() == 1920 and image.height() == 720, (image.width(), image.height())
    return image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Private directory for rendered frames")
    parser.add_argument("--extracted", type=Path,
                        help="Atlas extraction root: real night backgrounds and compiled CustomStyle (local only)")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    app = QGuiApplication([])
    mock = MockDataSource()
    QQmlEngine.setObjectOwnership(mock, QQmlEngine.CppOwnership)
    qmlRegisterSingletonType(MockDataSource, "com.byd.DataSource", 1, 0, "DataSource", lambda engine, script: mock)
    qmlRegisterType(Placeholder, "com.byd.WarningLight", 1, 0, "DashCastPlaceholder")
    qmlRegisterType(WindowPlaceholder, "com.byd.AndroidNativeWindowItem", 1, 0, "DashCastPlaceholder")
    engine = QQmlEngine()
    warnings = []
    engine.warnings.connect(lambda items: warnings.extend(item.toString() for item in items))

    carrier = ambient.OUT / ambient.CARRIER
    assert QResource.registerResource(str(carrier))
    patched = {row[0]: row[5] for row in rcc.decode(carrier.read_bytes()) if row[0] in ambient.NIGHT_BACKGROUND}
    singleton = args.extracted / "libBydCluster/common/singleton" if args.extracted else None
    fixture_rows = []
    if singleton:
        for name in ("qmldir", "CustomStyle.qml", "Global.qml", "TranslateStr.qml"):
            data = (singleton / name).read_bytes()
            fixture_rows.append((f"common/singleton/{name}", 0, 1, 0, data, data))
    else:
        fixture_rows += [("common/singleton/qmldir", 0, 1, 0, b"singleton CustomStyle CustomStyle.qml\n",
                          b"singleton CustomStyle CustomStyle.qml\n"),
                         ("common/singleton/CustomStyle.qml", 0, 1, 0, STUB_STYLE, STUB_STYLE)]
    placeholder = b"import QtQuick 2.15\nItem {}\n"
    for path, root in patched.items():
        header = root[:root.index(b"Item {\r\n    id:rootItemId\r\n")]
        assert sum(line.lstrip(b"\xef\xbb\xbf").startswith(b"import ") for line in header.splitlines()) == 11, header
        start = root.index(ambient.ANCHOR) + len(ambient.ANCHOR)
        end = root.index(b"    // DASHCAST_AMBIENT_END\r\n") + len(b"    // DASHCAST_AMBIENT_END\r\n")
        block = root[start:end]
        assert block == ambient.block(path)
        folder = path.rsplit("/", 1)[0]
        fixture = (header + b"Item {\r\n    id:rootItemId\r\n" + block +
                   b"    width: 1920\r\n    height: 720\r\n"
                   b"    Item { id: bgImageContainerId; anchors.fill: parent; z: CustomStyle.bgZ\r\n"
                   b"        Image { id: bgImageId; objectName: \"fixtureBackground\"; cache: false; visible: true; source: \""
                   + ambient.NIGHT_BACKGROUND[path].encode() + b"\" } }\r\n"
                   b"    Rectangle { x: 900; y: 200; width: 40; height: 40; color: \"#ffffff\" }\r\n}\r\n")
        fixture_rows += [(f"dashcast_fixture/{path}", 0, 1, 0, fixture, fixture),
                         (f"dashcast_fixture/{folder}/alwaysDisplay/DashCastPlaceholder.qml", 0, 1, 0, placeholder, placeholder),
                         (f"dashcast_fixture/{folder}/adas3d/DashCastPlaceholder.qml", 0, 1, 0, placeholder, placeholder)]
        background = ambient.NIGHT_BACKGROUND[path][len("qrc:/"):]
        if args.extracted:
            theme = "cluster_theme1" if path.startswith("qml/") else "cluster_theme2"
            data = (args.extracted / theme / background).read_bytes()
        else:
            data = synthetic_background()
        fixture_rows.append((background, 0, 1, 0, data, data))
    with tempfile.TemporaryDirectory(prefix="dashcast-ambient-fixture-") as tmp:
        fixture_carrier = Path(tmp) / "fixture.rcc"
        fixture_carrier.write_bytes(rcc.build(fixture_rows))
        assert QResource.registerResource(str(fixture_carrier))
        summary = []
        for path in patched:
            summary.append(check_root(app, engine, mock, warnings, path, args.output))
        assert QResource.unregisterResource(str(fixture_carrier))
    assert QResource.unregisterResource(str(carrier))
    for line in summary:
        print(line)
    print(f"PASS Qt {QT_VERSION_STR}: owned ambient block compiled under the factory import header for both roots; "
          f"{'real' if args.extracted else 'synthetic'} night backgrounds; guards; animations stop when hidden; "
          "foreground and far pixels untouched; bounded per-frame change. No OEM root or plugin executed.")


def check_root(app, engine, mock, warnings, path, output):
    warnings.clear()
    component = QQmlComponent(engine, QUrl(f"qrc:/dashcast_fixture/{path}"))
    assert not component.isError(), [error.toString() for error in component.errors()]
    root = component.create()
    assert root is not None, [error.toString() for error in component.errors()]
    layer = next(item for item in root.childItems() if item.property("z") == -1.5)
    glow = layer.childItems()[0]
    breath = root.findChild(QObject, "dashCastAmbientBreathAnim")
    streak = root.findChild(QObject, "dashCastAmbientStreakAnim")
    background = root.findChild(QObject, "fixtureBackground")
    window = QQuickWindow()
    window.resize(1920, 720)
    window.setColor(QColor("#000000"))
    root.setParentItem(window.contentItem())
    window.show()
    QTest.qWait(200)
    assert glow.property("status") == 1, "glow image not loaded from the ambient carrier"

    def hidden():
        app.processEvents()
        return not layer.property("visible") and not breath.property("running") and not streak.property("running")

    mock.set("powerGear", 2)
    assert hidden()
    reference = grab(root, 150)
    mock.set("powerGear", 3)
    app.processEvents()
    assert layer.property("visible") and breath.property("running") and streak.property("running")
    frames, glow_delta, streak_x = [], [], []
    for _ in range(CYCLE_MS // FRAME_MS + 1):
        frame = grab(root, FRAME_MS)
        frames.append(frame)
        assert frame.pixelColor(920, 220).name() == "#ffffff", "functional foreground altered"
        for x, y in ((20, 690), (1900, 30), (960, 620), (200, 254)):
            assert frame.pixelColor(x, y) == reference.pixelColor(x, y), (path, x, y)
        glow_delta.append(lum(frame, 960, 230) - lum(reference, 960, 230))
        # Subtracting the row below cancels the slowly varying glow, leaving only the 3 px streak.
        row = [(lum(frame, x, 255) - lum(reference, x, 255) - lum(frame, x, 262) + lum(reference, x, 262), x)
               for x in range(480, 1441, 4)]
        peak, x = max(row)
        streak_x.append(x if peak > 6 else None)
    steps = [abs(b - a) for a, b in zip(glow_delta, glow_delta[1:], strict=False)]
    amplitude = max(glow_delta) - min(glow_delta)
    assert min(glow_delta) > 2, "glow not visible at its lowest opacity"
    assert 8 <= amplitude <= 60, amplitude
    assert max(steps) <= 8, max(steps)
    runs, current = [], []
    for x in streak_x + [None]:
        if x is None:
            if current:
                runs.append(current)
            current = []
        else:
            current.append(x)
    seen = max(runs, key=len)
    assert len(seen) >= 6 and seen[-1] - seen[0] > 300 and seen == sorted(seen), streak_x
    worst = max(frames, key=lambda image: lum(image, 960, 230))
    calm = min(frames, key=lambda image: lum(image, 960, 230))
    name = path.replace("/", "_").replace(".qml", "")
    worst.save(str(output / f"{name}_peak.png"))
    calm.save(str(output / f"{name}_trough.png"))
    reference.save(str(output / f"{name}_without_layer.png"))
    for index, frame in enumerate(frames):
        frame.save(str(output / f"{name}_frame_{index:02d}.png"))
    for key, bad in (("powerGear", None), ("powerGear", 2), ("playAnim", False), ("playAnim", None)):
        mock.set(key, bad)
        assert hidden(), (key, bad)
        mock.set(key, 3 if key == "powerGear" else True)
    night = ambient.NIGHT_BACKGROUND[path]
    for other in (night.replace("/night/", "/day/"), night.replace("/bg.png", "/navi_full_bg.png"),
                  "qrc:/images10_25/poweron/IMG_000.ktx"):
        background.setProperty("source", other)
        assert hidden(), other
    background.setProperty("source", night)
    background.setProperty("visible", False)
    assert hidden()
    background.setProperty("visible", True)
    app.processEvents()
    assert layer.property("visible") and breath.property("running")
    own = [line for line in warnings if "dashCastAmbient" in line or "DASHCAST" in line or "ambient" in line]
    assert not own, own
    root.setParentItem(None)
    window.close()
    root.deleteLater()
    app.processEvents()
    engine.clearComponentCache()
    return (f"{path}: glow +{min(glow_delta):.1f}..+{max(glow_delta):.1f} luma (amplitude {amplitude:.1f}), "
            f"max step {max(steps):.1f}/{FRAME_MS} ms, streak x {seen[0]}->{seen[-1]}, "
            f"{len(warnings)} unrelated engine warnings")


if __name__ == "__main__":
    main()
