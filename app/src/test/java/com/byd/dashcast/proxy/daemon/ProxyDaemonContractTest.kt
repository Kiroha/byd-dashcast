package com.byd.dashcast.proxy.daemon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ProxyDaemonContractTest {
    @Test
    fun `binder transaction codes are unique`() {
        val byCode = ProxyDaemonContract::class.java.declaredFields
            .filter { field ->
                Modifier.isStatic(field.modifiers) &&
                    field.type == Int::class.javaPrimitiveType &&
                    field.name.startsWith("TXN_")
            }
            .groupBy { field -> field.getInt(null) }
        val duplicates = byCode.filterValues { fields -> fields.size > 1 }

        assertTrue(
            duplicates.entries.joinToString { (code, fields) ->
                "$code=${fields.joinToString { it.name }}"
            },
            duplicates.isEmpty()
        )
    }
}