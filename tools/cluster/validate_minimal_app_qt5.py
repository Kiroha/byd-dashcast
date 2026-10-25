#!/usr/bin/env python3
"""Render FINAL RCC declarations and owned fixtures on Qt5; no OEM code/vehicle.

The complete proprietary root is NOT executed. Its exact added declarations,
including onChildrenChanged and the onCompleted call, are extracted from the
final research-alias root and executed in a whole-root adversarial fixture.
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

import build_full_dashboard as qml
import build_minimal_app as minimal
import build_live_marker as rcc
from test_full_app import factory_sources
from test_minimal_app import candidate
from PyQt5 import sip
from PyQt5.QtCore import QFile, QEvent, QObject, QResource, QUrl, qInstallMessageHandler, qVersion
from PyQt5.QtGui import QGuiApplication, QImage
from PyQt5.QtQml import QQmlComponent, QQmlPropertyMap
from PyQt5.QtQuick import QQuickView
from PyQt5.QtTest import QTest

WIDTH, HEIGHT = 1920, 720
FIXTURE = b'''import QtQuick 2.15
Rectangle {
    objectName: "ownedApplicationFixture"
    width: 1920; height: 720; color: "#123456"
    Rectangle { x:0; y:0; width:1920; height:1; color:"#ff0000" }
    Rectangle { x:0; y:719; width:1920; height:1; color:"#00ff00" }
    Rectangle { x:0; y:0; width:1; height:720; color:"#0000ff" }
    Rectangle { x:1919; y:0; width:1; height:720; color:"#ffff00" }
    Rectangle { x:0; y:0; width:5; height:5; color:"#00ffff" }
    Rectangle { x:1915; y:0; width:5; height:5; color:"#ff00ff" }
    Rectangle { x:0; y:715; width:5; height:5; color:"#ff8000" }
    Rectangle { x:1915; y:715; width:5; height:5; color:"#8000ff" }
    Component.onCompleted: { Tracker.live += 1; Tracker.created += 1; Tracker.maximum = Math.max(Tracker.maximum, Tracker.live) }
    Component.onDestruction: Tracker.live -= 1
}'''
EMPTY = b'import QtQuick 2.15\nItem { width:1920; height:720 }'
BROKEN = b'import QtQuick 2.15\nItem { unavailableFixtureProperty:true }'


def expected_application_pixels():
    """Independent analytic RGBA expectation for the deterministic app fixture."""
    result = bytearray()
    for y in range(HEIGHT):
        fill = b"\xff\x00\x00\xff" if y == 0 else b"\x00\xff\x00\xff" if y == HEIGHT - 1 else b"\x12\x34\x56\xff"
        row = bytearray(fill * WIDTH)
        row[:4], row[-4:] = b"\x00\x00\xff\xff", b"\xff\xff\x00\xff"
        if y < 5:
            row[:20], row[-20:] = b"\x00\xff\xff\xff" * 5, b"\xff\x00\xff\xff" * 5
        elif y >= HEIGHT - 5:
            row[:20], row[-20:] = b"\xff\x80\x00\xff" * 5, b"\x80\x00\xff\xff" * 5
        result.extend(row)
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--candidate-dir", type=Path, default=minimal.OUT)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest, carrier, captured = candidate(args.candidate_dir)
    root_bytes = captured[minimal.NAMESPACE + "qml10_25/cluster.qml"][5]
    added = minimal.blocks(root_bytes.decode("utf-8"))
    assert added == {name: minimal.block_text(name) for name in minimal.PATCHES}
    _, originals = factory_sources()
    assert minimal.restore_root(root_bytes, originals["qml10_25/cluster.qml"]) == originals["qml10_25/cluster.qml"]
    imports = "\n".join(line.lstrip("\ufeff").strip() for line in root_bytes.decode("utf-8").splitlines()
                        if line.lstrip("\ufeff").startswith("import Qt"))
    assert imports == "\n".join(line.lstrip("\ufeff").strip() for line in
        originals["qml10_25/cluster.qml"].decode("utf-8").splitlines()
        if line.lstrip("\ufeff").startswith("import Qt"))
    presentation = captured[minimal.PRESENTATION_SOURCE][5]
    assert rcc.sha(presentation) == minimal.PRESENTATION_SHA
    for field in ('AndroidNativeWindowItem', 'disp_name: "xdjaVirtualSurface"', 'disp_mode: 1', 'multi_view: true'):
        assert field in presentation.decode("utf-8"), field
    for name, fragment in added.items():
        (args.output / ("final-added-" + name.lower() + ".qml.txt")).write_text(fragment + "\n")
    app = QGuiApplication([])
    messages, results = [], []
    qInstallMessageHandler(lambda kind, context, message: messages.append(message))
    expected = expected_application_pixels()
    black = b"\x00\x00\x00\xff" * WIDTH * HEIGHT
    original_text = originals["qml10_25/cluster.qml"].decode("utf-8")
    visual_ids = []
    for kind, depth, start, opening in qml.qml_objects(original_text):
        if depth == 1 and kind in {"Item", "Image", "Rectangle", "Center", "Loader", "WarinLightLayout", "Text"}:
            ident = re.search(r"\bid:\s*(\w+)", original_text[start:qml.brace_end(original_text, opening)]).group(1)
            if ident != "presentationLoaderId":
                visual_ids.append(ident)
    assert len(visual_ids) == 11

    def pump(until=lambda: False, timeout=3000):
        end = time.monotonic() + timeout / 1000
        while time.monotonic() < end:
            app.processEvents()
            app.sendPostedEvents(None, QEvent.DeferredDelete)
            if until():
                return
            QTest.qWait(2)
        assert until(), "Timed out waiting for an owned Qt fixture transition"

    # Real Qt resource readback of every final path, with no canonical root alias.
    actual_path = args.candidate_dir / manifest["carrier_filename"]
    assert QResource.registerResource(str(actual_path))
    try:
        for path, row in captured.items():
            handle = QFile(":" + "/" + path)
            assert handle.open(QFile.ReadOnly), path
            assert bytes(handle.readAll()) == row[5], path
            handle.close()
            sip.delete(handle)
        assert not QFile.exists(":/qml/cluster.qml")
        assert not QFile.exists(":/qml10_25/cluster.qml")
    finally:
        assert QResource.unregisterResource(str(actual_path))

    def scene(fragments):
        static = "\n".join('Rectangle { id: ' + name + '; objectName: "' + name +
            '"; anchors.fill: parent; z:10000; color:"#ff00ff" }' for name in visual_ids)
        return imports + '''
Item {
    id: rootItemId
    width: 1920; height: 720
    property Item fixtureDynamic: null
    function fixtureAddVisual() {
        fixtureDynamic = Qt.createQmlObject('import QtQuick 2.15; Rectangle { objectName:"ownedDynamicVisual"; width:1920; height:720; z:20000; color:"#ffffff" }', rootItemId)
    }
    function fixtureHandler() { fixtureAddVisual() }
    QtObject { objectName: "ownedNonvisual"; property int sentinel: 7 }
''' + fragments["DECLARATIONS"] + "\n" + fragments["LOADER"] + "\n" + static + '''
    Component.onCompleted: {
''' + fragments["INITIAL"] + '''
        fixtureAddVisual()
    }
}'''

    def pixels(root):
        grabbed = root.grabToImage()
        assert grabbed is not None
        pump(lambda: not grabbed.image().isNull())
        image = grabbed.image()
        assert (image.width(), image.height()) == (WIDTH, HEIGHT)
        rgba = image.convertToFormat(QImage.Format_RGBA8888)
        assert rgba.bytesPerLine() == WIDTH * 4
        return bytes(rgba.bits().asstring(rgba.sizeInBytes())), image

    def assert_pixels(root, target, entry, filename=None):
        actual, image = pixels(root)
        assert actual == target, (entry["case"], "whole-root pixel mismatch", rcc.sha(actual), rcc.sha(target))
        entry["whole_root_rgba_sha256"] = rcc.sha(actual)
        entry["pixels_checked"] = WIDTH * HEIGHT
        if filename:
            assert image.save(str(args.output / filename))
            entry["image"] = filename

    def host_ready(root):
        host = root.findChild(QObject, "dashcastMinimalPresentationHost")
        pump(lambda: host.property("status") in (1, 3))
        if host.property("status") == 1:
            child = host.property("item")
            pump(lambda: child.property("presentationStatus") in (1, 3))
        return host

    def verify_containment(root):
        hidden = root.findChild(QObject, "dashcastOemHiddenHost")
        canvas = root.findChild(QObject, "dashcastBenchCanvas")
        host = root.findChild(QObject, "dashcastMinimalPresentationHost")
        assert not hidden.isVisible()
        assert set(root.childItems()) == {hidden, canvas, host}
        assert host.parentItem() is root and canvas.parentItem() is root
        for name in visual_ids + ["ownedDynamicVisual"]:
            for item in root.findChildren(QObject, name):
                assert item.parentItem() is hidden, name
        assert root.findChild(QObject, "ownedNonvisual").parent() is root
        assert not root.property("dashcastContainBusy")
        assert not root.property("dashcastContainError"), root.property("dashcastContainErrorReason")
        return hidden

    def run_case(name, inspect, stock=FIXTURE, child=None, mutate=None, root_error=None):
        rows = [row for row in rcc.decode(carrier) if row[0] != minimal.CHILD]
        if child is None:
            child = captured[minimal.CHILD][5]
        if child is not False:
            rows.append((minimal.CHILD, 0, 1, 0, child, child))
        if stock is not False:
            rows.append((minimal.PRESENTATION, 0, 1, 0, stock, stock))
        path = args.output / (name + ".fixture.rcc")
        path.write_bytes(rcc.build(rows))
        assert QResource.registerResource(str(path))
        view = QQuickView()
        view.resize(WIDTH, HEIGHT)
        engine = view.engine()
        source = QQmlPropertyMap()
        for key, value in dict(dilinkName=2, screenSize=2, naviType=4, powerGear=3, playAnim=True).items():
            source.insert(key, value)
        style = QQmlPropertyMap(); style.insert("presentationZ", 10)
        tracker = QQmlPropertyMap()
        for key in ("live", "maximum", "created"):
            tracker.insert(key, 0)
        engine.rootContext().setContextProperty("DataSource", source)
        engine.rootContext().setContextProperty("CustomStyle", style)
        engine.rootContext().setContextProperty("Tracker", tracker)
        fragments = dict(added)
        if mutate:
            mutate(fragments)
        component = QQmlComponent(engine)
        component.setData(scene(fragments).encode(), QUrl("qrc:/owned-whole-bench-root.qml"))
        root = component.create() if component.isReady() else None
        entry = {"case": name, "parent_created": root is not None,
                 "errors": [error.toString() for error in component.errors()]}
        try:
            if root_error:
                assert root is None and any(root_error in error for error in entry["errors"]), entry
                entry["mutation_detected"] = True
            else:
                assert root is not None, entry
                view.setContent(QUrl("qrc:/owned-whole-bench-root.qml"), component, root)
                view.show()
                QTest.qWait(15)
                host = host_ready(root)
                inspect(root, host, source, tracker, entry)
                assert tracker.value("maximum") <= 1, entry
                entry.update(maximum_application_consumers=tracker.value("maximum"),
                             containment_passes=root.property("dashcastContainPasses"),
                             containment_error=root.property("dashcastContainError"))
        finally:
            view.close()
            sip.delete(view)
            app.sendPostedEvents(None, QEvent.DeferredDelete)
            app.processEvents()
            assert tracker.value("live") == 0, (name, "orphaned owned presentation")
            assert QResource.unregisterResource(str(path)), name
        results.append(entry)

    def success(root, host, source, tracker, entry):
        verify_containment(root)
        assert host.property("status") == 1
        assert host.property("item").property("androidFrameState") == "UNKNOWN"
        assert tracker.value("live") == 1
        assert_pixels(root, expected, entry, "PC_SIMULATION_full_application.png" if entry["case"] == "final-exact-full-application" else None)

    def blank(root, host, source, tracker, entry):
        verify_containment(root)
        assert_pixels(root, black, entry, "PC_SIMULATION_no_application_black.png" if entry["case"] == "no-application-content" else None)

    run_case("final-exact-full-application", success)
    run_case("no-application-content", blank, stock=EMPTY)
    run_case("missing-application-source", blank, stock=False)
    run_case("invalid-application-source", blank, stock=BROKEN)
    run_case("missing-owned-child", blank, child=False)
    run_case("invalid-owned-child", blank, child=BROKEN)
    run_case("required-property-child-failure", blank, child=b'import QtQuick 2.15\nItem { required property int absent }')

    def change_states(root, host, source, tracker, entry):
        success(root, host, source, tracker, entry)
        hidden = verify_containment(root)
        for item in list(hidden.childItems()):
            item.setProperty("visible", True)
            item.setProperty("opacity", 1.0)
            item.setProperty("z", 1000000)
        for key, values in (("naviType", (0, 1, 3, 4)), ("powerGear", (0, 1, 2, 3)),
                            ("playAnim", (False, True))):
            for value in values:
                source.insert(key, value)
                app.processEvents()
                verify_containment(root)
                assert_pixels(root, expected, entry)
        entry["power_state_never_admits_parked"] = True
    run_case("mode-and-oem-opacity-visible-writes", change_states)

    def context_changes(root, host, source, tracker, entry):
        success(root, host, source, tracker, entry)
        for key, other, normal in (("screenSize", 1, 2), ("dilinkName", 0, 2)):
            source.insert(key, other)
            pump(lambda: host.property("status") == 0 and tracker.value("live") == 0)
            blank(root, host, source, tracker, entry)
            source.insert(key, normal)
            host_ready(root)
            success(root, host, source, tracker, entry)
    run_case("context-loss-and-recreation", context_changes)

    def dynamic_children(root, host, source, tracker, entry):
        success(root, host, source, tracker, entry)
        for _ in range(5):
            root.fixtureHandler()
            app.processEvents()
            hidden = verify_containment(root)
            dynamic = root.property("fixtureDynamic")
            assert dynamic.parentItem() is hidden
            # Reattachment to the root triggers containment again synchronously.
            dynamic.setParentItem(root)
            verify_containment(root)
            assert_pixels(root, expected, entry)
            dynamic.deleteLater()
            app.sendPostedEvents(None, QEvent.DeferredDelete)
            app.processEvents()
        before = root.property("dashcastContainPasses")
        for _ in range(4):
            root.dashcastContainOemChildren()
        assert root.property("dashcastContainPasses") == before + 4
        verify_containment(root)
        assert_pixels(root, expected, entry)
        assert root.property("dashcastContainPasses") < 50
        entry["dynamic_on_completed_and_handler_children"] = True
        entry["idempotent_and_bounded"] = True
    run_case("dynamic-create-reparent-remove-and-idempotence", dynamic_children)

    def application_loss(root, host, source, tracker, entry):
        success(root, host, source, tracker, entry)
        inner = host.property("item").findChild(QObject, "stockPresentation")
        inner.setProperty("source", QUrl("qrc:/missing-after-first-frame.qml"))
        pump(lambda: inner.property("status") == 3 and tracker.value("live") == 0)
        blank(root, host, source, tracker, entry)
        inner.setProperty("source", QUrl("qrc:/" + minimal.PRESENTATION))
        host_ready(root)
        success(root, host, source, tracker, entry)
        host.setProperty("source", QUrl())
        pump(lambda: host.property("status") == 0 and tracker.value("live") == 0)
        blank(root, host, source, tracker, entry)
    run_case("application-and-owned-child-loss-after-frame", application_loss)

    def child_exception(root, host, source, tracker, entry):
        blank(root, host, source, tracker, entry)
        assert any("owned child exception" in message for message in messages)
        assert host.property("item").property("androidFrameState") == "UNKNOWN"
    run_case("child-callback-exception", child_exception, stock=EMPTY,
             child=captured[minimal.CHILD][5].replace(b'Component.onCompleted: componentConstructed = true',
             b'Component.onCompleted: { throw new Error("owned child exception"); componentConstructed = true }'))

    def mutate_declarations(fragments):
        fragments["DECLARATIONS"] += "\nunavailableAddedProperty: true"
    run_case("invalid-added-declaration-negative", None, mutate=mutate_declarations, root_error="unavailableAddedProperty")
    run_case("invalid-loader-declaration-negative", None,
             mutate=lambda f: f.update(LOADER=f["LOADER"].replace("asynchronous: true", "unavailableAddedProperty: true")),
             root_error="unavailableAddedProperty")

    def visual_mutation(root, host, source, tracker, entry):
        actual, _ = pixels(root)
        assert actual != expected
        assert root.property("dashcastContainError")
        assert not root.property("dashcastContainBusy")
        before = root.property("dashcastContainPasses")
        QTest.qWait(40)
        app.processEvents()
        assert root.property("dashcastContainPasses") == before
        entry["mutation_detected"] = True
        entry["bounded_error_observed"] = True
    run_case("escaped-visual-negative", visual_mutation,
             mutate=lambda f: f.update(DECLARATIONS=f["DECLARATIONS"].replace(
                 "child.parent = dashcastOemHiddenHost", "child.parent = rootItemId")))
    run_case("reparent-exception-negative", visual_mutation,
             mutate=lambda f: f.update(DECLARATIONS=f["DECLARATIONS"].replace(
                 "child.parent = dashcastOemHiddenHost", 'throw new Error("owned reparent failure")')))

    def initial_exception(root, host, source, tracker, entry):
        actual, _ = pixels(root)
        assert actual != expected
        assert not root.property("dashcastBenchConstructed")
        assert any("owned initial failure" in message for message in messages)
        entry["mutation_detected"] = True
    run_case("on-completed-call-negative", initial_exception,
             mutate=lambda f: f.update(INITIAL='throw new Error("owned initial failure");\n' + f["INITIAL"]))

    # Reproduce the retired resource's actual unsupported Binding declarations.
    legacy, _ = factory_sources()
    old = legacy["qml10_25/cluster.qml"][5].decode("utf-8")
    chunks = re.findall(r"// DASHCAST_FULL_APP_BEGIN\r?\n(.*?)// DASHCAST_FULL_APP_END", old, re.S)
    assert len(chunks) == 9 and chunks[0].count("Binding {") == 2
    old_scene = imports + '\nItem { id:rootItemId; width:1920; height:720; property Item mediaInfoUi:null; property Item navigationUi:null\nItem{id:alwyasDisplayId;visible:true}\nItem{id:warningLayoutId;visible:true}\n' + chunks[0] + "\n" + "\n".join("Item {\n" + chunk + "\n}" for chunk in chunks[1:]) + "\n}"
    view = QQuickView(); component = QQmlComponent(view.engine())
    component.setData(old_scene.encode(), QUrl("qrc:/retired-negative.qml"))
    errors = [error.toString() for error in component.errors()]
    assert component.isError() and any("Binding.restoreMode" in error for error in errors), errors
    results.append({"case": "retired-binding-negative-control", "parent_created": False,
                    "mutation_detected": True, "errors": errors})
    sip.delete(component); sip.delete(view)
    report = {"qt_runtime": qVersion(), "candidate_sha256": manifest["carrier_sha256"],
              "cases": results, "final_resource_readback_count": len(captured),
              "static_oem_visual_fixture_groups": visual_ids, "qt_messages": messages,
              "vehicle_commands": False, "oem_code_executed": False,
              "native_contract_semantics": "UNKNOWN", "native_pixels": "UNKNOWN",
              "limits": "Whole owned bench root and synthetic app pixels only. Proprietary root/plugins, native external layers, Android frames, firmware recovery and road use are not validated."}
    (args.output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    print("PASS:", len(results), "final-declaration/whole-root Qt cases; PC simulation only, native pixels UNKNOWN")


if __name__ == "__main__":
    main()
