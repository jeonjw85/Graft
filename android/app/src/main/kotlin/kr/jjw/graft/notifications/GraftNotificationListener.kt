package kr.jjw.graft.notifications

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kr.jjw.graft.service.ConnectionRepository

/**
 * Forwards notifications the user allowed, syncs dismissals, and executes inline replies from Mac.
 *
 * The filter applies before anything leaves the phone; a denied notification-access permission just
 * means this service never runs, and the rest of the app is unaffected.
 */
class GraftNotificationListener : NotificationListenerService() {
    private data class ReplyAction(
        val pendingIntent: PendingIntent,
        val remoteInput: RemoteInput,
    )

    private val filter by lazy {
        NotificationFilter(
            allowedPackages = { NotificationAllowList.cached() },
            selfPackage = packageName,
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The listener runs in its own process and may start before the settings screen has ever
        // been opened, so it loads the saved allow list itself. Without this the cache stays empty
        // and nothing is ever forwarded.
        NotificationAllowList.refresh(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        NotificationAllowList.refresh(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        synchronized(cacheLock) {
            replyActions.clear()
            forwardedKeys.clear()
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
        val normalText = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        val body = if (!bigText.isNullOrBlank()) bigText else (normalText ?: "")
        val key = sbn.key ?: "${sbn.packageName}:${sbn.id}"
        val replyAction = extractReplyAction(sbn)

        val candidate = PostedNotification(
            pkg = sbn.packageName,
            app = appLabel(sbn.packageName),
            title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "",
            text = body,
            postedAt = sbn.postTime,
            key = key,
            priority = when (sbn.notification.priority) {
                android.app.Notification.PRIORITY_HIGH,
                android.app.Notification.PRIORITY_MAX,
                -> "high"
                android.app.Notification.PRIORITY_LOW,
                android.app.Notification.PRIORITY_MIN,
                -> "low"
                else -> "normal"
            },
            replyable = replyAction != null,
        )

        val accepted = filter.accept(
            candidate = candidate,
            isOngoing = sbn.isOngoing,
            isGroupSummary = (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0,
        ) ?: return

        synchronized(cacheLock) {
            if (replyAction != null) {
                replyActions[key] = replyAction
                while (replyActions.size > MAX_CACHED_KEYS) {
                    val oldest = replyActions.keys.firstOrNull() ?: break
                    replyActions.remove(oldest)
                }
            } else {
                replyActions.remove(key)
            }
            forwardedKeys.add(key)
            while (forwardedKeys.size > MAX_CACHED_KEYS) {
                val oldest = forwardedKeys.firstOrNull() ?: break
                forwardedKeys.remove(oldest)
            }
        }

        ConnectionRepository.onNotification(accepted)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key ?: "${sbn.packageName}:${sbn.id}"
        val wasForwarded = synchronized(cacheLock) {
            replyActions.remove(key)
            forwardedKeys.remove(key)
        }
        if (wasForwarded) {
            ConnectionRepository.onNotificationRemoved(key)
        }
    }

    private fun extractReplyAction(sbn: StatusBarNotification): ReplyAction? {
        val actions = sbn.notification.actions ?: return null
        for (action in actions) {
            val remoteInput = action.remoteInputs?.firstOrNull { it.allowFreeFormInput }
                ?: action.remoteInputs?.firstOrNull()
                ?: continue
            val intent = action.actionIntent ?: continue
            return ReplyAction(intent, remoteInput)
        }
        return null
    }

    private fun appLabel(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val MAX_CACHED_KEYS = 200
        private val cacheLock = Any()
        private val replyActions = LinkedHashMap<String, ReplyAction>()
        private val forwardedKeys = LinkedHashSet<String>()

        @Volatile
        private var instance: GraftNotificationListener? = null

        /** Dismisses a notification on the phone when the user dismisses its banner on the Mac. */
        fun dismiss(key: String): Boolean {
            val listener = instance ?: return false
            synchronized(cacheLock) {
                replyActions.remove(key)
                forwardedKeys.remove(key)
            }
            return runCatching {
                listener.cancelNotification(key)
                true
            }.getOrDefault(false)
        }

        /** Sends an inline reply typed on the Mac through the notification's cached RemoteInput. */
        fun reply(key: String, text: String): Boolean {
            val listener = instance ?: return false
            val action = synchronized(cacheLock) { replyActions[key] } ?: return false
            if (text.isBlank()) return false

            return runCatching {
                val fillIn = Intent()
                val results = Bundle().apply {
                    putCharSequence(action.remoteInput.resultKey, text)
                }
                RemoteInput.addResultsToIntent(arrayOf(action.remoteInput), fillIn, results)
                action.pendingIntent.send(listener, 0, fillIn)
                true
            }.getOrDefault(false)
        }
    }
}
