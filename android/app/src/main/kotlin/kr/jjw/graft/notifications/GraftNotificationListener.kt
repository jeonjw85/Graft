package kr.jjw.graft.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kr.jjw.graft.service.ConnectionRepository

/**
 * Forwards notifications the user allowed.
 *
 * The filter applies before anything leaves the phone; a denied notification-access permission just
 * means this service never runs, and the rest of the app is unaffected.
 */
class GraftNotificationListener : NotificationListenerService() {
    private val filter by lazy {
        NotificationFilter(
            allowedPackages = { NotificationAllowList.cached() },
            selfPackage = packageName,
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val candidate = PostedNotification(
            pkg = sbn.packageName,
            app = appLabel(sbn.packageName),
            title = extras.getCharSequence("android.title")?.toString() ?: "",
            text = extras.getCharSequence("android.text")?.toString() ?: "",
            postedAt = sbn.postTime,
            key = sbn.key ?: "${sbn.packageName}:${sbn.id}",
            priority = when (sbn.notification.priority) {
                android.app.Notification.PRIORITY_HIGH,
                android.app.Notification.PRIORITY_MAX,
                -> "high"
                android.app.Notification.PRIORITY_LOW,
                android.app.Notification.PRIORITY_MIN,
                -> "low"
                else -> "normal"
            },
        )

        val accepted = filter.accept(
            candidate = candidate,
            isOngoing = sbn.isOngoing,
            isGroupSummary = (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0,
        ) ?: return

        ConnectionRepository.onNotification(accepted)
    }

    private fun appLabel(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)
}
