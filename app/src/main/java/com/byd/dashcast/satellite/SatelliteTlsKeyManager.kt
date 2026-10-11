package com.byd.dashcast.satellite

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** Serve exactly the identity whose DER hash pairing advertises, even with other app keys present. */
internal class SatelliteTlsKeyManager(
    private val alias: String,
    private val key: PrivateKey,
    private val certificate: X509Certificate,
) : X509ExtendedKeyManager() {
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        alias.takeIf { keyType == "RSA" }

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        chooseServerAlias(keyType, issuers, null)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        chooseServerAlias(keyType, issuers, null)?.let { arrayOf(it) }

    override fun getCertificateChain(alias: String?): Array<X509Certificate> =
        if (alias == this.alias) arrayOf(certificate) else emptyArray()

    override fun getPrivateKey(alias: String?): PrivateKey? = key.takeIf { alias == this.alias }
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
}
