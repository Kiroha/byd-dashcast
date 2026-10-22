import QtQuick 2.15
import QtQuick.Window 2.14
import QtQuick.Controls 2.5
import com.byd.DataSource 1.0
import com.byd.WarningLight 1.0
import QtQuick3D 1.15
import QtGraphicalEffects 1.0
import com.byd.AndroidNativeWindowItem 1.0
import "./alwaysDisplay"
import "qrc:/common/singleton"
import "./adas3d"
Item {
    id:rootItemId

    // DASHCAST_FULL_SURFACE_TEST_BEGIN
    Rectangle {
        id: dashCastFullSurfaceTestId
        anchors.fill: parent
        z: CustomStyle.bgZ
        visible: DataSource.powerGear === DataSource.POWER_GEAR_ON && DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN && DataSource.dayNightMode === 0x02
        color: "#061018"
        border.color: "#00cddd"; border.width: 6
        Rectangle { x: 0; y: 0; width: 120; height: 12; color: "#00cddd" }
        Rectangle { x: parent.width - width; y: 0; width: 120; height: 12; color: "#00cddd" }
        Rectangle { x: 0; y: parent.height - height; width: 120; height: 12; color: "#00cddd" }
        Rectangle { x: parent.width - width; y: parent.height - height; width: 120; height: 12; color: "#00cddd" }
        Text { x: 24; y: 318; text: "DASHCAST TEST"; color: "#00cddd"; font.pixelSize: 28 }
    }
    // DASHCAST_FULL_SURFACE_TEST_END
    width: 1920
    height: 720
    scale: DataSource.screenSize === 0x01 ? 2/3:1
    x:DataSource.screenSize === 0x01 ? -320 : 0
    y:DataSource.screenSize === 0x01 ? -120 : 0


    Component.onCompleted: {
        if(DataSource.playAnim  === false){

        }else{
            DataSource.sendPluginMsgInt(DataSource.RECV_MSG_ID_POWERON_ANIM,DataSource.POWER_ON_ANIM_CRASH)
            console.log("DataSource.playAnim = true, send crash msg")
        }
        updateBgImageSource()
        var component
        if(rootItemId.powerOnAnimationUi === null && DataSource.playAnim  === false){
            component = Qt.createComponent("qrc:/qml10_25/PowerOnAnimation.qml")
            rootItemId.powerOnAnimationUi = component.createObject(rootItemId,{"width": rootItemId.width,"height": rootItemId.height,"z":CustomStyle.powerOnAnimationZ})
            rootItemId.powerOnAnimationUi.powerOnAnimationFinish.connect(rootItemId.onPowerOnAnimationFinish)
        }
        updateDashBoardUi();
        DataSource.sendPluginMsgId(DataSource.RECV_MSG_ID_QML_LOAD_COMPLETE)
        ecoSportModeImgId.lastMode = DataSource.ecoSportMode
        ecoSportModeImgId.lastModeAnim = DataSource.ecoSportSwitchAnim
        updateEcoSportModeImage()
        switchTerrainModeAnim()
        textInfoUpdate()
        textColorUpdate()
        updateCarDistanceItem()
        var curTimestamp = new Date().getTime()
        adasWindowBackground.visible = Qt.binding(function(){return DataSource.adasPlatform === 0x1?(DataSource.adasWindow === 0x2 && DataSource.powerGear === 3)
                                                                                                   :(DataSource.adasInterfaceDisplay === 0x02 && DataSource.powerGear === 3 && DataSource.adasType === 0) })
        adas2dNaviWindowBg.visible = Qt.binding(function(){return DataSource.adasPlatform === 0x1?(DataSource.naviType === 0x4 && DataSource.powerGear === 3 && DataSource.adasWindow !== 0)
                                                                                                 :(DataSource.naviType === 0x4 && DataSource.powerGear === 3 && DataSource.adasInterfaceDisplay !== 0 && DataSource.adasType === 0)})

        updateAdasContainer()
        console.log("[theme1 cluster.qml] onCompleted timestamp is ",curTimestamp)
    }

    property Item mediaInfoUi: null
    property Item navigationUi: null
    property Item phonePrompt:null
    property Item powerOnAnimationUi: null
    property Item approachAnimationUi: null
    property Item themeAnimationUi: null
    property Item powerDashBoardUi: null
    property Item speedDashBoardUi: null
    property Item adasCtrlUi: null
    property bool isInThemeSwithAnim: false
    property bool isInEcoSportSwithAnim: false
    property bool needUpdateEcoSportSwichAnim: false
    property bool isOnSwitchTerrainModeAnim: false
    property int mPreTerrainMode: 0
    property int mPreNaviType: 0
    property bool adasTextTimeout: false
    function onPowerOnAnimationFinish(){
        if( DataSource.powerGear === 3){
            if(rootItemId.approachAnimationUi === null){
                var component = Qt.createComponent("qrc:/qml10_25/ApproachAnimation.qml")
                rootItemId.approachAnimationUi = component.createObject(rootItemId,{"width": rootItemId.width,"height": rootItemId.height,"z":CustomStyle.powerOnAnimationZ-1})
                rootItemId.approachAnimationUi.approachAnimationFinished.connect(rootItemId.onApproachAnimationFinished)
                rootItemId.approachAnimationUi.visible = true
                rootItemId.approachAnimationUi.startApproachAnim()
            }
            if(rootItemId.powerOnAnimationUi !== null){
                rootItemId.powerOnAnimationUi.destroy()
                rootItemId.powerOnAnimationUi = null
            }
        }
    }

    function onApproachAnimationFinished(){
        DataSource.powerOnAnimRccRegister(false)
        DataSource.approachAnimRccRegister(false)
        if(rootItemId.approachAnimationUi !== null){
            rootItemId.approachAnimationUi.destroy()
            rootItemId.approachAnimationUi = null
        }
        alwyasDisplayId.visible = true
        warningLayoutId.visible = true
        centerContainerId.visible = true
        DataSource.playAnim = true
        updateBgImageSource()
        DataSource.sendPluginMsgInt(DataSource.RECV_MSG_ID_POWERON_ANIM,DataSource.POWER_ON_ANIM_COMPLETE)
        switchTerrainModeAnim()
    }

    function onThemeAnimationFinished(){
        DataSource.sendPluginMsgInt(DataSource.RECV_MSG_ID_THEME_SWITCH_ANIM,DataSource.THEME_SWITCH_SWITCH_ANIM_COMPLETE)
    }

    function updateDashBoardUi(){
        var component
        if(rootItemId.powerDashBoardUi != null){
            rootItemId.powerDashBoardUi.destroy()
            rootItemId.powerDashBoardUi = null
        }
        if(rootItemId.speedDashBoardUi != null){
            rootItemId.speedDashBoardUi.destroy()
            rootItemId.speedDashBoardUi = null
        }
        if(DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN){
            return
        }

        if(DataSource.themeIndex === 0x01){

            component = Qt.createComponent("qrc:/qml10_25/alwaysDisplay/PowerDashBoardClassic.qml")
            rootItemId.powerDashBoardUi = component.createObject(alwyasDisplayId,{"x": 0,"y": 62})
            component = Qt.createComponent("qrc:/qml10_25/alwaysDisplay/SpeedDashBoardClassic.qml")
            rootItemId.speedDashBoardUi = component.createObject(alwyasDisplayId,{"x": 1106,"y": 62})
        }else{
            component = Qt.createComponent("qrc:/qml10_25/alwaysDisplay/PowerDashBoardSimple.qml")
            rootItemId.powerDashBoardUi = component.createObject(alwyasDisplayId,{"x": 0,"y": 0})
            component = Qt.createComponent("qrc:/qml10_25/alwaysDisplay/SpeedDashBoardSimple.qml")
            rootItemId.speedDashBoardUi = component.createObject(alwyasDisplayId,{"x": 0,"y": 0})
        }
    }
    function updateBgImageSource(){

        if(DataSource.powerGear === DataSource.POWER_GEAR_OFF || DataSource.powerGear === DataSource.POWER_GEAR_ACC ){
            //OFF档背景图使用开机动画第一帧，如果用bg.png，开机的时候先从bg.png切换到开机动画，看上去会闪一下
            bgImageId.source = "qrc:/images10_25/poweron/IMG_000.ktx"
            console.log("updateBgImageSource1 bgImageId set to ",bgImageId.source)
        }else if(DataSource.powerGear === DataSource.POWER_GEAR_ON){
            if(DataSource.naviType === DataSource.NAVI_TYPE_SMALL_SCREEN || DataSource.naviType===DataSource.NAVI_TYPE_FULL_SCREEN){
                if(DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN){
                    if(DataSource.dayNightMode === 0x02){
                        bgImageId.source = "qrc:/images10_25/display_always/night/navi_full_bg.png"
                    }else{
                        bgImageId.source = "qrc:/images10_25/display_always/day/navi_full_bg.png"
                    }
                }else if(DataSource.naviType === DataSource.NAVI_TYPE_SMALL_SCREEN){
                    if(DataSource.dayNightMode === 0x02){
                        bgImageId.source = "qrc:/images10_25/display_always/night/navi_small_bg.png"
                    }else{
                        bgImageId.source = "qrc:/images10_25/display_always/day/navi_small_bg.png"
                    }
                }
                console.log("updateBgImageSource2 bgImageId set to ",bgImageId.source)
            }else if(isInThemeSwithAnim){
                terrainImageId.source = ""
            }else{
        switch(DataSource.terrainMode){
        case 4:
            bgImageId.source =DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/snow_mode.png":"qrc:/images10_25/terrainMode/day/snow_mode.png"
            break
        case 5:
            bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/mud_mode.png":"qrc:/images10_25/terrainMode/day/mud_mode.png"
            break
        case 6:
            bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/sand_mode.png":"qrc:/images10_25/terrainMode/day/sand_mode.png"
            break
        case 7:
            bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/snow_mode.png":"qrc:/images10_25/terrainMode/day/snow_mode.png"
            break
        case 8:
            bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/mud_mode.png":"qrc:/images10_25/terrainMode/day/mud_mode.png"
            break
        case 9:
            bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/sand_mode.png":"qrc:/images10_25/terrainMode/day/sand_mode.png"
            break
        case 0x0A:
        case 0x0B:
            bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/mountain_mode.png":"qrc:/images10_25/terrainMode/day/mountain_mode.png"
            break
        default:
            bgImageId.source = Qt.binding(function(){ return DataSource.dayNightMode === 0x02?"qrc:/images10_25/display_always/night/bg.png":"qrc:/images10_25/display_always/day/bg.png"})
            break
        }
                console.log("updateBgImageSource3 bgImageId set to ",bgImageId.source)
            }

        }else{
            bgImageId.source = "qrc:/images10_25/poweron/IMG_000.ktx"
            console.log("updateBgImageSource4 bgImageId set to ",bgImageId.source)
        }
    }

    function switchTerrainModeAnim(){
        if(DataSource.naviType === DataSource.NAVI_TYPE_SMALL_SCREEN || DataSource.naviType===DataSource.NAVI_TYPE_FULL_SCREEN){
            return
        }
        if(mPreTerrainMode === DataSource.terrainMode){
            return
        }
        if(isOnSwitchTerrainModeAnim === true){
            return
        }
        //更新背景
        bgImageId.source = Qt.binding(function(){ return DataSource.dayNightMode === 0x02?"qrc:/images10_25/display_always/night/bg.png":"qrc:/images10_25/display_always/day/bg.png"})
        console.log("switchTerrainModeAnim bgImageId set to ",bgImageId.source)
        terrainImageId.visible = true
        if(DataSource.terrainMode >=4 && DataSource.terrainMode <= 0x0B){
            if(mPreTerrainMode <=3 || mPreTerrainMode > 0x0B){
                updateTerrainState()
                terrainImageId.opacity = 0
                terrainSwitchShowAnim.start()
            }else{
                terrainSwitchAnim.start()
            }
            isOnSwitchTerrainModeAnim = true
        }else{
            if(terrainImageId.opacity >0){
                terrainSwitchHideAnim.start()
                isOnSwitchTerrainModeAnim = true
            }
        }
        mPreTerrainMode = DataSource.terrainMode
    }

    function updateTerrainState(){
        switch(DataSource.terrainMode){
        case 4:
            terrainImageId.state = "CLASSIC_SNOW"
            break
        case 5:
            terrainImageId.state = "CLASSIC_MUD"
            break
        case 6:
            terrainImageId.state = "CLASSIC_SAND"
            break
        case 7:
            terrainImageId.state = "TECHNOLOGY_SNOW"
            break
        case 8:
            terrainImageId.state = "TECHNOLOGY_MUD"
            break
        case 9:
            terrainImageId.state = "TECHNOLOGY_SAND"
            break
        case 0x0A:
            terrainImageId.state = "CLASSIC_MOUNTAIN"
            break
        case 0x0B:
            terrainImageId.state = "TECHNOLOGY_MOUNTAIN"
            break
        default:
            break
        }
    }

    Timer {
        id:dashBoardSelfCheckFinishTimerId
        interval: 2000
        running: false
        repeat: false
        onTriggered: {
            DataSource.sendPluginMsgInt(DataSource.RECV_MSG_ID_POWERON_ANIM,DataSource.POWER_ON_ANIM_SELFCHECK_COMPLETE)
        }
    }

    Connections{
        target: DataSource
        function onDayNightModeChanged(){
            textColorUpdate()
            switchTerrainModeAnim()
            updateBgImageSource()
        }

        function onPowerGearChanged(){
            updateAdasContainer()
            onNaviStateChanged()
            createMediaInfo()
            if( DataSource.powerGear === 3){
                if(rootItemId.powerOnAnimationUi !== null){
                    centerContainerId.visible = false
                }
                //因为alwyasDisplayId及centerContainerId加载后默认不可见，crash重启或ON档下断电再上电需要显式设置为可见
                if(DataSource.playAnim  === true){
                    alwyasDisplayId.visible = true
                    centerContainerId.visible = true
                }
            }else if(DataSource.powerGear === 0x01 || DataSource.powerGear === 0x02){
                mPreTerrainMode = 0
                DataSource.playAnim = false
                alwyasDisplayId.visible = false
                centerContainerId.visible = true
                warningLayoutId.visible = true
                var component
                if(rootItemId.powerOnAnimationUi === null){
                    component = Qt.createComponent("qrc:/qml10_25/PowerOnAnimation.qml")
                    rootItemId.powerOnAnimationUi = component.createObject(rootItemId,{"width": rootItemId.width,"height": rootItemId.height,"z":CustomStyle.powerOnAnimationZ})
                    rootItemId.powerOnAnimationUi.powerOnAnimationFinish.connect(rootItemId.onPowerOnAnimationFinish)
                }
                if(rootItemId.approachAnimationUi !== null){
                    rootItemId.approachAnimationUi.destroy()
                    rootItemId.approachAnimationUi = null
                }
                removeThemeComponent()
            }
            updateCarDistanceItem()
            textInfoUpdate()
            updateBgImageSource()
        }

        function onPluginMsgReceived(id,value){
            var themeComponent
            if(id === DataSource.SEND_MSG_ID_DASHBOARD_SELFCHECK){
                dashBoardSelfCheckFinishTimerId.start()
            }else if(id === DataSource.SEND_MSG_ID_THEME_SWITCH_REQUEST){
                if(value === DataSource.THEME_SWITCH_REQUEST_TO_PLAY_HIDE_ANIM){
                    isInThemeSwithAnim = true
                    if(rootItemId.themeAnimationUi === null){
                        themeComponent = Qt.createComponent("qrc:/qml10_25/ThemeChangeAnimation.qml");
                        rootItemId.themeAnimationUi = themeComponent.createObject(rootItemId,{"width": rootItemId.width,"height": rootItemId.height,"z":CustomStyle.bgZ})
                        rootItemId.themeAnimationUi.themeAnimationFinish.connect(rootItemId.onThemeAnimationFinished)
                    }
                    themeSwithOpacityHideAnimId.start()
                }else if(value === DataSource.THEME_SWITCH_REQUEST_TO_PLAY_DISP_ANIM){
                    isInThemeSwithAnim = false
                    if(rootItemId.themeAnimationUi !== null){
                        rootItemId.themeAnimationUi.visible = false
                    }
                    removeThemeComponent()
                    rootItemId.powerDashBoardUi.visible = true
                    rootItemId.speedDashBoardUi.visible = true
                    bgImageId.visible = true
                    headLineId.visible = true
                    driveRangeInfoBgId.visible = true
                    themeSwithOpacityShowAnimId.start()
                }else if(value === DataSource.THEME_SWITCH_REQUEST_TO_PLAY_SWITCH_ANIM){
                    bgImageId.visible = false
                    headLineId.visible = false
                    driveRangeInfoBgId.visible = false
                    rootItemId.powerDashBoardUi.visible = false
                    rootItemId.speedDashBoardUi.visible = false
                }else if(value === DataSource.THEME_SWITCH_POWER_OFF_STATE){
                    themeSwithOpacityHideAnimId.stop()
                    themeSwithOpacityShowAnimId.stop()
                    isInThemeSwithAnim = false
                    bgImageId.visible = true
                    headLineId.visible = true
                    driveRangeInfoBgId.visible = true
                    rootItemId.powerDashBoardUi.visible = true
                    rootItemId.speedDashBoardUi.visible = true
                    driveRangeInfoId.opacity = 1
                    warningLayoutId.opacity = 1
                }
            }

        }
        function onThemeIndexChanged(){
            updateDashBoardUi();
            if(isInThemeSwithAnim){
                rootItemId.powerDashBoardUi.visible = false
                rootItemId.speedDashBoardUi.visible = false
            }
            updateBgImageSource()
        }

        function onNaviTypeChanged(){
            if(DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN){
                updateBgImageSource()
                updateDashBoardUi()
            }else if(DataSource.naviType === DataSource.NAVI_TYPE_SMALL_SCREEN){
                updateBgImageSource()
                if(mPreNaviType === DataSource.NAVI_TYPE_FULL_SCREEN){
                    updateDashBoardUi()
                }

            }else{
                if(DataSource.powerGear !== 3){
                    updateBgImageSource()
                }else{
                    if(mPreTerrainMode === DataSource.terrainMode){
                        updateBgImageSource()
                    }else{
                        switchTerrainModeAnim()
                    }
                }
                if(mPreNaviType === DataSource.NAVI_TYPE_FULL_SCREEN){
                    updateDashBoardUi()
                }
            }
            mPreNaviType = DataSource.naviType
        }

        function onMediaInfoNeedDisplayChanged(){
            createMediaInfo()
        }
        function onNaviStateChanged(){
            if( (DataSource.naviState === 0 || DataSource.naviState === 1)&&(DataSource.powerGear === 3) && (DataSource.dilinkName === 0x1)){
                if(rootItemId.navigationUi === null){
                    var component = Qt.createComponent("qrc:/qml10_25/alwaysDisplay/Navigation.qml")
                    rootItemId.navigationUi = component.createObject(rootItemId)
                }
            } else {
                if(rootItemId.navigationUi != null){
                    rootItemId.navigationUi.destroy()
                    rootItemId.navigationUi = null
                }
            }
        }

        function onTimeFormatChanged(){
            timeDisplayId.currentTime = currentDateTime()
        }

        function onEcoSportModeChanged(){
            //updateEcoSportModeImage()
        }

        function onEcoSportSwitchAnimChanged(){
            updateEcoSportModeImage()
        }

        function onTerrainModeChanged(){
            if(DataSource.playAnim === true){
                switchTerrainModeAnim()
            }
        }

        function onAccRemoteTextChanged(){
            adasTextTimeout = false
            textInfoUpdate()
        }

        function onAccTipTextShowTimeChanged(){
            textInfoUpdate()
        }
        function onAccTipTextColorChanged(){
            textColorUpdate()
        }
        function onTranslateLoadCompleteChanged(){
            textInfoUpdate()
        }
        function onSpeedUpTimerInterfaceDisplayChanged(){
            textInfoUpdate()
        }
        function onDischargeNeedDisplayChanged(){
            textInfoUpdate()
        }
        function onChargeNeedDisplayChanged(){
            textInfoUpdate()
        }
        function onCarDistanceLevelChanged(){
            updateCarDistanceItem()
        }
        function onAdasTypeChanged(){
            updateAdasContainer()
        }
        function onAdasInterfaceDisplayChanged()
        {
            updateAdasContainer()
        }

    }
    //connection end

    function updateCarDistanceItem()
    {
        adasCarDistanceTimer.stop()
        if(DataSource.carDistanceLevel !== 0 && DataSource.powerGear === 3){
            adasCarDistanceTimer.start()
            adasCarDistanceItemId.visible = true
        }else{
            adasCarDistanceItemId.visible = false
        }
    }

    function removeThemeComponent(){
        if(rootItemId.themeAnimationUi !== null){
            rootItemId.themeAnimationUi.destroy()
            rootItemId.themeAnimationUi = null
            console.log("remove theme component")
        }
    }

    function createMediaInfo(){
        if( (DataSource.mediaInfoNeedDisplay === DataSource.UI_DISPLAY) &&(DataSource.powerGear === 3))
        {
            if(rootItemId.mediaInfoUi === null){
                var component = Qt.createComponent("qrc:/qml10_25/alwaysDisplay/MediaInfo.qml")
                rootItemId.mediaInfoUi = component.createObject(rootItemId)
            }
        }else {
            if(rootItemId.mediaInfoUi != null){
                rootItemId.mediaInfoUi.destroy()
                rootItemId.mediaInfoUi = null
            }
        }
    }

    function currentDateTime(){
        if(DataSource.timeFormat === 2){
            return Qt.formatDateTime(new Date(), "hh:mm");
        }else if(DataSource.timeFormat === 1){
            var hour = Qt.formatDateTime(new Date(), "hh");
            if(hour < 1){
                if(!timeDisplayId.isAm)
                    timeDisplayId.isAm = true
                return "12:" + Qt.formatDateTime(new Date(), "mm")
            } else if (hour < 12){
                if(!timeDisplayId.isAm)
                    timeDisplayId.isAm = true
                return Qt.formatDateTime(new Date(), "h:mm");
            } else if(hour < 13) {
                if(timeDisplayId.isAm)
                    timeDisplayId.isAm = false
                return Qt.formatDateTime(new Date(), "hh:mm");
            } else {
                if(timeDisplayId.isAm)
                    timeDisplayId.isAm = false
                return (hour-12)+ ":" + Qt.formatDateTime(new Date(), "mm")
            }
        }
    }

    function updateEcoSportModeImage(){
        var needAnimation = false
        if(isInEcoSportSwithAnim){
            needUpdateEcoSportSwichAnim = true
            console.log("is in eco sport switch anim")
            return
        }
        if(DataSource.ecoSportMode === ecoSportModeImgId.lastMode&&
                DataSource.ecoSportSwitchAnim === ecoSportModeImgId.lastModeAnim){
            return
        }
        /*if(DataSource.ecoSportMode === 0)
        {
            ecoSportModeImgId.visible = false
        }else{
            needAnimation = true
        }*/
        switch(DataSource.ecoSportSwitchAnim){
        case 1:
            ecoSportModeImgId.visible = true
            ecoSportModeImgId.source= DataSource.ecoSportMode === 1 ?"qrc:/images10_25/display_always/eco_sport_anim/eco.png":"qrc:/images10_25/display_always/eco_sport_anim/sport.png"
            ecoSportModeImgShowAnimId.start()
            break
        case 2:
            //if(needAnimation){
                ecoSportModeImgHideAnimId.start()
            //}
            break
        case 3:
            isInEcoSportSwithAnim = true
            ecoToSportTimerId.start()
            break
        case 4:
            isInEcoSportSwithAnim = true
            sportToEcoTimerId.start()
            break
        default:
            console.log("eco sport switch anim is not need")
        }
        ecoSportModeImgId.lastMode = DataSource.ecoSportMode
        ecoSportModeImgId.lastModeAnim =DataSource.ecoSportSwitchAnim
    }
    function reUpdateEcoSportAnimation()
    {
        if(needUpdateEcoSportSwichAnim){
            updateEcoSportModeImage()
            needUpdateEcoSportSwichAnim = false
        }
    }
    Timer{
        id:sportToEcoTimerId
        interval: 35
        repeat: true
        onTriggered: {
            ecoSportModeImgId.source = "qrc:/images10_25/display_always/eco_sport_anim/sport.png"
            sportToEcoTimerId.stop()
            isInEcoSportSwithAnim = false
            reUpdateEcoSportAnimation()
            ecoSportModeImgId.source = "qrc:/images10_25/display_always/eco_sport_anim/eco.png"
        }
    }
    Timer{
        id:ecoToSportTimerId
        interval: 35
        repeat: true
        onTriggered: {
            ecoSportModeImgId.source = "qrc:/images10_25/display_always/eco_sport_anim/eco.png"
            ecoToSportTimerId.stop()
            isInEcoSportSwithAnim = false
            reUpdateEcoSportAnimation()
            ecoSportModeImgId.source = "qrc:/images10_25/display_always/eco_sport_anim/sport.png"
        }
    }
    PropertyAnimation{
        id:ecoSportModeImgShowAnimId
        target: ecoSportModeImgId
        property: "opacity"
        from:0
        to:1
        easing.type: Easing.InOutQuad
        duration: 240
        onStarted: {
            isInEcoSportSwithAnim = true
        }
        onFinished: {
            isInEcoSportSwithAnim = false
            reUpdateEcoSportAnimation()
        }
    }
    PropertyAnimation{
        id:ecoSportModeImgHideAnimId
        target: ecoSportModeImgId
        property: "opacity"
        from:1
        to:0
        easing.type: Easing.InOutQuad
        duration: 240
        onStarted: {
            isInEcoSportSwithAnim = true
        }
        onFinished: {
            ecoSportModeImgId.visible = false
            isInEcoSportSwithAnim = false
            reUpdateEcoSportAnimation()
        }
    }

    //背景
    Item{
        id: bgImageContainerId
        opacity: DataSource.powerGear === DataSource.POWER_GEAR_ON && DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN && DataSource.dayNightMode === 0x02 ? 0 : 1
        //visible: DataSource.presentationTestMode === 0?true:false
        anchors.fill: parent
        z:CustomStyle.bgZ

        Image {
            id: bgImageId
            //z:CustomStyle.bgZ
            cache: false
            visible: true
        }

        Image {
            id: terrainImageId
            //z:CustomStyle.bgZ
            anchors.centerIn: parent
            cache: false
            visible: false
            states: [
                State {
                    name: "CLASSIC_SNOW"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/snow.png":"qrc:/images10_25/terrainMode/day/snow_mode.png"
                        }
                    }
                },
                State {
                    name: "CLASSIC_MUD"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/mud.png":"qrc:/images10_25/terrainMode/day/mud.png"}}

                },
                State {
                    name: "CLASSIC_SAND"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/sand.png":"qrc:/images10_25/terrainMode/day/sand.png"}}

                },
                State {
                    name: "TECHNOLOGY_SNOW"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/snow.png":"qrc:/images10_25/terrainMode/day/snow_mode.png"}}

                },
                State {
                    name: "TECHNOLOGY_MUD"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/mud.png":"qrc:/images10_25/terrainMode/day/mud.png"}}

                },
                State {
                    name: "TECHNOLOGY_SAND"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            DataSource.dayNightMode === 0x02?"qrc:/images10_25/terrainMode/night/sand.png":"qrc:/images10_25/terrainMode/day/sand.png"}}
                },
                State {
                    name: "CLASSIC_MOUNTAIN"
                    PropertyChanges {
                        target: terrainImageId;
                        source: {
                            y: 36
                            DataSource.dayNightMode === 0x02 ? "qrc:/images10_25/terrainMode/night/mountain.png" : "qrc:/images10_25/terrainMode/day/mountain.png"
                        }
                    }
                },
                State {
                    name: "TECHNOLOGY_MOUNTAIN"
                    PropertyChanges {
                        target: terrainImageId;
                        source: {
                            y: 0
                            DataSource.dayNightMode === 0x02 ? "qrc:/images10_25/terrainMode/night/mountain.png" : "qrc:/images10_25/terrainMode/day/mountain.png"
                        }
                    }
                }
            ]
        }
    }
    //ON档常显
    Item{
        id:alwyasDisplayId
        anchors.fill: parent
        visible:false //刚加载的时候不可见，报文查询后会更新是否可见
        //ECO/Sport Mode
        Image {
            property int lastMode : 0
            property int lastModeAnim: 0
            anchors.horizontalCenter: parent.horizontalCenter
            y:0
            id: ecoSportModeImgId
            visible: false
        }

        //城市
        Image {
            id: bgCityImageId
            x:640
            y:142
            cache: false
            visible: DataSource.terrainMode === 0 && (DataSource.naviType !== DataSource.NAVI_TYPE_SMALL_SCREEN && DataSource.naviType!==DataSource.NAVI_TYPE_FULL_SCREEN) ? true : false
            source: DataSource.dayNightMode === 0x02?"qrc:/images10_25/display_always/night/bg_city.png":"qrc:/images10_25/display_always/day/bg_city.png"
        }
        Image {
            id: headLineId
            opacity: DataSource.powerGear === DataSource.POWER_GEAR_ON && DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN && DataSource.dayNightMode === 0x02 ? 0 : 1
            y:DataSource.themeIndex === 0x01 ? 75:(DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN?75:-15)
            anchors.horizontalCenter: parent.horizontalCenter
            source:DataSource.themeIndex === 0x01 ? "qrc:/images10_25/display_always/headLine.png":(DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN?"qrc:/images10_25/display_always/headLine.png":"qrc:/images10_25/display_always/easy_headLine.png")
        }

        Item {
            id: headInfoId
            anchors.horizontalCenter: parent.horizontalCenter
            width: 1920
            height: 65

            //档位
            WarningLight{
                id: gearModeId
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.bottom: parent.bottom
                displayState:DataSource.gearMode > 0?DataSource.gearDisplayState:0

                Text {
                    id: gearModeImageId
                    font.pixelSize: 68
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: -21
                    states:[
                        State {
                            name: "P"
                            when:DataSource.gearMode === 1
                            PropertyChanges {
                                target: gearModeImageId
                                text: "P"
                            }
                        },
                        State {
                            name: "R"
                            when:DataSource.gearMode === 2
                            PropertyChanges {
                                target: gearModeImageId
                                text: "R"
                            }
                        },
                        State {
                            name: "N"
                            when:DataSource.gearMode === 3
                            PropertyChanges {
                                target: gearModeImageId
                                text: "N"
                            }
                        },
                        State {
                            name: "D"
                            when:DataSource.gearMode === 4
                            PropertyChanges {
                                target: gearModeImageId
                                text: "D"
                            }
                        },
                        State {
                            name: "M"
                            when:DataSource.gearMode === 5
                            PropertyChanges {
                                target: gearModeImageId
                                text: "M"
                            }
                        },
                        State {
                            name: "S"
                            when:DataSource.gearMode === 6
                            PropertyChanges {
                                target: gearModeImageId
                                text: "S"
                            }
                        }
                    ]
                }

                Text {
                    id: realGearTextId
                    anchors.left: gearModeImageId.right
                    anchors.leftMargin: 1
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: -14
                    font.pixelSize: 46
                    text: DataSource.realGear > 0 ? DataSource.realGear : ""
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                }
            }
            //标准回馈
            WarningLight{
                id:feedbackLight
                width: 205
                height: 28
                anchors.right: hevWarningLightId.right
                anchors.bottom:hevWarningLightId.bottom
                anchors.bottomMargin: (DataSource.language === 0x01 || DataSource.language === 0x03) ? 0 : (DataSource.energyFeedBackLight === 1? 0 : 4)
                visible: ((DataSource.vehicleType === DataSource.CAR_TYPE_EV)&&(DataSource.energyFeedBackLight === 1||DataSource.energyFeedBackLight === 2))?true:false

                Text {
                    id: feedbackImg
                    text: DataSource.energyFeedBackLight === 1
                          ? (DataSource.language === 0x01?"标准回馈":DataSource.language === 0x09?"스탠다드":(DataSource.language === 0x03?"標準回饋":"Standard"))
                          : (DataSource.language === 0x01?"较大回馈":DataSource.language === 0x09?"하이":(DataSource.language === 0x03?"較大回饋":"High"))
                    font.pixelSize: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 41 : 36
                    color: "#17975f"
                    font.family: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? "HFMoBestSans" : CustomStyle.naviFontFamily
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.verticalCenterOffset: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 2 : -4
                    anchors.right: parent.right
                }
            }
            //ECO
            WarningLight{
                id: ecoWarningLightId
                x: 1029
                y: 38 - 4
                width: 95
                height: 28
                visible: DataSource.modeLight === 0x01?true:false

                Text {
                    id: ecoWarningLightImageId
                    text: "ECO"
                    font.pixelSize: 48
                    color: "#17975f"
                    //font.family: CustomStyle.plagiata
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.left : parent.left
                }
            }
            //SPORT
            WarningLight{
                id: sportWarningLightId
                width: 205
                height: 28
                anchors.left: ecoWarningLightId.left
                anchors.bottom:ecoWarningLightId.bottom
                visible: DataSource.modeLight === 0x02?true:false

                Text {
                    id: sportWarningLightImageId
                    text:DataSource.sportModeFlag !== 1?"SPORT":"POWER"
                    font.pixelSize: 48
                    color: "#17975f"
                    //font.family: CustomStyle.plagiata
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.left : parent.left
                }
            }
            //NORMAL
            WarningLight{
                id: normalWarningLightId
                width: 205
                height: 28
                anchors.left: ecoWarningLightId.left
                anchors.bottom:ecoWarningLightId.bottom
                visible: DataSource.modeLight === 0x03?true:false

                Text {
                    id: normalWarningLightImageId
                    text: "NORMAL"
                    font.pixelSize: 48
                    color: "#17975f"
                    //font.family: CustomStyle.plagiata
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.left : parent.left
                }
            }
            //EV
            WarningLight{
                id: evWarningLightId
                width: 60
                height: 28
                anchors.right: hevWarningLightId.right
                anchors.bottom:hevWarningLightId.bottom
                displayState: DataSource.evLight

                Text {
                    id: evWarningLightImageId
                    text: "EV"
                    font.pixelSize: 48
                    color: DataSource.evLightColor === 0x04?"#17975f":"#179be0"
                    //font.family: CustomStyle.plagiata
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.right: parent.right
                }
            }
            //HEV
            WarningLight{
                id: hevWarningLightId
                x: 799
                y: 38 - 4
                width: 91
                height: 28
                displayState: DataSource.hevLight

                Text {
                    id: hevWarningLightImageId
                    text: "HEV"
                    font.pixelSize: 48
                    color: "#17975f"
                    //font.family: CustomStyle.plagiata
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.right: parent.right
                }
            }
            //GRASS
            WarningLight{
                id: grassWarningLightId

                width: 205
                height: 28
                anchors.left: ecoWarningLightId.left
                anchors.bottom:ecoWarningLightId.bottom
                visible: DataSource.modeLight === 0x04?true:false
                Image{
                    id: grassWarningLightImageId
                    anchors.bottom:parent.bottom
                    anchors.bottomMargin: 1
                    source: "qrc:/images10_25/warninglight/grassLight.png"
                }
            }
            //MUDDY
            WarningLight{
                id: muddyWarningLightId
                width: 205
                height: 28
                anchors.left: ecoWarningLightId.left
                anchors.bottom:ecoWarningLightId.bottom

                visible: DataSource.modeLight === 0x05?true:false
                Image{
                    id: muddyWarningLightImageId
                    anchors.bottom:parent.bottom
                    anchors.bottomMargin: 1
                    source: "qrc:/images10_25/warninglight/muddyLight.png"
                }
            }
            //SANDY
            WarningLight{
                id: sandWarningLightId
                width: 205
                height: 28
                anchors.left: ecoWarningLightId.left
                anchors.bottom:ecoWarningLightId.bottom

                visible: DataSource.modeLight === 0x06?true:false
                Image{
                    id: sandWarningLightImageId
                    anchors.bottom:parent.bottom
                    anchors.bottomMargin: 1
                    source: "qrc:/images10_25/warninglight/sandLight.png"
                }
            }
            //MOUNTAIN
            WarningLight {
                id: mountainWarningLightId
                width: 205
                height: 28
                anchors.left: ecoWarningLightId.left
                anchors.bottom: ecoWarningLightId.bottom

                visible: DataSource.modeLight === 0x07 ? true : false
                Image {
                    id: mountainWarningLightImageId
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 1
                    source: "qrc:/images10_25/warninglight/mountainLight.png"
                }
            }

            //WADE
            WarningLight{
                id: wadeWarningLightId

                width: 205
                height: 28
                anchors.left: ecoWarningLightId.left
                anchors.bottom:ecoWarningLightId.bottom
                visible: DataSource.modeLight === 0xC?true:false
                Image{
                    id: wadeWarningLightImageId
                    anchors.bottom:parent.bottom
                    anchors.bottomMargin: 1
                    source: "qrc:/images10_25/warninglight/wadeLight.png"
                }
            }

            //时间显示
            Item{
                id: timeDisplayId
                height: parent.height
                visible: DataSource.timeDisplay === 0x01 ? true : false
                property bool isAm: true
                property string currentTime: ""

                Text{
                    x: 528
                    y: 38 - 3
                    width: 102
                    height: 28
                    text: timeDisplayId.currentTime
                    horizontalAlignment: Text.AlignLeft
                    verticalAlignment: Text.AlignVCenter
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    font.pixelSize: 45
                    visible: DataSource.timeFormat === 2 ? true: false
                }

                Text{
                    anchors.left: timeFormatId.right
                    anchors.leftMargin: 3
                    anchors.bottom: timeFormatId.bottom
                    anchors.bottomMargin: -12
                    text: timeDisplayId.currentTime
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    font.pixelSize: 45
                    visible: timeFormatId.visible && (DataSource.language === 0x01 || DataSource.language === 0x03) ? true: false
                }

                Text{
                    anchors.right: timeFormatId.left
                    anchors.rightMargin: 8
                    anchors.bottom: timeFormatId.bottom
                    anchors.bottomMargin: -16
                    text: timeDisplayId.currentTime
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    font.pixelSize: 45
                    visible: timeFormatId.visible && (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? true: false
                }

                Text{
                    id:timeFormatId
                    x: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 630  : 533
                    y: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 35 : 39
                    width: 62
                    height: 28
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                    color: DataSource.dayNightMode === 0x02 ?"#bfffffff":"#bf333333"
                    text: timeDisplayId.isAm ? ((DataSource.language === 0x01 || DataSource.language === 0x03) ? "上午" : "AM") :
                                               ((DataSource.language === 0x01 || DataSource.language === 0x03) ? "下午" : "PM")
                    font.pixelSize: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 44 : 30
                    visible: DataSource.timeFormat === 1 ? true: false
                }

                Timer{
                    id:timerId
                    interval: 1000
                    repeat: true
                    running: true
                    onTriggered: {
                        timeDisplayId.currentTime = currentDateTime()
                    }
                }

                Component.onCompleted: {
                    timeDisplayId.currentTime = currentDateTime()
                }
            }

            Rectangle {
                width: parent.width
                height: parent.height
                visible: DataSource.themeIndex === 0x01 || DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? true : false
                color: "transparent"

                //方位
                Image{
                    id: compassId
                    x:1499
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 0//DataSource.language !== 0x01 ? -5 : -5
                    visible: directionTextId.visible
                    source: DataSource.dayNightMode === 0x02 ?"qrc:/images10_25/display_always/night/compass.png":"qrc:/images10_25/display_always/day/compass.png"
                }
                Text{
                    id:directionTextId
                    visible: DataSource.direction !== 0 ? 1 : 0
                    anchors.left: compassId.right
                    anchors.leftMargin: 5
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? (-11 - 7 + 5) : (-3- 8 + 5)
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    font.pixelSize: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 46 : 30
                    text: "SE"
                    states:[
                        State {
                            name: "N"
                            when:DataSource.direction === 1
                            PropertyChanges {
                                target: directionTextId
                                text:TranslateStr.alwyasDisplay_N_Str
                            }
                        },
                        State {
                            name: "NE"
                            when:DataSource.direction === 2
                            PropertyChanges {
                                target: directionTextId
                                text: TranslateStr.alwyasDisplay_NE_Str
                            }
                        },
                        State {
                            name: "E"
                            when:DataSource.direction === 3
                            PropertyChanges {
                                target: directionTextId
                                text: TranslateStr.alwyasDisplay_E_Str
                            }
                        },
                        State {
                            name: "SE"
                            when:DataSource.direction === 4
                            PropertyChanges {
                                target: directionTextId
                                text: TranslateStr.alwyasDisplay_SE_Str
                            }
                        },
                        State {
                            name: "S"
                            when:DataSource.direction === 5
                            PropertyChanges {
                                target: directionTextId
                                text: TranslateStr.alwyasDisplay_S_Str
                            }
                        },
                        State {
                            name: "SW"
                            when:DataSource.direction === 6
                            PropertyChanges {
                                target: directionTextId
                                text: TranslateStr.alwyasDisplay_SW_Str
                            }
                        },
                        State {
                            name: "W"
                            when:DataSource.direction === 7
                            PropertyChanges {
                                target: directionTextId
                                text: TranslateStr.alwyasDisplay_W_Str
                            }
                        },
                        State {
                            name: "NW"
                            when:DataSource.direction === 8
                            PropertyChanges {
                                target: directionTextId
                                text: TranslateStr.alwyasDisplay_NW_Str
                            }
                        }
                    ]
                }
                //温度
                Item{
                    id: outsideTempId
                    visible: DataSource.outsideTempDisplay !== 0 ? 1 : 0

                    Text {
                        id: tempValueId

                        x: 1338
                        y: 32 + 4 - 5
                        width: 62
                        height: 34

                        color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                        font.pixelSize: 44
                        text: DataSource.outsideTemp

                        horizontalAlignment: Text.AlignRight
                        verticalAlignment: Text.AlignVCenter
                    }

                    Text {
                        id: tempUnitId

                        anchors.left: tempValueId.right
                        anchors.leftMargin: 15

                        anchors.bottom: tempValueId.bottom
                        anchors.bottomMargin: -6

                        color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                        font.pixelSize: 28
                        y:0
                        text: DataSource.outsideTempUnit === 0 ? "℃":"℉"
                    }
                }
            }
        }

        Rectangle {
            width: parent.width
            height: parent.height
            visible: DataSource.themeIndex === 0x01 || DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? false : true
            color: "transparent"
            //方位
            Image{
                id: compassEasyId
                x:288
                anchors.bottom: parent.bottom
                anchors.bottomMargin: 58
                visible: directionTextEasyId.visible
                source: DataSource.dayNightMode === 0x02 ?"qrc:/images10_25/display_always/night/compass.png":"qrc:/images10_25/display_always/day/compass.png"
            }
            Text{
                id:directionTextEasyId
                visible: DataSource.direction !== 0 ? 1 : 0
                anchors.left: compassEasyId.right
                anchors.leftMargin: 5
                anchors.bottom: compassEasyId.bottom
                anchors.bottomMargin: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? -11-3 :  -3 - 3
                color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                font.pixelSize: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 46 : 30
                text: "SE"
                states:[
                    State {
                        name: "N"
                        when:DataSource.direction === 1
                        PropertyChanges {
                            target: directionTextEasyId
                            text: TranslateStr.alwyasDisplay_N_Str
                        }
                    },
                    State {
                        name: "NE"
                        when:DataSource.direction === 2
                        PropertyChanges {
                            target: directionTextEasyId
                            text: TranslateStr.alwyasDisplay_NE_Str
                        }
                    },
                    State {
                        name: "E"
                        when:DataSource.direction === 3
                        PropertyChanges {
                            target: directionTextEasyId
                            text: TranslateStr.alwyasDisplay_E_Str
                        }
                    },
                    State {
                        name: "SE"
                        when:DataSource.direction === 4
                        PropertyChanges {
                            target: directionTextEasyId
                            text: TranslateStr.alwyasDisplay_SE_Str
                        }
                    },
                    State {
                        name: "S"
                        when:DataSource.direction === 5
                        PropertyChanges {
                            target: directionTextEasyId
                            text: TranslateStr.alwyasDisplay_S_Str
                        }
                    },
                    State {
                        name: "SW"
                        when:DataSource.direction === 6
                        PropertyChanges {
                            target: directionTextEasyId
                            text: TranslateStr.alwyasDisplay_SW_Str
                        }
                    },
                    State {
                        name: "W"
                        when:DataSource.direction === 7
                        PropertyChanges {
                            target: directionTextEasyId
                            text: TranslateStr.alwyasDisplay_W_Str
                        }
                    },
                    State {
                        name: "NW"
                        when:DataSource.direction === 8
                        PropertyChanges {
                            target: directionTextEasyId
                            text: TranslateStr.alwyasDisplay_NW_Str
                        }
                    }
                ]
            }
            //温度
            Item{
                id: outsideTempEasyId
                visible: DataSource.outsideTempDisplay !== 0 ? 1 : 0
                anchors.bottom: parent.bottom
                Text {
                    id: tempValueEasyId
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 48 - 4
                    anchors.right: tempUnitEasyId.left
                    anchors.rightMargin: 5
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    font.pixelSize: 44
                    text: DataSource.outsideTemp
                }

                Text {
                    id: tempUnitEasyId

                    x: 185
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 48


                    //anchors.bottom: tempValueEasyId.bottom
                    //anchors.bottomMargin: -6 - 3

                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    font.pixelSize: 28
                    text: DataSource.outsideTempUnit === 0 ? "℃":"℉"
                }
            }
        }

        //电量、油量表
        Item {
            id: driveRangeInfoContainerId
            y: 578
            width: 1920
            anchors.horizontalCenter: parent.horizontalCenter
            property bool mirror: (DataSource.language === 0x15)

            Image {
                id:driveRangeInfoBgId
                opacity: DataSource.powerGear === DataSource.POWER_GEAR_ON && DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN && DataSource.dayNightMode === 0x02 ? 0 : 1
                anchors.horizontalCenter: parent.horizontalCenter
                cache: false
                y:0
                source: {
                    if (DataSource.themeIndex === 0x01){
                        DataSource.dayNightMode === 0x02 ? "qrc:/images10_25/display_always/night/bg_soc_fuel.png" : "qrc:/images10_25/display_always/day/bg_soc_fuel.png"
                    } else{
                        DataSource.dayNightMode === 0x02 ? ((DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN)?"qrc:/images10_25/display_always/night/bg_soc_fuel.png":"qrc:/images10_25/display_always/night/bg_soc_fuel_easy.png") : ((DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN)?"qrc:/images10_25/display_always/day/bg_soc_fuel.png":"qrc:/images10_25/display_always/day/bg_soc_fuel_easy.png")

                    }
                }
            }

            Item {
                id: driveRangeInfoId
                anchors.fill: parent

                //hev电量、油量表


                Item {
                    id:hevDriveRangeInfoId
                    anchors.fill: parent
                    visible: DataSource.vehicleType !== DataSource.CAR_TYPE_EV ? true : false

                    //电续驶里程
                    Item{
                        width: socProgressId.width
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.horizontalCenterOffset: -200 - 48
                        Item{
                            anchors.fill: parent
                            LayoutMirroring.enabled: driveRangeInfoContainerId.mirror
                            LayoutMirroring.childrenInherit: true
                            Image {
                                id: batteryLightId
                                source: DataSource.dayNightMode === 0x02 ? "qrc:/images10_25/display_always/night/battery_icon.png":"qrc:/images10_25/display_always/day/battery_icon.png"
                                anchors.left: socProgressId.left
                                anchors.leftMargin: 31
                                y: 19
                                visible: (DataSource.lowPowerBatLight === 0 || DataSource.lowPowerBatLight === 2) ? true : false
                                cache: false
                            }

                            WarningLight{
                                id: batteryWarningLightId
                                anchors.left: socProgressId.left
                                anchors.leftMargin: 31
                                width: batteryWarningLightImageId.width
                                y: 19
                                displayState: DataSource.lowPowerBatLight
                                Image{
                                    id: batteryWarningLightImageId
                                    source: "qrc:/images10_25/warninglight/lowBat.png"
                                }
                            }

                            Text {
                                id: socVauleId
                                anchors.bottom: socUnitId.bottom
                                anchors.bottomMargin: -9 + 5
                                font.pixelSize: 54
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff" : "#333333"
                                visible: DataSource.batteryMileMode === 2 ? true : false
                                text: DataSource.powerBatterySoc === 0xFFE ? " ": DataSource.powerBatterySoc
                                anchors.right: socProgressId.right
                                anchors.rightMargin: DataSource.language === 0x18 ? 8 : 39
                            }

                            Text {
                                id: socUnitId
                                text: "%"
                                LayoutMirroring.enabled: (DataSource.language === 0x18) ? true : false
                                anchors.left: socVauleId.right
                                anchors.leftMargin: 6
                                y: 22 - 1
                                visible: socVauleId.visible
                                font.pixelSize: 36
                                opacity: 0.75
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                            }

                            Text {
                                id: socMileId
                                anchors.bottom: socMileUnitId.bottom
                                anchors.bottomMargin: -4
                                font.pixelSize: 54
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                                visible: DataSource.batteryMileMode === 1 ? true : false
                                text: DataSource.socMile
                                anchors.right: socMileUnitId.left
                                anchors.rightMargin: 5
                            }

                            Text {
                                id: socMileUnitId
                                y: 26 - 3
                                anchors.right: socProgressId.right
                                anchors.rightMargin: 8
                                visible: socMileId.visible
                                font.pixelSize: 36
                                opacity: 0.75
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                                text: DataSource.odoUnit !== 2 ? "km" : (CustomStyle.isSingular(DataSource.socMile) ? "mile" : "miles")
                            }

                            ProgressBar{
                                id: socProgressId
                                x: 0
                                y: 66
                                width: 360
                                height: 14
                                background: Image {
                                    cache: false
                                    source: DataSource.dayNightMode === 0x02 ?"qrc:/images10_25/display_always/night/bg_progress.png":"qrc:/images10_25/display_always/day/bg_progress.png"
                                }

                                contentItem: Item{
                                    Rectangle {
                                        id: socRect
                                        anchors.left: parent.left
                                        width: driveRangeInfoContainerId.mirror ? (1-socProgressId.visualPosition) * parent.width : socProgressId.visualPosition * parent.width
                                        height: parent.height
                                        radius: 6
                                    }
                                    LinearGradient{
                                        anchors.fill: socRect
                                        gradient: Gradient{
                                            GradientStop { position: 0.0; color: DataSource.batteryAlarm === 1 ? "#22c8c5":"#ff0000"}
                                            GradientStop { position: 1.0; color: DataSource.batteryAlarm === 1 ? "#46bf36":"#ff0000"}
                                        }
                                        source: socRect
                                        start: driveRangeInfoContainerId.mirror ? Qt.point(socRect.width,0): Qt.point(0,0)
                                        end: driveRangeInfoContainerId.mirror ?Qt.point(0,0) :  Qt.point(socRect.width,0)
                                    }
                                }
                                value: DataSource.powerBatterySoc !==0xFFE ? DataSource.powerBatterySoc/100 : 0
                            }

                            Image {
                                id: lowSoc
                                source: "qrc:/images10_25/display_always/low_soc_fuel.png"
                                anchors.bottom: socProgressId.bottom
                                anchors.left: socProgressId.left
                                cache: false
                            }
                        }


                    }

                    //燃油续驶里程
                    Item {
                        width: fuelProgressId.width
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.horizontalCenterOffset: 200 + 52
                        Item{
                            anchors.fill: parent
                            LayoutMirroring.enabled: driveRangeInfoContainerId.mirror
                            LayoutMirroring.childrenInherit: true
                            Image {
                                id: fuelLightId
                                source: DataSource.dayNightMode === 0x02 ?"qrc:/images10_25/display_always/night/fuel_icon.png":"qrc:/images10_25/display_always/day/fuel_icon.png"
                                anchors.left: fuelProgressId.left
                                anchors.leftMargin:23
                                y: 16
                                cache: false
                                visible: (DataSource.lowFuelLight === 0 || DataSource.lowFuelLight === 2) ? true : false
                            }
                            WarningLight{
                                id: fuelWarningLightId
                                anchors.left: fuelProgressId.left
                                anchors.leftMargin:23
                                width: fuelWarningLightImageId.width
                                y: 16
                                displayState: DataSource.lowFuelLight
                                Image{
                                    id: fuelWarningLightImageId
                                    source: "qrc:/images10_25/warninglight/lowFuelLight.png"
                                }
                            }

                            Text {
                                id: fuelMileId
                                anchors.bottom: fuelMileUnitId.bottom
                                anchors.bottomMargin: -5
                                font.pixelSize: 54
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                                visible: DataSource.fuelMile !== "0" ? true : false
                                text: DataSource.fuelMile
                                anchors.right: fuelMileUnitId.left
                                anchors.rightMargin: 5
                            }

                            Text {
                                id: fuelMileUnitId
                                y: 26 - 3
                                anchors.right: fuelProgressId.right
                                anchors.rightMargin: 9
                                visible: fuelMileId.visible
                                font.pixelSize: 36
                                opacity: 0.75
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                                text: DataSource.odoEvUnit !== 2 ? "km" : "miles"
                            }

                            ProgressBar{
                                id: fuelProgressId
                                x: 0
                                y: 66
                                width: 360
                                height: 14
                                background: Image {
                                    cache: false
                                    source: DataSource.dayNightMode === 0x02 ?"qrc:/images10_25/display_always/night/bg_progress.png":"qrc:/images10_25/display_always/day/bg_progress.png"
                                }

                                contentItem: Item{
                                    Rectangle {
                                        id: fuelRect
                                        anchors.left: parent.left
                                        width: driveRangeInfoContainerId.mirror ? (1-fuelProgressId.visualPosition) * parent.width : fuelProgressId.visualPosition * parent.width
                                        height: parent.height
                                        radius: 6
                                    }
                                    LinearGradient{
                                        anchors.fill: fuelRect
                                        gradient: Gradient{
                                            GradientStop { position: 0.0; color: DataSource.lowFuelAlarm === 1 ? "#22c8c5":"#ff0000"}
                                            GradientStop { position: 1.0; color: DataSource.lowFuelAlarm === 1 ? "#46bf36":"#ff0000"}
                                        }
                                        source: fuelRect
                                        start: driveRangeInfoContainerId.mirror ? Qt.point(fuelRect.width,0): Qt.point(0,0)
                                        end: driveRangeInfoContainerId.mirror ?Qt.point(0,0) :  Qt.point(fuelRect.width,0)
                                    }
                                }
                                value: DataSource.fuelPercent/100
                            }

                            Image {
                                id: lowFuel
                                source: "qrc:/images10_25/display_always/low_soc_fuel.png"
                                anchors.bottom: fuelProgressId.bottom
                                anchors.left: fuelProgressId.left
                                cache: false
                            }
                        }
                    }
                }



                //ev电量表
                Item {
                    anchors.fill: parent
                    LayoutMirroring.enabled: driveRangeInfoContainerId.mirror
                    LayoutMirroring.childrenInherit: true

                    //电续驶里程
                    Item{
                        id:evDriveRangeInfoId
                        anchors.fill: parent
                        visible: DataSource.vehicleType !== DataSource.CAR_TYPE_EV ? false : true
                        Image {
                            id: evBatteryLightId
                            source: DataSource.dayNightMode === 0x02 ? "qrc:/images10_25/display_always/night/battery_icon.png":"qrc:/images10_25/display_always/day/battery_icon.png"
                            anchors.left: parent.left
                            anchors.leftMargin: 623
                            y: 18
                            visible: (DataSource.lowPowerBatLight === 0 || DataSource.lowPowerBatLight === 2) ? true : false
                            cache: false
                        }
                        WarningLight{
                            id: evBatteryWarningLightId
                            anchors.left: parent.left
                            anchors.leftMargin: 623
                            width: evBatteryWarningLightImageId.width
                            y: 18
                            displayState: DataSource.lowPowerBatLight
                            Image{
                                id: evBatteryWarningLightImageId
                                source: "qrc:/images10_25/warninglight/lowBat.png"
                            }
                        }

                        Text {
                            id: evSocVauleId
                            anchors.left: evBatteryLightId.right
                            anchors.leftMargin: 38
                            anchors.bottom: evSocUnitId.bottom
                            anchors.bottomMargin: -8 + 4
                            font.pixelSize: 54
                            color: DataSource.dayNightMode === 0x02 ? "#ffffff" : "#333333"
                            text: DataSource.powerBatterySoc === 0xFFE ? " ": DataSource.powerBatterySoc
                            visible: DataSource.powerBatterySoc !== 101 ? true : false
                        }

                        Text {
                            id: evSocUnitId
                            LayoutMirroring.enabled: (DataSource.language === 0x18) ? true : false
                            text: "%"
                            anchors.left: evSocVauleId.right
                            anchors.rightMargin: 8
                            y: 22 - 2
                            visible: evSocVauleId.visible
                            font.pixelSize: 36
                            color: DataSource.dayNightMode === 0x02 ? "#66ffffff":"#66333333"
                        }

                        Text {
                            id: evSocMileId
                            anchors.bottom: evSocMileUnitId.bottom
                            anchors.bottomMargin: -3 - 2
                            font.pixelSize: 54
                            color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                            text: DataSource.socMile
                            anchors.right: evSocMileUnitId.left
                            anchors.rightMargin: 5
                            visible: DataSource.socMile !== "1000" ? true : false
                        }

                        Text {
                            id: evSocMileUnitId
                            y: 26 - 6
                            anchors.right: evSocProgressId.right
                            anchors.rightMargin: driveRangeInfoContainerId.mirror ? 32 : 37
                            visible: evSocMileId.visible
                            font.pixelSize: 36
                            color: DataSource.dayNightMode === 0x02 ? "#66ffffff":"#66333333"
                            text: DataSource.odoUnit !== 2 ? "km" : (CustomStyle.isSingular(DataSource.socMile) ? "mile" : "miles")
                        }

                        Text {
                            id: evMileStandardId
                            anchors.right: evSocMileId.left
                            anchors.rightMargin: 10
                            anchors.bottom: evSocMileUnitId.bottom
                            anchors.bottomMargin: 4
                            color: DataSource.dayNightMode === 0x02?"#ffffff":"#333333"
                            font.pixelSize: 24
                            visible: evSocMileId.visible
                            states: [
                                State {
                                    name: "invalid"
                                    when:DataSource.evMileStandard === 0
                                    PropertyChanges {
                                        target: evMileStandardId
                                        text: ""
                                    }
                                },
                                State {
                                    name: "NEDC"
                                    when:DataSource.evMileStandard === 1
                                    PropertyChanges {
                                        target: evMileStandardId
                                        text: "NEDC"
                                    }
                                },
                                State {
                                    name: "CLTC"
                                    when:DataSource.evMileStandard === 2
                                    PropertyChanges {
                                        target: evMileStandardId
                                        text: "CLTC"
                                    }
                                }
                            ]
                        }

                        ProgressBar{
                            id: evSocProgressId
                            anchors.left: parent.left
                            anchors.leftMargin: 585
                            y: 63
                            width: 718
                            height: 14
                            background: Image {
                                cache: false
                                source: "qrc:/images10_25/display_always/bg_progress_ev.png"
                            }

                            contentItem: Item{
                                Rectangle {
                                    id: evSocRect
                                    anchors.left: parent.left
                                    width: driveRangeInfoContainerId.mirror ? (1-evSocProgressId.visualPosition) * parent.width : evSocProgressId.visualPosition * parent.width
                                    height: parent.height
                                    radius: 6
                                }
                                LinearGradient{
                                    anchors.fill: evSocRect
                                    gradient: Gradient{
                                        GradientStop { position: 0.0; color: DataSource.batteryAlarm === 1 ? "#22c8c5":"#ff0000"}
                                        GradientStop { position: 1.0; color: DataSource.batteryAlarm === 1 ? "#46bf36":"#ff0000"}
                                    }
                                    source: evSocRect
                                    start: driveRangeInfoContainerId.mirror ? Qt.point(evSocRect.width,0): Qt.point(0,0)
                                    end: driveRangeInfoContainerId.mirror ?Qt.point(0,0) :  Qt.point(evSocRect.width,0)
                                }
                            }
                            value: DataSource.powerBatterySoc !== 101 && DataSource.powerBatterySoc !==0xFFE ? DataSource.powerBatterySoc/100 : 0
                        }

                        Image {
                            id: evLowSoc
                            source: "qrc:/images10_25/display_always/low_soc_fuel.png"
                            anchors.bottom: evSocProgressId.bottom
                            anchors.left: evSocProgressId.left
                            cache: false
                        }
                    }
                }
            }
        }

        //里程
        Item {
            id: odoItemId
            x: {
                //当语言为希伯来语跟阿拉伯语言时，文字阅读顺序为从右至左，文字显示为右对齐导致跟展车警告灯存在重叠情况，因此在经典跟极简模式下向左移动若干像素
                //http://dlc.coding.byd.com/p/dilink-6125f/bug-tracking/issues/518267/detail
                if(DataSource.language === 0x15){
                    DataSource.themeIndex === 0x01 || DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? 1426 : 1445
                }else{
                    DataSource.themeIndex === 0x01 || DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? 1446 : 1505}
            }
            width: 400
            LayoutMirroring.enabled: driveRangeInfoContainerId.mirror
            LayoutMirroring.childrenInherit: true

            anchors.bottom: parent.bottom
            anchors.bottomMargin: DataSource.themeIndex === 0x01 || DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? 12 : 58 - 7

            Text {
                id: odoNameId
                anchors.bottom: parent.bottom
                anchors.bottomMargin: DataSource.language!==1 ? -2 : 0
                anchors.left: parent.left
                font.pixelSize: 30
                textFormat: (DataSource.language === 0x01 || DataSource.language === 0x03) ? Text.PlainText : Text.RichText
                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                visible: DataSource.odoIndex !== 0 ? true : false
                states:[
                    State {
		    //laijunhao 20240813 for PR:http://dlc.coding.byd.com/p/dilink-6125f/bug-tracking/issues/517433/detail
                    //中文繁体和印地语的里程名称显示PC和台架显示不一致，并且台架显示与数值没有对齐，以台架为准进行微调，调整后会存在这两种语言在PC端不对齐的情况
                //qiuzihao 20250429 经产品盛学瑞确认，常显里程使用英文，不区分小语种。
                //总里程：ODO 里程一：TRIP A    里程二：TRIP B   EV里程：TRIP EV   HEV里程：TRIP HEV
                        name: "odoTotal"
                        when:(DataSource.odoIndex === 1)
                        PropertyChanges {
                            target: odoNameId
                            anchors.bottomMargin: 0
                            text: TranslateStr.alwyasDisplay_totalOdoStr
                        }
                    },
                    State {
                        name: "odo1"
                        when:(DataSource.odoIndex === 2)
                        PropertyChanges {
                            target: odoNameId
                            anchors.bottomMargin: 0
                            text: TranslateStr.alwyasDisplay_odo1Str
                        }
                    },
                    State {
                        name: "odo2"
                        when:(DataSource.odoIndex === 3)
                        PropertyChanges {
                            target: odoNameId
                            anchors.bottomMargin: 0
                            text: TranslateStr.alwyasDisplay_odo2Str
                        }
                    },
                    State {
                        name: "odoHev"
                        when:(DataSource.odoIndex === 4)
                        PropertyChanges {
                            target: odoNameId
                            anchors.bottomMargin: 0
                            text: TranslateStr.alwyasDisplay_odoHevStr
                        }
                    },
                    State {
                        name: "odoEv"
                        when:(DataSource.odoIndex === 5)
                        PropertyChanges {
                            target: odoNameId
                            anchors.bottomMargin: 0
                            text: TranslateStr.alwyasDisplay_odoEvStr
                        }
                    }
                ]
            }

            Text {
                id: odoValueId
                anchors.bottom: parent.bottom
                anchors.bottomMargin: -6
                anchors.left: odoNameId.right
                anchors.leftMargin: 11
                font.pixelSize: 42
                visible: DataSource.odoIndex !== 0 ? true : false
                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                text: DataSource.odoValue
            }

            Text {
                id: totalOdoUnitId
                anchors.bottom: parent.bottom
                anchors.bottomMargin: -2
                anchors.left: odoValueId.right
                anchors.leftMargin: 8
                visible: odoValueId.visible && DataSource.odoIndex === 1
                font.pixelSize: 30
                color: DataSource.dayNightMode === 0x02 ? "#b3ffffff":"#333333"
                text: DataSource.odoUnit !== 2 ? "km" : (CustomStyle.isSingular(DataSource.odoValue) ? "mile" : "miles")
            }

            Text {
                id: odo1UnitId
                anchors.left: odoValueId.right
                anchors.leftMargin: 8
                visible: odoValueId.visible && (DataSource.odoIndex === 2 || DataSource.odoIndex === 3)
                font.pixelSize: 30
                color: DataSource.dayNightMode === 0x02 ? "#b3ffffff":"#333333"
                text: DataSource.odo1Unit !== 2 ? "km" : "miles"
                anchors.bottom: parent.bottom
                anchors.bottomMargin: -2
            }

            Text {
                id: odoEvUnitId
                anchors.left: odoValueId.right
                anchors.leftMargin: 8
                visible: odoValueId.visible && (DataSource.odoIndex === 4 || DataSource.odoIndex === 5)
                font.pixelSize: 30
                color: DataSource.dayNightMode === 0x02 ? "#b3ffffff":"#333333"
                text: DataSource.odoEvUnit !== 2 ? "km" : (CustomStyle.isSingular(DataSource.odoValue) ? "mile":"miles")
                anchors.bottom: parent.bottom
                anchors.bottomMargin: -2
            }
        }

        //        //功率表盘
        //        Loader{
        //            visible: true
        //            id:powerDashBoardId
        //            x:DataSource.themeIndex === 0x02?7:106//204
        //            y:DataSource.themeIndex === 0x02?55:95//176
        //            source:DataSource.themeIndex === 0x02?"qrc:/qml10_25/alwaysDisplay/PowerDashBoardScience.qml":"qrc:/qml10_25/alwaysDisplay/PowerDashBoardClassic.qml"
        //        }

        //        //速度表盘
        //        Loader{
        //            visible: true
        //            id:speedDashBoardId
        //            x:DataSource.themeIndex === 0x02?1274:1087//1307
        //            y:DataSource.themeIndex === 0x02?54:95//185
        //            source:DataSource.themeIndex === 0x02?"qrc:/qml10_25/alwaysDisplay/SpeedDashBoardScience.qml":"qrc:/qml10_25/alwaysDisplay/SpeedDashBoardClassic.qml"
        //        }

        Item {
            id: naviPowerId
            x: 149
            y: 588
            width: 267
            height: 75

            visible: DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? true : false

            Item {
                width: 10 + powerValueId.contentWidth + powerUnitId.contentWidth
                height:75
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.verticalCenter: parent.verticalCenter

                Text {
                    id: powerValueId
                    text: DataSource.power
                    color: DataSource.dayNightMode === 0x02?"#ffffff":"#333333"
                    font.pixelSize: 100
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin:  -30
                    visible: DataSource.power === 1001 ? false : true
                }
                Text {
                    id: powerUnitId
                    text: DataSource.powerUnit === 2 ? "HP" : "kW"
                    color: DataSource.dayNightMode === 0x02?"#ffffff":"#333333"
                    font.pixelSize: 45
                    anchors.left: powerValueId.right
                    anchors.leftMargin: 12
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: -14
                    visible: DataSource.power === 1001 ? false : true
                }
            }
        }

        Item {
            id: naviSpeedId



            visible: DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? true : false

            Item {
                width: 10 + speedValueId.contentWidth + speedUnitId.contentWidth
                height:71
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.verticalCenter: parent.verticalCenter
                Text {
                    id: speedValueId
                    text: DataSource.speed
                    color: DataSource.dayNightMode === 0x02?"#ffffff":"#333333"
                    font.pixelSize: 94
                    visible: DataSource.speed === -1 ? false : true
                    anchors.bottom:parent.bottom
                    anchors.bottomMargin: -28
                }
                Text {
                    id: speedUnitId
                    text: DataSource.speedUnit === 2 ? "mph" : "km/h"
                    color: DataSource.dayNightMode === 0x02?"#ffffff":"#333333"
                    font.pixelSize: 45
                    anchors.left: speedValueId.right
                    anchors.leftMargin: 12
                    anchors.bottom: speedValueId.bottom
                    anchors.bottomMargin: 14
                    visible: DataSource.speed === -1 ? false : true
                }
            }

        }
    }

    Image {
        id: adasWindowBackground
        x:1266.5
        y:392
        source: DataSource.dayNightMode === 0x01?"qrc:/images10_25/adas2d/window_light_new.png":"qrc:/images10_25/adas2d/window_night_new.png"
        cache: false
        visible: DataSource.adasPlatform === 0x1?(DataSource.adasWindow === 0x2?true:false)
                                                :(DataSource.adasInterfaceDisplay === 0x02 && DataSource.adasType === 0)?true:false
    }
    Image {
        id: adas2dNaviWindowBg
        x:1256
        y:225
        visible: (DataSource.naviType === 0x4 && DataSource.adasInterfaceDisplay === 1) ? true:false
        source: DataSource.dayNightMode === 0x02?"qrc:/images10_25/warningInfo/night_bg.png":"qrc:/images10_25/warningInfo/day_bg.png"
    }
    Rectangle{
        id:adasContainerParentId
        x:1278-15+60
        y:439+7
        z:3
        width: 189
        height: 120
        clip: true
        visible: true
        radius: 10
        color: "transparent"
        layer.enabled: true
        layer.effect: OpacityMask{
            maskSource: Rectangle {
                width: 189
                height: 120
                radius: 10
            }
        }
        Loader {
            id:adasLoaderId
            x: DataSource.carNameType === 0x11 ? -455 : -465
            y: DataSource.carNameType === 0x11 ? -191 : -188
            z: 3
            scale: DataSource.carNameType === 0x11 ? 0.38: 0.39
            source: DataSource.adasPlatform === 0x1?(DataSource.adasWindow === 0x2 && DataSource.powerGear === 3 && DataSource.playAnim ?"qrc:/qml10_25/adas2d/Adas3R1V.qml":"")
                                                   :(DataSource.adasInterfaceDisplay === 0x02 && DataSource.adasType === 0 && DataSource.powerGear === 3 && DataSource.playAnim)? "qrc:/qml10_25/adas2d/Adas.qml":""
            onStatusChanged: {
                if (adasLoaderId.status === Loader.Ready){
                    adasLoaderId.visible = DataSource.adasPlatform === 0x1?(DataSource.adasWindow === 0x2?true:false)
                                                                          :(DataSource.adasInterfaceDisplay === 0x2?true:false)
                }
            }
        }

//        Adas3dContainer{
//            id:adas3dContainerId
//            x:-10
//            y: -98
//            z:3
//            width:608
//            height: 290
//            scale: 0.39
//            transformOrigin:Item.TopLeft
//            visible:false
//        }
    }

    //ADAS车间时距
    Item {
        id: adasCarDistanceItemId
        z:4
        visible: false
        width: 305
        height: 260
        x:1266.5//-15是由于背景切图有部分留白
        y:392
        Image {
            visible: true
            source: DataSource.dayNightMode === 0x01?"qrc:/images10_25/adas2d/window_light_new.png":"qrc:/images10_25/adas2d/window_night_new.png"
            cache: false
        }
        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: TranslateStr.adasCarTimeSet
            horizontalAlignment: Text.AlignHCenter
            width: DataSource.language === 0x16 ? 189:185
            wrapMode: DataSource.language === 0x1 ? Text.NoWrap : Text.WordWrap
            font.pixelSize: 18
            color: DataSource.dayNightMode === 0x02 ?"#DAE1EB":"#1D2C36"
            y:DataSource.language === 0x16 ? 55:73
        }

        Image{
            y:122
            anchors.horizontalCenter: parent.horizontalCenter
            //car body type 0 is car ,1 is suv
            source:(DataSource.carBodyType === 0 ? "qrc:/images10_25/adas2d/car/acc_" :"qrc:/images10_25/adas2d/suv/acc_") + DataSource.carDistanceLevel + ".png"
        }
    }

    Center{
        z:3
        id:centerContainerId
        visible: false //刚加载的时候不可见，报文查询后会更新是否可见
    }

    Loader{
        id:presentationLoaderId
        z:CustomStyle.presentationZ
        source: DataSource.dilinkName === 0x02?"qrc:/qml10_25/alwaysDisplay/Presentation.qml":""
        asynchronous: true
    }


    WarinLightLayout{
        id:warningLayoutId
        anchors.fill: parent
        z:CustomStyle.warningLightZ
    }

    //ADAS begin
    Loader{
        id:adasDebugLoaderId
        x:250
        y:200
        z:6
        source: DataSource.selfStudyResultShow === 1 ||  DataSource.adasDebugMode === 1?"qrc:/qml10_25/adas2d/AdasDebug.qml":""
    }

    /**
    ADAS文字信息
    */
    Item{
        id:adasTipItemId
        anchors.horizontalCenter: parent.horizontalCenter
        //y:520+15-4
        anchors.bottom:parent.bottom
        anchors.bottomMargin: 150
        z:111
        width:Math.min(adasTipText.contentWidth +14+4,adasTipText.width+24)
        //height: 42+4
        height: adasTipText.contentHeight + 14
        BorderImage{
            id:adasTipTextBg
            source:DataSource.dayNightMode === 0x1 ? "qrc:/images10_25/adas2d/pop-light.png" : "qrc:/images10_25/adas2d/pop-night.png"
            border.bottom: 10
            border.left: 10
            border.right: 10
            border.top: 10
            anchors.fill: parent
        }
        Text{
            id: adasTipText
            font.pixelSize: 28
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.verticalCenter: parent.verticalCenter
            //anchors.baseline: parent.bottom
            //anchors.baselineOffset: - ((42+4) / 3 - 3.5)
            textFormat:DataSource.language === 0xE || DataSource.language === 0x15?Text.RichText:Text.AutoText
            wrapMode: DataSource.language === 0xE || DataSource.language === 0x15? Text.NoWrap : Text.WordWrap
            width: DataSource.language === 0xE || DataSource.language === 0x15? (adasTipText.implicitWidth) :800
            horizontalAlignment: Text.AlignHCenter  // 水平居中
            verticalAlignment: Text.AlignVCenter    // 垂直居中
        }
    }


    Text {
        id: adasTextTip3R1V
        y:520+20
        z:111
        anchors.horizontalCenter: parent.horizontalCenter
        font.pixelSize: 28
        visible: DataSource.adasTextTip !== 0 && DataSource.powerGear === 3 ? true : false
        color: DataSource.dayNightMode === 0x01?"#333333":"#ffffff"
        textFormat: Text.RichText
        text: {
            switch(DataSource.adasTextTip){
            case 0x1:
                TranslateStr.adasTip3R1v_0x1   //自适应巡航已开启
                break
            case 0x2:
                TranslateStr.adasTip3R1v_0x2   //自适应巡航已取消
                break
            case 0x3:
                TranslateStr.adasTip3R1v_0x3   //自适应巡航已激活
                break
            case 0x4:
                TranslateStr.adasTip3R1v_0x4   //自适应巡航无法激活
                break
            case 0x5:
                TranslateStr.adasTip3R1v_0x5   //请系好主驾安全带
                break
            case 0x6:
                TranslateStr.adasTip3R1v_0x6    //请确认车门是否关好
                break
            case 0x7:
                TranslateStr.adasTip3R1v_0x7    //请确认挡位是否在D挡
                break
            case 0x8:
                TranslateStr.adasTip3R1v_0x8     //请释放 EPB
                break
            case 0x9:
                TranslateStr.adasTip3R1v_0x9      //请开启 ESP
                break
            default:
                ""
                break
            }
        }
    }

    //ADAS Text Info
    function textInfoUpdate(){
        if(DataSource.powerGear !== 3){
            adasTipText.visible = false
            adasTipTextBg.visible = false
            return
        }
        switch(DataSource.accRemoteText)
        {
        case 0x1:
            //紧急制动中
            adasTipText.text = TranslateStr.adasTip_0x1
            break
        case 0x2:
            //请轻转方向盘
            adasTipText.text = TranslateStr.adasTip_0x2
            break;
        case 0x3:
            //请立即接管
            adasTipText.text = TranslateStr.adasTip_0x3
            break
        case 0x4:
            //请制动
            adasTipText.text = TranslateStr.adasTip_0x4
            break
        case 0x5:
            //请接管
            adasTipText.text = TranslateStr.adasTip_0x5
            break
        case 0x6:
            //请保持安全车距
            adasTipText.text = TranslateStr.adasTip_0x6
            break
        case 0x7:
            //前方自动导航即将退出，请注意接管车辆
            adasTipText.text = TranslateStr.adasTip_0x7
            break
        case 0x8:
            //辅助变道即将退出，请接管
            adasTipText.text = TranslateStr.adasTip_0x8
            break
        case 0x9:
            //开门请注意
            adasTipText.text = TranslateStr.adasTip_0x9
            break
        //case 0xA:
            //驾驶辅助功能受限
        //    adasTipText.text = TranslateStr.adasTip_0xA
        //    break
        case 0xB:
            //环境受限，智能摄像头不可用
            adasTipText.text = TranslateStr.adasTip_0xB
            break
        case 0xC:
            //超越加速中
            adasTipText.text = TranslateStr.adasTip_0xC
            break
        case 0xD:
            //请确认是否调整限速至识别限速
            adasTipText.text = TranslateStr.adasTip_0xD
            break
        case 0xE:
        case 0x48:
            //请确认安全带是否系好
            adasTipText.text = TranslateStr.adasTip_0xE
            break
        case 0xF:
        case 0x49:
            //请确认车门是否关好
            adasTipText.text = TranslateStr.adasTip_0xF
            break
        case 0x10:
        case 0x4A:
            //请确认挡位是否在D档
            adasTipText.text = TranslateStr.adasTip_0x10
            break
        case 0x11:
        case 0x4B:
            //请释放EPB
            adasTipText.text = TranslateStr.adasTip_0x11
            break
        case 0x12:
        case 0x4C:
            //请开启ESP
            adasTipText.text = TranslateStr.adasTip_0x12
            break
        case 0x13:
            //前车已驶离，请踩加速踏板起步
            adasTipText.text = TranslateStr.adasTip_0x13
            break
        case 0x14:
            //即将进入匝道
            adasTipText.text = TranslateStr.adasTip_0x14
            break
        case 0x15:
            //即将汇入主路
            adasTipText.text = TranslateStr.adasTip_0x15
            break
        case 0x16:
            //当前不适合变道
            adasTipText.text = TranslateStr.adasTip_0x16
            break
        case 0x17:
            //变道已取消
            adasTipText.text = TranslateStr.adasTip_0x17
            break
        case 0x18:
            //变道已完成
            adasTipText.text = TranslateStr.adasTip_0x18
            break
        case 0x19:
        case 0x47:
            //自适应巡航无法激活
            adasTipText.text = TranslateStr.adasTip_0x19
            break
        case 0x1A:
            //自适应巡航已退出
            adasTipText.text = TranslateStr.adasTip_0x1A
            break
        case 0x1B:
            //自适应巡航已激活
            adasTipText.text = TranslateStr.adasTip_0x1B
            break
        case 0x1C:
            //高速智能领航已退出
            adasTipText.text = TranslateStr.adasTip_0x1C
            break
        case 0x1D:
            //高速智能领航已开启
            adasTipText.text = TranslateStr.adasTip_0x1D
            break
        case 0x1E:
            //车道领航已退出
            adasTipText.text = TranslateStr.adasTip_0x1E
            break
        case 0x1F:
            //车道领航已激活
            adasTipText.text = TranslateStr.adasTip_0x1F
            break
        case 0x20:
            //车道辅助功能已关闭
            adasTipText.text = TranslateStr.adasTip_0x20
            break
        case 0x21:
            //车道偏离提醒功能已开启
            adasTipText.text = TranslateStr.adasTip_0x21
            break
        case 0x22:
            //车道纠偏辅助功能已开启
            adasTipText.text = TranslateStr.adasTip_0x22
            break
        case 0x23:
            //邻车靠近避让已开启
            adasTipText.text = TranslateStr.adasTip_0x23
            break
        case 0x24:
            //请握住方向盘
            adasTipText.text = TranslateStr.adasTip_0x24
            break
        case 0x25:
            //前车已启动，请注意周边环境
            adasTipText.text = TranslateStr.adasTip_0x25
            break
        case 0x26:
        case 0x4E:
            //当前路况不满足智能领航激活条件 当前路况不满足激活条件
            adasTipText.text = TranslateStr.adasTip_0x26
            break
        case 0x27:
            //辅助变道已退出，请接管
            adasTipText.text = TranslateStr.adasTip_0x27
            break
        case 0x28:
            //车道保持功能已开启
            adasTipText.text = TranslateStr.adasTip_0x28
            break
        case 0x29:
            //车道偏离预警和车道保持功能已开启
            adasTipText.text = TranslateStr.adasTip_0x29
            break
        case 0x30:
            //邻车靠近避让已关闭
            adasTipText.text = TranslateStr.adasTip_0x30
            break
        case 0x31:
            //自动紧急制动已关闭
            adasTipText.text = TranslateStr.adasTip_0x31
            break
        case 0x32:
            //交通标识识别已关闭
            adasTipText.text = TranslateStr.adasTip_0x32
            break
        case 0x33:
            //驾驶辅助系统热保护，请开空调
            adasTipText.text = TranslateStr.adasTip_0x33
            break
        case 0x34:
            //紧急转向辅助开启
            adasTipText.text = TranslateStr.adasTip_0x34
            break
        case 0x35:
            //紧急转向辅助已关闭
            adasTipText.text = TranslateStr.adasTip_0x35
            break
        case 0x36:
            //车道辅助功能已开启
            adasTipText.text = TranslateStr.adasTip_0x36
            break
        case 0x37://方向盘转动图片
            //车道偏离，请注意
            adasTipText.text = TranslateStr.adasTip_0x37
            break
        case 0x38://方向盘图片
            //车道偏离，请注意
            adasTipText.text = TranslateStr.adasTip_0x37
            break
        case 0x39:
            //前方复杂路况，请降低车速
            adasTipText.text = TranslateStr.adasTip_0x39
            break
        case 0x40:
        case 0x4D:
            //当前驾驶模式下无法激活
            adasTipText.text = TranslateStr.adasTip_0x40
            break
        case 0x41:
            //正在小幅避让，稍后恢复居中
            adasTipText.text = TranslateStr.adasTip_0x41
            break
        case 0x42:
            //双击可激活/退出辅助驾驶功能
            adasTipText.text = TranslateStr.adasTip_0x42
            break
        case 0x43:
            //请手扶方向盘
            adasTipText.text = TranslateStr.adasTip_0x43
            break
        case 0x44:
            //长时间脱手驾驶，本上电循环无法激活
            adasTipText.text = TranslateStr.adasTip_0x44
            break
        case 0x45:
            //弯道减速通行中
            adasTipText.text = TranslateStr.adasTip_0x45
            break
        case 0x46:
            //正在跟随旁车道车辆行驶
            adasTipText.text = TranslateStr.adasTip_0x46
            break
        case 0x4F:
            //已退出至手动驾驶
            adasTipText.text = TranslateStr.adasTip_0x4F
            break
        case 0x50:
        case 0x51:
            //请先在中控屏开启驾驶辅助功能
            adasTipText.text = TranslateStr.adasTip_0x50_0x51
            break
        case 0x52:
        case 0x53:
            //请控制车辆，已退出至手动驾驶
            adasTipText.text = TranslateStr.adasTip_0x52_0x53
            break
        case 0x54:
        case 0x55:
            //请控制方向，已降级至自适应巡航
            adasTipText.text = TranslateStr.adasTip_0x54_0x55
            break
        case 0x0A:
        case 0x56:
            //驾驶辅助功能暂不可用
            adasTipText.text = TranslateStr.adasTip_0x56
            break
        case 0x57:
        case 0x58:
            //无法激活自适应巡航，拖车尺寸超限
            adasTipText.text = TranslateStr.adasTip_0x57_58
            break
        case 0x59:
            //安全起见，带拖车行驶时无法调节跟车距离
            adasTipText.text = TranslateStr.adasTip_0x59
            break
        case 0x5A:
            //自适应巡航已激活，带拖车行驶请注意安全
            adasTipText.text = TranslateStr.adasTip_0x5A
            break
        default:
            adasTipText.text = ""
            break
        }

        if((DataSource.chargeNeedDisplay === 0
            &&DataSource.dischargeNeedDisplay === 0
            &&DataSource.speedUpTimerInterfaceDisplay === 0)
                &&DataSource.accRemoteText > 0 && !adasTextTimeout){
            adasTipText.visible = true
            adasTipTextBg.visible = true
        }else{
            adasTipText.visible = false
            adasTipTextBg.visible = false
        }
    }
    function textColorUpdate()
    {
        switch(DataSource.accTipTextColor){
        case 0:
            adasTipText.color = DataSource.dayNightMode === 0x01?"#333333":"#ffffff"
            break
        case 1:
            adasTipText.color = "#EE7600"
            break
        case 2:
            adasTipText.color = "red"
            break
        default:
            adasTipText.color = DataSource.dayNightMode === 0x01?"#333333":"#ffffff"
            break
        }
    }

    /*
    *设置ADAS小窗口容器显示
    **/
    function updateAdasContainer(){
        if(DataSource.powerGear !== 3){
            //非ON挡下让ADAS小窗口显示在背景下面，让3D ADAS在用户不知情的情况下提前加载，避免切换ON后有因为ADAS加载导致卡顿的情况，影响用户体验
            adasContainerParentId.z = -10
            //adas3dContainerId.visible = true
        }else
        {
            adasContainerParentId.z = 3
            //adas3dContainerId.visible =(DataSource.adasInterfaceDisplay === 0x02 && DataSource.adasType === 1) ? true:false
        }
    }
    Timer{
        id:adasCarDistanceTimer
        repeat: true
        running: false
        interval: 5000
        onTriggered: {
            adasCarDistanceTimer.stop()
            adasCarDistanceItemId.visible = false
        }
    }
    //ADAS end

    //动效 begin
    ParallelAnimation{
        id:themeSwithOpacityHideAnimId
        PropertyAnimation{
            //id:driveRangeInfoHideAnimId
            target: driveRangeInfoId
            property: "opacity"
            from:1
            to:0
            easing.type: Easing.InOutQuad
            duration: CustomStyle.themeOpacityChangeTimer
        }
        PropertyAnimation{
            //id:warningLayoutIdHideAnimId
            target: warningLayoutId
            property: "opacity"
            from:1
            to:0
            easing.type: Easing.InOutQuad
            duration: CustomStyle.themeOpacityChangeTimer
        }
        onFinished: {
            DataSource.sendPluginMsgInt(DataSource.RECV_MSG_ID_THEME_SWITCH_ANIM,DataSource.THEME_SWITCH_HIDE_ANIM_COMPLETE)
        }
    }

    ParallelAnimation{
        id:themeSwithOpacityShowAnimId
        PropertyAnimation{
            //id:driveRangeInfoShowAnimId
            target: driveRangeInfoId
            property: "opacity"
            from:0
            to:1
            easing.type: Easing.InOutQuad
            duration: CustomStyle.themeOpacityChangeTimer
        }
        PropertyAnimation{
            //id:warningLayoutIdShowAnimId
            target: warningLayoutId
            property: "opacity"
            from:0
            to:1
            easing.type: Easing.InOutQuad
            duration: CustomStyle.themeOpacityChangeTimer
        }
        onFinished: {
            DataSource.sendPluginMsgInt(DataSource.RECV_MSG_ID_THEME_SWITCH_ANIM,DataSource.THEME_SWITCH_DISP_ANIM_COMPLETE)
        }
    }

    PropertyAnimation{
        id:terrainSwitchAnim
        target:terrainImageId
        property: "opacity"
        duration: CustomStyle.terrainModeAnimationTimer/2
        from: 1
        to:0
        onFinished: {
            updateTerrainState()
            terrainSwitchAnimSec.start()
        }
    }
    PropertyAnimation{
        id:terrainSwitchAnimSec
        target:terrainImageId
        property: "opacity"
        duration: CustomStyle.terrainModeAnimationTimer/2
        from: 0
        to:1
        onFinished: {
            isOnSwitchTerrainModeAnim = false
            switchTerrainModeAnim()
            updateBgImageSource()
            terrainImageId.visible = false
        }
    }
    ParallelAnimation{
        id:terrainSwitchShowAnim
        PropertyAnimation{
            target: terrainImageId
            property: "opacity"
            from:0
            to:1
            duration: CustomStyle.terrainModeAnimationTimer
        }
        PropertyAnimation{
            target: bgCityImageId
            property: "opacity"
            from:1
            to:0
            duration: CustomStyle.terrainModeAnimationTimer
            onFinished: {
                isOnSwitchTerrainModeAnim = false
                switchTerrainModeAnim()
            }
        }
        onFinished: {
            isOnSwitchTerrainModeAnim = false
            switchTerrainModeAnim()
            updateBgImageSource()
            terrainImageId.visible = false
        }
    }
    ParallelAnimation{
        id:terrainSwitchHideAnim
        PropertyAnimation{
            target: terrainImageId
            duration: CustomStyle.terrainModeAnimationTimer
            property: "opacity"
            from:1
            to:0
        }
        PropertyAnimation{
            target: bgCityImageId
            duration: CustomStyle.terrainModeAnimationTimer
            property: "opacity"
            from:0
            to:1
        }
        onFinished: {
            isOnSwitchTerrainModeAnim = false
            switchTerrainModeAnim()
        }
    }
    //动效 end
}
