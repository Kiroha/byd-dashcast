Loader {
    id: presentationLoaderId
    objectName: "dashcastPresentationHost"
    z: CustomStyle.presentationZ
    asynchronous: true
    property bool dashcastChildFailed: false
    readonly property bool dashcastCandidateRequested: DataSource.naviType === 0x04 && DataSource.screenSize === 0x02 && rootItemId.width === 1920 && rootItemId.height === 720 && DataSource.powerGear === 0x03 && DataSource.playAnim === true
    readonly property bool dashcastCandidateSelected: DataSource.dilinkName === 0x02 && dashcastCandidateRequested && !dashcastChildFailed
    source: DataSource.dilinkName !== 0x02 ? "" : (dashcastCandidateSelected ? "qrc:/dashcast/isolated/FullApp.qml" : "qrc:/qml10_25/alwaysDisplay/Presentation.qml")

    function dashcastCheckChild() {
        if (!dashcastCandidateSelected)
            return
        if (item === null) {
            if (status === Loader.Ready)
                dashcastChildFailed = true
            return
        }
        var valid = false
        try {
            valid = item.contractVersion === 1 && item.componentConstructed === true && item.androidFrameState === "UNVERIFIED" &&
                    (item.presentationStatus === Loader.Loading || item.presentationStatus === Loader.Ready) &&
                    item.presentationStatusChanged !== undefined && typeof item.presentationStatusChanged.connect === "function" &&
                    item.componentConstructedChanged !== undefined && typeof item.componentConstructedChanged.connect === "function"
        } catch (error) {
            valid = false
        }
        if (!valid)
            dashcastChildFailed = true
    }
    onStatusChanged: {
        // Qt can clear source when incubation fails; retain the selected branch separately.
        if (dashcastCandidateSelected &&
                (status === Loader.Error || (status === Loader.Ready && item === null)))
            dashcastChildFailed = true
    }
    onLoaded: {
        dashcastCheckChild()
        if (dashcastCandidateSelected && item !== null) {
            try {
                item.presentationStatusChanged.connect(dashcastCheckChild)
                item.componentConstructedChanged.connect(dashcastCheckChild)
            } catch (error) {
                dashcastChildFailed = true
            }
        }
    }
}
