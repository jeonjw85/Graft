package kr.jjw.graft.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json

/** 프로토콜 v1 제어 프레임의 인코딩·디코딩. */
object MessageCodec {
    const val CURRENT_VERSION = 1
    const val MAX_CONTROL_FRAME_BYTES = 512 * 1024
    const val MAX_NESTING_DEPTH = 64

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

        val text = decodeUtf8Strict(bytes)
        if (exceedsMaxNestingDepth(text) || hasUnpairedSurrogateEscape(text)) {
            throw CodecException(CodecException.Kind.MALFORMED)
        }

        val envelope = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (error: Exception) {
            throw CodecException(CodecException.Kind.MALFORMED)
        }

        if (envelope.v != CURRENT_VERSION) {
            throw CodecException(CodecException.Kind.UNSUPPORTED_VERSION)
        }
        return envelope
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw CodecException(CodecException.Kind.MALFORMED)
    }

    private fun exceedsMaxNestingDepth(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false

        for (char in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_NESTING_DEPTH) return true
                }
                '}', ']' -> if (depth > 0) depth -= 1
            }
        }
        return false
    }

    /** JSON 문자열 안의 짝이 맞지 않는 `\\uD800`~`\\uDFFF` 이스케이프를 찾는다. */
    private fun hasUnpairedSurrogateEscape(text: String): Boolean {
        var index = 0
        var inString = false
        var pendingHighSurrogate = false

        while (index < text.length) {
            val char = text[index]
            if (!inString) {
                if (char == '"') inString = true
                index += 1
                continue
            }

            if (char == '\\' && index + 5 < text.length && text[index + 1] == 'u') {
                val code = text.substring(index + 2, index + 6).toIntOrNull(16)
                if (code != null) {
                    when (code) {
                        in 0xD800..0xDBFF -> {
                            if (pendingHighSurrogate) return true
                            pendingHighSurrogate = true
                        }
                        in 0xDC00..0xDFFF -> {
                            if (!pendingHighSurrogate) return true
                            pendingHighSurrogate = false
                        }
                        else -> {
                            if (pendingHighSurrogate) return true
                            pendingHighSurrogate = false
                        }
                    }
                    index += 6
                    continue
                }
            }

            if (pendingHighSurrogate) return true
            inString = char != '"'
            index += if (char == '\\') 2 else 1
        }

        return pendingHighSurrogate
    }
}
