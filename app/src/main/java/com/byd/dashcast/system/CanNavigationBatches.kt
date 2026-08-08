package com.byd.dashcast.system

import com.byd.dashcast.proxy.daemon.CanWriteVerbs

/** Exact legacy CAN operation groups, expressed as immutable ordered batch records. */
object CanNavigationBatches {

    @JvmStatic
    fun navigationState(active: Boolean): List<CanBatchOperation> {
        val operations = ArrayList<CanBatchOperation>(if (active) 2 else 9)
        operations.add(
            CanBatchOperation.naviStatus(
                if (active) CanWriteVerbs.NAVI_STATUS_ACTIVE else CanWriteVerbs.NAVI_STATUS_STOPPED
            )
        )
        if (active) {
            operations.add(
                CanBatchOperation.settingInt(CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS, 3)
            )
        } else {
            operations.add(CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE, 0))
            operations.add(
                CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST, -1)
            )
            operations.add(
                CanBatchOperation.instrumentBytes(CanWriteVerbs.INSTRUMENT_NEXT_PATHNAME, ByteArray(0))
            )
            operations.add(CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MILEAGE, -1))
            operations.add(CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_HOUR, 0))
            operations.add(CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MINUTE, 0))
            operations.add(
                CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_REMAINING_SEC, 0)
            )
        }
        return operations
    }

    /**
     * OEM parity: the factory nav ({@code AmapService.sendNavigateInfoToCAN}) rewrites
     * {@code INSTRUMENT_SEND_NAVI_STATUS} = active on EVERY guidance frame — its only always-written
     * register. A cluster that treats it as a liveness heartbeat drops the guidance widget when it
     * stops, so re-assert it per update. Deliberately a SINGLE-register batch: unlike
     * {@link #navigationState}, it does NOT rewrite {@code SETTING_NAVI_SCREEN_STATUS} (the OEM
     * writes that only at nav-start), keeping the per-frame heartbeat minimal.
     */
    @JvmStatic
    fun navStatusHeartbeat(): List<CanBatchOperation> =
        listOf(CanBatchOperation.naviStatus(CanWriteVerbs.NAVI_STATUS_ACTIVE))

    @JvmStatic
    fun simpleGuidance(turnIconId: Int, distanceMeters: Int): List<CanBatchOperation> =
        listOf(
            // NOTE: we used to ALSO write the icon into INSTRUMENT_GUIDE_ROAD_DISTANCE (0x43F01030)
            // as a "dual-display" register. Dropped 2026-08-09: the OEM never writes it, and the
            // BYD SDK REFUSES it on many cars — "no permission to use the feature: 0x43f01030 with
            // this device: 1007!" appears 200x across the tester corpus, including on cars whose
            // arrows render fine. So it was never needed, and it only produced noise.
            CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE, turnIconId),
            CanBatchOperation.instrumentInt(
                CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST, distanceMeters
            )
        )

    @JvmStatic
    fun secondaryGuidance(iconId: Int, distance: Int): List<CanBatchOperation> =
        listOf(
            CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_LEAD_MSG, iconId),
            CanBatchOperation.instrumentInt(
                CanWriteVerbs.INSTRUMENT_DISTANCE_TARGET_AHEAD, distance
            )
        )

    @JvmStatic
    fun restRoute(restHour: Int, restMinute: Int, restMileage: Long): List<CanBatchOperation> =
        listOf(
            CanBatchOperation.instrumentInt(
                CanWriteVerbs.INSTRUMENT_NAVI_MILEAGE, restMileage.toInt()
            ),
            CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_HOUR, restHour),
            CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MINUTE, restMinute),
            CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_REMAINING_SEC, 0)
        )

    /**
     * Wall-clock ETA ("arrive at HH:MM") — OEM parity with AmapService's EXPECTED_ARRIVE_* block.
     * Distinct from {@link #restRoute} (remaining DURATION). The SECOND register is always 0 and
     * latches the day/hour/minute triple, exactly like the OEM.
     */
    @JvmStatic
    fun expectedArrival(day: Int, hour: Int, minute: Int): List<CanBatchOperation> =
        listOf(
            CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_EXPECTED_ARRIVE_DAY, day),
            CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_EXPECTED_ARRIVE_HOUR, hour),
            CanBatchOperation.instrumentInt(
                CanWriteVerbs.INSTRUMENT_EXPECTED_ARRIVE_MINUTE, minute
            ),
            CanBatchOperation.instrumentInt(
                CanWriteVerbs.INSTRUMENT_EXPECTED_ARRIVE_SECOND, 0
            )
        )
}
