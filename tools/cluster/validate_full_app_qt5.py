#!/usr/bin/env python3
"""Qt5 checks of owned decoration/layout and native primitive fragments only.

No factory root, OEM plugin, vehicle connection or native command is executed.
"""
import argparse
import os
import re
import subprocess
from pathlib import Path

os.environ["QT_QPA_PLATFORM"] = "offscreen"
os.environ["QT_QUICK_BACKEND"] = "software"
os.environ["QSG_RENDER_LOOP"] = "basic"

import build_full_app as fullapp
import build_full_dashboard as dashboard
import build_live_marker as rcc
from PyQt5.QtCore import QResource, QUrl, QRectF, QPointF
from PyQt5.QtGui import QColor, QGuiApplication
from PyQt5.QtQml import QQmlComponent, QQmlEngine, QQmlPropertyMap
from PyQt5.QtQuick import QQuickWindow
from PyQt5.QtTest import QTest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--qmllint", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    app = QGuiApplication([])
    engine = QQmlEngine()
    asset = fullapp.OUT / "full_app_21f7735c.rcc"
    assert QResource.registerResource(str(asset))
    rows = {row[0]: row[5] for row in rcc.decode(asset.read_bytes())}
    if args.qmllint:
        for path, value in rows.items():
            if path.endswith(".qml"):
                target = args.output / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(value)
                subprocess.run([str(args.qmllint), str(target)], check=True)

    def create(qml):
        component = QQmlComponent(engine)
        component.setData(qml.encode(), QUrl("qrc:/dashcast/owned-full-app-test.qml"))
        assert not component.isError(), [error.toString() for error in component.errors()]
        root = component.create()
        assert root is not None, [error.toString() for error in component.errors()]
        return component, root


    native = rows["qml10_25/cluster.qml"].decode("utf-8")
    guards = [re.search(r"readonly property bool " + name + r": [^\r\n]+", native).group()
              for name in ("dashcastRequested", "dashcastComponentReady", "dashcastFullAppReady")]
    loader = next(obj for obj in dashboard.qml_objects(native) if obj[0] == "Loader" and
                  "id: dashcastFullAppLoader" in native[obj[3]:dashboard.brace_end(native, obj[3])])
    loader_text = native[loader[2]:dashboard.brace_end(native, loader[3])]
    fixture_items = []
    for key in fullapp.HIDDEN_ITEMS:
        obj = fullapp.native_id(native, key)
        body = native[obj[3]:dashboard.brace_end(native, obj[3])]
        marker = body.index(fullapp.BEGIN)
        owned = body[marker + len(fullapp.BEGIN):body.index(fullapp.END, marker)]
        assert "opacity:" in owned
        fixture_items.append('Item { id: ' + key + '; objectName: "' + key + '"; anchors.fill: parent; ' +
                             owned + '\nRectangle { anchors.fill: parent; color: "white" } }')
    source = QQmlPropertyMap()
    for key, value in dict(naviType=4, screenSize=2, powerGear=3, playAnim=True).items():
        source.insert(key, value)
    style = QQmlPropertyMap(); style.insert("bgZ", -2)
    engine.rootContext().setContextProperty("DataSource", source)
    engine.rootContext().setContextProperty("CustomStyle", style)
    component, root = create('''import QtQuick 2.15
Item { id: rootItemId; width: 1920; height: 720
    Rectangle { anchors.fill: parent; z: -3; color: "#ff00ff" }
    Item { id: alwyasDisplayId; visible: true }
''' + "\n".join(guards + fixture_items) + "\n" + loader_text + "\n}")
    window = QQuickWindow(); window.resize(1920, 720)
    root.setParentItem(window.contentItem()); window.show(); QTest.qWait(80)
    assert root.property("dashcastFullAppReady")
    targets = {item.objectName(): item for item in root.childItems() if item.objectName()}
    assert all(item.opacity() == 0 for item in targets.values())
    grab = root.grabToImage(); QTest.qWait(80); image = grab.image()
    for x, y in ((0, 0), (1919, 719), (700, 90), (500, 500)):
        assert image.pixelColor(x, y).name() == "#ff00ff", (x, y)
    image.save(str(args.output / "full-app-transparent-720.png"))
    for key, invalid, valid in (("naviType", 0, 4), ("screenSize", 1, 2), ("powerGear", 2, 3), ("playAnim", False, True)):
        source.insert(key, invalid); app.processEvents()
        assert not root.property("dashcastFullAppReady"), key
        assert all(item.opacity() == 1 for item in targets.values()), key
        source.insert(key, valid); app.processEvents(); assert root.property("dashcastFullAppReady")
    root.setWidth(1280); app.processEvents(); assert not root.property("dashcastFullAppReady")
    root.setWidth(1920); app.processEvents(); assert root.property("dashcastFullAppReady")
    loader_item = next(item for item in root.childItems() if item.metaObject().className().startswith("QQuickLoader"))
    loader_item.setProperty("source", QUrl("qrc:/dashcast/missing-owned-fixture.qml"))
    app.processEvents(); assert not root.property("dashcastFullAppReady")
    assert all(item.opacity() == 1 for item in targets.values())
    root.setParentItem(None); window.close()
    root.deleteLater(); app.sendPostedEvents(None, 52); app.processEvents()
    print("PASS: Qt5 full1920x720 transparent app, actual generated opacity/geometry/Loader guards and native fallback; no factory root or OEM code executed")


if __name__ == "__main__":
    main()
