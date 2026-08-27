package net.morsecode.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Runtime permissions, branched per API level (Section C):
 *  - pre-33: one READ_EXTERNAL_STORAGE covers photos/videos/music;
 *  - 33+: granular READ_MEDIA_IMAGES / VIDEO / AUDIO;
 *  - 33+: POST_NOTIFICATIONS for the transfer foreground service;
 *  - 26+: REQUEST_INSTALL_PACKAGES routed through Settings (no runtime prompt).
 *
 * Requests use ActivityResultContracts; results are logged but never block the
 * UI — denied permissions degrade the affected section gracefully.
 */
class PermissionsManager(private val activity: Activity) {

    private val requestPermissions = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* results intentionally non-blocking; sections degrade on denial */ }

    fun requestOnLaunch() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
            perms += Manifest.permission.READ_MEDIA_AUDIO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        perms += Manifest.permission.ACCESS_FINE_LOCATION // mDNS multicast discovery
        if (perms.isNotEmpty()) requestPermissions.launch(perms.toTypedArray())
    }

    /** Opens Settings > Install unknown apps on 26+ (REQUEST_INSTALL_PACKAGES has no runtime prompt). */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (activity.packageManager.canRequestPackageInstalls()) return
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            )
            runCatching { activity.startActivity(intent) }
        }
    }

    fun has(permission: String): Boolean =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
