property bool dashcastBenchConstructed: false
property bool dashcastContainBusy: false
property bool dashcastContainError: false
property string dashcastContainErrorReason: ""
property int dashcastContainPasses: 0

function dashcastContainOemChildren() {
    if (!dashcastBenchConstructed || dashcastContainBusy)
        return
    if (!dashcastOemHiddenHost || !dashcastBenchCanvas || !presentationLoaderId) {
        dashcastContainError = true
        dashcastContainErrorReason = "containment hosts unavailable"
        return
    }
    dashcastContainBusy = true
    try {
        // Snapshot before changing visual parents: children changes synchronously.
        var pending = []
        var i
        for (i = 0; i < rootItemId.children.length; ++i)
            pending.push(rootItemId.children[i])
        for (i = 0; i < pending.length; ++i) {
            var child = pending[i]
            if (child !== dashcastOemHiddenHost && child !== dashcastBenchCanvas &&
                    child !== presentationLoaderId && child.parent === rootItemId)
                child.parent = dashcastOemHiddenHost
        }
        dashcastContainPasses += 1
        // One bounded pass only; report failure rather than scheduling a retry.
        for (i = 0; i < rootItemId.children.length; ++i) {
            var remaining = rootItemId.children[i]
            if (remaining !== dashcastOemHiddenHost && remaining !== dashcastBenchCanvas &&
                    remaining !== presentationLoaderId) {
                dashcastContainError = true
                dashcastContainErrorReason = "direct visual child escaped containment"
            }
        }
    } catch (error) {
        dashcastContainError = true
        dashcastContainErrorReason = String(error)
    } finally {
        dashcastContainBusy = false
    }
}

onChildrenChanged: dashcastContainOemChildren()
Component.onDestruction: dashcastBenchConstructed = false

Item {
    id: dashcastOemHiddenHost
    objectName: "dashcastOemHiddenHost"
    width: rootItemId.width
    height: rootItemId.height
    visible: false
}
Rectangle {
    id: dashcastBenchCanvas
    objectName: "dashcastBenchCanvas"
    anchors.fill: parent
    z: CustomStyle.presentationZ - 1
    color: "#000000"
}
