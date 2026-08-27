package net.morsecode.media

/**
 * The local-library abstraction (Section E.2).
 *
 * Android backs this with MediaStore; Desktop with a filesystem scan. The tabs
 * never know which. Every returned item is read-only local data that feeds the
 * existing transfer pipeline via multi-select.
 */
interface MediaLibrary {
    suspend fun getPhotos(): List<PhotoItem>
    suspend fun getVideos(): List<VideoItem>
    suspend fun getAudio(): List<AudioItem>
    suspend fun getAllFiles(): List<GenericFile>
    suspend fun getStorageUsage(): StorageUsage
}

expect fun createMediaLibrary(): MediaLibrary

/**
 * Installed-app enumeration (Section E.2).
 *
 * Meaningful only on Android (PackageManager). Desktop has NO implementation —
 * `AppsTab` checks [net.morsecode.ui.isDesktopPlatform] and shows the empty
 * state from Section D instead of ever calling this.
 */
interface AppLibrary {
    suspend fun getInstalledApps(includeSystemApps: Boolean): List<AppInfo>
}
