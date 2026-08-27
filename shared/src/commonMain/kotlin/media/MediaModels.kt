package net.morsecode.media

/**
 * Section E.1 — the common data models. Field-for-field from the spec.
 *
 * Every tab renders one of these; every "Send" action turns a multi-selection
 * of these into a Section 5 TRANSFER_REQUEST. They are deliberately plain data
 * so they cross the KMP boundary freely.
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val apkSizeBytes: Long,
    val isSystemApp: Boolean,
    val iconUri: String?,
)

data class PhotoItem(
    val uri: String,
    val filename: String,
    val sizeBytes: Long,
    val dateTakenEpochMs: Long,
    val widthPx: Int,
    val heightPx: Int,
)

data class VideoItem(
    val uri: String,
    val filename: String,
    val relativePath: String,
    val sizeBytes: Long,
    val dateAddedEpochMs: Long,
    val durationMs: Long,
    val thumbnailUri: String?,
)

data class AudioItem(
    val uri: String,
    val filename: String,
    val artist: String?,
    val album: String?,
    val sizeBytes: Long,
    val durationMs: Long,
)

data class GenericFile(
    val uri: String,
    val filename: String,
    val relativePath: String,
    val sizeBytes: Long,
    val extension: String,
    val modifiedEpochMs: Long,
)

/** Used by [FilesTab]'s `StorageUsageBar` (Section E.4). */
data class StorageUsage(
    val usedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** The Files tab's non-exclusive category buckets (Section E.3). */
enum class FileCategory(val label: String) {
    DOCUMENTS("Documents"),
    EBOOKS("Ebooks"),
    APKS("Apks"),
    ARCHIVES("Archives"),
    BIG_FILES("Big Files"),
}
