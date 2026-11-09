package kr.jjw.graft.core.transport

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager

/**
 * Presents one identity to the TLS stack.
 *
 * Android supplies an Android Keystore key handle as [privateKey]; this class never reads key
 * bytes, so a non-extractable key works unchanged.
 */
class IdentityKeyManager(
    private val alias: String,
    private val privateKey: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509KeyManager {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        arrayOf(alias)

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = alias

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

    override fun getPrivateKey(alias: String?): PrivateKey = privateKey
}
