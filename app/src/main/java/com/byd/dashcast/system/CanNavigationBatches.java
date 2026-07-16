package com.byd.dashcast.system;

import com.byd.dashcast.proxy.daemon.CanWriteVerbs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact legacy CAN operation groups, expressed as immutable ordered batch records. */
public final class CanNavigationBatches {

    private CanNavigationBatches() {}

    public static List<CanBatchOperation> navigationState(boolean active) {
        List<CanBatchOperation> operations = new ArrayList<>(active ? 2 : 9);
        operations.add(CanBatchOperation.naviStatus(active
                ? CanWriteVerbs.NAVI_STATUS_ACTIVE : CanWriteVerbs.NAVI_STATUS_STOPPED));
        if (active) {
            operations.add(CanBatchOperation.settingInt(
                    CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS, 3));
        } else {
            operations.add(CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE, 0));
            operations.add(CanBatchOperation.instrumentInt(
                    CanWriteVerbs.INSTRUMENT_GUIDE_ROAD_DISTANCE, 0));
            operations.add(CanBatchOperation.instrumentInt(
                    CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST, -1));
            operations.add(CanBatchOperation.instrumentBytes(
                    CanWriteVerbs.INSTRUMENT_NEXT_PATHNAME, new byte[0]));
            operations.add(CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MILEAGE, -1));
            operations.add(CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_HOUR, 0));
            operations.add(CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MINUTE, 0));
            operations.add(CanBatchOperation.instrumentInt(
                    CanWriteVerbs.INSTRUMENT_NAVI_REMAINING_SEC, 0));
        }
        return operations;
    }

    public static List<CanBatchOperation> simpleGuidance(int turnIconId, int distanceMeters) {
        return Arrays.asList(
                CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE, turnIconId),
                CanBatchOperation.instrumentInt(
                        CanWriteVerbs.INSTRUMENT_GUIDE_ROAD_DISTANCE, turnIconId),
                CanBatchOperation.instrumentInt(
                        CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST, distanceMeters));
    }

    public static List<CanBatchOperation> secondaryGuidance(int iconId, int distance) {
        return Arrays.asList(
                CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_LEAD_MSG, iconId),
                CanBatchOperation.instrumentInt(
                        CanWriteVerbs.INSTRUMENT_DISTANCE_TARGET_AHEAD, distance));
    }

    public static List<CanBatchOperation> restRoute(int restHour, int restMinute, long restMileage) {
        return Arrays.asList(
                CanBatchOperation.instrumentInt(
                        CanWriteVerbs.INSTRUMENT_NAVI_MILEAGE, (int) restMileage),
                CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_HOUR, restHour),
                CanBatchOperation.instrumentInt(CanWriteVerbs.INSTRUMENT_NAVI_MINUTE, restMinute),
                CanBatchOperation.instrumentInt(
                        CanWriteVerbs.INSTRUMENT_NAVI_REMAINING_SEC, 0));
    }
}
