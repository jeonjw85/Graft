package kr.jjw.graft.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.jjw.graft.R

/**
 * Rings the phone at alarm volume (bypassing silent mode) when triggered from the Mac, with an
 * automatic timeout and a notification action to stop immediately.
 */
class PhoneRinger(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var ringtone: Ringtone? = null
    private var timeoutJob: Job? = null

    @Synchronized
    fun start() {
        stop()
        ensureChannel()

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = runCatching {
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
        }.getOrNull()

        startVibration()
        postNotification()

        timeoutJob = scope.launch {
            delay(RING_TIMEOUT_MILLIS)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator()?.cancel() }
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(RING_NOTIFICATION_ID)
        }
    }

    private fun startVibration() {
        runCatching {
            val vib = vibrator() ?: return
            if (!vib.hasVibrator()) return
            val pattern = longArrayOf(0, 500, 400, 500, 600)
            vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            RING_CHANNEL_ID,
            context.getString(R.string.ring_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.ring_channel_desc)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun postNotification() {
        val stopIntent = PendingIntent.getService(
            context,
            RING_NOTIFICATION_ID,
            Intent(context, ConnectionService::class.java).apply {
                action = ConnectionService.ACTION_STOP_RING
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, RING_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.ring_title))
            .setContentText(context.getString(R.string.ring_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(stopIntent)
            .setDeleteIntent(stopIntent)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.action_stop_ringing),
                    stopIntent,
                ).build()
            )
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(RING_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val RING_CHANNEL_ID = "graft-ring"
        private const val RING_NOTIFICATION_ID = 2
        private const val RING_TIMEOUT_MILLIS = 20_000L
    }
}
