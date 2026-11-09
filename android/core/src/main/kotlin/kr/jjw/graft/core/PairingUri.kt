package kr.jjw.graft.core

/** The parsed contents of a pairing QR code. */
data class PairingOffer(
    val version: Int,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val name: String,
)

class PairingUriException(message: String) : Exception(message)

object PairingUri {
    const val SCHEME = "graft"
    const val HOST = "pair"
    private const val FINGERPRINT_LENGTH = 43

    /** Builds the exact string that goes into the QR code. */
    fun build(host: String, port: Int, fingerprint: String, token: String, name: String): String {
        fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        return buildString {
            append("$SCHEME://$HOST")
            append("?v=1")
            append("&h=").append(encode(host))
            append("&p=").append(port)
            append("&fp=").append(fingerprint)
            append("&t=").append(token)
            append("&n=").append(encode(name))
        }
    }

    fun parse(raw: String): PairingOffer {
        if (!raw.startsWith("$SCHEME://$HOST")) throw PairingUriException("not a pairing URI: $raw")
        val query = raw.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) throw PairingUriException("missing query")

        val parameters = query.split('&').associate { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
            val value = part.substringAfter('=', missingDelimiterValue = "")
            java.net.URLDecoder.decode(key, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
        }

        fun value(name: String): String =
            parameters[name] ?: throw PairingUriException("missing field: $name")

        val version = value("v").toIntOrNull() ?: throw PairingUriException("invalid version")
        if (version != 1) throw PairingUriException("unsupported version: $version")

        val port = value("p").toIntOrNull() ?: throw PairingUriException("invalid port")
        if (port !in 1..65535) throw PairingUriException("invalid port: $port")

        val fingerprint = value("fp")
        if (!isValidFingerprint(fingerprint)) throw PairingUriException("invalid fingerprint")

        val token = value("t")
        if (!isValidToken(token)) throw PairingUriException("invalid token")

        return PairingOffer(
            version = version,
            host = value("h"),
            port = port,
            fingerprint = fingerprint,
            token = token,
            name = value("n"),
        )
    }

    /** A SPKI SHA-256 fingerprint is 32 bytes encoded without padding, so 43 base64url characters. */
    fun isValidFingerprint(value: String): Boolean =
        value.length == FINGERPRINT_LENGTH &&
            value.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-' || it == '_' }

    /** Either a 16-128 character base64url token or the six-digit manual fallback code. */
    fun isValidToken(value: String): Boolean {
        if (value.length == 6 && value.all { it.isDigit() }) return true
        if (value.length !in 16..128) return false
        return value.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-' || it == '_' }
    }
}
