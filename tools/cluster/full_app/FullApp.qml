import QtQuick 2.15

// The existing presentation branch owns the only native surface consumer.
Item {
    width: 1920
    height: 720
    readonly property int contractVersion: 1
    property bool componentConstructed: false
    readonly property int presentationStatus: stockPresentation.status
    readonly property bool presentationCreated: stockPresentation.status === Loader.Ready && stockPresentation.item !== null
    readonly property string androidFrameState: "UNVERIFIED"

    Loader {
        id: stockPresentation
        objectName: "stockPresentation"
        anchors.fill: parent
        source: "qrc:/qml10_25/alwaysDisplay/Presentation.qml"
        asynchronous: true
    }
    Component.onCompleted: componentConstructed = true
}
