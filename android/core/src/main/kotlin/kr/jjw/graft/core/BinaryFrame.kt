package kr.jjw.graft.core

/** One binary file-data frame. The 16-byte header is big-endian; see docs/protocol.md. */
class BinaryFrame(
    val transferId: UInt,
    val offset: ULong,
    val data: ByteArray,
) {
    val id: Int get() = transferId.toInt()
    val startOffset: Long get() = offset.toLong()

    fun encoded(): ByteArray {
        require(data.size <= MAX_DATA_SIZE) { "payload exceeds 64 KiB" }

        val encoded = ByteArray(HEADER_SIZE + data.size)
        encoded[0] = MAGIC_0
        encoded[1] = MAGIC_1
        encoded[2] = VERSION
        encoded[3] = 0

        var tid = transferId
        for (index in 7 downTo 4) {
            encoded[index] = (tid and 0xFFu).toByte()
            tid = tid shr 8
        }

        var off = offset
        for (index in 15 downTo 8) {
            encoded[index] = (off and 0xFFu).toByte()
            off = off shr 8
        }

        data.copyInto(encoded, destinationOffset = HEADER_SIZE)
        return encoded
    }

    companion object {
        const val HEADER_SIZE = 16
        const val MAX_DATA_SIZE = 64 * 1024
        private const val MAGIC_0: Byte = 0x47
        private const val MAGIC_1: Byte = 0x46
        private const val VERSION: Byte = 1

        fun decode(raw: ByteArray): BinaryFrame {
            if (raw.size < HEADER_SIZE) throw BinaryFrameException(BinaryFrameErrorKind.TOO_SHORT)
            if (raw[0] != MAGIC_0 || raw[1] != MAGIC_1) {
                throw BinaryFrameException(BinaryFrameErrorKind.BAD_MAGIC)
            }
            if (raw[2] != VERSION) {
                throw BinaryFrameException(BinaryFrameErrorKind.UNSUPPORTED_VERSION)
            }
            if (raw[3] != 0.toByte()) {
                throw BinaryFrameException(BinaryFrameErrorKind.RESERVED_NOT_ZERO)
            }

            var transferId = 0u
            for (index in 4 until 8) {
                transferId = (transferId shl 8) or raw[index].toUByte().toUInt()
            }

            var offset = 0uL
            for (index in 8 until 16) {
                offset = (offset shl 8) or raw[index].toUByte().toULong()
            }

            val payload = raw.copyOfRange(HEADER_SIZE, raw.size)
            if (payload.size > MAX_DATA_SIZE) {
                throw BinaryFrameException(BinaryFrameErrorKind.OVERSIZED_PAYLOAD)
            }

            return BinaryFrame(transferId, offset, payload)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BinaryFrame) return false
        return transferId == other.transferId &&
            offset == other.offset &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int =
        31 * (31 * transferId.hashCode() + offset.hashCode()) + data.contentHashCode()

    override fun toString(): String =
        "BinaryFrame(tid=$transferId, offset=$offset, bytes=${data.size})"
}

enum class BinaryFrameErrorKind {
    TOO_SHORT,
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    RESERVED_NOT_ZERO,
    OVERSIZED_PAYLOAD,
}

class BinaryFrameException(val kind: BinaryFrameErrorKind) : Exception(kind.name)
