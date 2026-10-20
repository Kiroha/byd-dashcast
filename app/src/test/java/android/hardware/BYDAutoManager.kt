package android.hardware

import android.content.Context

/** OEM API fixture: no JNI, services or vehicle interaction. */
class BYDAutoManager(val context: Context) {
    init { constructorCalls++; constructorFailure?.let { throw it } }
    fun getInt(device: Int, property: Int): Int { devices += device; properties += property; return -10011 }
    companion object {
        var constructorCalls = 0
        var constructorFailure: RuntimeException? = null
        val devices = mutableListOf<Int>()
        val properties = mutableListOf<Int>()
    }
}
