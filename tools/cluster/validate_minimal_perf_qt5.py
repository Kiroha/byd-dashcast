#!/usr/bin/env python3
"""Execute final owned RCC additions and existing Loader with Qt-only payloads.

Never instantiate the complete factory root, CenterChild, an OEM plugin or the
real ADAS sources. Metrics describe the explicitly instrumented stand-ins.
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
import build_minimal_perf as perf
import build_live_marker as rcc
from test_full_app import factory_sources
from test_minimal_perf import candidate
from validate_minimal_app_qt5 import FIXTURE, BROKEN, expected_application_pixels
from PyQt5 import sip
from PyQt5.QtCore import QFile, QEvent, QObject, QResource, QTimer, QUrl, pyqtSignal, qInstallMessageHandler, qVersion
from PyQt5.QtGui import QGuiApplication, QImage
from PyQt5.QtQml import QQmlComponent, QQmlPropertyMap
from PyQt5.QtQuick import QQuickView
from PyQt5.QtTest import QTest

ADAS = "qml10_25/adas2d/Adas.qml"
ADAS_ALT = "qml10_25/adas2d/Adas3R1V.qml"
PRESENTATION = "qml10_25/alwaysDisplay/Presentation.qml"
PAYLOAD = b'''import QtQuick 2.15
Item {
    objectName: "ownedAdasWorkloadStandIn"
    property bool needAnimate: false
    property bool registered: false
    Component.onCompleted: {
        registered = true
        Tracker.payloadCreated += 1
        Tracker.payloadLive += 1
        Tracker.payloadMaximum = Math.max(Tracker.payloadMaximum, Tracker.payloadLive)
    }
    Component.onDestruction: {
        if (registered) {
            Tracker.payloadDestroyed += 1
            Tracker.payloadLive -= 1
        }
    }
    Timer {
        objectName: "ownedRadarTimerStandIn"
        interval: 100; running: true; repeat: true
        onTriggered: Tracker.timerFirings += 1
    }
    Connections {
        target: Events
        function onPulse() { Tracker.handlerInvocations += 1 }
    }
}'''


class SourceEvents(QObject):
    pulse = pyqtSignal()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--candidate-dir", type=Path, default=perf.OUT)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest, carrier, captured = candidate(args.candidate_dir)
    final = captured[perf.NAMESPACE + perf.LARGE_ROOT][5]
    added = perf.blocks(final.decode("utf-8"))
    assert added == {name: perf.block_text(name) for name in perf.PATCHES}
    _, originals = factory_sources()
    assert perf.restore_root(final) == originals[perf.LARGE_ROOT]
    original = originals[perf.LARGE_ROOT].decode("utf-8")
    imports = "\n".join(line.lstrip("\ufeff").strip() for line in original.splitlines()
                        if line.lstrip("\ufeff").startswith("import Qt"))
    final_loader = perf.loader_object(final.decode())
    original_loader = perf.loader_object(original)
    # Read and execute the actual unchanged Presentation Loader too.
    presentations = [obj for obj in qml.qml_objects(original) if obj[0] == "Loader" and
                     re.match(r"Loader\s*\{\s*id:\s*presentationLoaderId\b", original[obj[2]:])]
    assert len(presentations) == 1
    obj = presentations[0]
    presentation_loader = original[obj[2]:qml.brace_end(original, obj[3])]
    assert presentation_loader in final.decode()
    for name, text in added.items():
        (args.output / ("final-added-" + name.lower() + ".qml.txt")).write_text(text + "\n")
    (args.output / "final-existing-adas-loader.qml.txt").write_text(final_loader + "\n")
    app = QGuiApplication([])
    messages, results = [], []
    qInstallMessageHandler(lambda kind, context, message: messages.append(message))

    def drain():
        app.sendPostedEvents(None, QEvent.DeferredDelete)
        app.processEvents()

    def pump(predicate, timeout=3000):
        end = time.monotonic() + timeout / 1000
        while time.monotonic() < end:
            drain()
            if predicate():
                return
            QTest.qWait(2)
        assert predicate(), "Timed out waiting for a Qt-only bench transition"

    actual_path = args.candidate_dir / manifest["carrier_filename"]
    assert QResource.registerResource(str(actual_path))
    try:
        for path, row in captured.items():
            handle = QFile(":/" + path)
            assert handle.open(QFile.ReadOnly), path
            assert bytes(handle.readAll()) == row[5], path
            handle.close()
            sip.delete(handle)
        assert not QFile.exists(":/qml/cluster.qml")
        assert not QFile.exists(":/qml10_25/cluster.qml")
    finally:
        assert QResource.unregisterResource(str(actual_path))

    # Ten visual siblings plus the real existing ADAS Loader's parent make the
    # original eleven groups. Standard Qt rectangles stand in for OEM groups.
    visual_ids = []
    for kind, depth, start, opening in qml.qml_objects(original):
        if depth == 1 and kind in {"Item", "Image", "Rectangle", "Center", "Loader", "WarinLightLayout", "Text"}:
            ident = re.search(r"\bid:\s*(\w+)", original[start:qml.brace_end(original, opening)]).group(1)
            if ident != "presentationLoaderId":
                visual_ids.append(ident)
    assert len(visual_ids) == 11

    def scene(mode, mutation=None):
        fragments = dict(added)
        if mutation:
            mutation(fragments)
        loader = final_loader
        if mode == "mask":
            loader = loader.replace(fragments["ADAS_ACTIVE"], "active: true")
        else:
            loader = loader.replace(added["ADAS_ACTIVE"], fragments["ADAS_ACTIVE"])
        if mode == "factory":
            loader = original_loader
        groups = "\n".join('Rectangle { id:' + name + '; objectName:"' + name +
            '"; anchors.fill:parent; z:10000; color:"#ff00ff" }'
            for name in visual_ids if name != "adasContainerParentId")
        return imports + '''
Item {
    id: rootItemId
    width:1920; height:720
    property Item fixtureDynamic: null
    function fixtureAddVisual() {
        fixtureDynamic = Qt.createQmlObject('import QtQuick 2.15; Rectangle { objectName:"ownedDynamic"; width:1920; height:720; color:"#ffffff"; z:20000 }', rootItemId)
    }
    function fixtureRapidToggle() {
        for (var i = 0; i < 30; ++i) {
            dashcastBenchProjectionActive = false
            dashcastBenchProjectionActive = true
        }
    }
''' + fragments["DECLARATIONS"] + "\n" + groups + '''
    Rectangle {
        id:adasContainerParentId
        objectName:"adasContainerParentId"
        width:189; height:120
''' + loader + "\n}\n" + presentation_loader + '''
    Component.onCompleted: {
''' + fragments["INITIAL"] + '''
    }
}'''

    def metrics(tracker, root):
        keys = ("payloadCreated", "payloadDestroyed", "payloadLive", "payloadMaximum",
                "timerFirings", "handlerInvocations", "live", "created", "maximum")
        values = {key: tracker.value(key) for key in keys}
        values["running_fixture_timers"] = sum(1 for child in root.findChildren(QObject, "ownedRadarTimerStandIn")
                                               if child.property("running"))
        return values

    def inspect_parents(root, suspended):
        hidden = root.findChild(QObject, "dashcastBenchHiddenHost")
        assert hidden is not None
        for name in visual_ids:
            group = root.findChild(QObject, name)
            assert group is not None, name
            assert group.parentItem() is (hidden if suspended else root), name
        dynamic = root.property("fixtureDynamic")
        if dynamic is not None:
            assert dynamic.parentItem() is (hidden if suspended else root)
        assert not root.property("dashcastBenchReconcileError")
        assert not root.property("dashcastBenchReconcileBusy")

    def run(name, check, mode="optimized", requested=True, payload=PAYLOAD, payload_alt=None, projected=FIXTURE,
            mutation=None, expected_error=None):
        # Only owned synthetic content is assigned the original runtime URLs in
        # this private fixture RCC. The research carrier itself has no such URLs.
        resources = {ADAS: payload, ADAS_ALT: payload if payload_alt is None else payload_alt, PRESENTATION: projected}
        path = args.output / (name + ".fixture.rcc")
        path.write_bytes(rcc.build([(key, 0, 1, 0, value, value) for key, value in resources.items()]))
        assert QResource.registerResource(str(path))
        view = QQuickView()
        engine = view.engine()
        source = QQmlPropertyMap()
        for key, value in {"dilinkName": 2, "screenSize": 2, "carNameType": 0,
                           "adasPlatform": 0, "adasWindow": 2, "powerGear": 3,
                           "playAnim": True, "adasInterfaceDisplay": 2, "adasType": 0}.items():
            source.insert(key, value)
        style = QQmlPropertyMap(); style.insert("presentationZ", 10)
        tracker = QQmlPropertyMap()
        for key in ("payloadCreated", "payloadDestroyed", "payloadLive", "payloadMaximum",
                    "timerFirings", "handlerInvocations", "live", "created", "maximum"):
            tracker.insert(key, 0)
        events = SourceEvents()
        for key, value in (("DataSource", source), ("CustomStyle", style), ("Tracker", tracker), ("Events", events)):
            engine.rootContext().setContextProperty(key, value)
        component = QQmlComponent(engine)
        component.setData(scene(mode, mutation).encode(), QUrl("qrc:/owned-perf-scene.qml"))
        root = component.createWithInitialProperties({"dashcastBenchProjectionActive": requested}) if component.isReady() else None
        entry = {"case": name, "mode": mode, "parent_created": root is not None,
                 "errors": [error.toString() for error in component.errors()]}
        try:
            if expected_error:
                assert root is None and any(expected_error in error for error in entry["errors"]), entry
                entry["mutation_detected"] = True
            else:
                assert root is not None, entry
                view.setContent(QUrl("qrc:/owned-perf-scene.qml"), component, root)
                view.show()
                # Loader has an id but no objectName in factory; use QML lookup.
                loader = next(child for child in root.findChildren(QObject)
                              if child.metaObject().className().startswith("QQuickLoader") and child.parentItem().objectName() == "adasContainerParentId")
                presentation = next(child for child in root.childItems()
                                    if child.metaObject().className().startswith("QQuickLoader"))
                pump(lambda: presentation.property("status") in (1, 3))
                inspect_parents(root, bool(root.property("dashcastBenchSuspendVisuals")))
                check(root, loader, presentation, source, tracker, events, entry)
                entry["metrics_before_teardown"] = metrics(tracker, root)
                assert tracker.value("maximum") <= 1, entry
        finally:
            handlers_before_teardown = tracker.value("handlerInvocations")
            QTimer.singleShot(0, events.pulse.emit)
            view.close()
            sip.delete(view)
            drain()
            events.pulse.emit()
            assert tracker.value("handlerInvocations") == handlers_before_teardown, entry
            assert tracker.value("live") == tracker.value("payloadLive") == 0, entry
            assert tracker.value("payloadCreated") == tracker.value("payloadDestroyed"), entry
            assert QResource.unregisterResource(str(path)), name
        entry["completed_payloads_destroyed_after_teardown"] = tracker.value("payloadDestroyed")
        results.append(entry)

    def observation(root, loader, presentation, source, tracker, events, entry):
        suspended = entry["mode"] == "optimized"
        assert loader.property("active") is not suspended
        pump(lambda: tracker.value("payloadLive") == (0 if suspended else 1))
        before = metrics(tracker, root)
        started = time.monotonic()
        for index in range(8):
            source.insert("carNameType", index % 2)
            events.pulse.emit()
            drain()
        QTest.qWait(350)
        drain()
        after = metrics(tracker, root)
        entry.update(nominal_observation_ms=350, source_pulses=8,
                     observed_elapsed_ms=round((time.monotonic() - started) * 1000, 1),
                     interval_before=before, interval_after=after)
        assert after["handlerInvocations"] - before["handlerInvocations"] == (0 if suspended else 8)
        assert after["running_fixture_timers"] == (0 if suspended else 1)
        if suspended:
            assert after["payloadCreated"] == after["timerFirings"] == 0
            assert loader.property("item") is None
        else:
            assert after["timerFirings"] > before["timerFirings"]
    run("factory-cold-observation", observation, mode="factory", requested=False)
    run("mask-cold-observation", observation, mode="mask")
    run("optimized-startup-active-gate-zero-completions", observation)

    def lifecycle(root, loader, presentation, source, tracker, events, entry):
        assert tracker.value("payloadCreated") == 0
        for cycle in range(6):
            # Resume uses the current source state; no new ADAS event required.
            root.setProperty("dashcastBenchProjectionActive", False)
            pump(lambda: loader.property("status") == 1 and tracker.value("payloadLive") == 1)
            inspect_parents(root, False)
            assert tracker.value("payloadCreated") == cycle + 1
            events.pulse.emit()
            root.fixtureAddVisual()
            # Queue a source signal, then unload before it can be delivered.
            QTimer.singleShot(0, events.pulse.emit)
            root.setProperty("dashcastBenchProjectionActive", True)
            pump(lambda: tracker.value("payloadLive") == 0)
            inspect_parents(root, True)
            before = metrics(tracker, root)
            for index in range(6):
                source.insert("adasPlatform", index % 2)
                source.insert("adasWindow", 2)
                source.insert("playAnim", index % 2 == 0)
                source.insert("powerGear", 3 if index % 2 else 1)
                events.pulse.emit()
            source.insert("playAnim", True); source.insert("powerGear", 3)
            source.insert("adasPlatform", 0)
            QTest.qWait(130); drain()
            after = metrics(tracker, root)
            assert after["payloadLive"] == after["running_fixture_timers"] == 0
            for key in ("payloadCreated", "timerFirings", "handlerInvocations"):
                assert before[key] == after[key], (key, before, after)
        assert tracker.value("payloadMaximum") <= 1
        entry["warm_cycles"] = 6
        entry["restored_without_new_adas_event"] = True
        entry["late_source_signal_ignored_after_unload"] = True
    run("warm-release-restore-and-source-storm", lifecycle)

    def context_and_churn(root, loader, presentation, source, tracker, events, entry):
        # A context loss restores OEM visual parents and cancels suspension.
        for key, other in (("screenSize", 1), ("dilinkName", 0)):
            source.insert(key, other)
            pump(lambda: tracker.value("payloadLive") == 1)
            inspect_parents(root, False)
            source.insert(key, 2)
            pump(lambda: tracker.value("payloadLive") == 0)
            inspect_parents(root, True)
        # All toggles in one JS call expose deferred deletion/recreation overlap.
        root.fixtureRapidToggle()
        pump(lambda: tracker.value("payloadLive") == 0)
        assert tracker.value("payloadMaximum") <= 1
        # Exercise asynchronous-mode toggles too. This case does not require
        # observing Loading; the teardown case records its actual status.
        # This assignment belongs only to the Qt stand-in fixture.
        loader.setProperty("asynchronous", True)
        for _ in range(30):
            root.setProperty("dashcastBenchProjectionActive", False)
            root.setProperty("dashcastBenchProjectionActive", True)
        pump(lambda: tracker.value("payloadLive") == 0)
        assert loader.property("item") is None
        stable = metrics(tracker, root)
        QTest.qWait(130); events.pulse.emit(); drain()
        after = metrics(tracker, root)
        assert after["payloadCreated"] == after["payloadDestroyed"]
        for key in ("payloadCreated", "timerFirings", "handlerInvocations"):
            assert stable[key] == after[key], (key, stable, after)
        root.setProperty("dashcastBenchProjectionActive", False)
        pump(lambda: tracker.value("payloadLive") == 1 and loader.property("status") == 1)
        inspect_parents(root, False)
        entry["synchronous_rapid_toggle_count"] = 30
        entry["asynchronous_rapid_toggle_count"] = 30
        entry["async_mode_toggle_cancellation"] = True
    run("context-loss-rapid-toggle-and-async-cancel", context_and_churn)

    def failure(root, loader, presentation, source, tracker, events, entry):
        assert tracker.value("payloadCreated") == 0
        root.setProperty("dashcastBenchProjectionActive", False)
        pump(lambda: loader.property("status") == 3)
        assert loader.property("item") is None and tracker.value("payloadLive") == 0
        inspect_parents(root, False)
        stable = metrics(tracker, root)
        passes = root.property("dashcastBenchReconcilePasses")
        QTest.qWait(130); drain()
        assert stable == metrics(tracker, root)
        assert passes == root.property("dashcastBenchReconcilePasses")
        assert loader.property("status") == 3
        root.setProperty("dashcastBenchProjectionActive", True)
        pump(lambda: loader.property("status") == 0)
        # The latest OEM predicate may select a different source on restoration.
        source.insert("adasPlatform", 1)
        root.setProperty("dashcastBenchProjectionActive", False)
        pump(lambda: loader.property("status") == 1 and tracker.value("payloadLive") == 1)
        assert tracker.value("payloadCreated") == 1
        entry["restoration_failed_distinct_from_restored"] = True
        entry["latest_valid_source_restored_after_failure"] = True
        entry["no_automatic_retry"] = True
    run("payload-creation-failure-and-explicit-resume", failure, payload=BROKEN, payload_alt=PAYLOAD)

    def pending_teardown(root, loader, presentation, source, tracker, events, entry):
        loader.setProperty("asynchronous", True)
        root.setProperty("dashcastBenchProjectionActive", False)
        entry["status_at_teardown"] = loader.property("status")
        assert entry["status_at_teardown"] in (1, 2)
        QTimer.singleShot(0, events.pulse.emit)
    run("teardown-with-asynchronous-load-and-queued-signal", pending_teardown)

    def content_failure(root, loader, presentation, source, tracker, events, entry):
        assert presentation.property("status") == 3
        assert loader.property("item") is None and tracker.value("payloadCreated") == 0
        inspect_parents(root, True)
        root.setProperty("dashcastBenchProjectionActive", False)
        pump(lambda: tracker.value("payloadLive") == 1)
        inspect_parents(root, False)
        entry["explicit_exit_restores_after_content_error"] = True
    run("projection-content-error-then-exit", content_failure, projected=BROKEN)

    # Pixel checks are separate from work-count observations.
    def pixels(root, loader, presentation, source, tracker, events, entry):
        expected = expected_application_pixels()
        def capture():
            grabbed = root.grabToImage()
            pump(lambda: not grabbed.image().isNull())
            image = grabbed.image().convertToFormat(QImage.Format_RGBA8888)
            return bytes(image.bits().asstring(image.sizeInBytes())), image
        actual, image = capture()
        assert actual == expected
        assert image.save(str(args.output / "PC_SIMULATION_perf_application_only.png"))
        entry["application_rgba_sha256"] = rcc.sha(actual)
        root.setProperty("dashcastBenchProjectionActive", False)
        pump(lambda: tracker.value("payloadLive") == 1)
        inspect_parents(root, False)
        actual, image = capture()
        assert actual != expected
        assert image.save(str(args.output / "PC_SIMULATION_perf_oem_fixture_restored.png"))
        entry["visual_parent_restoration_pixels_changed"] = True
    run("separate-pixel-entry-and-restoration", pixels)

    def black_pixels(root, loader, presentation, source, tracker, events, entry):
        grabbed = root.grabToImage()
        pump(lambda: not grabbed.image().isNull())
        image = grabbed.image().convertToFormat(QImage.Format_RGBA8888)
        actual = bytes(image.bits().asstring(image.sizeInBytes()))
        assert actual == b"\x00\x00\x00\xff" * 1920 * 720
        entry["whole_canvas_opaque_black"] = True
    run("empty-projection-content-opaque-black", black_pixels,
        projected=b'import QtQuick 2.15\nItem { width:1920; height:720 }')

    def stuck_restore(root, loader, presentation, source, tracker, events, entry):
        root.setProperty("dashcastBenchProjectionActive", False)
        assert root.property("dashcastBenchReconcileError")
        assert not root.property("dashcastBenchReconcileBusy")
        passes = root.property("dashcastBenchReconcilePasses")
        QTest.qWait(130); drain()
        assert passes == root.property("dashcastBenchReconcilePasses")
        entry["mutation_detected"] = True
        entry["bounded_restoration_error"] = True
    run("stuck-visual-restoration-negative", stuck_restore,
        mutation=lambda f: f.update(DECLARATIONS=f["DECLARATIONS"].replace(
            "child.parent = destination", "child.parent = dashcastBenchHiddenHost")))
    run("invalid-owned-declaration-negative", None,
        mutation=lambda f: f.update(DECLARATIONS=f["DECLARATIONS"] + "\nunavailableAddedProperty: true"),
        expected_error="unavailableAddedProperty")
    run("invalid-exact-loader-addition-negative", None,
        mutation=lambda f: f.update(ADAS_ACTIVE="unavailableAddedProperty: true"),
        expected_error="unavailableAddedProperty")

    report = {
        "qt_runtime": qVersion(), "backend": os.environ["QT_QUICK_BACKEND"],
        "platform": os.environ["QT_QPA_PLATFORM"], "render_loop": os.environ["QSG_RENDER_LOOP"],
        "resolution": [1920, 720], "candidate_sha256": manifest["carrier_sha256"],
        "resource_readback_count": len(captured), "cases": results, "qt_messages": messages,
        "payload_fixture_sha256": rcc.sha(PAYLOAD), "application_fixture_sha256": rcc.sha(FIXTURE),
        "factory_root_sha256": rcc.sha(originals[perf.LARGE_ROOT]),
        "oem_code_executed": False, "vehicle_commands": False,
        "native_contract_semantics": "UNKNOWN", "vehicle_performance": "UNKNOWN",
        "native_animation_cleanup": "UNKNOWN",
        "counter_scope": "Lifecycle counters begin at Component.onCompleted. Live/maximum and destruction counts cover completed fixture payloads and Presentation consumers only; partially incubated or cancelled-before-completion objects are not counted.",
        "counter_semantics": {
            "payloadCreated": "fixture payloads that reached Component.onCompleted",
            "payloadDestroyed": "destruction callbacks of previously completed fixture payloads",
            "payloadLive": "completed fixture payloads whose destruction callback has not run",
            "payloadMaximum": "maximum concurrent completed fixture payloads",
            "created": "Presentation stand-ins that reached Component.onCompleted",
            "live": "completed Presentation stand-ins whose destruction callback has not run",
            "maximum": "maximum concurrent completed Presentation stand-ins",
        },
        "limits": "Executed final owned declarations and existing Loader only, with synthetic Qt payload/consumer. Lifecycle counts exclude partial incubation and do not bound all in-flight allocations. Timer/handler counts are fixture work, not actual ADAS CPU, RAM, GPU, VRAM, heat, external-layer release or hardware restoration. Center native children and their work remain retained. No benchmark speedup or animation savings claimed.",
    }
    (args.output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    print("PASS:", len(results), "Qt-only final-declaration lifecycle/work-count cases; vehicle performance UNKNOWN")


if __name__ == "__main__":
    main()
