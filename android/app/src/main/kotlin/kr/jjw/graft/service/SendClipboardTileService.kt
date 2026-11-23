package kr.jjw.graft.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kr.jjw.graft.R
import kr.jjw.graft.ui.SendClipboardActivity

/**
 * Quick Settings tile for one-tap Android -> Mac clipboard sharing from any app.
 */
class SendClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val paired = ConnectionService.isPaired(this)
        val connected = ConnectionRepository.state.value.status is ConnectionStatus.Connected

        tile.state = when {
            !paired -> Tile.STATE_UNAVAILABLE
            connected -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_send_clipboard)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !paired -> getString(R.string.tile_subtitle_not_paired)
                connected -> getString(R.string.tile_subtitle_connected)
                else -> getString(R.string.tile_subtitle_tap)
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SendClipboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                TILE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TILE_REQUEST_CODE = 101
    }
}
