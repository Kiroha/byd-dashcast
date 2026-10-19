package com.byd.dashcast.proxy.daemon

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CanWriteVerbsReadTest {
    private val fields = listOf("sDevice", "sSettingDevice", "sEvClass", "sIntField", "sGetMethod",
        "sSettingGetMethod").associateWith { name ->
        CanWriteVerbs::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
    private val previous = fields.mapValues { (_, field) -> field.get(null) }

    @After fun restoreCaches() {
        previous.forEach { (name, value) -> fields.getValue(name).set(null, value) }
    }

    private fun install(device: Any, setting: Boolean) {
        fields.getValue(if (setting) "sSettingDevice" else "sDevice").set(null, device)
        fields.getValue(if (setting) "sSettingGetMethod" else "sGetMethod").set(null, null)
        fields.getValue("sEvClass").set(null, Event::class.java)
        fields.getValue("sIntField").set(null, null)
    }
    private fun read(setting: Boolean, feature: Int = 0x23500008): Int {
        val context = RuntimeEnvironment.getApplication()
        return if (setting) CanWriteVerbs.settingGetInt(context, feature)
            else CanWriteVerbs.getInt(context, feature)
    }

    @Test fun `legacy integer getters still accept singleton and array events for both devices`() {
        for (setting in listOf(false, true)) for (result in listOf(Event(1), arrayOf(Event(1)))) {
            val device = LegacyGetter(result)
            install(device, setting)
            assertEquals(1, read(setting))
            assertEquals(listOf(0x23500008), device.features)
        }
    }

    @Test fun `inherited typed SDK getter receives primitive int class once per integer read`() {
        for (setting in listOf(false, true)) for (result in listOf(Event(1), arrayOf(Event(1)))) {
            val device = InheritedTypedGetter(result)
            install(device, setting)
            assertEquals(1, read(setting))
            assertEquals(1, read(setting, 123))
            assertEquals(listOf(0x23500008, 123), device.features)
            assertEquals(listOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), device.types)
            assertFalse(device.types.contains(Int::class.javaObjectType))
        }
    }

    @Test fun `inherited legacy getter takes priority over a typed overload on the runtime class`() {
        for (setting in listOf(false, true)) {
            val device = BothGetters(Event(1))
            install(device, setting)
            assertEquals(1, read(setting))
            assertEquals(listOf(0x23500008), device.features)
            assertEquals(0, device.typedCalls)
        }
    }

    @Test fun `legacy SDK denial is not retried through the typed overload`() {
        for (setting in listOf(false, true)) {
            val device = BothGetters(Event(1), SecurityException("denied"))
            install(device, setting)
            val failure = assertThrows(InvocationTargetException::class.java) { read(setting) }
            assertTrue(failure.cause is SecurityException)
            assertEquals(listOf(0x23500008), device.features)
            assertEquals(0, device.typedCalls)
        }
    }

    @Test fun `typed SDK exceptions are propagated without a second invocation`() {
        for (setting in listOf(false, true)) {
            val device = TypedGetter(Event(1), SecurityException("denied"))
            install(device, setting)
            val failure = assertThrows(InvocationTargetException::class.java) { read(setting) }
            assertTrue(failure.cause is SecurityException)
            assertEquals(1, device.features.size)
            assertEquals(listOf(Int::class.javaPrimitiveType), device.types)
        }
    }

    @Test fun `SDK error sentinels are preserved without falling back or retrying`() {
        for (setting in listOf(false, true)) for (sentinel in listOf(-2147482648, -999999999)) {
            val legacy = BothGetters(Event(sentinel))
            install(legacy, setting)
            assertEquals(sentinel, read(setting))
            assertEquals(1, legacy.features.size)
            assertEquals(0, legacy.typedCalls)
            val typed = TypedGetter(Event(sentinel))
            install(typed, setting)
            assertEquals(sentinel, read(setting))
            assertEquals(1, typed.features.size)
        }
    }

    @Test fun `null empty and null first SDK results fail without invoking another getter`() {
        for (setting in listOf(false, true)) for (result in listOf(null, emptyArray<Event>(), arrayOfNulls<Event>(1))) {
            val legacy = BothGetters(result)
            install(legacy, setting)
            assertThrows(IllegalStateException::class.java) { read(setting) }
            assertEquals(1, legacy.features.size)
            assertEquals(0, legacy.typedCalls)
            val typed = TypedGetter(result)
            install(typed, setting)
            assertThrows(IllegalStateException::class.java) { read(setting) }
            assertEquals(1, typed.features.size)
        }
    }

    @Test fun `unrelated public and nonpublic getter signatures are never invoked`() {
        for (setting in listOf(false, true)) {
            val device = UnsupportedGetters()
            install(device, setting)
            assertThrows(NoSuchMethodException::class.java) { read(setting) }
            assertEquals(0, device.calls)
        }
    }

    class Event(@JvmField val intValue: Int)
    open class LegacyGetter(private val result: Any?, private val failure: RuntimeException? = null) {
        val features = mutableListOf<Int>()
        fun get(ids: IntArray): Any? {
            features += ids.single()
            failure?.let { throw it }
            return result
        }
    }
    open class TypedGetter(private val result: Any?, private val failure: RuntimeException? = null) {
        val features = mutableListOf<Int>()
        val types = mutableListOf<Class<*>>()
        fun get(ids: IntArray, type: Class<*>): Any? {
            features += ids.single(); types += type
            check(type == Int::class.javaPrimitiveType)
            failure?.let { throw it }
            return result
        }
    }
    class InheritedTypedGetter(result: Any?) : TypedGetter(result)
    class BothGetters(result: Any?, failure: RuntimeException? = null) : LegacyGetter(result, failure) {
        var typedCalls = 0
        fun get(ids: IntArray, type: Class<*>): Any? {
            typedCalls++
            error("Legacy getter must take priority")
        }
    }
    class UnsupportedGetters {
        var calls = 0
        fun get(ids: IntArray, type: String): Any? { calls++; return Event(1) }
        private fun get(ids: IntArray): Any? { calls++; return Event(1) }
    }
}
