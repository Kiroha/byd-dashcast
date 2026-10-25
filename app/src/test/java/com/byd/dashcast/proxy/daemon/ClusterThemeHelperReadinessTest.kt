package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.clusterlive.ClusterThemePolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeHelperReadinessTest {
    private val token = "a".repeat(32)
    private val filename = ClusterThemePolicy.PREFIX + "b".repeat(32) + ".rcc"
    private fun ready() = JSONObject().put("token", token).put("filename", filename).put("pid", 123).put("start", "456")

    @Test fun `empty and incomplete record are bounded readiness work rather than immediate refusal`() {
        val stages = listOf<String?>(null, "", "{\"token\":", ready().toString())
        var reads = 0; var time = 0L
        val result = ClusterThemeHelperReadiness.await(token, filename, 3000, { true }, {
            stages[minOf(reads++, stages.lastIndex)]?.let(ClusterThemeHelperReadiness::parse)
        }, { time }, { time += 25 })
        assertEquals(4, reads); assertEquals(75, time); assertEquals(123, result.getInt("pid"))
    }
    @Test fun `never complete record reaches deadline without admission`() {
        var time = 0L; var reads = 0
        val error = assertThrows(IllegalStateException::class.java) {
            ClusterThemeHelperReadiness.await(token, filename, 3000, { true }, {
                reads++; ClusterThemeHelperReadiness.parse("{\"token\":\"a\"")
            }, { time }, { time += 25 })
        }
        assertEquals(3000, time); assertEquals(120, reads)
        assertTrue(error.message!!.contains("no GO sent"))
    }
    @Test fun `child death ends wait without reading an old record`() {
        var reads = 0
        assertThrows(IllegalStateException::class.java) {
            ClusterThemeHelperReadiness.await(token, filename, 3000, { false }, { reads++; ready() }, { 0 }, { fail("No wait after child death") })
        }
        assertEquals(0, reads)
    }
    @Test fun `complete foreign or malformed identity is refused immediately rather than retried`() {
        val cases = listOf(ready().put("token", "c".repeat(32)), ready().put("filename", filename + ".other"),
            ready().put("pid", 0), ready().put("pid", "123"), ready().put("start", "stale"), ready().put("start", 456))
        for (record in cases) {
            var reads = 0
            assertThrows(IllegalStateException::class.java) {
                ClusterThemeHelperReadiness.await(token, filename, 3000, { true }, { reads++; record }, { 0 }, { fail("Do not retry a foreign identity") })
            }
            assertEquals(1, reads)
        }
    }
    @Test fun `parse cannot treat a missing closing object as a ready identity`() {
        assertNull(ClusterThemeHelperReadiness.parse(ready().toString().dropLast(1)))
        assertNull(ClusterThemeHelperReadiness.parse("{broken}"))
        assertEquals(token, ClusterThemeHelperReadiness.parse(ready().toString() + "\n")!!.getString("token"))
    }
}
