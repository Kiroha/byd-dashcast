package com.byd.dashcast.proxy.daemon

import android.app.Application
import android.hardware.BYDAutoManager
import android.os.Parcel
import com.byd.dashcast.proxy.AdasTargetProperties
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AdasTargetReaderTest {
    @Before fun resetFixture() {
        resetReader()
        BYDAutoManager.constructorCalls = 0
        BYDAutoManager.constructorFailure = null
        BYDAutoManager.devices.clear(); BYDAutoManager.properties.clear()
    }
    @After fun cleanup() { resetReader(); BYDAutoManager.constructorFailure = null }
    private fun resetReader() {
        for (name in listOf("backend", "initializationFailure"))
            AdasTargetReader::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
    }
    @Test fun `bounded snapshot initializes one original context manager and reuses it across keys`() {
        val context = RuntimeEnvironment.getApplication()
        val before = BYDAutoManager.constructorCalls
        val readsBefore = BYDAutoManager.properties.size
        for (key in AdasTargetProperties.allowed) assertEquals(-10011, AdasTargetReader.read(context, key))
        assertEquals(1, BYDAutoManager.constructorCalls - before)
        assertEquals(AdasTargetProperties.allowed, BYDAutoManager.properties.drop(readsBefore))
        assertTrue(BYDAutoManager.devices.drop(readsBefore).all { it == 1038 })
        val backendField = AdasTargetReader::class.java.getDeclaredField("backend").apply { isAccessible = true }
        val backend = backendField.get(null)
        val managerField = backend.javaClass.getDeclaredField("manager").apply { isAccessible = true }
        assertSame(context, (managerField.get(backend) as BYDAutoManager).context)
        assertThrows(IllegalArgumentException::class.java) { AdasTargetReader.read(context, 0x12d0002a) }
        assertEquals(1, BYDAutoManager.constructorCalls - before)
    }

    @Test fun `partial OEM constructor failure is retained instead of reinitializing for subsequent properties`() {
        val context = RuntimeEnvironment.getApplication()
        BYDAutoManager.constructorFailure = IllegalStateException("fixture native initialization failed")
        val first = assertThrows(Exception::class.java) { AdasTargetReader.read(context, 0x29501000) }
        val next = assertThrows(IllegalStateException::class.java) { AdasTargetReader.read(context, 0x2950101c) }
        assertSame(first, next)
        assertTrue(next.message!!.contains("java.lang.IllegalStateException: fixture native initialization failed"))
        val reply = Parcel.obtain()
        try {
            reply.writeException(ProxyDaemonMain.wrapThrowable(next)); reply.setDataPosition(0)
            val received = assertThrows(IllegalStateException::class.java) { reply.readException() }
            assertTrue(received.message!!.contains("fixture native initialization failed"))
        } finally { reply.recycle() }
        assertEquals(1, BYDAutoManager.constructorCalls)
        assertTrue(BYDAutoManager.properties.isEmpty())
    }
}
