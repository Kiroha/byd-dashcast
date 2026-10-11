package com.byd.dashcast.satellite

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date

/** Unit fixtures verify migration/alias ownership; the ART probe covers real KeyStore authorization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteTlsMigrationTest {
    private val provider = object : Provider("AndroidKeyStore", 1.0, "Test-only identity store") {
        init {
            put("KeyStore.AndroidKeyStore", TestStore::class.java.name)
            put("KeyPairGenerator.RSA", Generator::class.java.name)
        }
    }
    private var removedProviders = emptyList<Pair<Int, Provider>>()

    @Before fun setUp() {
        fixture = KeyStore.getInstance("PKCS12").apply {
            SatelliteTlsMigrationTest::class.java.getResourceAsStream("/tls/test-only-server.p12")!!
                .use { load(it, "test-only".toCharArray()) }
        }
        aliases.clear()
        spec = null
        generated = 0
        failGeneration = false
        mismatchedPrivateKey = null
        removedProviders = Security.getProviders().mapIndexedNotNull { index, value ->
            if (value.javaClass.name.contains("conscrypt", true) || value.name == provider.name) index to value else null
        }
        removedProviders.forEach { Security.removeProvider(it.second.name) }
        Security.insertProviderAt(provider, 1)
        RuntimeEnvironment.getApplication().getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
        SatellitePairingSession.close()
    }

    @After fun tearDown() {
        Security.removeProvider(provider.name)
        removedProviders.forEach { (index, value) -> Security.insertProviderAt(value, index + 1) }
        SatellitePairingSession.close()
    }

    @Test fun `legacy migration creates a signing-only identity with raw TLS authorization`() {
        aliases.add("dashcast_satellite_tls_v1")
        val fingerprint = SatelliteTls.fingerprint()
        assertTrue(aliases.contains("dashcast_satellite_tls_v1"))
        assertTrue(aliases.contains("dashcast_satellite_tls_v2"))
        assertTrue(SatelliteTls.migratedFromLegacy)
        assertEquals(64, fingerprint.length)
        val parameters = spec!!
        assertEquals(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY, parameters.purposes)
        assertTrue(parameters.digests.contains(KeyProperties.DIGEST_NONE))
        assertTrue(parameters.encryptionPaddings.contains(KeyProperties.ENCRYPTION_PADDING_NONE))
        assertTrue(parameters.signaturePaddings.contains(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1))
        assertEquals("dashcast_satellite_tls_v2", parameters.keystoreAlias)
    }

    @Test fun `repeated context and profile calls keep one certificate without closing pairing`() {
        aliases.add("dashcast_satellite_tls_v1")
        val app = RuntimeEnvironment.getApplication()
        SatellitePrefs.setEnabled(app, true)
        val token = SatellitePrefs.token(app)
        val attempt = SatellitePairingSession.begin()
        val fingerprint = SatelliteTls.fingerprint()
        SatelliteTls.context()
        assertEquals(fingerprint, SatelliteTls.fingerprint())
        assertEquals(1, generated)
        assertEquals(attempt, SatellitePairingSession.snapshot()?.attempt)
        assertEquals(token, SatellitePrefs.token(app))
        assertTrue(SatellitePrefs.requiresTlsRePair(app))
        SatellitePrefs.confirmTlsIdentity(app)
        assertFalse(SatellitePrefs.requiresTlsRePair(app))
    }

    @Test fun `generation failure keeps the legacy identity without fallback or deleting keys`() {
        aliases.add("dashcast_satellite_tls_v1")
        failGeneration = true
        try { SatelliteTls.context(); fail("Expected identity preparation failure") }
        catch (error: SatelliteTls.StartupFailure) {
            assertEquals(SatelliteReceiverFailure.KEY_GENERATION, error.reason)
        }
        assertEquals(setOf("dashcast_satellite_tls_v1"), aliases)
    }

    @Test fun `fresh installation does not claim a migration`() {
        SatelliteTls.context()
        assertFalse(SatelliteTls.migratedFromLegacy)
        assertFalse(SatellitePrefs.requiresTlsRePair(RuntimeEnvironment.getApplication()))
    }

    @Test fun `existing incomplete v2 fails closed without regenerating or using legacy`() {
        aliases.addAll(listOf("dashcast_satellite_tls_v1", "dashcast_satellite_tls_v2"))
        missingPrivateKey = true
        try { SatelliteTls.fingerprint(); fail("Expected unusable identity failure") }
        catch (error: SatelliteTls.StartupFailure) {
            assertEquals(SatelliteReceiverFailure.KEY_MANAGER, error.reason)
        } finally { missingPrivateKey = false }
        assertEquals(0, generated)
        assertEquals(setOf("dashcast_satellite_tls_v1", "dashcast_satellite_tls_v2"), aliases)
    }

    @Test fun `existing v2 certificate from another key is rejected without changing either alias`() {
        aliases.addAll(listOf("dashcast_satellite_tls_v1", "dashcast_satellite_tls_v2"))
        mismatchedPrivateKey = KeyPairGenerator.getInstance("RSA", "SunRsaSign").apply {
            initialize(2048)
        }.generateKeyPair().private
        try { SatelliteTls.context(); fail("Expected certificate and key mismatch") }
        catch (error: SatelliteTls.StartupFailure) {
            assertEquals(SatelliteReceiverFailure.KEY_MANAGER, error.reason)
        }
        assertEquals(0, generated)
        assertEquals(setOf("dashcast_satellite_tls_v1", "dashcast_satellite_tls_v2"), aliases)
    }

    class Generator : KeyPairGeneratorSpi() {
        override fun initialize(size: Int, random: SecureRandom?) = error("Use an explicit key policy")
        override fun initialize(parameters: AlgorithmParameterSpec?, random: SecureRandom?) {
            spec = parameters as KeyGenParameterSpec
        }
        override fun generateKeyPair(): KeyPair {
            if (failGeneration) throw ProviderException("test-only generation failure")
            generated++
            aliases.add(spec!!.keystoreAlias)
            return KeyPair(certificate().publicKey, privateKey())
        }
    }

    class TestStore : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineContainsAlias(alias: String?) = aliases.contains(alias)
        override fun engineAliases() = Collections.enumeration(aliases)
        override fun engineSize() = aliases.size
        override fun engineGetKey(alias: String?, password: CharArray?): Key? = if (aliases.contains(alias) && !missingPrivateKey) privateKey() else null
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = if (aliases.contains(alias)) arrayOf(certificate()) else null
        override fun engineGetCertificate(alias: String?): Certificate? = if (aliases.contains(alias)) certificate() else null
        override fun engineGetCreationDate(alias: String?): Date = Date(0)
        override fun engineIsKeyEntry(alias: String?) = aliases.contains(alias)
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = error("unused")
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = error("unused")
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = error("unused")
        override fun engineDeleteEntry(alias: String?) = error("Never delete existing identities during migration")
        override fun engineStore(stream: OutputStream?, password: CharArray?) = error("unused")
    }

    companion object {
        private lateinit var fixture: KeyStore
        private val aliases = mutableSetOf<String>()
        private var spec: KeyGenParameterSpec? = null
        private var generated = 0
        private var failGeneration = false
        private var missingPrivateKey = false
        private var mismatchedPrivateKey: PrivateKey? = null
        private fun privateKey() = mismatchedPrivateKey ?: fixture.getKey(fixture.aliases().nextElement(), "test-only".toCharArray()) as PrivateKey
        private fun certificate() = fixture.getCertificate(fixture.aliases().nextElement())
    }
}
