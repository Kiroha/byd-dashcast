#!/usr/bin/env python3
"""Host-only Qt5 checks: owned decoration/layout and self-owned lamp mocks.

Factory roots are extracted for optional qmllint parsing, never instantiated.
No BYD plugin, Android connection or vehicle command is loaded/executed.
"""
import argparse
import os
import subprocess
from pathlib import Path

os.environ["QT_QPA_PLATFORM"] = "offscreen"
os.environ["QT_QUICK_BACKEND"] = "software"
os.environ["QSG_RENDER_LOOP"] = "basic"

import build_full_dashboard as dashboard
import build_live_marker as rcc
from PyQt5.QtCore import QFile, QIODevice, QPointF, QRectF, QResource, QUrl, QT_VERSION_STR
from PyQt5.QtGui import QColor, QGuiApplication
from PyQt5.QtQml import QQmlComponent, QQmlEngine, QQmlPropertyMap
from PyQt5.QtQuick import QQuickWindow
from PyQt5.QtTest import QTest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--qmllint", type=Path)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    app = QGuiApplication([]); engine = QQmlEngine()
    asset = dashboard.OUT / "full_dashboard_21f7735c.rcc"
    assert QResource.registerResource(str(asset))
    if args.qmllint:
        for row in rcc.decode(asset.read_bytes()):
            if row[0].endswith(".qml"):
                target = args.output / row[0]; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(row[5])
                subprocess.run([str(args.qmllint), str(target)], check=True)

    def create(qml=None, url=None):
        component = QQmlComponent(engine)
        if qml is not None: component.setData(qml.encode(), QUrl("qrc:/dashcast/owned-test.qml"))
        else: component.loadUrl(QUrl(url))
        assert not component.isError(), [error.toString() for error in component.errors()]
        root = component.create()
        assert root is not None, [error.toString() for error in component.errors()]
        return component, root

    # Prove first-registration lookup with two exclusively self-owned carriers.
    paths = []
    for name in ("first", "second"):
        path = args.output / (name + ".rcc"); data = name.encode()
        path.write_bytes(rcc.build([("dashcast_owned/precedence", 0, 1, 0, data, data)])); paths.append(path)
        assert QResource.registerResource(str(path))
    source = QFile(":/dashcast_owned/precedence"); assert source.open(QIODevice.ReadOnly)
    assert bytes(source.readAll()) == b"first"; source.close(); del source
    assert QResource.unregisterResource(str(paths[0]))
    source = QFile(":/dashcast_owned/precedence")
    assert source.open(QIODevice.ReadOnly); assert bytes(source.readAll()) == b"second"; source.close(); del source
    assert QResource.unregisterResource(str(paths[1]))

    component, chrome = create(qml='''import QtQuick 2.15
Item { width: 1920; height: 720; property alias night: decoration.night
    Rectangle { anchors.fill: parent; z: -3; color: "#ff00ff" }
    DashCastDashboard { id: decoration; z: -2 }
    Rectangle { x: 48; y: 100; width: 40; height: 40; color: "#ffffff" }
}''')
    window = QQuickWindow(); window.resize(1920, 720); window.setColor(QColor("#ff00ff"))
    chrome.setParentItem(window.contentItem()); window.show(); QTest.qWait(80)
    for night in (False, True):
        chrome.setProperty("night", night); app.processEvents()
        grab = chrome.grabToImage(); QTest.qWait(100); image = grab.image()
        assert not image.isNull()
        # The real application rectangle is transparent, never an opaque card.
        assert image.pixelColor(800, 400).name() == "#ff00ff", image.pixelColor(800, 400).name()
        assert image.pixelColor(232, 300).name() != "#ff00ff"
        assert image.pixelColor(1568, 500).name() != "#ff00ff"
        assert image.pixelColor(60, 110).name() == "#ffffff"
        assert image.pixelColor(40, 100).name() == "#142634"
        image.save(str(args.output / ("owned-night.png" if night else "owned-day.png")))
    chrome.setParentItem(None); window.close(); chrome.deleteLater(); app.processEvents()

    items, refs = [], []
    for i in range(76):
        refs.append("lamp" + str(i))
        # Lamp7 includes a wider seat-layout mock; group75 keeps three children.
        content = "Rectangle { width: 40; height: 40 }"
        if i == 7: content = "Item { Rectangle { width: 40; height: 48 } Item { x:42; y:10; Rectangle { width: 36; height: 28 } } }"
        if i == 75: content = "Rectangle { width: 58; height: 23 } Item { x:5; y:5 } Item { x:21; y:4 } Item { x:37; y:5 }"
        items.append(f'''Item {{ id: lamp{i}; objectName: "lamp{i}"; x: {i * 3}; y: {i * 2}
            property var state: 1
            {content}
            transform: Translate {{
                x: root.active ? root.packed.positions[{i}].x - lamp{i}.x - root.bounds[{i}].x : 0
                y: root.active ? root.packed.positions[{i}].y - lamp{i}.y - root.bounds[{i}].y : 0
            }}
        }}''')
    fixture = '''import QtQuick 2.15
import "qrc:/dashcast/DashboardLayout.js" as Layout
Item { id: root; width: 1920; height: 720; property bool requested: true
    readonly property var lamps: [''' + ",".join(refs) + ''']
    readonly property var bounds: [''' + ",".join("Layout.nativeBounds(" + key + ")" for key in refs) + ''']
    readonly property var states: [''' + ",".join(f"({key}.state !== 0 && {key}.state !== 2)" for key in refs) + ''']
    readonly property var packed: Layout.pack(lamps, bounds, states)
    readonly property bool active: requested && packed.fits
    ''' + "\n".join(items) + "\n}"
    component, root = create(qml=fixture); app.processEvents(); assert root.property("active")
    def native_bounds():
        value = root.property("bounds")
        return value.toVariant() if hasattr(value, "toVariant") else value
    assert native_bounds()[7]["width"] == 78 and native_bounds()[7]["height"] == 48
    lamps = root.childItems()
    seat = next(item for item in lamps if item.objectName() == "lamp7")
    original = seat.mapToItem(root, QPointF(0, 0)); seat.setX(911); seat.setY(625); app.processEvents()
    assert seat.mapToItem(root, QPointF(0, 0)) == original
    seat.childItems()[0].childItems()[1].setX(75); app.processEvents()
    assert native_bounds()[7]["width"] == 111
    assert seat.mapToItem(root, QPointF(0, 0)) == original
    seat.childItems()[0].childItems()[1].setX(42); app.processEvents()
    # Blink and unknown state keep a slot; 0/2 are the only native off states.
    for state in (3, 8, None):
        seat.setProperty("state", state); app.processEvents(); assert seat.mapToItem(root, QPointF(0, 0)) == original
    root.setProperty("requested", False); app.processEvents()
    assert seat.mapToItem(root, QPointF(0, 0)) == QPointF(911, 625)
    root.setProperty("requested", True); seat.childItems()[0].childItems()[0].setHeight(200); app.processEvents()
    assert not root.property("active")  # No clipped or suppressed warning fallback.
    seat.childItems()[0].childItems()[0].setHeight(48); app.processEvents(); assert root.property("active")
    for i, first in enumerate(lamps):
        def mapped(item, index):
            b = native_bounds()[index]
            return item.mapRectToItem(root, QRectF(b["x"], b["y"], b["width"], b["height"]))
        a = mapped(first, i)
        assert a.x() >= 32 and a.right() <= 1888 and a.y() >= 88 and a.bottom() <= 240
        for j, second in enumerate(lamps[i + 1:], i + 1):
            assert not a.intersects(mapped(second, j))
    root.deleteLater(); app.processEvents(); engine.clearComponentCache()
    # Execute only the two primitive OEM Item/Text instrument fragments with a
    # simulated property map. No factory root, lifecycle handler or BYD plugin.
    rows = {row[0]: row[5] for row in rcc.decode(asset.read_bytes())}
    native = rows["qml10_25/cluster.qml"].decode("utf-8")
    fragments = []
    for key in ("naviPowerId", "naviSpeedId"):
        start = native.index("        Item {\r\n            id: " + key) if key == "naviPowerId" else native.rfind("        Item {", 0, native.index("            id: " + key))
        opening = native.index("{", start)
        fragment = native[start:dashboard.brace_end(native, opening)]
        assert {obj[0] for obj in dashboard.qml_objects(fragment)} <= {"Item", "Text", "Translate"}
        for ident in (key, "powerValueId", "powerUnitId", "speedValueId", "speedUnitId"):
            fragment = fragment.replace("id: " + ident + "\r\n", "id: " + ident + '\r\n objectName: "' + ident + '"\r\n')
        fragments.append(fragment)
    source = QQmlPropertyMap()
    for key, value in dict(speed=199, speedUnit=0, power=-199, powerUnit=0,
                           naviType=4, NAVI_TYPE_FULL_SCREEN=4, dayNightMode=1).items(): source.insert(key, value)
    engine.rootContext().setContextProperty("DataSource", source)
    primitive_fixture = '''import QtQuick 2.15
Item { id: rootItemId; width: 1920; height: 720
    readonly property bool dashcastDashboardReady: DataSource.naviType === 4 && speedValueId.contentWidth + speedUnitId.contentWidth + 12 <= 368 && powerValueId.contentWidth + powerUnitId.contentWidth + 12 <= 368
    Rectangle { anchors.fill: parent; z: -3; color: "#334455" }
    DashCastDashboard { z: -2; night: DataSource.dayNightMode === 2; visible: rootItemId.dashcastDashboardReady }
''' + "\n".join(fragments) + "\n}"
    component, root = create(qml=primitive_fixture)
    window = QQuickWindow(); window.resize(1920, 720); root.setParentItem(window.contentItem()); window.show(); QTest.qWait(50)
    by_name = {item.objectName(): item for item in root.findChildren(type(root)) if item.objectName()}
    # QObject subclasses vary across Qt bindings; collect nested visual items directly.
    def descend(item):
        return [child for child in item.childItems()] + [nested for child in item.childItems() for nested in descend(child)]
    by_name = {item.objectName(): item for item in descend(root) if item.objectName()}
    for speed, power, unit in ((0, 0, 0), (199, -199, 0), (299, 999, 2)):
        for key, value in dict(speed=speed, power=power, speedUnit=unit, powerUnit=unit).items(): source.insert(key, value)
        app.processEvents(); assert root.property("dashcastDashboardReady"), (speed, power, unit)
        for name in ("speedValueId", "speedUnitId", "powerValueId", "powerUnitId"):
            item = by_name[name]; bounds = item.mapRectToItem(root, QRectF(0, 0, item.width(), item.height()))
            assert bounds.left() >= 32 and bounds.right() <= 432 and bounds.top() >= 256 and bounds.bottom() <= 568, (name, bounds)
    for night in (1, 2):
        source.insert("dayNightMode", night); app.processEvents()
        grab = root.grabToImage(); QTest.qWait(100); image = grab.image(); assert not image.isNull()
        assert image.pixelColor(40, 100).name() == "#142634"
        image.save(str(args.output / ("native-primitives-day.png" if night == 1 else "native-primitives-night.png")))
    source.insert("speed", 999999); source.insert("power", -999999); app.processEvents()
    assert not root.property("dashcastDashboardReady")  # Never overflow into the application.
    source.insert("speed", -1); source.insert("power", 1001); app.processEvents()
    assert not by_name["speedValueId"].isVisible() and not by_name["powerValueId"].isVisible()
    source.insert("naviType", 0); app.processEvents()
    assert not root.property("dashcastDashboardReady")
    assert by_name["naviPowerId"].mapToItem(root, QPointF(0, 0)) == QPointF(149, 588)
    assert by_name["naviSpeedId"].mapToItem(root, QPointF(0, 0)) == QPointF(0, 0)
    root.setParentItem(None); window.close(); root.deleteLater(); app.processEvents()
    print("PASS Qt", QT_VERSION_STR, ": first-registration aliases; transparent application rectangle; day/night; 76 groups/78 lamp mocks; native Text/Item primitives with simulated data and units; stock/full transitions; imperative seat coordinates; blink/unknown states; overflow fallback. No factory root or OEM plugin executed.")


if __name__ == "__main__": main()
