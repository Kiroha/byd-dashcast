// PC bench input only: no Android frame, parked or movement admission.
property bool dashcastBenchProjectionActive: false
readonly property bool dashcastBenchSuspendVisuals: dashcastBenchProjectionActive && DataSource.dilinkName === 0x02 && DataSource.screenSize === 0x02
property bool dashcastBenchConstructed: false
property bool dashcastBenchReconcileBusy: false
property bool dashcastBenchReconcileError: false
property string dashcastBenchReconcileErrorReason: ""
property int dashcastBenchReconcilePasses: 0

function dashcastReconcileVisuals() {
    if (!dashcastBenchConstructed || dashcastBenchReconcileBusy)
        return
    if (!dashcastBenchHiddenHost || !dashcastBenchCanvas || !presentationLoaderId) {
        dashcastBenchReconcileError = true
        dashcastBenchReconcileErrorReason = "bench hosts unavailable"
        return
    }
    dashcastBenchReconcileBusy = true
    try {
        var suspend = dashcastBenchSuspendVisuals
        var origin = suspend ? rootItemId : dashcastBenchHiddenHost
        var destination = suspend ? dashcastBenchHiddenHost : rootItemId
        var pending = []
        var i
        for (i = 0; i < origin.children.length; ++i)
            pending.push(origin.children[i])
        for (i = 0; i < pending.length; ++i) {
            var child = pending[i]
            if (child !== dashcastBenchHiddenHost && child !== dashcastBenchCanvas &&
                    child !== presentationLoaderId && child.parent === origin)
                child.parent = destination
        }
        dashcastBenchReconcilePasses += 1
        // One pass; expose a failure without a retry timer or event loop.
        for (i = 0; i < origin.children.length; ++i) {
            var remaining = origin.children[i]
            if (remaining !== dashcastBenchHiddenHost && remaining !== dashcastBenchCanvas &&
                    remaining !== presentationLoaderId) {
                dashcastBenchReconcileError = true
                dashcastBenchReconcileErrorReason = "visual parent transition incomplete"
            }
        }
    } catch (error) {
        dashcastBenchReconcileError = true
        dashcastBenchReconcileErrorReason = String(error)
    } finally {
        dashcastBenchReconcileBusy = false
    }
}

onDashcastBenchSuspendVisualsChanged: dashcastReconcileVisuals()
onChildrenChanged: dashcastReconcileVisuals()
Component.onDestruction: dashcastBenchConstructed = false

Item {
    id: dashcastBenchHiddenHost
    objectName: "dashcastBenchHiddenHost"
    width: rootItemId.width
    height: rootItemId.height
    visible: false
}
Rectangle {
    id: dashcastBenchCanvas
    objectName: "dashcastBenchCanvas"
    anchors.fill: parent
    z: CustomStyle.presentationZ - 1
    visible: rootItemId.dashcastBenchSuspendVisuals
    color: "#000000"
}
