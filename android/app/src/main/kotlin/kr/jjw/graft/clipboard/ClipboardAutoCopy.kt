package kr.jjw.graft.clipboard

/**
 * Decides when a clipboard received from the Mac may be written to the phone's clipboard.
 *
 * Android only permits clipboard writes while an Activity is focused, and overwriting the clipboard
 * minutes after the text arrived would destroy whatever the user copied since. So a copy happens
 * once per text, only while the app is in the foreground, and only within a short window of arrival.
 */
class ClipboardAutoCopy(private val windowMillis: Long = 5_000) {
    private var lastCopied: String? = null

    fun shouldCopy(
        text: String,
        arrivedAtMillis: Long,
        hasFocus: Boolean,
        nowMillis: Long,
    ): Boolean {
        if (!hasFocus) return false
        if (text.isEmpty()) return false
        if (text == lastCopied) return false
        if (nowMillis - arrivedAtMillis > windowMillis) return false

        lastCopied = text
        return true
    }
}
