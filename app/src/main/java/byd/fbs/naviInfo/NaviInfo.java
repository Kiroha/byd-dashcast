package byd.fbs.naviInfo;

import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers accessor/builder for {@code byd.fbs.naviInfo.NaviInfo} — the OEM navigation-guidance
 * struct sent to the instrument-cluster HUD via {@code AutoContainer.sendInfo2(4, bytes)}.
 *
 * <p>Vendored (not hand-written) from the schema-generated class decompiled out of the OEM
 * {@code com.example.amapservice} APK, where {@code AmapService.sendNaviInfoTo1for2Clster()}
 * builds this exact struct before calling {@code sendInfo2}. Field order and vtable offsets below
 * are the wire contract the OEM's own cluster renderer expects — do not reorder the {@code add*}
 * calls in {@link #createNaviInfo}, that would silently corrupt every field after the swap.
 */
public final class NaviInfo extends Table {

    public static NaviInfo getRootAsNaviInfo(ByteBuffer bb) {
        return getRootAsNaviInfo(bb, new NaviInfo());
    }

    public static NaviInfo getRootAsNaviInfo(ByteBuffer bb, NaviInfo obj) {
        bb.order(ByteOrder.LITTLE_ENDIAN);
        return obj.__assign(bb.getInt(bb.position()) + bb.position(), bb);
    }

    public void __init(int i, ByteBuffer byteBuffer) {
        // Must go through the base Table.__reset(int, ByteBuffer), not raw field assignment: this
        // build of the FlatBuffers runtime caches vtable_start/vtable_size in __reset(), and every
        // __offset() lookup silently returns "field not found" if that cache is never populated.
        __reset(i, byteBuffer);
    }

    public NaviInfo __assign(int i, ByteBuffer byteBuffer) {
        __init(i, byteBuffer);
        return this;
    }

    public int naviState() {
        int o = __offset(4);
        return o != 0 ? bb.getInt(o + bb_pos) : 0;
    }

    public String nextRouteName() {
        int o = __offset(6);
        return o != 0 ? __string(o + bb_pos) : null;
    }

    public int curToSegmentDist() {
        int o = __offset(8);
        return o != 0 ? bb.getInt(o + bb_pos) : 0;
    }

    public String forwardState() {
        int o = __offset(10);
        return o != 0 ? __string(o + bb_pos) : null;
    }

    public int nextTurnIcon() {
        int o = __offset(12);
        return o != 0 ? bb.getInt(o + bb_pos) : 0;
    }

    public int routeRemainTime() {
        int o = __offset(14);
        return o != 0 ? bb.getInt(o + bb_pos) : 0;
    }

    public int routeRemainDist() {
        int o = __offset(16);
        return o != 0 ? bb.getInt(o + bb_pos) : 0;
    }

    public String stringEtaArrivalTime() {
        int o = __offset(18);
        return o != 0 ? __string(o + bb_pos) : null;
    }

    public String exitNameInfo() {
        int o = __offset(20);
        return o != 0 ? __string(o + bb_pos) : null;
    }

    public String exitDirectionInfo() {
        int o = __offset(22);
        return o != 0 ? __string(o + bb_pos) : null;
    }

    public String routrRemainDisAuto() {
        int o = __offset(24);
        return o != 0 ? __string(o + bb_pos) : null;
    }

    public String routrRemainTimeAuto() {
        int o = __offset(26);
        return o != 0 ? __string(o + bb_pos) : null;
    }

    public String SegRemainDisAuto() {
        int o = __offset(28);
        return o != 0 ? __string(o + bb_pos) : null;
    }

    public int nextNextTurnIcon() {
        int o = __offset(30);
        return o != 0 ? bb.getInt(o + bb_pos) : 0;
    }

    public int nextToSegmentDist() {
        int o = __offset(32);
        return o != 0 ? bb.getInt(o + bb_pos) : 0;
    }

    public String nextNextRouteName() {
        int o = __offset(34);
        return o != 0 ? __string(o + bb_pos) : null;
    }

    public int roungAboutNum() {
        int o = __offset(36);
        return o != 0 ? bb.getInt(o + bb_pos) : 0;
    }

    public int nextRoungAboutNum() {
        int o = __offset(38);
        return o != 0 ? bb.getInt(o + bb_pos) : 0;
    }

    public static int createNaviInfo(FlatBufferBuilder b,
            int naviState, int nextRouteNameOff, int curToSegmentDist, int forwardStateOff,
            int nextTurnIcon, int routeRemainTime, int routeRemainDist, int stringEtaArrivalTimeOff,
            int exitNameInfoOff, int exitDirectionInfoOff, int routrRemainDisAutoOff,
            int routrRemainTimeAutoOff, int segRemainDisAutoOff, int nextNextTurnIcon,
            int nextToSegmentDist, int nextNextRouteNameOff, int roungAboutNum, int nextRoungAboutNum) {
        b.startTable(18);
        addNextRoungAboutNum(b, nextRoungAboutNum);
        addRoungAboutNum(b, roungAboutNum);
        addNextNextRouteName(b, nextNextRouteNameOff);
        addNextToSegmentDist(b, nextToSegmentDist);
        addNextNextTurnIcon(b, nextNextTurnIcon);
        addSegRemainDisAuto(b, segRemainDisAutoOff);
        addRoutrRemainTimeAuto(b, routrRemainTimeAutoOff);
        addRoutrRemainDisAuto(b, routrRemainDisAutoOff);
        addExitDirectionInfo(b, exitDirectionInfoOff);
        addExitNameInfo(b, exitNameInfoOff);
        addStringEtaArrivalTime(b, stringEtaArrivalTimeOff);
        addRouteRemainDist(b, routeRemainDist);
        addRouteRemainTime(b, routeRemainTime);
        addNextTurnIcon(b, nextTurnIcon);
        addForwardState(b, forwardStateOff);
        addCurToSegmentDist(b, curToSegmentDist);
        addNextRouteName(b, nextRouteNameOff);
        addNaviState(b, naviState);
        return endNaviInfo(b);
    }

    public static void startNaviInfo(FlatBufferBuilder b) {
        b.startTable(18);
    }

    public static void addNaviState(FlatBufferBuilder b, int naviState) {
        b.addInt(0, naviState, 0);
    }

    public static void addNextRouteName(FlatBufferBuilder b, int off) {
        b.addOffset(1, off, 0);
    }

    public static void addCurToSegmentDist(FlatBufferBuilder b, int v) {
        b.addInt(2, v, 0);
    }

    public static void addForwardState(FlatBufferBuilder b, int off) {
        b.addOffset(3, off, 0);
    }

    public static void addNextTurnIcon(FlatBufferBuilder b, int v) {
        b.addInt(4, v, 0);
    }

    public static void addRouteRemainTime(FlatBufferBuilder b, int v) {
        b.addInt(5, v, 0);
    }

    public static void addRouteRemainDist(FlatBufferBuilder b, int v) {
        b.addInt(6, v, 0);
    }

    public static void addStringEtaArrivalTime(FlatBufferBuilder b, int off) {
        b.addOffset(7, off, 0);
    }

    public static void addExitNameInfo(FlatBufferBuilder b, int off) {
        b.addOffset(8, off, 0);
    }

    public static void addExitDirectionInfo(FlatBufferBuilder b, int off) {
        b.addOffset(9, off, 0);
    }

    public static void addRoutrRemainDisAuto(FlatBufferBuilder b, int off) {
        b.addOffset(10, off, 0);
    }

    public static void addRoutrRemainTimeAuto(FlatBufferBuilder b, int off) {
        b.addOffset(11, off, 0);
    }

    public static void addSegRemainDisAuto(FlatBufferBuilder b, int off) {
        b.addOffset(12, off, 0);
    }

    public static void addNextNextTurnIcon(FlatBufferBuilder b, int v) {
        b.addInt(13, v, 0);
    }

    public static void addNextToSegmentDist(FlatBufferBuilder b, int v) {
        b.addInt(14, v, 0);
    }

    public static void addNextNextRouteName(FlatBufferBuilder b, int off) {
        b.addOffset(15, off, 0);
    }

    public static void addRoungAboutNum(FlatBufferBuilder b, int v) {
        b.addInt(16, v, 0);
    }

    public static void addNextRoungAboutNum(FlatBufferBuilder b, int v) {
        b.addInt(17, v, 0);
    }

    public static int endNaviInfo(FlatBufferBuilder b) {
        return b.endTable();
    }

    public static void finishNaviInfoBuffer(FlatBufferBuilder b, int offset) {
        b.finish(offset);
    }
}
