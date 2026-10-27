#!/usr/bin/env python3
"""Execute the final owned blocks of the full-screen carrier in Qt with stand-in OEM items only.

The proprietary root, its plugins and native types are never executed. DataSource and
CustomStyle are owned stand-ins; pixels describe this fixture, not the vehicle.
"""
import argparse
import json
import os
import re
import time
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
os.environ.setdefault("QT_QUICK_BACKEND", "software")
os.environ.setdefault("QSG_RENDER_LOOP", "basic")

import build_fullscreen_minimal as fullscreen
import build_live_marker as rcc
from PyQt5.QtCore import (Q_RETURN_ARG, QEvent, QFile, QMetaObject, QResource, QUrl, QVariant,
                          qInstallMessageHandler, qVersion)
from PyQt5.QtGui import QGuiApplication
from PyQt5.QtQml import QQmlComponent, QQmlPropertyMap
from PyQt5.QtQuick import QQuickView
from PyQt5.QtTest import QTest

OEM = "#ff8000"
APP = "#00a050"
SOURCE = """%(imports)s
Item {
    id: rootItemId
    width: 1920
    height: 720
%(declarations)s
    Component.onCompleted: {
%(initial)s
    }
    Rectangle { objectName: "oemBackground"; anchors.fill: parent; color: "%(oem)s" }
    Rectangle { objectName: "oemTelltales"; x: 32; y: 88; width: 400; height: 60; color: "#ff0000"; z: 5 }
    Rectangle {
        id: presentationLoaderId
        objectName: "presentationStandIn"
        x: 0; y: 0; width: 1920; height: 720
        z: CustomStyle.presentationZ
        color: "%(app)s"
        visible: rootItemId.dashcastBenchSuspendVisuals
    }
    function createDynamicOem() {
        return Qt.createQmlObject('import QtQuick 2.15; Rectangle { objectName: "dynamicOem"; width: 10; height: 10 }', rootItemId)
    }
}
"""


