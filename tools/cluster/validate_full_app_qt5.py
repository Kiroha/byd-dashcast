#!/usr/bin/env python3
"""Execute every added candidate declaration with Qt5 and owned presentation fixtures.

The complete OEM roots and proprietary Presentation component are never executed.
The stock component is SHA-pinned and inspected, then replaced by an owned Item
only inside these isolated test processes. This is not native/hardware validation.
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

import build_full_app as fullapp
import build_live_marker as rcc
from test_full_app import candidate, owned_loader, factory_sources
from PyQt5 import sip
from PyQt5.QtCore import QEvent, QObject, QResource, QUrl, pyqtProperty, qInstallMessageHandler, qVersion
from PyQt5.QtGui import QGuiApplication
from PyQt5.QtQml import QQmlComponent, QQmlEngine, QQmlIncubationController, QQmlPropertyMap, qmlRegisterType
from PyQt5.QtQuick import QQuickItem
from PyQt5.QtTest import QTest

CHILD = "dashcast/isolated/FullApp.qml"
STOCK_URL = "qrc:/" + fullapp.PRESENTATION
CANDIDATE_URL = "qrc:/" + CHILD
FIXTURE = b'''import QtQuick 2.15
Item {
    objectName: "ownedPresentationFixture"
    width: 1920; height: 720
    Component.onCompleted: { Tracker.live += 1; Tracker.created += 1; Tracker.maximum = Math.max(Tracker.maximum, Tracker.live) }
    Component.onDestruction: Tracker.live -= 1
}'''


class MissingContractSignals(QQuickItem):
    """Owned adversarial contract: readable fields without change signals."""
    contractVersion = pyqtProperty(int, lambda self: 1, constant=True)
    componentConstructed = pyqtProperty(bool, lambda self: True, constant=True)
    presentationStatus = pyqtProperty(int, lambda self: 1, constant=True)
    androidFrameState = pyqtProperty(str, lambda self: "UNVERIFIED", constant=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--presentation-source", type=Path,
                        help="Optional exact OEM source readback; the normal artifact validator needs no firmware files")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.presentation_source:
        original = args.presentation_source.read_bytes()
        assert rcc.sha(original) == fullapp.PRESENTATION_SHA
        for field in ('AndroidNativeWindowItem', 'disp_name: "xdjaVirtualSurface"', 'disp_mode: 1',
                      'multi_view: true', 'width: DataSource.screenSize === 0x01 ? 1280:1920',
                      'height: DataSource.screenSize === 0x01 ? 480:720'):
            assert field in original.decode("utf-8"), field
    manifest, carrier, captured = candidate()
    assert manifest["factory_presentation"] == {"path": fullapp.PRESENTATION, "sha256": fullapp.PRESENTATION_SHA}
    native = captured["qml10_25/cluster.qml"][5].decode("utf-8")
    fragment = owned_loader(native)
    # Use the captured import header and the complete replacement, not a hand-written subset.
    imports = "\n".join(line.lstrip("\ufeff").strip() for line in native.splitlines()
                        if line.lstrip("\ufeff").startswith("import Qt"))
    assert imports == "\n".join(line.lstrip("\ufeff").strip() for line in
        factory_sources()[1]["qml10_25/cluster.qml"].decode("utf-8").splitlines()
        if line.lstrip("\ufeff").startswith("import Qt"))
    (args.output / "all-added-declarations.qml").write_text(imports + "\n" + fragment)
    app = QGuiApplication([])
    qmlRegisterType(MissingContractSignals, "DashCastOwnedTest", 1, 0, "MissingContractSignals")
    messages = []
    qInstallMessageHandler(lambda kind, context, message: messages.append(message))
    results = []

    def pump(engine, until=lambda: False, timeout=3000):
        end = time.monotonic() + timeout / 1000
        while time.monotonic() < end:
            app.processEvents()
            engine.incubationController().incubateFor(5)
            app.sendPostedEvents(None, QEvent.DeferredDelete)
            if until(): return
            QTest.qWait(2)
        assert until(), "Timed out waiting for an owned Qt fixture transition"

    def run_case(name, child=None, stock=FIXTURE, mutate_fragment=None, inspect=None):
        case_dir = args.output / name
        case_dir.mkdir(exist_ok=True)
        rows = [row for row in rcc.decode(carrier) if row[0] != CHILD]
        if child is None: child = captured[CHILD][5]
        if child is not False: rows.append((CHILD, 0, 1, 0, child, child))
        rows.append((fullapp.PRESENTATION, 0, 1, 0, stock, stock))
        path = case_dir / "owned-fixture.rcc"
        path.write_bytes(rcc.build(rows))
        assert QResource.registerResource(str(path))
        engine = QQmlEngine()
        controller = QQmlIncubationController(); engine.setIncubationController(controller)
        source = QQmlPropertyMap()
        for key, value in dict(naviType=4, screenSize=2, powerGear=3, playAnim=True, dilinkName=2).items(): source.insert(key, value)
        style = QQmlPropertyMap(); style.insert("presentationZ", 0)
        tracker = QQmlPropertyMap()
        for key in ("live", "maximum", "created"): tracker.insert(key, 0)
        engine.rootContext().setContextProperty("DataSource", source)
        engine.rootContext().setContextProperty("CustomStyle", style)
        engine.rootContext().setContextProperty("Tracker", tracker)
        added = mutate_fragment(fragment) if mutate_fragment else fragment
        scene = imports + "\nItem { id: rootItemId; width: 1920; height: 720\n" + added + "\n}"
        component = QQmlComponent(engine)
        component.setData(scene.encode(), QUrl("qrc:/owned-parent.qml"))
        root = component.create() if component.isReady() else None
        entry = {"case": name, "parent_created": root is not None,
                 "errors": [error.toString() for error in component.errors()]}
        try:
            if mutate_fragment:
                assert root is None and any("unavailableAddedProperty" in error for error in entry["errors"])
            else:
                assert root is not None, entry
                host = root.findChild(QObject, "dashcastPresentationHost")
                assert host is not None
                pump(engine, lambda: host.property("status") in (1, 3))
                # A ready outer Loader does not stand in for inner source readiness.
                try:
                    pump(engine, lambda: host.property("dashcastChildFailed") or
                         host.property("item") is not None and host.property("item").property("presentationStatus") == 1)
                except AssertionError as error:
                    raise AssertionError((name, host.property("status"), host.property("source").toString(),
                                          host.property("dashcastChildFailed"), messages[-8:])) from error
                inspect(engine, root, host, source, tracker, entry)
                entry.update(outer_status=host.property("status"), child_failed=host.property("dashcastChildFailed"),
                             live_presentations=tracker.value("live"), maximum_presentations=tracker.value("maximum"))
                assert tracker.value("maximum") <= 1, entry
        finally:
            if root is not None:
                root.deleteLater(); app.sendPostedEvents(None, QEvent.DeferredDelete); app.processEvents()
            sip.delete(component)
            sip.delete(engine)
            assert tracker.value("live") == 0, name
            assert QResource.unregisterResource(str(path)), name
        results.append(entry)

    def fallback(engine, root, host, source, tracker, entry):
        pump(engine, lambda: host.property("source").toString() == STOCK_URL and host.property("status") == 1)
        assert host.property("dashcastChildFailed")
        assert host.property("item").objectName() == "ownedPresentationFixture"
        created = tracker.value("created")
        source.insert("naviType", 0); source.insert("naviType", 4)
        QTest.qWait(20); app.processEvents()
        assert tracker.value("created") == created, "A failed candidate must not retry automatically"
        entry["fallback_latched"] = True

    def success(engine, root, host, source, tracker, entry):
        item = host.property("item")
        assert not host.property("dashcastChildFailed")
        assert item.property("presentationCreated")
        assert item.property("androidFrameState") == "UNVERIFIED"
        assert host.property("source").toString() == CANDIDATE_URL
        assert tracker.value("live") == 1
        entry["android_frame_state"] = item.property("androidFrameState")

    run_case("exact-final-candidate", inspect=success)
    run_case("missing-child", child=False, inspect=fallback)
    run_case("invalid-child", child=b'import QtQuick 2.15\nItem { unavailableChildProperty: true }', inspect=fallback)
    run_case("null-child-creation", child=b'import QtQuick 2.15\nItem { required property string missing }', inspect=fallback)
    run_case("missing-child-contract", child=b'import QtQuick 2.15\nItem { readonly property int contractVersion: 1; property bool componentConstructed: true }', inspect=fallback)
    run_case("missing-contract-signal", child=b'import DashCastOwnedTest 1.0\nMissingContractSignals {}', inspect=fallback)
    run_case("exception-before-construction", child=captured[CHILD][5].replace(
        b'Component.onCompleted: componentConstructed = true',
        b'Component.onCompleted: { throw new Error("owned construction failure"); componentConstructed = true }'), inspect=fallback)

    def after_exception(engine, root, host, source, tracker, entry):
        success(engine, root, host, source, tracker, entry)
        assert any("owned post-construction failure" in m for m in messages)
        entry["exception_did_not_verify_pixels"] = True
    run_case("exception-after-construction", child=captured[CHILD][5].replace(
        b'Component.onCompleted: componentConstructed = true',
        b'Component.onCompleted: { componentConstructed = true; throw new Error("owned post-construction failure") }'), inspect=after_exception)

    def stock_error(engine, root, host, source, tracker, entry):
        pump(engine, lambda: host.property("dashcastChildFailed") and host.property("status") == 3)
        assert host.property("source").toString() == STOCK_URL
        assert tracker.value("created") == 0
        entry["fallback_error_bounded"] = True
    run_case("invalid-stock-presentation", stock=b'import QtQuick 2.15\nItem { unavailableStockProperty: true }', inspect=stock_error)

    def lose_inner(engine, root, host, source, tracker, entry):
        success(engine, root, host, source, tracker, entry)
        inner = host.property("item").findChild(QObject, "stockPresentation")
        inner.setProperty("source", QUrl("qrc:/missing-after-success.qml"))
        fallback(engine, root, host, source, tracker, entry)
    run_case("source-loss-after-success", inspect=lose_inner)

    def context_changes(engine, root, host, source, tracker, entry):
        success(engine, root, host, source, tracker, entry)
        for key, other, normal in (("naviType", 0, 4), ("screenSize", 1, 2), ("powerGear", 2, 3), ("playAnim", False, True)):
            source.insert(key, other)
            pump(engine, lambda: host.property("source").toString() == STOCK_URL and host.property("status") == 1)
            source.insert(key, normal)
            pump(engine, lambda: host.property("source").toString() == CANDIDATE_URL and
                 host.property("item") is not None and host.property("item").property("presentationStatus") == 1)
        root.setProperty("width", 1280)
        pump(engine, lambda: host.property("source").toString() == STOCK_URL and host.property("status") == 1)
        root.setProperty("width", 1920)
        source.insert("dilinkName", 0)
        pump(engine, lambda: host.property("status") == 0 and tracker.value("live") == 0)
        source.insert("dilinkName", 2)
        pump(engine, lambda: host.property("item") is not None and host.property("item").property("presentationStatus") == 1)
        success(engine, root, host, source, tracker, entry)
    run_case("context-cancel-and-retry", inspect=context_changes)
    run_case("added-declaration-negative-control", mutate_fragment=lambda s: s.replace(
        "asynchronous: true", "asynchronous: true\n unavailableAddedProperty: true", 1))

    # The retired resource remains a negative control, never accepted as a healthy profile.
    old_rows, _ = factory_sources()
    old = old_rows["qml10_25/cluster.qml"][5].decode("utf-8")
    chunks = re.findall(r"// DASHCAST_FULL_APP_BEGIN\r?\n(.*?)// DASHCAST_FULL_APP_END", old, re.S)
    assert len(chunks) == 9 and chunks[0].count("Binding {") == 2
    old_scene = imports + '\nItem { id: rootItemId; width:1920; height:720; property Item mediaInfoUi:null; property Item navigationUi:null\nItem {id:alwyasDisplayId;visible:true}\nItem {id:warningLayoutId;visible:true}\n' + chunks[0] + "\n" + "\n".join('Item {\n' + chunk + '\n}' for chunk in chunks[1:]) + '\n}'
    old_engine = QQmlEngine(); old_component = QQmlComponent(old_engine)
    old_component.setData(old_scene.encode(), QUrl("qrc:/retired-negative.qml"))
    errors = [error.toString() for error in old_component.errors()]
    assert old_component.isError() and any("Binding.restoreMode" in error for error in errors), errors
    results.append({"case": "retired-carrier-negative-control", "parent_created": False, "errors": errors})
    sip.delete(old_component); sip.delete(old_engine)
    result = {"qt_runtime": qVersion(), "candidate_sha256": manifest["carrier_sha256"], "cases": results,
              "presentation_source_sha256": fullapp.PRESENTATION_SHA,
              "presentation_source_readback": args.presentation_source is not None, "vehicle_commands": False,
              "oem_code_executed": False, "qt_messages": messages,
              "limits": "Owned Qt and one owned Presentation stand-in only; no native rendering, GPU, firmware recovery or pixel verification."}
    (args.output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    print("PASS:", len(results), "actual-declaration/owned-fixture cases; Android pixels and recovery remain unverified")


if __name__ == "__main__":
    main()
