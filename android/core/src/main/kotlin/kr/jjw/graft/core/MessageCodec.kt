package kr.jjw.graft.core

import kotlinx.serialization.json.Json

/** 프로토콜 v1 제어 프레임의 인코딩·디코딩. */
object MessageCodec {
    const val CURRENT_VERSION = 1
    const val MAX_CONTROL_FRAME_BYTES = 512 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(envelope: Envelope): ByteArray {
        val bytes = json.encodeToString(Envelope.serializer(), envelope)
            .toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CONTROL_FRAME_BYTES) {
            throw CodecException(CodecException.Kind.OVERSIZED)
        }
        return bytes
    }

    fun decode(bytes: ByteArray): Envelope {
        if (bytes.size > MAX_CONTROL_FRAME_BYTES) {
            throw CodecException(CodecException.Kind.OVERSIZED)
        }

        val envelope = try {
            json.decodeFromString(Envelope.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw CodecException(CodecException.Kind.MALFORMED)
        }

        if (envelope.v != CURRENT_VERSION) {
            throw CodecException(CodecException.Kind.UNSUPPORTED_VERSION)
        }
        return envelope
    }
}
