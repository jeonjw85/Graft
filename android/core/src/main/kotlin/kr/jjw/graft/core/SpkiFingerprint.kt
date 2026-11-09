package kr.jjw.graft.core

import java.security.MessageDigest
import java.util.Base64

/**
 * Computes the base64url-encoded SHA-256 fingerprint of a SubjectPublicKeyInfo block.
 *
 * This must produce byte-identical output to the Swift implementation, so the encoding is fixed:
 * `30 59` + P-256 algorithm identifier + `03 42 00` (bit string) + 65-byte uncompressed point.
 */
object SpkiFingerprint {
    private val P256_SPKI_PREFIX: ByteArray = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D,
        0x02, 0x01, 0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01,
        0x07, 0x03, 0x42, 0x00
    )

    private const val RAW_P256_POINT_LENGTH = 65
    private const val UNCOMPRESSED_POINT_TAG: Byte = 0x04

    fun base64Url(spki: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(spki)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Builds the SPKI DER from a raw P-256 point (`04 || X || Y`) and returns its fingerprint.
     */
    fun base64UrlFromRawPoint(point: ByteArray): String {
        require(point.size == RAW_P256_POINT_LENGTH) {
            "P-256 points are $RAW_P256_POINT_LENGTH bytes, got ${point.size}"
        }
        require(point[0] == UNCOMPRESSED_POINT_TAG) { "expected an uncompressed point" }
        return base64Url(P256_SPKI_PREFIX + point)
    }

    fun base64UrlFromEncodedKey(encoded: ByteArray): String = base64Url(encoded)
}
