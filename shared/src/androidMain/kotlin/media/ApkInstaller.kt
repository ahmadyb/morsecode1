package net.morsecode.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import net.morsecode.storage.AndroidAppContext

/**
 * Triggers the install flow for a received APK (Sections E.4 and C).
 *
 * The unknown-sources story is version-branched exactly as Section C demands:
 *  - API 26+: `REQUEST_INSTALL_PACKAGES` is a runtime-checkable permission,
 *    surfaced via [canInstallUnknown] and `canRequestPackageInstalls()`.
 *  - API <26: it is the global "unknown sources" system setting instead.
 */
object ApkInstaller {

    private val context get() = AndroidAppContext.context

    /** Whether this device currently permits installing non-market APKs. */
    fun canInstallUnknown(ctx: Context = context): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        ctx.packageManager.canRequestPackageInstalls()
    } else {
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
    }

    /**
     * Launches the package installer for [apk]. The file is shared through the
     * FileProvider declared in the manifest, never as a raw file:// Uri —
     * file:// across apps has been blocked since N.
     */
    fun install(apk: File, ctx: Context = context): Boolean {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }
}
