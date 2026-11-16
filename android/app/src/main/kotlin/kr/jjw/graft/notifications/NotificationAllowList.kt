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

    /**
     * Test seam: the real load path reads DataStore and needs a Context, which unit tests do not
     * have. Production code never calls this.
     */
    internal fun seedForTest(packages: Set<String>) {
        cache = packages
    }

    /**
     * Loads the saved list into the cache the listener reads.
     *
     * Uses a plain thread rather than `runBlocking` so a listener callback is never blocked on the
     * DataStore's own dispatcher, which can deadlock when called from the main thread.
     */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        Thread {
            val loaded = runCatching {
                kotlinx.coroutines.runBlocking {
                    appContext.allowListStore.data.first()[KEY] ?: emptySet()
                }
            }.getOrDefault(emptySet())
            cache = loaded
        }.start()
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
