package kr.jjw.graft.core

/**
 * Builds and parses the Bonjour service instance name.
 *
 * The name carries the first eight characters of the fingerprint so two Macs on one network do not
 * collide, and the TXT record still carries the full fingerprint for verification. A name that is
 * discovered without a matching short fingerprint is ignored rather than treated as an error.
 */
object BonjourServiceInfo {
    const val SERVICE_TYPE = "_graft._tcp"
    const val MAX_BYTES = 63

    fun serviceName(deviceName: String, fingerprint: String): String {
        val short = fingerprint.take(8)
        return truncate("$deviceName ($short)", maxBytes = MAX_BYTES)
    }

    /** Extracts the short fingerprint suffix from a discovered name, or null when absent. */
    fun shortFingerprint(serviceName: String): String? {
        val open = serviceName.lastIndexOf('(')
        val close = serviceName.lastIndexOf(')')
        if (open < 0 || close <= open + 1) return null
        val value = serviceName.substring(open + 1, close)
        return value.takeIf { it.length == 8 }
    }

    /** Truncates on a UTF-8 character boundary so a Korean device name is never cut mid-character. */
    fun truncate(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value

        val builder = StringBuilder()
        var used = 0
        for (character in value) {
            val size = character.toString().toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            builder.append(character)
            used += size
        }
        return builder.toString()
    }
}
