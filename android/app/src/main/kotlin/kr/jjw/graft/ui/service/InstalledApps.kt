package kr.jjw.graft.ui.service

import android.content.Context
import android.content.Intent

// The package manager is the only source for "which apps can post notifications here".
/** Installed launchable apps, sorted by label. */
fun installedApps(context: Context): List<Pair<String, String>> {
    val manager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return manager.queryIntentActivities(intent, 0)
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            pkg to (info.loadLabel(manager)?.toString() ?: pkg)
        }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}
