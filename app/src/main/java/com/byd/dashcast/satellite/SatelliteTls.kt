package com.byd.dashcast.satellite

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** Self-signed installation identity: the satellite MUST pin the certificate from pairing. */
object SatelliteTls {
    private const val ALIAS = "dashcast_satellite_tls_v1"

    @Synchronized
    private fun store(): KeyStore {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=DashCast Satellite"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(0))
                .setCertificateNotAfter(Date(4_102_444_800_000L))
                .build())
            generator.generateKeyPair()
        }
        return store
    }

    fun context(): SSLContext {
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        manager.init(store(), null)
        return SSLContext.getInstance("TLSv1.2").apply { init(manager.keyManagers, null, null) }
    }

    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(store().getCertificate(ALIAS).encoded).joinToString("") { "%02x".format(it) }
}
