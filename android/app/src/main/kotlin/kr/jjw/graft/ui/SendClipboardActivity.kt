package kr.jjw.graft.ui

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kr.jjw.graft.R
import kr.jjw.graft.service.ConnectionService

/**
 * Brief translucent activity launched from the Quick Settings tile or the connection notification.
 *
 * Android 10+ allows reading the system clipboard only while an activity has window focus. This
 * activity gains focus for a fraction of a second, sends the clipboard text to the Mac, shows a
 * short toast, and exits immediately without appearing in Recents.
 */
class SendClipboardActivity : Activity() {
    private var handled = false
    private val handler = Handler(Looper.getMainLooper())
    private val safetyFinish = Runnable {
        if (!handled) {
            handled = true
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler.postDelayed(safetyFinish, 1_500L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        handler.removeCallbacks(safetyFinish)

        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()

        val message = when {
            !ConnectionService.isPaired(this) -> getString(R.string.toast_pair_first)
            text.isNullOrEmpty() -> getString(R.string.toast_no_clipboard_text)
            else -> {
                val directError = ConnectionService.sendClipboard(text)
                if (directError == null) {
                    getString(R.string.toast_clipboard_sent)
                } else {
                    ConnectionService.sendSharedText(this, text)
                    getString(R.string.toast_clipboard_sending)
                }
            }
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(safetyFinish)
        super.onDestroy()
    }
}
