package net.morsecode.media

import android.content.pm.ApplicationInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.morsecode.storage.AndroidAppContext

/**
 * PackageManager-backed [AppLibrary] (Section E.2).
 *
 * Only apps the manifest's <queries> grants visibility for are returned — the
 * privacy-respecting default (Section C). We deliberately never list or promote
 * apps the user hasn't installed: no "Hot" section, ever.
 */
class AppLibraryAndroid : AppLibrary {

    private val context get() = AndroidAppContext.context

    override suspend fun getInstalledApps(includeSystemApps: Boolean): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
                .mapNotNull { ai ->
                    val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (!includeSystemApps && isSystem) return@mapNotNull null
                    AppInfo(
                        packageName = ai.packageName,
                        appName = ai.loadLabel(pm).toString(),
                        versionName = runCatching {
                            @Suppress("DEPRECATION")
                            pm.getPackageInfo(ai.packageName, 0).versionName ?: ""
                        }.getOrDefault(""),
                        apkSizeBytes = runCatching { File(ai.sourceDir).length() }.getOrDefault(0L),
                        isSystemApp = isSystem,
                        iconUri = null,
                    )
                }
                .sortedBy { it.appName.lowercase() }
        }
}
