package com.byd.dashcast.satellite

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatellitePairingCodeTest {
    private val profile = "{\"version\":1,\"hosts\":[\"192.168.49.1\"],\"port\":47832,\"path\":\"/satellite/v1\",\"certificateSha256\":\"" +
        "a".repeat(64) + "\",\"token\":\"" + "A".repeat(43) + "\"}"
    private val code = "012345"
    private data class Frame(val sender: String, val text: String)
    private data class Result(val profile: String?, val frames: List<Frame>,
        val clientFailure: Throwable?, val serverFailure: Throwable?)

    /** Reproducible fixture entropy only. Production entry points construct SecureRandom themselves. */
    private class FixtureRandom(seed: String) : SecureRandom() {
        private val seedBytes = seed.toByteArray(Charsets.US_ASCII)
        private var counter = 0L
        private var block = ByteArray(0)
        private var offset = 0
        override fun nextBytes(bytes: ByteArray) {
            for (index in bytes.indices) {
                if (offset == block.size) {
                    val input = seedBytes + java.nio.ByteBuffer.allocate(8).putLong(counter++).array()
                    block = MessageDigest.getInstance("SHA-256").digest(input)
                    offset = 0
                }
                bytes[index] = block[offset++]
            }
        }
    }

    private fun exchange(clientCode: String = code, seed: String = "fixture",
        mutate: (String, String) -> String = { _, text -> text }): Result {
        val toClient = LinkedBlockingQueue<Any>()
        val toServer = LinkedBlockingQueue<Any>()
        val frames = CopyOnWriteArrayList<Frame>()
        val serverFailure = AtomicReference<Throwable?>()
        fun receive(queue: LinkedBlockingQueue<Any>): String {
            val value = queue.poll(10, TimeUnit.SECONDS) ?: throw IOException("Test exchange timed out")
            if (value is Throwable) throw IOException("Peer stopped")
            return value as String
        }
        fun send(role: String, queue: LinkedBlockingQueue<Any>, text: String) {
            val changed = mutate(role, text)
            frames.add(Frame(role, changed))
            queue.put(changed)
        }
        val server = Thread {
            try {
                SatellitePairingExchange.serverWithRandom(profile, code,
                    { send("server", toClient, it) }, { receive(toServer) }, FixtureRandom("$seed-server"))
            } catch (failure: Throwable) {
                serverFailure.set(failure)
                toClient.offer(failure)
            }
        }.apply { isDaemon = true; start() }
        var clientFailure: Throwable? = null
        val received = try {
            SatellitePairingExchange.clientWithRandom(clientCode,
                { send("client", toServer, it) }, { receive(toClient) }, FixtureRandom("$seed-client"))
        } catch (failure: Throwable) {
            clientFailure = failure
            toServer.offer(failure)
            null
        }
        server.join(10_000)
        assertFalse("Exchange worker did not terminate", server.isAlive)
        return Result(received, frames.toList(), clientFailure, serverFailure.get())
    }

    private fun assertRejected(result: Result) {
        assertNull(result.profile)
        assertNotNull(result.clientFailure)
    }

    @Test fun `six digit code accepts grouping without accepting Unicode or longer secrets`() {
        assertEquals(code, SatellitePairingCode.normalize("012-345"))
        assertEquals(code, SatellitePairingCode.normalize(" 012 345 "))
        assertEquals("000-000", SatellitePairingCode.format("000000"))
        for (invalid in listOf("12345", "1234567", "ABCDEF", "012345\n", "０１２３４５", "٠١٢٣٤٥", "x".repeat(33))) {
            try { SatellitePairingCode.normalize(invalid); fail("Invalid code accepted") }
            catch (_: IllegalArgumentException) {}
        }
        repeat(50) { assertTrue(SatellitePairingCode.newCode().matches(Regex("[0-9]{6}"))) }
    }

    @Test fun `mutual confirmation precedes profile and full transcript matches pinned fixture`() {
        val result = exchange()
        assertNull(result.clientFailure)
        assertNull(result.serverFailure)
        assertEquals(profile, result.profile)
        assertEquals(listOf("client:jpake.round1", "server:jpake.round1", "client:jpake.round2",
            "server:jpake.round2", "server:jpake.round3", "client:jpake.round3", "server:pairing.profile"),
            result.frames.map { it.sender + ":" + JSONObject(it.text).getString("type") })
        val fixture = JSONObject().put("pairingVersion", 1).put("suite", "JPAKE-NIST3072-SHA256-HKDF-AES256GCM")
            .put("code", code).put("profile", profile).put("fixtureEntropy", "SHA256(seedASCII || counterUint64BE), counter starts at zero")
            .put("clientSeed", "fixture-client").put("serverSeed", "fixture-server")
            .put("frames", JSONArray().apply { result.frames.forEach {
                put(JSONObject().put("sender", it.sender).put("message", JSONObject(it.text)))
            } })
        val expected = JSONObject(File("../docs/satellite/pairing-fixtures.json").readText())
        assertEquals(expected.getString("suite"), fixture.getString("suite"))
        assertEquals(expected.getString("profile"), profile)
        val expectedFrames = expected.getJSONArray("frames")
        assertEquals(result.frames.size, expectedFrames.length())
        for (index in result.frames.indices) {
            val actual = fixture.getJSONArray("frames").getJSONObject(index)
            val stored = expectedFrames.getJSONObject(index)
            assertEquals(stored.getString("sender"), actual.getString("sender"))
            assertEquals(stored.getJSONObject("message").toString(), actual.getJSONObject("message").toString())
        }
        assertTrue(result.frames.all { it.text.toByteArray().size <= SatellitePairingCode.MAX_FRAME_BYTES })
        assertFalse(result.frames.last().text.contains("certificateSha256"))
        assertFalse(result.frames.last().text.contains(profile))
    }

    @Test fun `wrong PIN never releases a profile or client confirmation`() {
        val result = exchange(clientCode = "987654")
        assertRejected(result)
        assertNotNull(result.serverFailure)
        assertFalse(result.frames.any { JSONObject(it.text).getString("type") == "pairing.profile" })
        assertFalse(result.frames.any { it.sender == "client" && JSONObject(it.text).getString("type") == "jpake.round3" })
    }

    @Test fun `forged confirmation is rejected before any profile is sent`() {
        for (role in listOf("client", "server")) {
            val result = exchange { sender, text ->
                val json = JSONObject(text)
                if (sender == role && json.getString("type") == "jpake.round3") json.put("mac", "0").toString() else text
            }
            assertRejected(result)
            assertFalse(result.frames.any { JSONObject(it.text).getString("type") == "pairing.profile" })
        }
    }

    @Test fun `replayed confirmation and encrypted profile fail in a fresh exchange`() {
        val old = exchange()
        for (type in listOf("jpake.round3", "pairing.profile")) {
            val recorded = old.frames.first { it.sender == "server" && JSONObject(it.text).getString("type") == type }.text
            val result = exchange(seed = "new-session") { role, text ->
                if (role == "server" && JSONObject(text).getString("type") == type) recorded else text
            }
            assertRejected(result)
        }
    }

    @Test fun `tampered ciphertext fails after successful password authentication`() {
        val result = exchange { role, text ->
            val json = JSONObject(text)
            if (role == "server" && json.getString("type") == "pairing.profile") {
                val encoded = json.getString("ciphertext")
                json.put("ciphertext", (if (encoded[0] == 'A') "B" else "A") + encoded.drop(1)).toString()
            } else text
        }
        assertRejected(result)
    }

    @Test fun `cheap initial validation rejects unbounded noncanonical malformed and reflected payloads`() {
        val valid = exchange().frames.first().text
        assertTrue(SatellitePairingExchange.isInitialMessage(valid))
        val malformed = listOf(
            valid.replace("\"version\":1", "\"version\":1.0"),
            JSONObject(valid).put("version", "1").toString(),
            JSONObject(valid).put("extra", "forbidden").toString(),
            JSONObject(valid).put("id", "server-" + "a".repeat(32)).toString(),
            JSONObject(valid).put("gx1", "00").toString(),
            JSONObject(valid).put("gx1", "-1").toString(),
            JSONObject(valid).put("gx1", "f".repeat(769)).toString(),
            JSONObject(valid).put("proof1", JSONArray().put("1")).toString(),
            JSONObject(valid).put("proof1", JSONArray().put("1").put("f".repeat(65))).toString(),
            valid + "{}", "[]", " ".repeat(SatellitePairingCode.MAX_FRAME_BYTES + 1))
        malformed.forEach { assertFalse(SatellitePairingExchange.isInitialMessage(it)) }
        // Passing the cheap shape gate does not skip the participant's zero-knowledge proofs.
        val result = exchange { role, text ->
            val json = JSONObject(text)
            if (role == "client" && json.getString("type") == "jpake.round1") json.put("gx1", "1").toString() else text
        }
        assertRejected(result)
        assertFalse(result.frames.any { JSONObject(it.text).getString("type") == "pairing.profile" })
    }
}
