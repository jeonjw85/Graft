package kr.jjw.graft.core

import java.security.MessageDigest
import java.security.SecureRandom

data class PairingToken(val value: String, val expiresAtMillis: Long)

/**
 * Issues single-use pairing tokens and enforces the per-minute attempt budget.
 *
 * One store owns both rules so the server has a single place to enforce rate limiting.
 */
class PairingTokenStore(
    private val ttlMillis: Long = 120_000,
    private val maxAttempts: Int = 5,
    private val windowMillis: Long = 60_000,
) {
    private val random = SecureRandom()
    private var current: PairingToken? = null
    private var consumed = false
    private val attempts = mutableListOf<Long>()

    fun issue(now: Long): PairingToken {
        val value = ByteArray(18).also(random::nextBytes)
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        return PairingToken(encoded, now + ttlMillis).also {
            current = it
            consumed = false
        }
    }

    /** Six digits from a cryptographically secure RNG, sharing the same TTL and budget. */
    fun issueNumericCode(now: Long): PairingToken {
        val value = "%06d".format(random.nextInt(1_000_000))
        return PairingToken(value, now + ttlMillis).also {
            current = it
            consumed = false
        }
    }

    fun attempt(token: String, now: Long): Boolean {
        // Check the budget before recording, otherwise the fifth legitimate attempt is always
        // rejected: recording first makes the count reach the limit one attempt too early.
        pruneAttempts(now)
        if (attempts.size >= maxAttempts) return false
        attempts.add(now)

        val active = current ?: return false
        if (consumed) return false
        if (now >= active.expiresAtMillis) return false
        if (!constantTimeEquals(active.value, token)) return false

        consumed = true
        return true
    }

    fun isRateLimited(now: Long): Boolean {
        pruneAttempts(now)
        return attempts.size >= maxAttempts
    }

    /** True while a token exists, has not been consumed, and has not expired. */
    fun isActive(now: Long): Boolean {
        val active = current ?: return false
        return !consumed && now < active.expiresAtMillis
    }

    private fun pruneAttempts(now: Long) {
        attempts.removeAll { now - it >= windowMillis }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val a = left.toByteArray(Charsets.UTF_8)
        val b = right.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(a, b)
    }
}
