package kr.jjw.graft.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 프로토콜 v1 제어 프레임의 엔벨로프. */
@Serializable
data class Envelope(
    val v: Int = MessageCodec.CURRENT_VERSION,
    val id: String,
    val re: String? = null,
    val type: String,
    val ts: Long,
    val payload: JsonElement = JsonObject(emptyMap()),
) {
    companion object {
        fun create(
            type: String,
            payload: JsonElement = JsonObject(emptyMap()),
            id: String = UUID.randomUUID().toString(),
            ts: Long = System.currentTimeMillis(),
        ): Envelope = Envelope(id = id, type = type, ts = ts, payload = payload)
    }
}
