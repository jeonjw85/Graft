package kr.jjw.graft.ui

/** Values typed by a user whose phone cannot scan the QR code. */
data class ManualPairing(
    val code: String,
    val prefix: String,
    val host: String,
    val port: Int,
) {
    /** The store keys the connection service reads when it starts. */
    fun prefs(): Map<String, Any> = mapOf(
        "host" to host,
        "port" to port,
        "prefix" to prefix,
        "token" to code,
    )
}

/** Validation for the manual pairing form, kept free of Android types so it is unit testable. */
object ManualPairingRules {
    const val PREFIX_LENGTH = 8
    const val DEFAULT_PORT = 47800

    sealed interface Result {
        data class Valid(val pairing: ManualPairing) : Result
        data class Invalid(val message: String) : Result
    }

    fun validate(code: String, prefix: String, host: String, port: String): Result {
        val trimmedCode = code.trim()
        if (trimmedCode.length != 6 || !trimmedCode.all { it.isDigit() }) {
            return Result.Invalid("Enter the six-digit code shown on your Mac")
        }

        val trimmedPrefix = prefix.trim()
        if (trimmedPrefix.length != PREFIX_LENGTH || !trimmedPrefix.all { isBase64Url(it) }) {
            return Result.Invalid("Enter the eight-character fingerprint shown on your Mac")
        }

        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            return Result.Invalid("Enter your Mac's IP address")
        }

        val portNumber = port.trim().toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: DEFAULT_PORT

        return Result.Valid(
            ManualPairing(
                code = trimmedCode,
                prefix = trimmedPrefix,
                host = trimmedHost,
                port = portNumber,
            )
        )
    }

    private fun isBase64Url(character: Char): Boolean =
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '-' || character == '_'
}
