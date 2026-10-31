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
    width: 1920
    height: 720

    //add on 2022/04/01
    //isCauseByDayNightModeChanged:用于判断地图投屏显示时，判断是否深浅模式切换，需要全屏背景遮挡地图
    property bool isCauseByDayNightModeChanged: false

    Component.onCompleted: {
        if(DataSource.playAnim  === false){

        }else{
            DataSource.sendPluginMsgInt(DataSource.RECV_MSG_ID_POWERON_ANIM,DataSource.POWER_ON_ANIM_CRASH)
            console.log("DataSource.playAnim = true, send crash msg")
        }
        updateBgImageSource()
        var component
        if(rootItemId.powerOnAnimationUi === null && DataSource.playAnim  === false){
            component = Qt.createComponent("qrc:/qml/PowerOnAnimation.qml")
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
                                                                                                   :(DataSource.adasInterfaceDisplay === 0x02 && DataSource.powerGear === 3 &&  DataSource.adasType ===0) })
        adas2dNaviWindowBg.visible = Qt.binding(function(){return DataSource.adasPlatform === 0x1?(DataSource.naviType === 0x4 && DataSource.adasWindow === 0x1 && DataSource.powerGear === 3)
                                                                                                 :(DataSource.naviType === 0x4 && DataSource.adasInterfaceDisplay === 1 && DataSource.powerGear === 3&&DataSource.adasType ===0)})
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
                var component = Qt.createComponent("qrc:/qml/ApproachAnimation.qml")
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

        if(DataSource.themeIndex === 0x02){
            component = Qt.createComponent("qrc:/qml/alwaysDisplay/PowerDashBoardScience.qml")
            rootItemId.powerDashBoardUi = component.createObject(alwyasDisplayId,{"x": 7,"y": 55})
            component = Qt.createComponent("qrc:/qml/alwaysDisplay/SpeedDashBoardScience.qml")
            rootItemId.speedDashBoardUi = component.createObject(alwyasDisplayId,{"x": 1274,"y": 55})
        }else{
            component = Qt.createComponent("qrc:/qml/alwaysDisplay/PowerDashBoardClassic.qml")
            rootItemId.powerDashBoardUi = component.createObject(alwyasDisplayId,{"x": 106,"y": 95})
            component = Qt.createComponent("qrc:/qml/alwaysDisplay/SpeedDashBoardClassic.qml")
            rootItemId.speedDashBoardUi = component.createObject(alwyasDisplayId,{"x": 1087,"y": 95})
        }
    }
    //========T291677 start========
    /**
        updateFullNaviDayNightModeChangeBgImageSource：修改大屏地图投屏时，深浅模式切换时的背景设置，不允许其他地方对其进行调用
    */
    function updateFullNaviDayNightModeChangeBgImageSource()
    {
        if(DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN){
            if(DataSource.dayNightMode === 0x02){
                bgImageId.source = "qrc:/images/display_always/night/navi_full_bg.png"
            }else{
                bgImageId.source = "qrc:/images/display_always/day/navi_full_bg.png"
            }
        }
    }

    Timer{
        id:naviDayNightModeChangeAnimId
        interval: 350   //品质表示接受的效果时间：350ms
        repeat: false
        onTriggered: {
            naviDayNightModeChangeAnimId.stop()
            updateFullNaviDayNightModeChangeBgImageSource()
            isCauseByDayNightModeChanged = false
        }
    }
    //========T291677 end========
    function updateBgImageSource(){

        if(DataSource.powerGear === DataSource.POWER_GEAR_OFF || DataSource.powerGear === DataSource.POWER_GEAR_ACC ){
            //OFF档背景图使用开机动画第一帧，如果用bg.png，开机的时候先从bg.png切换到开机动画，看上去会闪一下
            bgImageId.source = "qrc:/images/poweron/IMG_000.ktx"
            console.log("updateBgImageSource1 bgImageId set to ",bgImageId.source)
        }else if(DataSource.powerGear === DataSource.POWER_GEAR_ON){
            if(DataSource.naviType === DataSource.NAVI_TYPE_SMALL_SCREEN || DataSource.naviType===DataSource.NAVI_TYPE_FULL_SCREEN){
                /**
                *modefied on :2022/04/01 by zhanghao
                *T291677
                *地图导航投屏时，需要用全屏背景遮挡投屏部分，使地图投屏深浅模式切换与IVI一致
                */
                if(DataSource.naviType === DataSource.NAVI_TYPE_SMALL_SCREEN)
                {
                    if(DataSource.dayNightMode === 0x02){
                        bgImageId.source = "qrc:/images/display_always/night/navi_small_bg.png"
                    }else{
                        bgImageId.source = "qrc:/images/display_always/day/navi_small_bg.png"
                    }
                }else{
                    if(!isCauseByDayNightModeChanged)
                    {
                        updateFullNaviDayNightModeChangeBgImageSource()
                    }else{
                        naviDayNightModeChangeAnimId.stop()
                        bgImageId.source = Qt.binding(function(){ return DataSource.dayNightMode === 0x02?"qrc:/images/display_always/night/bg.png":"qrc:/images/display_always/day/bg.png"})
                        naviDayNightModeChangeAnimId.start()
                        console.log("Full Navi is cause day night mode change! current bgImage source :"+bgImageId.source)
                    }
                    console.log("updateBgImageSource2 bgImageId set to ",bgImageId.source)
                }
                //=========================T291677 end
            }else if(isInThemeSwithAnim){
                terrainImageId.source = ""
            }else{
                switch(DataSource.terrainMode){
                case 4:
                    bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/classic_snow_mode.png":"qrc:/images/terrainMode/day/classic_snow_mode.png"
                    break
                case 5:
                    bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/classic_mud_mode.png":"qrc:/images/terrainMode/day/classic_mud_mode.png"
                    break
                case 6:
                    bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/classic_sand_mode.png":"qrc:/images/terrainMode/day/classic_sand_mode.png"
                    break
                case 7:
                    bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/tec_snow_mode.png":"qrc:/images/terrainMode/day/tec_snow_mode.png"
                    break
                case 8:
                    bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/tec_mud_mode.png":"qrc:/images/terrainMode/day/tec_mud_mode.png"
                    break
                case 9:
                    bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/tec_sand_mode.png":"qrc:/images/terrainMode/day/tec_sand_mode.png"
                    break
                case 0x0A:
                    bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/classic_moun_mode.png":"qrc:/images/terrainMode/day/classic_moun_mode.png"
                    break
                case 0x0B:
                    bgImageId.source = DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/tec_moun_mode.png":"qrc:/images/terrainMode/day/tec_moun_mode.png"
                    break
                default:
                    bgImageId.source = Qt.binding(function(){ return DataSource.dayNightMode === 0x02?"qrc:/images/display_always/night/bg.png":"qrc:/images/display_always/day/bg.png"})
                    break
                }
                console.log("updateBgImageSource3 bgImageId set to ",bgImageId.source)
            }

        }else{
            bgImageId.source = "qrc:/images/poweron/IMG_000.ktx"
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
        bgImageId.source = Qt.binding(function(){ return DataSource.dayNightMode === 0x02?"qrc:/images/display_always/night/bg.png":"qrc:/images/display_always/day/bg.png"})
        console.log("switchTerrainModeAnim bgImageId set to ",bgImageId.source)
        terrainImageId.visible = true
        if(DataSource.terrainMode >= 4 && DataSource.terrainMode <= 0x0B){
            if(mPreTerrainMode <= 3 || mPreTerrainMode > 0x0B){
                updateTerrainState()
                terrainImageId.opacity = 0
                terrainSwitchShowAnim.start()
            }else{
                terrainSwitchAnim.start()
            }
            isOnSwitchTerrainModeAnim = true
        }else{
            if(terrainImageId.opacity > 0){
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
            isCauseByDayNightModeChanged = true
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
                    component = Qt.createComponent("qrc:/qml/PowerOnAnimation.qml")
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
                        themeComponent = Qt.createComponent("qrc:/qml/ThemeChangeAnimation.qml");
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
                    var component = Qt.createComponent("qrc:/qml/alwaysDisplay/Navigation.qml")
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
        function onAdasPlatformChanged(){
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
                var component = Qt.createComponent("qrc:/qml/alwaysDisplay/MediaInfo.qml")
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
            ecoSportModeImgId.source= DataSource.ecoSportMode === 1 ?"qrc:/images/display_always/eco_sport_anim/IMG_120.png":"qrc:/images/display_always/eco_sport_anim/IMG_100.png"
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
        interval: 20
        repeat: true
        property int defaultIndex: 100
        property int maxIndex: 120
        onTriggered: {
            ecoSportModeImgId.source = "qrc:/images/display_always/eco_sport_anim/IMG_"+defaultIndex+".png"
            defaultIndex+=1
            if(defaultIndex >maxIndex){
                sportToEcoTimerId.stop()
                defaultIndex = 100
                isInEcoSportSwithAnim = false
                reUpdateEcoSportAnimation()
                ecoSportModeImgId.source = "qrc:/images/display_always/eco_sport_anim/IMG_120.png"
            }
        }
    }
    Timer{
        id:ecoToSportTimerId
        interval: 20
        repeat: true
        property int defaultIndex: 120
        property int minIndex: 100
        onTriggered: {
            ecoSportModeImgId.source = "qrc:/images/display_always/eco_sport_anim/IMG_"+defaultIndex+".png"
            defaultIndex-=1
            if(defaultIndex <=minIndex){
                ecoToSportTimerId.stop()
                isInEcoSportSwithAnim = false
                defaultIndex = 120
                reUpdateEcoSportAnimation()
                ecoSportModeImgId.source = "qrc:/images/display_always/eco_sport_anim/IMG_100.png"
            }
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
        opacity: DataSource.naviType === 0x04 && DataSource.screenSize === 0x02 && DataSource.powerGear === 0x03 && DataSource.playAnim === true && rootItemId.width === 1920 && rootItemId.height === 720 ? 0 : 1
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
                            source: DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/classic_snow":"qrc:/images/terrainMode/day/classic_snow"
                        }
                    }
                },
                State {
                    name: "CLASSIC_MUD"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            source: DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/classic_mud":"qrc:/images/terrainMode/day/classic_mud"}}

                },
                State {
                    name: "CLASSIC_SAND"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            source: DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/classic_sand":"qrc:/images/terrainMode/day/classic_sand"}}
                },
                State {
                    name: "CLASSIC_MOUNTAIN"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            source: DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/classic_mountain.png":"qrc:/images/terrainMode/day/classic_mountain.png"}}

                },
                State {
                    name: "TECHNOLOGY_SNOW"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            source: DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/tec_snow_mode.png":"qrc:/images/terrainMode/day/tec_snow_mode.png"}}

                },
                State {
                    name: "TECHNOLOGY_MUD"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            source: DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/technology_mud":"qrc:/images/terrainMode/day/technology_mud"}}

                },
                State {
                    name: "TECHNOLOGY_SAND"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            source: DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/technology_sand":"qrc:/images/terrainMode/day/technology_sand"}}
                },
                State {
                    name: "TECHNOLOGY_MOUNTAIN"
                    PropertyChanges {
                        target: terrainImageId;
                        source:{
                            source: DataSource.dayNightMode === 0x02?"qrc:/images/terrainMode/night/technology_mountain.png":"qrc:/images/terrainMode/day/technology_mountain.png"}}
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
            x:168
            //y:37
            //y:12
            y:21
            id: ecoSportModeImgId
            visible: false
        }

        //城市
        Image {
            id: bgCityImageId
            x:635
            y:135
            cache: false
            visible: DataSource.terrainMode === 0 && (DataSource.naviType !== DataSource.NAVI_TYPE_SMALL_SCREEN && DataSource.naviType!==DataSource.NAVI_TYPE_FULL_SCREEN) ? true : false
            source: DataSource.dayNightMode === 0x02?"qrc:/images/display_always/night/bg_city.png":"qrc:/images/display_always/day/bg_city.png"
        }
        Image {
            id: headLineId
            opacity: DataSource.naviType === 0x04 && DataSource.screenSize === 0x02 && DataSource.powerGear === 0x03 && DataSource.playAnim === true && rootItemId.width === 1920 && rootItemId.height === 720 ? 0 : 1
            y:124
            anchors.horizontalCenter: parent.horizontalCenter
            source: "qrc:/images/display_always/headLine.png"
        }

        Item {
            id: headInfoId
            anchors.horizontalCenter: parent.horizontalCenter
            width: 1020
            height: 124-12

            //档位
            WarningLight{
                id: gearModeId
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.bottom: parent.bottom
                displayState:DataSource.gearMode > 0?DataSource.gearDisplayState:0
                Text {
                    id: gearModeImageId
                    font.pixelSize: 64
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: -20
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
                    anchors.bottomMargin: -8-5
                    font.pixelSize: 42
                    text: DataSource.realGear > 0 ? DataSource.realGear : ""
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                }
            }
            //标准回馈
            WarningLight{
                id:feedbackLight
                x: 227
                y: (DataSource.language === 0x01 || DataSource.language === 0x03) ? 87 : 80
                width: 205
                height: 30
                visible: ((DataSource.vehicleType === DataSource.CAR_TYPE_EV)&&(DataSource.energyFeedBackLight === 1||DataSource.energyFeedBackLight === 2))?true:false

                Text {
                    id: feedbackImg
                    text: DataSource.energyFeedBackLight === 1
                          ? (DataSource.language === 0x01?"标准回馈":DataSource.language === 0x09?"스탠다드":(DataSource.language === 0x03?"標準回饋":"STANDARD"))
                          : (DataSource.language === 0x01?"较大回馈":DataSource.language === 0x09?"하이":(DataSource.language === 0x03?"較大回饋":"HIGH"))
                    font.pixelSize: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 41 : 30
                    color: "#17975f"
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.verticalCenterOffset: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 2 : -5
                    anchors.right: parent.right
                }
            }

            //ECO
            WarningLight{
                id: ecoWarningLightId
                x:1042 - (rootItemId.width - headInfoId.width)/2
                y: 81
                width: 83
                height: 32

                visible: DataSource.modeLight === 0x01?true:false

                Text {
                    id: ecoWarningLightImageId
                    text: "ECO"
                    font.pixelSize: 41
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
                height: 32
                anchors.left: ecoWarningLightId.left
                anchors.bottom:ecoWarningLightId.bottom
                visible: DataSource.modeLight === 0x02?true:false
                Text {
                    id: sportWarningLightImageId
                    text: "SPORT"
                    font.pixelSize: 41
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
                height: 32
                anchors.left: ecoWarningLightId.left
                anchors.bottom:ecoWarningLightId.bottom
                visible: DataSource.modeLight === 0x03?true:false
                Text {
                    id: normalWarningLightImageId
                    text: "NORMAL"
                    font.pixelSize: 41
                    color: "#17975f"
                    //font.family: CustomStyle.plagiata
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.left : parent.left
                }
            }
            //EV
            WarningLight{
                id: evWarningLightId
                x: 809 - (rootItemId.width - headInfoId.width)/2 - 12
                y:81
                width: 78
                height: 32
                displayState: DataSource.evLight

                Text {
                    id: evWarningLightImageId
                    text: "EV"
                    font.pixelSize: 41
                    color: DataSource.evLightColor === 0x04?"#17975f":"#179be0"
                    //font.family: CustomStyle.plagiata
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.right: parent.right
                }
            }
            //HEV
            WarningLight{
                id: hevWarningLightId
                width: 78
                height: 32
                anchors.right: evWarningLightId.right
                anchors.bottom:ecoWarningLightId.bottom

                displayState: DataSource.hevLight
                Text {
                    id: hevWarningLightImageId
                    text: "HEV"
                    font.pixelSize: 41
                    color: "#17975f"
                    //font.family: CustomStyle.plagiata
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.right: parent.right
                }
            }
            //GRASS
            WarningLight{
                id: grassWarningLightId
                //                x: 599
                x:ecoWarningLightId.x
                y:69 + 2
                visible: DataSource.modeLight === 0x04?true:false
                Image{
                    id: grassWarningLightImageId
                    y:0
                    source: "qrc:/common/images/warninglight/grassLight.png"
                }
            }
            //MUDDY
            WarningLight{
                id: muddyWarningLightId
                //                x: 596
                x:ecoWarningLightId.x
                y:75 + 2
                visible: DataSource.modeLight === 0x05?true:false
                Image{
                    id: muddyWarningLightImageId
                    y:0
                    source: "qrc:/common/images/warninglight/muddyLight.png"
                }
            }
            //SANDY
            WarningLight{
                id: sandWarningLightId
                //                x: 597
                x:ecoWarningLightId.x - 1
                y:72 + 2
                visible: DataSource.modeLight === 0x06?true:false
                Image{
                    id: sandWarningLightImageId
                    y:0
                    source: "qrc:/common/images/warninglight/sandLight.png"
                }
            }
            //MOUNTAIN
            WarningLight{
                id: mountainWarningLightId
                //                x: 597
                x: ecoWarningLightId.x - 1
                y:  72 + 2
                visible: DataSource.modeLight === 0x07 ? true : false
                Image{
                    id: mountainWarningLightImageId
                    y: 0
                    source: "qrc:/common/images/warninglight/mountainLight.png"
                }
            }
            //WADE
            WarningLight{
                id: wadeWarningLightId

                x:ecoWarningLightId.x
                y:69 + 2
                visible: DataSource.modeLight === 0xC?true:false
                Image{
                    id: wadeWarningLightImageId
                    y:0
                    source: "qrc:/common/images/warninglight/wadeLight.png"
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
                    x: 3
                    text: timeDisplayId.currentTime
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    //font.pixelSize: 42
                    font.pixelSize: 38
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: -13
                    visible: DataSource.timeFormat === 2 ? true: false
                }

                Text{
                    anchors.left: timeFormatId.right
                    anchors.leftMargin: 3

                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: -13

                    text: timeDisplayId.currentTime
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    //font.pixelSize: 42
                    font.pixelSize: 38
                    visible: timeFormatId.visible && (DataSource.language === 0x01 || DataSource.language === 0x03) ? true: false
                }

                Text{
                    anchors.right: timeFormatId.left
                    anchors.rightMargin: 3

                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: -13

                    text: timeDisplayId.currentTime
                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    //font.pixelSize: 42
                    font.pixelSize: 38
                    visible: timeFormatId.visible && (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? true: false
                }

                Text{
                    id:timeFormatId

                    anchors.left: parent.left

                    anchors.leftMargin: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 105 : 3

                    anchors.bottom: parent.bottom
                    //anchors.bottomMargin: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? -13 : -6
                    anchors.bottomMargin: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? -13 : -9

                    color: DataSource.dayNightMode === 0x02 ?"#bfffffff":"#bf333333"

                    text: timeDisplayId.isAm ? ((DataSource.language === 0x01 || DataSource.language === 0x03) ? "上午" : "AM") :
                                               ((DataSource.language === 0x01 || DataSource.language === 0x03) ? "下午" : "PM")

                    //font.pixelSize: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 42 : 26
                    font.pixelSize: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 38 : 30
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

            //方位
            Image{
                id: compassId
                anchors.right: directionTextId.left
                anchors.bottom: directionTextId.bottom
                anchors.bottomMargin: 2
                visible: directionTextId.visible
                source: DataSource.dayNightMode === 0x02 ?"qrc:/images/display_always/night/compass.png":"qrc:/images/display_always/day/compass.png"
            }
            Text{
                id:directionTextId
                visible: DataSource.direction !== 0 ? 1 : 0
                x: 1251 - (rootItemId.width - headInfoId.width)/2
                y: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 84 : 85
                width: 49
                height: 29
                horizontalAlignment: Text.AlignLeft
                verticalAlignment: Text.AlignVCenter
                color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                font.pixelSize: (DataSource.language !== 0x01 && DataSource.language !== 0x03) ? 38 : 28
                states:[
                    State {
                        name: "N"
                        when:DataSource.direction === 1
                        PropertyChanges {
                            target: directionTextId
                            text: TranslateStr.alwyasDisplay_N_Str
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
                    x: 1369 - (rootItemId.width - headInfoId.width)/2
                    y: 81
                    width: 57
                    height: 32
                    horizontalAlignment: Text.AlignRight
                    verticalAlignment: Text.AlignVCenter

                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    font.pixelSize: 42
                    text: DataSource.outsideTemp
                }

                Text {
                    id: tempUnitId

                    anchors.left: tempValueId.right
                    anchors.leftMargin: 0

                    anchors.bottom: tempValueId.bottom
                    anchors.bottomMargin: -7

                    color: DataSource.dayNightMode === 0x02 ?"#ffffff":"#333333"
                    font.pixelSize: 28
                    text: DataSource.outsideTempUnit === 0 ? "℃":"℉"
                }
            }
        }

        //电量、油量表
        Item {
            id: driveRangeInfoContainerId
            y: 564
            width: 864
            height: 78
            anchors.horizontalCenter: parent.horizontalCenter
            //https://dlc.coding.byd.com/p/dilink-6125f/bug-tracking/issues/551472/detail
            //希伯来语取消镜像显示
            property bool mirror: false //(DataSource.language === 0x15)

            Image {
                id:driveRangeInfoBgId
                opacity: DataSource.naviType === 0x04 && DataSource.screenSize === 0x02 && DataSource.powerGear === 0x03 && DataSource.playAnim === true && rootItemId.width === 1920 && rootItemId.height === 720 ? 0 : 1
                anchors.horizontalCenter: parent.horizontalCenter
                cache: false
                y:DataSource.themeIndex === 0x02 ?-12:0
                source: {
                    if (DataSource.themeIndex === 0x02){
                        DataSource.dayNightMode === 0x02 ? "qrc:/images/display_always/night/bg_soc_fuel_tech.png" : "qrc:/images/display_always/day/bg_soc_fuel_tech.png"
                    } else{
                        DataSource.dayNightMode === 0x02 ? "qrc:/images/display_always/night/bg_soc_fuel.png" : "qrc:/images/display_always/day/bg_soc_fuel.png"
                    }
                }
            }

            Item {
                id: driveRangeInfoId
                anchors.fill: parent

                //hev电量、油量表
                Item{
                    id:hevDriveRangeInfoId
                    anchors.fill: parent
                    visible: DataSource.vehicleType !== DataSource.CAR_TYPE_EV ? true : false
                    //电续驶里程
                    Item {
                        width: socProgressId.width
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.horizontalCenterOffset: -200 - 2

                        Item {
                            anchors.fill: parent
                            LayoutMirroring.enabled: driveRangeInfoContainerId.mirror
                            LayoutMirroring.childrenInherit: true

                            Image {
                                id: batteryLightId
                                source: DataSource.dayNightMode === 0x02 ? "qrc:/images/display_always/night/battery_icon.png":"qrc:/images/display_always/day/battery_icon.png"
                                anchors.left: socProgressId.left
                                anchors.leftMargin:24
                                y: 16
                                visible: (DataSource.lowPowerBatLight === 0 || DataSource.lowPowerBatLight === 2) ? true : false
                                cache: false
                            }
                            WarningLight{
                                id: batteryWarningLightId
                                anchors.left: socProgressId.left
                                anchors.leftMargin:24
                                width: batteryWarningLightImageId.width
                                y: 16
                                displayState: DataSource.lowPowerBatLight
                                Image{
                                    id: batteryWarningLightImageId
                                    source: "qrc:/common/images/warninglight/lowBat.png"
                                }
                            }

                            Text {
                                id: socVauleId
                                anchors.bottom: socUnitId.bottom
                                anchors.bottomMargin: -8
                                font.pixelSize: 42
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff" : "#333333"
                                visible: DataSource.batteryMileMode === 2 ? true : false
                                text: DataSource.powerBatterySoc === 0xFFE ? " ": DataSource.powerBatterySoc
                                anchors.right: socProgressId.right
                                anchors.rightMargin: DataSource.language === 0x18 ? 8 : 33
                            }

                            Text {
                                id: socUnitId
                                text: "%"
                                LayoutMirroring.enabled: (DataSource.language === 0x18) ? true : false
                                anchors.left: socVauleId.right
                                anchors.leftMargin: 4
                                y: 18
                                visible: socVauleId.visible
                                font.pixelSize: 27
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                                opacity: 0.75
                            }

                            Text {
                                id: socMileId
                                anchors.bottom: socMileUnitId.bottom
                                anchors.bottomMargin: -5
                                font.pixelSize: 42
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                                visible: DataSource.batteryMileMode === 1 ? true : false
                                text: DataSource.socMile
                                anchors.right: socMileUnitId.left
                                anchors.rightMargin: 5
                            }

                            Text {
                                id: socMileUnitId
                                y: 18
                                anchors.right: socProgressId.right
                                anchors.rightMargin: 8
                                visible: socMileId.visible
                                font.pixelSize: 27
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                                opacity: 0.75
                                text: DataSource.odoUnit !== 2 ? "km" : (CustomStyle.isSingular(DataSource.socMile) ? "mile" : "miles")
                            }

                            ProgressBar{
                                id: socProgressId
                                x: 0
                                y: 52
                                width: 291
                                height: 11
                                background: Image {
                                    cache: false
                                    source: DataSource.dayNightMode === 0x02 ?"qrc:/images/display_always/night/bg_progress.png":"qrc:/images/display_always/day/bg_progress.png"
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
                                source: "qrc:/images/display_always/low_soc_fuel.png"
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
                        anchors.horizontalCenterOffset: 200 + 3
                        Item{
                            anchors.fill: parent
                            LayoutMirroring.enabled: driveRangeInfoContainerId.mirror
                            LayoutMirroring.childrenInherit: true

                            Image {
                                id: fuelLightId
                                source: DataSource.dayNightMode === 0x02 ?"qrc:/images/display_always/night/fuel_icon.png":"qrc:/images/display_always/day/fuel_icon.png"
                                anchors.left: fuelProgressId.left
                                anchors.leftMargin:21
                                y: 16
                                cache: false
                                visible: (DataSource.lowFuelLight === 0 || DataSource.lowFuelLight === 2) ? true : false
                            }
                            WarningLight{
                                id: fuelWarningLightId
                                anchors.left: fuelProgressId.left
                                anchors.leftMargin:21
                                width: fuelWarningLightImageId.width
                                y: 16
                                displayState: DataSource.lowFuelLight
                                Image{
                                    id: fuelWarningLightImageId
                                    source: "qrc:/common/images/warninglight/lowFuelLight.png"
                                }
                            }

                            Text {
                                id: fuelMileId
                                anchors.bottom: fuelMileUnitId.bottom
                                anchors.bottomMargin: -5
                                font.pixelSize: 42
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                                visible: DataSource.fuelMile !== "0" ? true : false
                                text: DataSource.fuelMile
                                anchors.right: fuelMileUnitId.left
                                anchors.rightMargin: 5
                            }

                            Text {
                                id: fuelMileUnitId
                                y: 18
                                anchors.right: fuelProgressId.right
                                anchors.rightMargin: 8
                                visible: fuelMileId.visible
                                font.pixelSize: 27
                                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                                opacity: 0.75
                                text: DataSource.odoEvUnit !== 2 ? "km" : "miles"
                            }

                            ProgressBar{
                                id: fuelProgressId
                                x: 0
                                y: 52
                                width: 291
                                height: 11
                                background: Image {
                                    cache: false
                                    source: DataSource.dayNightMode === 0x02 ?"qrc:/images/display_always/night/bg_progress.png":"qrc:/images/display_always/day/bg_progress.png"
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
                                source: "qrc:/images/display_always/low_soc_fuel.png"
                                anchors.bottom: fuelProgressId.bottom
                                anchors.left: fuelProgressId.left
                                cache: false
                            }
                        }
                    }


                }

                Item {
                    anchors.fill: parent
                    LayoutMirroring.enabled: driveRangeInfoContainerId.mirror
                    LayoutMirroring.childrenInherit: true

                    //ev电量表
                    Item{
                        id:evDriveRangeInfoId
                        anchors.fill: parent
                        visible: DataSource.vehicleType !== DataSource.CAR_TYPE_EV ? false : true

                        //电续驶里程
                        Image {
                            id: evBatteryLightId
                            source: DataSource.dayNightMode === 0x02 ? "qrc:/images/display_always/night/battery_icon.png":"qrc:/images/display_always/day/battery_icon.png"
                            anchors.left: parent.left
                            anchors.leftMargin: 108+49
                            y: 18
                            visible: (DataSource.lowPowerBatLight === 0 || DataSource.lowPowerBatLight === 2) ? true : false
                            cache: false
                        }
                        WarningLight{
                            id: evBatteryWarningLightId
                            anchors.left: parent.left
                            anchors.leftMargin: 108+49
                            width: evBatteryWarningLightImageId.width
                            y: 18
                            displayState: DataSource.lowPowerBatLight
                            Image{
                                id: evBatteryWarningLightImageId
                                source: "qrc:/common/images/warninglight/lowBat.png"
                            }
                        }

                        Text {
                            id: evSocVauleId
                            anchors.left: evBatteryLightId.right
                            anchors.leftMargin: 40
                            anchors.bottom: evSocUnitId.bottom
                            anchors.bottomMargin: -4
                            font.pixelSize: 42
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
                            y: 19
                            visible: evSocVauleId.visible
                            font.pixelSize: 27
                            color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                            opacity: 0.75
                        }

                        Text {
                            id: evSocMileId
                            anchors.bottom: evSocMileUnitId.bottom
                            anchors.bottomMargin: -4
                            font.pixelSize: 42
                            color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                            text: DataSource.socMile
                            anchors.right: evSocMileUnitId.left
                            anchors.rightMargin: 5
                            visible: DataSource.socMile !== "1000" ? true : false
                        }

                        Text {
                            id: evSocMileUnitId
                            y: 19
                            anchors.right: evSocProgressId.right
                            anchors.rightMargin: 37
                            visible: evSocMileId.visible
                            font.pixelSize: 27
                            color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                            opacity: 0.75
                            text: DataSource.odoUnit !== 2 ? "km" : (CustomStyle.isSingular(DataSource.socMile) ? "mile" : "miles")
                        }

                        Text {
                            id: evMileStandardId
                            anchors.right: evSocMileId.left
                            anchors.rightMargin: 10
                            anchors.bottom: evSocMileUnitId.bottom
                            anchors.bottomMargin: 1
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
                            anchors.leftMargin: 84+46
                            y: 52
                            width: 600
                            height: 11
                            background: Image {
                                cache: false
                                source: "qrc:/images/display_always/bg_progress_ev.png"
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
                            source: "qrc:/images/display_always/low_soc_fuel.png"
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
            x: driveRangeInfoContainerId.mirror ?1420 : 1430
            width: 310
            LayoutMirroring.enabled: driveRangeInfoContainerId.mirror
            LayoutMirroring.childrenInherit: true

            anchors.bottom: parent.bottom
            anchors.bottomMargin: 46

            Text {
                id: odoNameId
                anchors.bottom: parent.bottom
                anchors.bottomMargin: DataSource.language !== 1 ? -2 : 0
                anchors.left: parent.left
                font.pixelSize: 22
                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#333333"
                textFormat: (DataSource.language === 0x01 || DataSource.language === 0x03) ? Text.PlainText : Text.RichText
                visible: DataSource.odoIndex !== 0 ? true : false
                states:[
                    State {
		    //laijunhao 20240813 for PR:http://dlc.coding.byd.com/p/dilink-6125f/bug-tracking/issues/517433/detail
                    //中文繁体和印地语的里程名称显示PC和台架显示不一致，并且台架显示与数值没有对齐，以台架为准进行微调，调整后会存在这两种语言在PC端不对齐的情况
            //qiuzihao 20250429 经产品盛学瑞确认，常显里程使用英文，不区分小语种  PR: http://dlc.coding.byd.com/p/dilink-6125f/bug-tracking/issues/539572/detail
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
                anchors.bottomMargin: -7
                anchors.left: odoNameId.right
                anchors.leftMargin: 17
                font.pixelSize: 37
                visible: DataSource.odoIndex !== 0 ? true : false
                color: DataSource.dayNightMode === 0x02 ? "#ffffff":"#121212"
                text: DataSource.odoValue
            }

            Text {
                id: totalOdoUnitId
                anchors.bottom: parent.bottom
                anchors.bottomMargin: -3
                anchors.left: odoValueId.right
                anchors.leftMargin: 10
                visible: odoValueId.visible && DataSource.odoIndex === 1
                font.pixelSize: 25
                color: DataSource.dayNightMode === 0x02 ? "#b3ffffff":"#121212"
                text: DataSource.odoUnit !== 2 ? "km" : (CustomStyle.isSingular(DataSource.odoValue) ? "mile" : "miles")
            }

            Text {
                id: odo1UnitId
                anchors.left: odoValueId.right
                anchors.leftMargin: 10
                visible: odoValueId.visible && (DataSource.odoIndex === 2 || DataSource.odoIndex === 3)
                font.pixelSize: 25
                color: DataSource.dayNightMode === 0x02 ? "#b3ffffff":"#121212"
                text: DataSource.odo1Unit !== 2 ? "km" : "miles"
                anchors.bottom: parent.bottom
                anchors.bottomMargin: -3
            }

            Text {
                id: odoEvUnitId
                anchors.left: odoValueId.right
                anchors.leftMargin: 10
                visible: odoValueId.visible && (DataSource.odoIndex === 4 || DataSource.odoIndex === 5)
                font.pixelSize: 22 //25
                color: DataSource.dayNightMode === 0x02 ? "#b3ffffff":"#121212"
                text: DataSource.odoEvUnit !== 2 ? "km" : (CustomStyle.isSingular(DataSource.odoValue) ? "mile":"miles")
                anchors.bottom: parent.bottom
                anchors.bottomMargin: -3
            }
        }

        //        //功率表盘
        //        Loader{
        //            visible: true
        //            id:powerDashBoardId
        //            x:DataSource.themeIndex === 0x02?7:106//204
        //            y:DataSource.themeIndex === 0x02?55:95//176
        //            source:DataSource.themeIndex === 0x02?"qrc:/qml/alwaysDisplay/PowerDashBoardScience.qml":"qrc:/qml/alwaysDisplay/PowerDashBoardClassic.qml"
        //        }

        //        //速度表盘
        //        Loader{
        //            visible: true
        //            id:speedDashBoardId
        //            x:DataSource.themeIndex === 0x02?1274:1087//1307
        //            y:DataSource.themeIndex === 0x02?54:95//185
        //            source:DataSource.themeIndex === 0x02?"qrc:/qml/alwaysDisplay/SpeedDashBoardScience.qml":"qrc:/qml/alwaysDisplay/SpeedDashBoardClassic.qml"
        //        }

        Item {
            id: naviPowerId
            visible: DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? true : false

            Text {
                id: powerValueId
                text: DataSource.power
                color: DataSource.dayNightMode === 0x02?"#ffffff":"#333333"
                font.pixelSize: 84
                //font.family: CustomStyle.widthFontFamily

                x:247
                y:565
                width:91
                height:63
                horizontalAlignment: Text.AlignRight
                verticalAlignment: Text.AlignVCenter

                visible: DataSource.power === 1001 ? false : true
            }
            Text {
                id: powerUnitId
                text: DataSource.powerUnit === 2 ? "HP" : "kW"
                color: DataSource.dayNightMode === 0x02?"#ffffff":"#333333"
                font.pixelSize: 36

                x:353
                y:600
                width:52
                height:28
                horizontalAlignment: Text.AlignLeft
                verticalAlignment: Text.AlignVCenter

                visible: DataSource.power === 1001 ? false : true
            }
        }

        Item {
            id: naviSpeedId
            visible: DataSource.naviType === DataSource.NAVI_TYPE_FULL_SCREEN ? true : false

            Text {
                id: speedValueId

                x:1501
                y:564
                width:93
                height:63
                horizontalAlignment: Text.AlignRight
                verticalAlignment: Text.AlignVCenter

                text: DataSource.speed
                color: DataSource.dayNightMode === 0x02?"#ffffff":"#333333"
                font.pixelSize: 84
                visible: DataSource.speed === -1 ? false : true
            }
            Text {
                id: speedUnitId

                x:1608
                y:594
                width:84
                height:33
                horizontalAlignment: Text.AlignLeft
                verticalAlignment: Text.AlignVCenter

                text: DataSource.speedUnit === 2 ? "mph" : "km/h"
                color: DataSource.dayNightMode === 0x02?"#ffffff":"#333333"
                font.pixelSize: 36
                visible: DataSource.speed === -1 ? false : true
            }
        }
    }
    Image {
        id: adasWindowBackground
        x:1206.5
        y:385
        source: DataSource.dayNightMode === 0x01?"qrc:/images/adas2d/window_light_new.png":"qrc:/images/adas2d/window_night_new.png"
        cache: false
        visible: DataSource.adasPlatform === 0x1?(DataSource.adasWindow === 0x2 ?true:false)
                                                :(DataSource.adasInterfaceDisplay === 0x02 && DataSource.adasType === 0)?true:false
    }
    Image {
        id: adas2dNaviWindowBg
        x:1256
        y:225
        visible: DataSource.adasPlatform === 0x1?((DataSource.naviType === 0x4 && DataSource.adasWindow === 0x1)?true:false)
                                                :((DataSource.naviType === 0x4 && DataSource.adasInterfaceDisplay === 1) ? true:false)
        source: DataSource.dayNightMode === 0x02?"qrc:/images/warningInfo/night_bg.png":"qrc:/images/warningInfo/day_bg.png"
    }
    Rectangle{
        id:adasContainerParentId
        x:1278-15
        y:439
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
            x: -465
            y: -188
            z:3
            scale: 0.39
            source: DataSource.adasPlatform === 0x1?((DataSource.adasWindow === 0x2 && DataSource.powerGear === 3 && DataSource.playAnim)?"qrc:/qml/adas2d/Adas3R1V.qml":"")
                                                   :(DataSource.adasInterfaceDisplay === 0x02 && DataSource.adasType === 0 && DataSource.powerGear === 3 && DataSource.playAnim)? "qrc:/qml/adas2d/Adas.qml":""
            onStatusChanged: {
                if (adasLoaderId.status === Loader.Ready){
                    adasLoaderId.visible = DataSource.adasPlatform === 0x1?(DataSource.adasWindow === 0x2?true:false)
                                                                          :((DataSource.adasInterfaceDisplay === 0x02)?true:false)
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
        x:1206.5//-15是由于背景切图有部分留白
        y:384.87
        Image {
            visible: true
            source: DataSource.dayNightMode === 0x01?"qrc:/images/adas2d/window_light_new.png":"qrc:/images/adas2d/window_night_new.png"
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
            source:(DataSource.carBodyType === 0 ? "qrc:/images/adas2d/car/acc_" :"qrc:/images/adas2d/suv/acc_") + DataSource.carDistanceLevel + ".png"
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
        source: DataSource.dilinkName === 0x02?"qrc:/qml/alwaysDisplay/Presentation.qml":""
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
        source: DataSource.selfStudyResultShow === 1 ||  DataSource.adasDebugMode === 1?"qrc:/qml/adas2d/AdasDebug.qml":""
    }

    /**
    ADAS文字信息
    */
    Item{
        id:adasTipItemId
        anchors.horizontalCenter: parent.horizontalCenter
        //y:520-4
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 160
        z:111
        width:Math.min(adasTipText.contentWidth +14+4,adasTipText.width+24)
        //height: 42+4
        height: adasTipText.contentHeight + 14
        BorderImage{
            id:adasTipTextBg
            source:DataSource.dayNightMode === 0x1 ? "qrc:/images/adas2d/pop-light.png" : "qrc:/images/adas2d/pop-night.png"
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
            //            anchors.baseline: parent.bottom
            //            anchors.baselineOffset: - ((42+4) / 3 - 3.5)
            textFormat:DataSource.language === 0xE || DataSource.language === 0x15?Text.RichText:Text.AutoText
            wrapMode: DataSource.language === 0xE || DataSource.language === 0x15? Text.NoWrap : Text.WordWrap
            width: DataSource.language === 0xE || DataSource.language === 0x15? (adasTipText.implicitWidth) :800
            horizontalAlignment: Text.AlignHCenter  // 水平居中
            verticalAlignment: Text.AlignVCenter    // 垂直居中
        }
    }


    Text {
        id: adasTextTip3R1V
        y:520+12
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

    //交通指示灯
    //    Item{
    //        x:DataSource.naviType === 4 ? 1167-17.5:(DataSource.dilinkName === 1 ?(DataSource.themeIndex === 0x2?1150+100:622):1150)//(DataSource.themeIndex === 0x2?(DataSource.dayNightMode===0x02?1150+17.5:1150)/*1160+5*/:(DataSource.dayNightMode===0x02?1150+17.5:1150)))
    //        y:DataSource.naviType === 4 ? 167-6:(DataSource.dilinkName === 1 ?(DataSource.themeIndex === 0x2?174:218):(DataSource.themeIndex === 0x2?174:164))
    //        width: 187
    //        height: 59
    //        z:2
    //        visible: DataSource.trafficSignalStatus === 0x1?true:false
    //        //        Image{
    //        //            source:"qrc:/common/images/warninglight/lightBg.png"
    //        //        }
    //        Image{
    //            source:DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/light_dark.png":"qrc:/common/images/warninglight/light_light.png"
    //        }
    //        Image{
    //            id:trafficLightLeftBgId
    //            visible: true
    //            x:17.5+6.7
    //            y:4.75+10.55/2
    //            source: {
    //                switch(DataSource.leftSignalStatus)
    //                {
    //                case 1:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/baseGreen_dark.png":"qrc:/common/images/warninglight/baseGreen_light.png"
    //                    break
    //                case 2:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/baseYellow_dark.png":"qrc:/common/images/warninglight/baseYellow_light.png"
    //                    break
    //                case 3:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/baseRed_dark.png":"qrc:/common/images/warninglight/baseRed_light.png"
    //                    break
    //                default:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/base_dark.png":"qrc:/common/images/warninglight/base_light.png"
    //                    break
    //                }
    //            }
    //        }
    //        Image{
    //            id:trafficLightStraightBgId
    //            visible: true
    //            x:trafficLightLeftBgId.x+40+10.35//62
    //            y:trafficLightLeftBgId.y//3.83
    //            source: {
    //                switch(DataSource.straightSignalStatus)
    //                {
    //                case 1:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/baseGreen_dark.png":"qrc:/common/images/warninglight/baseGreen_light.png"
    //                    break
    //                case 2:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/baseYellow_dark.png":"qrc:/common/images/warninglight/baseYellow_light.png"
    //                    break
    //                case 3:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/baseRed_dark.png":"qrc:/common/images/warninglight/baseRed_light.png"
    //                    break
    //                default:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/base_dark.png":"qrc:/common/images/warninglight/base_light.png"
    //                    break
    //                }
    //            }
    //        }
    //        Image{
    //            id:trafficLightRightBgId
    //            visible: true
    //            x:trafficLightStraightBgId.x+40+8.35//106//116
    //            y:trafficLightLeftBgId.y//3.83
    //            source: {
    //                switch(DataSource.rightSignalStatus)
    //                {
    //                case 1:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/baseGreen_dark.png":"qrc:/common/images/warninglight/baseGreen_light.png"
    //                    break
    //                case 2:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/baseYellow_dark.png":"qrc:/common/images/warninglight/baseYellow_light.png"
    //                    break
    //                case 3:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/baseRed_dark.png":"qrc:/common/images/warninglight/baseRed_light.png"
    //                    break
    //                default:
    //                    DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/base_dark.png":"qrc:/common/images/warninglight/base_light.png"
    //                    break
    //                }
    //            }
    //        }
    //        WarningLight{
    //            id:trafficLightLeftId
    //            visible: true
    //            x:trafficLightLeftBgId.x+4//12
    //            y:trafficLightLeftBgId.y+5//7.83
    //            displayState: DataSource.leftSignalDisplayStatus === 0x2 || DataSource.leftSignalDisplayStatus === 0x3 ? 6 : DataSource.leftSignalDisplayStatus
    //            flashCompletedShow: DataSource.leftSignalDisplayStatus === 0x2 ? true : false
    //            flashCount: 2+1
    //            Image{
    //                source: {
    //                    switch(DataSource.leftSignalStatus)
    //                    {
    //                    case 1:
    //                        DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/greenLeft_dark.png":"qrc:/common/images/warninglight/greenLeft_light.png"
    //                        break
    //                    case 2:
    //                        DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/yellowLeft_dark.png":"qrc:/common/images/warninglight/yellowLeft_light.png"
    //                        break
    //                    case 3:
    //                        DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/redLeft_dark.png":"qrc:/common/images/warninglight/redLeft_light.png"
    //                        break
    //                    default:
    //                        ""
    //                        break
    //                    }
    //                }
    //            }
    //        }
    //        WarningLight{
    //            id:trafficLightStraightId
    //            visible: true
    //            x:trafficLightStraightBgId.x+4/*68*///21
    //            y:trafficLightLeftBgId.y+5//8.83
    //            displayState: DataSource.straightSignalDisplayStatus === 0x2 || DataSource.straightSignalDisplayStatus === 0x3 ? 6 : DataSource.straightSignalDisplayStatus
    //            flashCompletedShow: DataSource.straightSignalDisplayStatus === 0x2 ? true : false
    //            flashCount: 2+1
    //            Image{
    //                source: {
    //                    switch(DataSource.straightSignalStatus)
    //                    {
    //                    case 1:
    //                        DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/greenLight_dark.png":"qrc:/common/images/warninglight/greenLight_light.png"
    //                        break
    //                    case 2:
    //                        DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/yellowLight_dark.png":"qrc:/common/images/warninglight/yellowLight_light.png"
    //                        break
    //                    case 3:
    //                        DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/redLight_dark.png":"qrc:/common/images/warninglight/redLight_light.png"
    //                        break
    //                    default:
    //                        ""
    //                        break
    //                    }
    //                }
    //            }
    //        }
    //        WarningLight{
    //            id:trafficLightRightId
    //            visible: true
    //            x:trafficLightRightBgId.x+4//118//36.81
    //            y:trafficLightLeftBgId.y+5//7.83
    //            displayState: DataSource.rightSignalDisplayStatus === 0x2 || DataSource.rightSignalDisplayStatus === 0x3 ? 6 : DataSource.rightSignalDisplayStatus
    //            flashCompletedShow: DataSource.rightSignalDisplayStatus === 0x2 ? true : false
    //            flashCount: 2+1
    //            Image{
    //                source: {
    //                    switch(DataSource.rightSignalStatus)
    //                    {
    //                    case 1:
    //                        DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/greenRight_dark.png":"qrc:/common/images/warninglight/greenRight_light.png"
    //                        break
    //                    case 2:
    //                        DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/yellowRight_dark.png":"qrc:/common/images/warninglight/yellowRight_light.png"
    //                        break
    //                    case 3:
    //                        DataSource.dayNightMode === 0x02?"qrc:/common/images/warninglight/redRight_dark.png":"qrc:/common/images/warninglight/redRight_light.png"
    //                        break
    //                    default:
    //                        ""
    //                        break
    //                    }
    //                }
    //            }
    //        }
    //    }

    //ADAS Text Info
    function textInfoUpdate(){
        if(DataSource.powerGear !== 3 || DataSource.adasPlatform === 0x1){
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
            //交通标志识别已关闭
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
        case 0x5B:
            //请确认是否调整车速至前方限速值
            adasTipText.text = TranslateStr.adasTip_0x5B
            break
        case 0x5C:
            //无法激活车道保持巡航，拖车尺寸超限
            adasTipText.text = TranslateStr.adasTip_0x5C
            break
        case 0x5D:
            //车道保持巡航已激活，带拖车行驶请注意安全
            adasTipText.text = TranslateStr.adasTip_0x5D
            break
        case 0x5E:
            //前车已驶离
            adasTipText.text = TranslateStr.adasTip_0x5E
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
        if(DataSource.adasPlatform === 0x1){
            //adas3dContainerId.visible = false
            adasContainerParentId.z = 3
            console.log("updateAdasContainer() return, z=", adasContainerParentId.z)
            return
        }

        if(DataSource.powerGear !== 3){
            //非ON挡下让ADAS小窗口显示在背景下面，让3D ADAS在用户不知情的情况下提前加载，避免切换ON后有因为ADAS加载导致卡顿的情况，影响用户体验
            adasContainerParentId.z = -10
            //adas3dContainerId.visible = true
        }else
        {
            adasContainerParentId.z = 3
            //adas3dContainerId.visible =(DataSource.adasInterfaceDisplay === 0x02 && DataSource.adasType === 1) ? true:false
        }
        console.log("updateAdasContainer(), z=", adasContainerParentId.z)
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
