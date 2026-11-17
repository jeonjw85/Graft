package kr.jjw.graft.service

/** What the ongoing notification should say, kept free of Android types so it can be tested. */
data class ConnectionNotificationState(
    val text: String,
    val ongoing: Boolean,
    val alertOnce: Boolean,
) {
    companion object {
        /**
         * A connected link is worth one quiet line; a retry is worth a countdown; a stopped link is
         * not worth pinning at all, so the user can dismiss it.
         */
        fun forStatus(status: String, peerName: String?, transferring: Boolean): ConnectionNotificationState {
            val text = when {
                transferring && peerName != null -> "Sending to $peerName"
                transferring -> "Sending"
                status == "Connected" -> peerName ?: "Connected"
                status == "Disconnected" -> "Disconnected"
                else -> status
            }
            return ConnectionNotificationState(
                text = text,
                ongoing = status != "Disconnected",
                alertOnce = status == "Disconnected",
            )
        }
    }
}
