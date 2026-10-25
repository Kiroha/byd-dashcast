Loader {
    id: presentationLoaderId
    objectName: "dashcastMinimalPresentationHost"
    anchors.fill: parent
    z: CustomStyle.presentationZ
    asynchronous: true
    // This is a rendering context, never a parked/movement admission predicate.
    source: DataSource.dilinkName === 0x02 && DataSource.screenSize === 0x02 ? "qrc:/offline/minimal-bench/MinimalApp.qml" : ""
}
