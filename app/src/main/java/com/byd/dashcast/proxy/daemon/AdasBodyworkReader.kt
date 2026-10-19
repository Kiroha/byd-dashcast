package com.byd.dashcast.proxy.daemon

import android.content.Context

/** Closed read-only surface matching Electro's distinct typed and raw power queries. */
internal object AdasBodyworkReader {
    fun read(context: Context, property: Int): Int {
        require(property == 0 || property == 0x40d00010 || property == 0x12d0002a)
        // SX361 typed BodyworkDevice uses device1001; the raw monitor uses device1000.
        if (property == 0) {
            val type = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")
            val device = type.getMethod("getInstance", Context::class.java).invoke(null, context)
            return type.getMethod("getPowerLevel").invoke(device) as Int
        }
        val type = Class.forName("android.hardware.BYDAutoManager")
        val manager = type.getConstructor(Context::class.java).newInstance(context)
        return type.getMethod("getInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(manager, 1000, property) as Int
    }
}
