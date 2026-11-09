package kr.jjw.graft.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json

/** Encoding and decoding for protocol v1 control frames. */
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

    /** Finds unpaired `\\uD800` to `\\uDFFF` escapes inside JSON strings. */
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