def indented(text, spaces):
    return "\n".join(" " * spaces + line for line in text.splitlines())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    carrier_path = fullscreen.OUT / fullscreen.CARRIER
    carrier = carrier_path.read_bytes()
    manifest = json.loads((fullscreen.OUT / fullscreen.MANIFEST).read_text())
    assert rcc.sha(carrier) == manifest["carrier_sha256"]
    rows = {row[0]: row for row in rcc.decode(carrier)}
    final = rows[fullscreen.LARGE_ROOT][5]
    added = fullscreen.blocks(final.decode("utf-8"))
    assert added == {name: fullscreen.block_text(name) for name in fullscreen.PATCHES}
    original = fullscreen.restore_root(final)
    assert rcc.sha(original) == fullscreen.cohort.ORIGINALS[fullscreen.LARGE_ROOT][1]
    # Proprietary com.byd.* and relative imports cannot be resolved off-vehicle.
    imports = "\n".join(line.lstrip("\ufeff").strip() for line in original.decode("utf-8").splitlines()
                        if line.lstrip("\ufeff").startswith("import Qt"))

    app = QGuiApplication([])
    messages, results = [], []
    qInstallMessageHandler(lambda kind, context, message: messages.append(message))
    assert QResource.registerResource(str(carrier_path))
    for path, row in rows.items():
        handle = QFile(":/" + path)
        assert handle.open(QFile.ReadOnly), path
        assert bytes(handle.readAll()) == row[5], path
        handle.close()
    results.append("all 86 final resources read through QResource")
    results.append("factory Qt import header: " + "; ".join(imports.splitlines()))

    data = QQmlPropertyMap()
    for key, value in {"naviType": 0, "powerGear": 3, "playAnim": True, "dilinkName": 2, "screenSize": 2}.items():
        data.insert(key, value)
    style = QQmlPropertyMap()
    style.insert("presentationZ", 50)
    fixture = args.output / "FullscreenFixture.qml"
    fixture.write_text(SOURCE % {"imports": imports, "declarations": indented(added["DECLARATIONS"], 4),
                                 "initial": indented(added["INITIAL"], 8), "oem": OEM, "app": APP})
    view = QQuickView()
    view.rootContext().setContextProperty("DataSource", data)
    view.rootContext().setContextProperty("CustomStyle", style)
    view.setSource(QUrl.fromLocalFile(str(fixture)))
    assert view.status() == QQuickView.Ready, [error.toString() for error in view.errors()]
    view.resize(1920, 720)
    view.show()
    root = view.rootObject()

    def settle():
        end = time.monotonic() + 0.3
        while time.monotonic() < end:
            app.sendPostedEvents(None, QEvent.DeferredDelete)
            app.processEvents()
            QTest.qWait(5)

    def pixel(x, y):
        grabbed = root.grabToImage()
        end = time.monotonic() + 3
        while grabbed.image().isNull() and time.monotonic() < end:
            app.processEvents()
            QTest.qWait(5)
        return grabbed.image().pixelColor(x, y).name()

    def oem_parent():
        return root.findChild(object, "oemBackground").parentItem().objectName()

    def check(label, suspended, background, telltale):
        settle()
        assert root.property("dashcastBenchSuspendVisuals") is suspended, label
        assert not root.property("dashcastBenchReconcileError"), root.property("dashcastBenchReconcileErrorReason")
        assert pixel(1200, 400) == background, (label, pixel(1200, 400))
        assert pixel(100, 100) == telltale, (label, pixel(100, 100))
        results.append(f"{label}: suspended={suspended} oemParent={oem_parent() or 'rootItemId'} "
                       f"center={pixel(1200, 400)} telltaleZone={pixel(100, 100)} "
                       f"passes={root.property('dashcastBenchReconcilePasses')}")

    check("factory scene outside full-screen mode", False, OEM, "#ff0000")
    data.insert("naviType", 4)
    check("full-screen projection mode", True, APP, APP)
    assert oem_parent() == "dashcastBenchHiddenHost"
    QMetaObject.invokeMethod(root, "createDynamicOem", Q_RETURN_ARG(QVariant))
    settle()
    created = root.findChild(object, "dynamicOem")
    assert created is not None and created.parentItem().objectName() == "dashcastBenchHiddenHost"
    results.append("dynamic OEM child created during full-screen mode was contained")
    data.insert("powerGear", 0)
    settle()
    check("power OFF during projection returns factory scene", False, OEM, "#ff0000")
    data.insert("powerGear", 3)
    data.insert("naviType", 0)
    settle()
    check("projection closed returns factory scene", False, OEM, "#ff0000")

    # The retired full-app blocks must still be rejected under this header, or the header check is ineffective.
    retired = {row[0]: row[5] for row in rcc.decode(
        (fullscreen.fullapp.LEGACY_ASSETS / "full_app_21f7735c.rcc").read_bytes())}[fullscreen.LARGE_ROOT].decode("utf-8")
    chunks = re.findall(r"// DASHCAST_FULL_APP_BEGIN\r?\n(.*?)// DASHCAST_FULL_APP_END", retired, re.S)
    assert len(chunks) == 9 and chunks[0].count("Binding {") == 2
    scene = (imports + "\nItem { id: rootItemId; width: 1920; height: 720; property Item mediaInfoUi: null; "
             "property Item navigationUi: null\nItem { id: alwyasDisplayId }\nItem { id: warningLayoutId }\n"
             + chunks[0] + "\n" + "\n".join("Item {\n" + chunk + "\n}" for chunk in chunks[1:]) + "\n}")
    component = QQmlComponent(view.engine())
    component.setData(scene.encode(), QUrl("qrc:/retired-negative.qml"))
    rejected = [error.toString() for error in component.errors()]
    assert component.isError() and any("Binding.restoreMode" in error for error in rejected), rejected
    results.append("negative control: retired full-app blocks rejected: " + rejected[0])

    qml_messages = [message for message in messages if ".qml" in message or "QML" in message or "rror" in message]
    assert not qml_messages, qml_messages
    results.append(f"Qt {qVersion()}; QML messages: {len(qml_messages)}; other Qt messages: {messages}")
    (args.output / "results.txt").write_text("\n".join(results) + "\n")
    print("\n".join(results))


if __name__ == "__main__":
    main()
