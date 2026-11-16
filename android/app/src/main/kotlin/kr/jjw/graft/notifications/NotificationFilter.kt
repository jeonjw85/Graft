package kr.jjw.graft.notifications

/** A notification the phone wants to forward. */
data class PostedNotification(
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val key: String,
    val priority: String,
)

/**
 * Decides which notifications may leave the phone.
 *
 * The allow list starts empty, so nothing is forwarded until the user chooses an app. Graft's own
 * notifications, ongoing/foreground-service entries, and group summaries are always dropped.
 */
class NotificationFilter(
    private val allowedPackages: () -> Set<String>,
    private val selfPackage: String,
    private val maxTextBytes: Int = MAX_TEXT_BYTES,
    private val dedupeCapacity: Int = 200,
) {
    private val seen = LinkedHashSet<String>()

    fun accept(
        candidate: PostedNotification,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
    ): PostedNotification? {
        if (candidate.pkg == selfPackage) return null
        if (isOngoing || isGroupSummary) return null
        if (!allowedPackages().contains(candidate.pkg)) return null

        val dedupeKey = "${candidate.key}|${candidate.postedAt}"
        if (!seen.add(dedupeKey)) return null
        trimDedupe()

        return candidate.copy(text = truncate(candidate.text, maxTextBytes))
    }

    /** Truncates on a UTF-8 boundary so a Korean character is never cut in half. */
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

    private fun trimDedupe() {
        while (seen.size > dedupeCapacity) {
            val oldest = seen.firstOrNull() ?: break
            seen.remove(oldest)
        }
    }

    companion object {
        const val MAX_TEXT_BYTES = 2 * 1024
    }
}
