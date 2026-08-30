package com.byd.dashcast.system

/** Immutable, ordered CAN write used by both Binder batching and legacy fallback. */
class CanBatchOperation private constructor(
    val type: Int,
    val featureId: Int,
    val intValue: Int,
    rawBytes: ByteArray?,
) {

    init {
        require(type in TYPE_NAVI_STATUS..TYPE_SETTING_INT) { "unknown CAN operation type $type" }
    }

    private val bytes: ByteArray? = rawBytes?.copyOf()

    interface Writer {
        @Throws(Throwable::class) fun setNaviStatus(status: Int): Int
        @Throws(Throwable::class) fun setInstrumentInt(featureId: Int, value: Int): Int
        @Throws(Throwable::class) fun setInstrumentBytes(featureId: Int, bytes: ByteArray): Int
        @Throws(Throwable::class) fun setSettingInt(featureId: Int, value: Int): Int
    }

    fun getBytes(): ByteArray? = bytes?.copyOf()

    @Throws(Throwable::class)
    fun execute(writer: Writer): Int =
        when (type) {
            TYPE_NAVI_STATUS -> writer.setNaviStatus(intValue)
            TYPE_INSTRUMENT_INT -> writer.setInstrumentInt(featureId, intValue)
            TYPE_INSTRUMENT_BYTES -> writer.setInstrumentBytes(featureId, getBytes() ?: ByteArray(0))
            TYPE_SETTING_INT -> writer.setSettingInt(featureId, intValue)
            else -> throw IllegalStateException("unknown CAN operation type $type")
        }

    companion object {
        const val TYPE_NAVI_STATUS = 1
        const val TYPE_INSTRUMENT_INT = 2
        const val TYPE_INSTRUMENT_BYTES = 3
        const val TYPE_SETTING_INT = 4
        const val MAX_BATCH_SIZE = 32

        @JvmStatic
        fun naviStatus(status: Int): CanBatchOperation =
            CanBatchOperation(TYPE_NAVI_STATUS, 0, status, null)

        @JvmStatic
        fun instrumentInt(featureId: Int, value: Int): CanBatchOperation =
            CanBatchOperation(TYPE_INSTRUMENT_INT, featureId, value, null)

        @JvmStatic
        fun instrumentBytes(featureId: Int, bytes: ByteArray?): CanBatchOperation =
            CanBatchOperation(TYPE_INSTRUMENT_BYTES, featureId, 0, bytes ?: ByteArray(0))

        @JvmStatic
        fun settingInt(featureId: Int, value: Int): CanBatchOperation =
            CanBatchOperation(TYPE_SETTING_INT, featureId, value, null)

        @JvmStatic
        fun fromWire(type: Int, featureId: Int, intValue: Int, bytes: ByteArray?): CanBatchOperation =
            CanBatchOperation(type, featureId, intValue, bytes)

        /** Executes an ordered prefix and stops before the first SDK-rejected write. */
        @JvmStatic
        @Throws(Throwable::class)
        fun executeAcceptedPrefix(operations: List<CanBatchOperation>, writer: Writer): Int {
            var applied = 0
            for (operation in operations) {
                if (operation.execute(writer) != 0) break
                applied++
            }
            return applied
        }
    }
}
