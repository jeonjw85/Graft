package kr.jjw.graft.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PairingUriTest {
    private val fingerprint = "Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ"
    private val token = "abcdefghijklmnopqrstuvwx"

    @Test
    fun `build matches the spec example`() {
        assertEquals(
            "graft://pair?v=1&h=192.168.0.12&p=47800&fp=Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ&t=abcdefghijklmnopqrstuvwx&n=MacBook%20Pro",
            PairingUri.build("192.168.0.12", 47800, fingerprint, token, "MacBook Pro")
        )
    }

    @Test
    fun `round trip preserves every field`() {
        val uri = PairingUri.build("10.0.0.5", 47800, fingerprint, token, "작업실 맥")
        val offer = PairingUri.parse(uri)

        assertEquals(1, offer.version)
        assertEquals("10.0.0.5", offer.host)
        assertEquals(47800, offer.port)
        assertEquals(fingerprint, offer.fingerprint)
        assertEquals(token, offer.token)
        assertEquals("작업실 맥", offer.name)
    }

    @Test
    fun `rejects unsupported version`() {
        assertThrows(PairingUriException::class.java) {
            PairingUri.parse(validUri().replace("v=1", "v=2"))
        }
    }

    @Test
    fun `rejects zero port`() {
        assertThrows(PairingUriException::class.java) {
            PairingUri.parse(validUri().replace("p=47800", "p=0"))
        }
    }

    @Test
    fun `rejects malformed fingerprint`() {
        assertThrows(PairingUriException::class.java) {
            PairingUri.parse(validUri().replace(fingerprint, "tooshort"))
        }
    }

    @Test
    fun `rejects short token`() {
        assertThrows(PairingUriException::class.java) {
            PairingUri.parse(validUri().replace("t=$token", "t=abc"))
        }
    }

    @Test
    fun `rejects a non pairing scheme`() {
        assertThrows(PairingUriException::class.java) {
            PairingUri.parse("https://example.com/?v=1")
        }
    }

    @Test
    fun `accepts numeric fallback code`() {
        val uri = validUri().replace("t=$token", "t=123456")
        assertEquals("123456", PairingUri.parse(uri).token)
    }

    @Test
    fun `rejects wrong numeric length`() {
        for (bad in listOf("t=12345", "t=1234567")) {
            assertThrows(PairingUriException::class.java) {
                PairingUri.parse(validUri().replace("t=$token", bad))
            }
        }
    }

    private fun validUri(): String =
        PairingUri.build("192.168.0.12", 47800, fingerprint, token, "MacBook Pro")
}

class PairingTokenStoreTest {
    private val now = 1_762_752_000_000L

    @Test
    fun `token consumes once`() {
        val store = PairingTokenStore()
        val token = store.issue(now)

        assertEquals(true, store.attempt(token.value, now))
        assertEquals(false, store.attempt(token.value, now))
    }

    @Test
    fun `expired token never consumes`() {
        val store = PairingTokenStore(ttlMillis = 120_000)
        val token = store.issue(now)

        assertEquals(false, store.attempt(token.value, now + 121_000))
        assertEquals(
            true,
            store.attempt(token.value, now + 119_000),
            "a rejected expired attempt must not burn the token"
        )
    }

    @Test
    fun `unknown token is rejected`() {
        val store = PairingTokenStore()
        store.issue(now)

        assertEquals(false, store.attempt("unrelated-token-value", now))
    }

    @Test
    fun `sixth attempt inside the window is rejected`() {
        val store = PairingTokenStore(maxAttempts = 5)
        val token = store.issue(now)

        repeat(5) { index -> store.attempt("wrong-$index-value-here", now) }

        assertEquals(false, store.attempt(token.value, now))
        assertEquals(true, store.isRateLimited(now))
    }

    @Test
    fun `fifth attempt with the correct token succeeds`() {
        val store = PairingTokenStore(maxAttempts = 5)
        val token = store.issue(now)

        repeat(4) { index -> store.attempt("wrong-$index-value-here", now) }

        assertEquals(true, store.attempt(token.value, now))
    }

    @Test
    fun `token is inactive after consumption`() {
        val store = PairingTokenStore()
        val token = store.issue(now)
        assertEquals(true, store.isActive(now))

        assertEquals(true, store.attempt(token.value, now))
        assertEquals(false, store.isActive(now))
    }

    @Test
    fun `token is inactive after expiry`() {
        val store = PairingTokenStore(ttlMillis = 120_000)
        store.issue(now)

        assertEquals(false, store.isActive(now + 121_000))
    }

    @Test
    fun `rate limit window slides`() {
        val store = PairingTokenStore(maxAttempts = 5)
        store.issue(now)

        repeat(5) { index -> store.attempt("wrong-$index-value-here", now) }

        assertEquals(true, store.isRateLimited(now))
        assertEquals(false, store.isRateLimited(now + 61_000))
    }

    @Test
    fun `numeric code is six digits`() {
        val store = PairingTokenStore()
        repeat(1_000) {
            val token = store.issueNumericCode(now)
            assertEquals(6, token.value.length)
            assertEquals(true, token.value.all { it.isDigit() })
        }
    }

    @Test
    fun `numeric code consumes once`() {
        val store = PairingTokenStore()
        val code = store.issueNumericCode(now)

        assertEquals(true, store.attempt(code.value, now))
        assertEquals(false, store.attempt(code.value, now))
    }

    @Test
    fun `issuing replaces the previous token`() {
        val store = PairingTokenStore()
        val first = store.issue(now)
        val second = store.issue(now)

        assertEquals(false, store.attempt(first.value, now))
        assertEquals(true, store.attempt(second.value, now))
    }
}
