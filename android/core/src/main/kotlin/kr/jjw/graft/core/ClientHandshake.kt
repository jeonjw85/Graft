package kr.jjw.graft.core

/**
 * The first message the phone sends once TLS is up.
 *
 * A paired phone says hello; an unpaired one asks to pair with the code the user typed. Sending
 * nothing leaves both sides waiting — the app shows "Connecting…" forever.
 */
object ClientHandshake {
    fun initial(paired: Boolean, token: String, deviceId: String, name: String): Envelope =
        if (paired) {
            Envelope.create(
                "hello",
                kotlinx.serialization.json.buildJsonObject {
                    put("proto", kotlinx.serialization.json.JsonPrimitive(1))
                },
            )
        } else {
            Envelope.create(
                "pair.request",
                kotlinx.serialization.json.buildJsonObject {
                    put("token", kotlinx.serialization.json.JsonPrimitive(token))
                    put("deviceId", kotlinx.serialization.json.JsonPrimitive(deviceId))
                    put("name", kotlinx.serialization.json.JsonPrimitive(name))
                    put("platform", kotlinx.serialization.json.JsonPrimitive("android"))
                },
            )
        }
}
