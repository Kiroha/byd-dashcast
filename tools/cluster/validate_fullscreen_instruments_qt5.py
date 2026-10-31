#!/usr/bin/env python3
"""Optional PyQt5 check: the added opacity bindings compile under the factory header and stay stock on doubt.

Only the three patched decorative objects run, in a fixture root with mocked BYD modules.
No OEM root, native plugin, Android connection or vehicle command is loaded or executed.
"""
import os
import sys
import tempfile
from pathlib import Path

os.environ["QT_QPA_PLATFORM"] = "offscreen"
os.environ["QT_QUICK_BACKEND"] = "software"
sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_fullscreen_instruments as profile  # noqa: E402
import build_live_marker as rcc  # noqa: E402
from PyQt5.QtCore import QObject, QResource, QUrl, QT_VERSION_STR, pyqtProperty, pyqtSignal  # noqa: E402
from PyQt5.QtGui import QGuiApplication  # noqa: E402
from PyQt5.QtQml import QQmlComponent, QQmlEngine, qmlRegisterSingletonType, qmlRegisterType  # noqa: E402
from PyQt5.QtQuick import QQuickItem  # noqa: E402,F401  Registers QQuickItem so created roots expose childItems().

NOMINAL = {"naviType": 4, "screenSize": 2, "powerGear": 3, "playAnim": True}
STYLE = b"pragma Singleton\nimport QtQuick 2.15\nQtObject { readonly property int bgZ: -2 }\n"


class MockDataSource(QObject):
    changed = pyqtSignal()

    def __init__(self):
        super().__init__()
        self.values = dict(NOMINAL)

    def set(self, key, value):
        self.values[key] = value
        self.changed.emit()

    naviType = pyqtProperty("QVariant", lambda self: self.values["naviType"], notify=changed)
    screenSize = pyqtProperty("QVariant", lambda self: self.values["screenSize"], notify=changed)
    powerGear = pyqtProperty("QVariant", lambda self: self.values["powerGear"], notify=changed)
    playAnim = pyqtProperty("QVariant", lambda self: self.values["playAnim"], notify=changed)


class Placeholder(QObject):
    pass


class WindowPlaceholder(QObject):
    pass


def opacities(app, items):
    app.processEvents()
    return [item.property("opacity") for item in items]


def main():
    app = QGuiApplication([])
    mock = MockDataSource()
    QQmlEngine.setObjectOwnership(mock, QQmlEngine.CppOwnership)
    qmlRegisterSingletonType(MockDataSource, "com.byd.DataSource", 1, 0, "DataSource", lambda engine, script: mock)
    qmlRegisterType(Placeholder, "com.byd.WarningLight", 1, 0, "DashCastPlaceholder")
    qmlRegisterType(WindowPlaceholder, "com.byd.AndroidNativeWindowItem", 1, 0, "DashCastPlaceholder")
    engine = QQmlEngine()
    carrier = rcc.decode((profile.OUT / profile.CARRIER).read_bytes())
    rows = [("common/singleton/qmldir", 0, 1, 0, b"singleton CustomStyle CustomStyle.qml\n",
             b"singleton CustomStyle CustomStyle.qml\n"), ("common/singleton/CustomStyle.qml", 0, 1, 0, STYLE, STYLE)]
    placeholder = b"import QtQuick 2.15\nItem {}\n"
    opacity = b"        " + profile.OPACITY
    for path, root in ((row[0], row[5]) for row in carrier if row[0] in profile.cohort.ORIGINALS):
        header = root[:root.index(b"Item {\r\n    id:rootItemId\r\n")]
        assert root.count(profile.OPACITY) == 3
        fixture = (header + b"Item {\r\n    id:rootItemId\r\n    width: 1920\r\n    height: 720\r\n"
                   b"    Item {\r\n        id: bgImageContainerId\r\n" + opacity + b"    }\r\n"
                   b"    Image {\r\n        id: headLineId\r\n" + opacity + b"    }\r\n"
                   b"    Image {\r\n        id:driveRangeInfoBgId\r\n" + opacity + b"    }\r\n}\r\n")
        folder = path.rsplit("/", 1)[0]
        rows += [(f"dashcast_fixture/{path}", 0, 1, 0, fixture, fixture),
                 (f"dashcast_fixture/{folder}/alwaysDisplay/P.qml", 0, 1, 0, placeholder, placeholder),
                 (f"dashcast_fixture/{folder}/adas3d/P.qml", 0, 1, 0, placeholder, placeholder)]
    with tempfile.TemporaryDirectory(prefix="dashcast-fullscreen-fixture-") as tmp:
        fixture_carrier = Path(tmp) / "fixture.rcc"
        fixture_carrier.write_bytes(rcc.build(rows))
        assert QResource.registerResource(str(fixture_carrier))
        for path in profile.cohort.ORIGINALS:
            component = QQmlComponent(engine, QUrl(f"qrc:/dashcast_fixture/{path}"))
            assert not component.isError(), [error.toString() for error in component.errors()]
            root = component.create()
            assert root is not None, [error.toString() for error in component.errors()]
            decorations = root.childItems()
            assert len(decorations) == 3
            assert opacities(app, decorations) == [0, 0, 0], opacities(app, decorations)
            for key, bad in (("naviType", 3), ("naviType", 2), ("naviType", None), ("screenSize", 1),
                             ("screenSize", None), ("powerGear", 2), ("powerGear", None), ("playAnim", False),
                             ("playAnim", None)):
                mock.set(key, bad)
                assert opacities(app, decorations) == [1, 1, 1], (path, key, bad)
                mock.set(key, NOMINAL[key])
                assert opacities(app, decorations) == [0, 0, 0]
            root.setProperty("width", 1280)
            assert opacities(app, decorations) == [1, 1, 1]
            root.setProperty("width", 1920)
            assert opacities(app, decorations) == [0, 0, 0]
            root.deleteLater()
            app.processEvents()
            engine.clearComponentCache()
        assert QResource.unregisterResource(str(fixture_carrier))
    print(f"PASS Qt {QT_VERSION_STR}: exact added bindings compile under both factory import headers; 0 only in "
          "ON + FULL + 1920x720 + completed start-up; 9 unknown or other states and a resized root keep stock "
          "opacity 1. No OEM root or plugin executed.")


if __name__ == "__main__":
    main()
