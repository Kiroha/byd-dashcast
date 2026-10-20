package com.byd.dashcast.proxy.daemon

import android.content.Context
import com.byd.dashcast.proxy.AdasTargetProperties

/** Read-only cache probe. Caller supplies the original system context, never a permission wrapper. */
internal object AdasTargetReader {
    private data class Backend(val manager: Any, val getInt: java.lang.reflect.Method)
    private var backend: Backend? = null
    private var initializationFailure: Throwable? = null

    @Synchronized
    fun read(context: Context, property: Int): Int {
        AdasTargetProperties.requireAllowed(property)
        initializationFailure?.let { throw it }
        // The OEM constructor initializes native state/listeners. Do it once, never once per key.
        val target = backend ?: try {
            val type = Class.forName("android.hardware.BYDAutoManager")
            val method = type.getMethod("getInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            Backend(type.getConstructor(Context::class.java).newInstance(context), method).also { backend = it }
        } catch (failure: Throwable) {
            val root = generateSequence(failure) { it.cause }.take(16).last()
            // Parcel does not preserve arbitrary cause chains. Keep the bounded root in the message.
            val error = IllegalStateException("OEM raw manager initialization failed; not retried in this daemon; " +
                "${root.javaClass.name}: ${root.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(512)}", failure)
            initializationFailure = error
            throw error
        }
        return target.getInt.invoke(target.manager, AdasTargetProperties.DEVICE, property) as Int
    }
}
