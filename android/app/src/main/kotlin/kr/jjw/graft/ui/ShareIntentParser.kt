package kr.jjw.graft.ui

import android.content.Intent
import android.net.Uri
import android.os.Build

/** Content handed to Graft from another app via the Android Share Sheet. */
sealed interface SharedPayload {
    data class Files(val uris: List<Uri>) : SharedPayload
    data class Text(val text: String) : SharedPayload
}

/** Extracts shared files or text from `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents. */
object ShareIntentParser {
    fun parse(intent: Intent?): SharedPayload? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> parseSingle(intent)
            Intent.ACTION_SEND_MULTIPLE -> parseMultiple(intent)
            else -> null
        }
    }

    private fun parseSingle(intent: Intent): SharedPayload? {
        val stream = parcelableUri(intent, Intent.EXTRA_STREAM)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (stream != null) {
            return SharedPayload.Files(listOf(stream))
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            return SharedPayload.Text(text)
        }
        return null
    }

    private fun parseMultiple(intent: Intent): SharedPayload? {
        val streams = parcelableUriList(intent, Intent.EXTRA_STREAM).ifEmpty {
            buildList {
                val clip = intent.clipData ?: return@buildList
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let(::add)
                }
            }
        }
        return if (streams.isNotEmpty()) SharedPayload.Files(streams) else null
    }

    @Suppress("DEPRECATION")
    private fun parcelableUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun parcelableUriList(intent: Intent, key: String): List<Uri> {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        }
        return list?.filterNotNull().orEmpty()
    }
}
