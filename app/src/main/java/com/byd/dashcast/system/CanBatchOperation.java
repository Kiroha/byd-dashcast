package com.byd.dashcast.system;

import java.util.Arrays;

/** Immutable, ordered CAN write used by both Binder batching and legacy fallback. */
public final class CanBatchOperation {

    public static final int TYPE_NAVI_STATUS = 1;
    public static final int TYPE_INSTRUMENT_INT = 2;
    public static final int TYPE_INSTRUMENT_BYTES = 3;
    public static final int TYPE_SETTING_INT = 4;
    public static final int MAX_BATCH_SIZE = 32;

    public interface Writer {
        void setNaviStatus(int status) throws Throwable;
        void setInstrumentInt(int featureId, int value) throws Throwable;
        void setInstrumentBytes(int featureId, byte[] bytes) throws Throwable;
        void setSettingInt(int featureId, int value) throws Throwable;
    }

    private final int type;
    private final int featureId;
    private final int intValue;
    private final byte[] bytes;

    private CanBatchOperation(int type, int featureId, int intValue, byte[] bytes) {
        if (type < TYPE_NAVI_STATUS || type > TYPE_SETTING_INT) {
            throw new IllegalArgumentException("unknown CAN operation type " + type);
        }
        this.type = type;
        this.featureId = featureId;
        this.intValue = intValue;
        this.bytes = bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
    }

    public static CanBatchOperation naviStatus(int status) {
        return new CanBatchOperation(TYPE_NAVI_STATUS, 0, status, null);
    }

    public static CanBatchOperation instrumentInt(int featureId, int value) {
        return new CanBatchOperation(TYPE_INSTRUMENT_INT, featureId, value, null);
    }

    public static CanBatchOperation instrumentBytes(int featureId, byte[] bytes) {
        return new CanBatchOperation(TYPE_INSTRUMENT_BYTES, featureId, 0,
                bytes == null ? new byte[0] : bytes);
    }

    public static CanBatchOperation settingInt(int featureId, int value) {
        return new CanBatchOperation(TYPE_SETTING_INT, featureId, value, null);
    }

    public static CanBatchOperation fromWire(int type, int featureId, int intValue, byte[] bytes) {
        return new CanBatchOperation(type, featureId, intValue, bytes);
    }

    public int getType() { return type; }
    public int getFeatureId() { return featureId; }
    public int getIntValue() { return intValue; }
    public byte[] getBytes() { return bytes == null ? null : Arrays.copyOf(bytes, bytes.length); }

    public void execute(Writer writer) throws Throwable {
        switch (type) {
            case TYPE_NAVI_STATUS:
                writer.setNaviStatus(intValue);
                return;
            case TYPE_INSTRUMENT_INT:
                writer.setInstrumentInt(featureId, intValue);
                return;
            case TYPE_INSTRUMENT_BYTES:
                writer.setInstrumentBytes(featureId, getBytes());
                return;
            case TYPE_SETTING_INT:
                writer.setSettingInt(featureId, intValue);
                return;
            default:
                throw new IllegalStateException("unknown CAN operation type " + type);
        }
    }
}
