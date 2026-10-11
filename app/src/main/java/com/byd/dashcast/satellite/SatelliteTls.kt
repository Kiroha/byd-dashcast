package com.byd.dashcast.satellite

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.net.ssl.SSLContext
import javax.net.ssl.KeyManager
import javax.security.auth.x500.X500Principal

/** Self-signed installation identity: the satellite MUST pin the certificate from pairing. */
object SatelliteTls {
    private const val LEGACY_ALIAS = "dashcast_satellite_tls_v1"
    private const val ALIAS = "dashcast_satellite_tls_v2"
    @Volatile internal var migratedFromLegacy = false
        private set

    @Synchronized
    private fun store(): KeyStore {
        val store = stage(SatelliteReceiverFailure.KEYSTORE) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        }
        // Key authorizations are immutable. Keep v1 intact while preparing v2; never
        // retry by deleting an installation identity or silently downgrading TLS.
        if (!stage(SatelliteReceiverFailure.KEYSTORE) { store.containsAlias(ALIAS) }) {
            stage(SatelliteReceiverFailure.KEY_GENERATION) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
                generator.initialize(KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    // API 29 Conscrypt signs an already encoded digest through the
                    // private RSA/ECB/NoPadding Cipher, authorized as PURPOSE_SIGN.
                    // NONE is a padding authorization, not permission to decrypt.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=DashCast Satellite"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(0))
                    .setCertificateNotAfter(Date(4_102_444_800_000L))
                    .build())
                generator.generateKeyPair()
            }
        }
        return store
    }

    fun context(): SSLContext {
        val manager = identity()
        return stage(SatelliteReceiverFailure.TLS_CONTEXT) {
            SSLContext.getInstance("TLSv1.2").apply { init(arrayOf<KeyManager>(manager), null, null) }
        }
    }

    @Synchronized
    private fun identity(): SatelliteTlsKeyManager {
        val store = store()
        return stage(SatelliteReceiverFailure.KEY_MANAGER) {
            val key = store.getKey(ALIAS, null) as? PrivateKey ?: error("Missing TLS private key")
            val certificate = store.getCertificate(ALIAS) as? X509Certificate ?: error("Missing TLS certificate")
            certificate.checkValidity()
            // Exercise the exact provider operation before publishing the certificate
            // in a pairing profile. A ready port is not proof of signing permission.
            // 257 bits is safely below a 2048-bit modulus and avoids RSA's fixed
            // points (0 and 1), which cannot detect a mismatched certificate/key.
            val input = ByteArray(256).apply {
                this[223] = 1
                ByteArray(32).also { SecureRandom().nextBytes(it) }.copyInto(this, 224)
            }
            val signed = Cipher.getInstance("RSA/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key)
                doFinal(input)
            }
            val recovered = Cipher.getInstance("RSA/ECB/NoPadding").run {
                init(Cipher.DECRYPT_MODE, certificate.publicKey)
                doFinal(signed)
            }
            check(MessageDigest.isEqual(input, recovered)) { "TLS certificate/key mismatch" }
            // A migration notice is a fact only after the replacement identity
            // passes provider signing and certificate/key validation.
            migratedFromLegacy = store.containsAlias(LEGACY_ALIAS)
            SatelliteTlsKeyManager(ALIAS, key, certificate)
        }
    }

    internal class StartupFailure(val reason: SatelliteReceiverFailure, cause: Throwable) :
        RuntimeException(cause)

    private fun <T> stage(reason: SatelliteReceiverFailure, action: () -> T): T = try {
        action()
    } catch (error: Exception) {
        throw StartupFailure(reason, error)
    } catch (error: LinkageError) {
        throw StartupFailure(reason, error)
    }

    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(identity().getCertificateChain(ALIAS).single().encoded).joinToString("") { "%02x".format(it) }
}
