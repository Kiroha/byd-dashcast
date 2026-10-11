package com.byd.dashcast.satellite

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class SatelliteTlsKeyManagerTest {
    @Test fun `server engine socket and advertised certificate select only the installation alias`() {
        val store = KeyStore.getInstance("PKCS12").apply {
            SatelliteTlsKeyManagerTest::class.java.getResourceAsStream("/tls/test-only-server.p12")!!
                .use { load(it, "test-only".toCharArray()) }
        }
        val original = store.aliases().nextElement()
        val key = store.getKey(original, "test-only".toCharArray()) as PrivateKey
        val certificate = store.getCertificate(original) as X509Certificate
        val manager = SatelliteTlsKeyManager("selected-v2", key, certificate)
        assertEquals("selected-v2", manager.chooseEngineServerAlias("RSA", null, null))
        assertEquals("selected-v2", manager.chooseServerAlias("RSA", null, null))
        assertArrayEquals(arrayOf("selected-v2"), manager.getServerAliases("RSA", null))
        assertSame(key, manager.getPrivateKey("selected-v2"))
        assertArrayEquals(certificate.encoded, manager.getCertificateChain("selected-v2").single().encoded)
        assertNull(manager.getPrivateKey("legacy-v1"))
        assertTrue(manager.getCertificateChain("legacy-v1").isEmpty())
        assertNull(manager.chooseEngineServerAlias("EC", null, null))
        assertNull(manager.chooseClientAlias(arrayOf("RSA"), null, null))
    }
}
