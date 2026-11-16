package kr.jjw.graft.notifications

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.allowListStore by preferencesDataStore(name = "graft-allow-list")

/**
 * Apps whose notifications may be forwarded.
 *
 * Starts empty: nothing leaves the phone until the user picks an app. The listener reads a cached
 * copy because `onNotificationPosted` must not block on disk.
 */
object NotificationAllowList {
    private val KEY = stringSetPreferencesKey("packages")
    @Volatile
    private var cache: Set<String> = emptySet()

    fun cached(): Set<String> = cache

    fun refresh(context: Context) {
        cache = runBlocking {
            runCatching {
                context.allowListStore.data.first()[KEY] ?: emptySet()
            }.getOrDefault(emptySet())
        }
    }

    suspend fun add(context: Context, packageName: String) {
        val current = context.allowListStore.data.first()[KEY] ?: emptySet()
        val updated = current + packageName
        context.allowListStore.edit { it[KEY] = updated }
        cache = updated
    }

    suspend fun remove(context: Context, packageName: String) {
        val current = context.allowListStore.data.first()[KEY] ?: emptySet()
        val updated = current - packageName
        context.allowListStore.edit { it[KEY] = updated }
        cache = updated
    }

    suspend fun load(context: Context): Set<String> =
        context.allowListStore.data.first()[KEY] ?: emptySet()
}
