package net.morsecode.media

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.morsecode.storage.AndroidAppContext

/**
 * Pulls an installed app's APK out so it can be handed to the transfer
 * pipeline as an ordinary file (Section E.4).
 *
 * MVP LIMITATION (documented per the spec): for apps installed as split APKs we
 * transfer the BASE apk only. Zipping base + splits is the obvious next step,
 * but a base-only transfer still installs for the common non-split case and is
 * the safer default to ship first.
 */
object ApkExtractor {

    /**
     * @return a readable copy of the base APK in the app cache, or null when
     *   the package is not visible / has no sourceDir.
     */
    suspend fun extractBaseApk(packageName: String): File? = withContext(Dispatchers.IO) {
        val context = AndroidAppContext.context
        val ai = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return@withContext null
        val source = File(ai.sourceDir ?: return@withContext null)
        if (!source.canRead()) return@withContext null

        val outDir = File(context.cacheDir, "apk").apply { mkdirs() }
        val out = File(outDir, "$packageName-${ai.versionCodeSafe()}.apk")
        runCatching { source.copyTo(out, overwrite = true) }.getOrNull()
    }

    private fun android.content.pm.ApplicationInfo.versionCodeSafe(): Long = runCatching {
        @Suppress("DEPRECATION")
        longVersionCode
    }.getOrDefault(0L)
}
