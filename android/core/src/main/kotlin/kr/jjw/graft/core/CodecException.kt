package kr.jjw.graft.core

class CodecException(
    val kind: Kind,
    message: String? = null,
) : Exception(message ?: kind.name) {
    enum class Kind { UNSUPPORTED_VERSION, MALFORMED, OVERSIZED }
}
