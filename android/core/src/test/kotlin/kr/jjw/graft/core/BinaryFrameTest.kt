package kr.jjw.graft.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BinaryFrameTest {
    private data class FrameRow(
        val name: String,
        val tid: Int,
        val offset: ULong,
        val dataHex: String,
        val encodedHex: String,
    )

    private data class InvalidRow(val name: String, val encodedHex: String)

    private fun load(): Pair<List<FrameRow>, List<InvalidRow>> {
        val file = File(
            System.getProperty("graft.fixturesDir") ?: "../../protocol/fixtures",
            "binary/frames.json"
        )
        val root = Json.parseToJsonElement(file.readText()).jsonObject

        val frames = root.getValue("frames").jsonArray.map { element ->
            val row = element.jsonObject
            FrameRow(
                name = row.getValue("name").jsonPrimitive.content,
                tid = row.getValue("tid").jsonPrimitive.content.toLong().toInt(),
                offset = row.getValue("offset").jsonPrimitive.content.toULong(),
                dataHex = row.getValue("dataHex").jsonPrimitive.content,
                encodedHex = row.getValue("encodedHex").jsonPrimitive.content,
            )
        }

        val invalid = root.getValue("invalid").jsonArray.map { element ->
            val row = element.jsonObject
            InvalidRow(
                name = row.getValue("name").jsonPrimitive.content,
                encodedHex = row.getValue("encodedHex").jsonPrimitive.content,
            )
        }
        return frames to invalid
    }

    @Test
    fun `fixture round trip`() {
        val (frames, _) = load()
        assertEquals(4, frames.size)

        for (row in frames) {
            val frame = BinaryFrame.decode(row.encodedHex.hexToBytes())
            assertEquals(row.tid.toUInt(), frame.transferId, row.name)
            assertEquals(row.offset, frame.offset, row.name)
            assertTrue(row.dataHex.hexToBytes().contentEquals(frame.data), row.name)
            assertTrue(row.encodedHex.hexToBytes().contentEquals(frame.encoded()), row.name)
        }
    }

    @Test
    fun `invalid frames are rejected`() {
        val (_, invalid) = load()
        assertTrue(invalid.isNotEmpty())

        for (row in invalid) {
            assertThrows(BinaryFrameException::class.java, { BinaryFrame.decode(row.encodedHex.hexToBytes()) }, row.name)
        }
    }

    @Test
    fun `error kinds are distinguishable`() {
        assertEquals(
            BinaryFrameErrorKind.TOO_SHORT,
            assertThrows(BinaryFrameException::class.java) { BinaryFrame.decode(ByteArray(0)) }.kind
        )
        assertEquals(
            BinaryFrameErrorKind.BAD_MAGIC,
            assertThrows(BinaryFrameException::class.java) {
                BinaryFrame.decode("58580100000000000000000000000000".hexToBytes())
            }.kind
        )
        assertEquals(
            BinaryFrameErrorKind.UNSUPPORTED_VERSION,
            assertThrows(BinaryFrameException::class.java) {
                BinaryFrame.decode("47460200000000000000000000000000".hexToBytes())
            }.kind
        )
        assertEquals(
            BinaryFrameErrorKind.RESERVED_NOT_ZERO,
            assertThrows(BinaryFrameException::class.java) {
                BinaryFrame.decode("47460101000000000000000000000000".hexToBytes())
            }.kind
        )
    }

    @Test
    fun `oversized payload is rejected`() {
        val header = "47460100000000010000000000000000".hexToBytes()
        val oversized = header + ByteArray(BinaryFrame.MAX_DATA_SIZE + 1)

        assertEquals(
            BinaryFrameErrorKind.OVERSIZED_PAYLOAD,
            assertThrows(BinaryFrameException::class.java) { BinaryFrame.decode(oversized) }.kind
        )
    }

    @Test
    fun `maximum payload is accepted`() {
        val header = "47460100000000010000000000000000".hexToBytes()
        val exact = header + ByteArray(BinaryFrame.MAX_DATA_SIZE) { 0xAB.toByte() }

        assertEquals(BinaryFrame.MAX_DATA_SIZE, BinaryFrame.decode(exact).data.size)
    }

    @Test
    fun `header size is sixteen`() {
        assertEquals(16, BinaryFrame.HEADER_SIZE)
        assertEquals(64 * 1024, BinaryFrame.MAX_DATA_SIZE)
    }
}
