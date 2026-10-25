import QtQuick 2.15

// Offline bench only. Loader readiness never establishes Android pixels.
Rectangle {
    width: 1920
    height: 720
    color: "#000000"
    clip: true
    readonly property int contractVersion: 1
    property bool componentConstructed: false
    readonly property int presentationStatus: stockPresentation.status
    readonly property string androidFrameState: "UNKNOWN"

    Loader {
        id: stockPresentation
        objectName: "stockPresentation"
        anchors.fill: parent
        source: "qrc:/qml10_25/alwaysDisplay/Presentation.qml"
        asynchronous: true
    }
    Component.onCompleted: componentConstructed = true
}
