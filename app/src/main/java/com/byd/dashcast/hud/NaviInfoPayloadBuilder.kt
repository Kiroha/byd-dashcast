package com.byd.dashcast.hud

import byd.fbs.naviInfo.NaviInfo
import com.google.flatbuffers.FlatBufferBuilder

/**
 * Builds the exact `byd.fbs.naviInfo.NaviInfo` FlatBuffer bytes the OEM's own navigation app
 * (`com.example.amapservice`) sends via `AutoContainer.sendInfo2(4, bytes)` to drive the
 * instrument-cluster HUD — decompiled from `AmapService.sendNaviInfoTo1for2Clster()`.
 *
 * All string fields must be created on the builder before [NaviInfo.createNaviInfo] starts the
 * object (FlatBuffers requires child strings/tables to be fully written before their parent).
 */
object NaviInfoPayloadBuilder {

    @JvmStatic
    fun build(
        naviState: Int,
        nextRouteName: String = "",
        curToSegmentDist: Int = 0,
        forwardState: String = "",
        nextTurnIcon: Int = 0,
        routeRemainTime: Int = 0,
        routeRemainDist: Int = 0,
        stringEtaArrivalTime: String = "",
        exitNameInfo: String = "",
        exitDirectionInfo: String = "",
        routrRemainDisAuto: String = "",
        routrRemainTimeAuto: String = "",
        segRemainDisAuto: String = "",
        nextNextTurnIcon: Int = 0,
        nextToSegmentDist: Int = 0,
        nextNextRouteName: String = "",
        roungAboutNum: Int = 0,
        nextRoungAboutNum: Int = 0,
    ): ByteArray {
        val b = FlatBufferBuilder(256)
        val nextRouteNameOff = b.createString(nextRouteName)
        val forwardStateOff = b.createString(forwardState)
        val stringEtaArrivalTimeOff = b.createString(stringEtaArrivalTime)
        val exitNameInfoOff = b.createString(exitNameInfo)
        val exitDirectionInfoOff = b.createString(exitDirectionInfo)
        val routrRemainDisAutoOff = b.createString(routrRemainDisAuto)
        val routrRemainTimeAutoOff = b.createString(routrRemainTimeAuto)
        val segRemainDisAutoOff = b.createString(segRemainDisAuto)
        val nextNextRouteNameOff = b.createString(nextNextRouteName)
        val root = NaviInfo.createNaviInfo(
            b, naviState, nextRouteNameOff, curToSegmentDist, forwardStateOff,
            nextTurnIcon, routeRemainTime, routeRemainDist, stringEtaArrivalTimeOff,
            exitNameInfoOff, exitDirectionInfoOff, routrRemainDisAutoOff,
            routrRemainTimeAutoOff, segRemainDisAutoOff, nextNextTurnIcon,
            nextToSegmentDist, nextNextRouteNameOff, roungAboutNum, nextRoungAboutNum
        )
        b.finish(root)
        return b.sizedByteArray()
    }
}
