package com.byd.dashcast.satellite

import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.util.BigIntegers
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bouncy Castle J-PAKE with mandatory mutual key confirmation, then a single encrypted profile.
 * A captured transcript does not turn the six-digit code into an offline password verifier.
 * The transport must separately enforce a short window and a global online attempt limit.
 */
internal object SatellitePairingExchange {
    private val group = JPAKEPrimeOrderGroups.NIST_3072
    private val minimumMac = BigInteger.ONE.shiftLeft(255).negate()
    private val maximumMac = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE)
    private const val CONTEXT_DOMAIN = "DashCast satellite pairing JPAKE v1\u0000"
    private const val KEY_INFO = "DashCast satellite pairing profile AES-256-GCM v1"

    fun client(code: String, send: (String) -> Unit, receive: () -> String): String =
        clientWithRandom(code, send, receive, SecureRandom())

    fun server(profile: String, code: String, send: (String) -> Unit, receive: () -> String) =
        serverWithRandom(profile, code, send, receive, SecureRandom())

    /** Cheap framing/schema checks before the server reserves a rate-limited cryptographic attempt. */
    fun isInitialMessage(frame: String): Boolean = try { round1(frame, "client"); true } catch (_: Exception) { false }

    internal fun clientWithRandom(code: String, send: (String) -> Unit, receive: () -> String,
        random: SecureRandom): String {
        val clientId = newId("client", random)
        val participant = participant(clientId, code, random)
        send(encode(participant.createRound1PayloadToSend()))
        val first = round1(receive(), "server")
        val serverId = first.participantId
        participant.validateRound1PayloadReceived(first)
        send(encode(participant.createRound2PayloadToSend()))
        participant.validateRound2PayloadReceived(round2(receive(), serverId))
        val material = participant.calculateKeyingMaterial()
        // BC requires creating our round 3 before validating the peer's round 3. Hold the frame
        // until validation succeeds so the server knows both confirmations preceded profile release.
        val confirmation = encode(participant.createRound3PayloadToSend(material))
        participant.validateRound3PayloadReceived(round3(receive(), serverId), material)
        send(confirmation)
        val context = context(clientId, serverId)
        val key = deriveKey(material, context)
        return try { openProfile(receive(), key, context, clientId, serverId) } finally { key.fill(0) }
    }

    internal fun serverWithRandom(profile: String, code: String, send: (String) -> Unit,
        receive: () -> String, random: SecureRandom) {
        require(profile.toByteArray(Charsets.UTF_8).size in 1..SatellitePairingCode.MAX_PROFILE_BYTES)
        val first = round1(receive(), "client")
        val clientId = first.participantId
        val serverId = newId("server", random)
        val participant = participant(serverId, code, random)
        val ownFirst = participant.createRound1PayloadToSend()
        participant.validateRound1PayloadReceived(first)
        send(encode(ownFirst))
        val ownSecond = participant.createRound2PayloadToSend()
        participant.validateRound2PayloadReceived(round2(receive(), clientId))
        send(encode(ownSecond))
        val material = participant.calculateKeyingMaterial()
        send(encode(participant.createRound3PayloadToSend(material)))
        participant.validateRound3PayloadReceived(round3(receive(), clientId), material)
        val context = context(clientId, serverId)
        val key = deriveKey(material, context)
        try { send(sealProfile(profile, key, context, clientId, serverId, random)) } finally { key.fill(0) }
    }

    private fun participant(id: String, code: String, random: SecureRandom): JPAKEParticipant {
        val password = SatellitePairingCode.normalize(code).toCharArray()
        return try { JPAKEParticipant(id, password, group, SHA256Digest(), random) }
        finally { password.fill('\u0000') }
    }

    private fun newId(role: String, random: SecureRandom): String = role + "-" +
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun message(type: String, id: String): JSONObject =
        JSONObject().put("version", 1).put("type", type).put("id", id)

    private fun proof(values: Array<BigInteger>): JSONArray = JSONArray().apply {
        require(values.size == 2)
        values.forEach { put(it.toString(16)) }
    }

    private fun encode(payload: JPAKERound1Payload): String = message("jpake.round1", payload.participantId)
        .put("gx1", payload.gx1.toString(16)).put("gx2", payload.gx2.toString(16))
        .put("proof1", proof(payload.knowledgeProofForX1)).put("proof2", proof(payload.knowledgeProofForX2)).toString()

    private fun encode(payload: JPAKERound2Payload): String = message("jpake.round2", payload.participantId)
        .put("a", payload.a.toString(16)).put("proof", proof(payload.knowledgeProofForX2s)).toString()

    private fun encode(payload: JPAKERound3Payload): String = message("jpake.round3", payload.participantId)
        .put("mac", payload.macTag.toString(16)).toString()

    private fun round1(text: String, role: String): JPAKERound1Payload {
        val json = parse(text, "jpake.round1", setOf("id", "gx1", "gx2", "proof1", "proof2"))
        val id = json.get("id")
        require(id is String && id.matches(Regex("$role-[0-9a-f]{32}")))
        return JPAKERound1Payload(id, groupValue(json.get("gx1")), groupValue(json.get("gx2")),
            parseProof(json.get("proof1")), parseProof(json.get("proof2")))
    }

    private fun round2(text: String, id: String): JPAKERound2Payload {
        val json = parse(text, "jpake.round2", setOf("id", "a", "proof"))
        require(json.get("id") == id)
        return JPAKERound2Payload(id, groupValue(json.get("a")), parseProof(json.get("proof")))
    }

    private fun round3(text: String, id: String): JPAKERound3Payload {
        val json = parse(text, "jpake.round3", setOf("id", "mac"))
        require(json.get("id") == id)
        val raw = json.get("mac")
        require(raw is String && raw.length <= 65 && raw.matches(Regex("0|-?[1-9a-f][0-9a-f]{0,63}")))
        val mac = BigInteger(raw, 16)
        require(mac >= minimumMac && mac <= maximumMac && mac.toString(16) == raw)
        return JPAKERound3Payload(id, mac)
    }

    private fun groupValue(value: Any): BigInteger = unsigned(value, group.p, positive = true)

    private fun unsigned(value: Any, limit: BigInteger, positive: Boolean = false): BigInteger {
        require(value is String && value.length <= (limit.bitLength() + 3) / 4 &&
            value.matches(Regex("0|[1-9a-f][0-9a-f]*")))
        return BigInteger(value, 16).also { require(it < limit && (!positive || it.signum() > 0)) }
    }

    private fun parseProof(value: Any): Array<BigInteger> {
        require(value is JSONArray && value.length() == 2)
        return arrayOf(groupValue(value.get(0)), unsigned(value.get(1), group.q))
    }

    private fun parse(text: String, type: String, fields: Set<String>): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size in 1..SatellitePairingCode.MAX_FRAME_BYTES)
        val tokener = JSONTokener(text)
        val json = tokener.nextValue() as? JSONObject ?: error("Invalid pairing frame")
        require(tokener.nextClean() == '\u0000')
        require(json.keys().asSequence().toSet() == fields + setOf("version", "type"))
        val version = json.get("version")
        require((version is Int || version is Long) && (version as Number).toLong() == 1L)
        require(json.get("type") == type)
        return json
    }

    private fun context(clientId: String, serverId: String): ByteArray =
        (CONTEXT_DOMAIN + clientId + "\u0000" + serverId).toByteArray(Charsets.US_ASCII)

    private fun deriveKey(material: BigInteger, context: ByteArray): ByteArray {
        val input = BigIntegers.asUnsignedByteArray(material)
        return try {
            val generator = HKDFBytesGenerator(SHA256Digest())
            generator.init(HKDFParameters(input, MessageDigest.getInstance("SHA-256").digest(context),
                KEY_INFO.toByteArray(Charsets.US_ASCII)))
            ByteArray(32).also { generator.generateBytes(it, 0, it.size) }
        } finally { input.fill(0) }
    }

    private fun sealProfile(profile: String, key: ByteArray, context: ByteArray, clientId: String,
        serverId: String, random: SecureRandom): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val plain = profile.toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher(Cipher.ENCRYPT_MODE, key, iv, context).doFinal(plain) }
            finally { plain.fill(0) }
        return JSONObject().put("version", 1).put("type", "pairing.profile")
            .put("clientId", clientId).put("serverId", serverId).put("iv", base64(iv))
            .put("ciphertext", base64(encrypted)).toString().also {
                require(it.toByteArray(Charsets.UTF_8).size <= SatellitePairingCode.MAX_FRAME_BYTES)
            }
    }

    private fun openProfile(text: String, key: ByteArray, context: ByteArray, clientId: String,
        serverId: String): String {
        val json = parse(text, "pairing.profile", setOf("clientId", "serverId", "iv", "ciphertext"))
        require(json.get("clientId") == clientId && json.get("serverId") == serverId)
        val iv = decode(json.get("iv"), 12, 12)
        val encrypted = decode(json.get("ciphertext"), 17, SatellitePairingCode.MAX_PROFILE_BYTES + 16)
        val plain = cipher(Cipher.DECRYPT_MODE, key, iv, context).doFinal(encrypted)
        return try {
            require(plain.size in 1..SatellitePairingCode.MAX_PROFILE_BYTES)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString()
        } finally { plain.fill(0) }
    }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, context: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(context)
        }

    private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: Any, minimum: Int, maximum: Int): ByteArray {
        require(value is String && value.length <= ((maximum + 2) / 3) * 4 &&
            value.matches(Regex("[A-Za-z0-9_-]+")))
        return Base64.getUrlDecoder().decode(value).also {
            require(it.size in minimum..maximum && base64(it) == value)
        }
    }
}
