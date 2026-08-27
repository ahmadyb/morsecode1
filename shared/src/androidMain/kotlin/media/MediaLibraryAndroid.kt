package net.morsecode.media

import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.morsecode.storage.AndroidAppContext

/**
 * MediaStore-backed [MediaLibrary] (Section E.2).
 *
 * All queries run on Dispatchers.IO. Column availability varies by API level
 * (e.g. `WIDTH`/`HEIGHT` on images, `RELATIVE_PATH` on Q+), so each projection
 * guards the optional columns and falls back for the API 23 baseline.
 */
class MediaLibraryAndroid : MediaLibrary {

    private val context get() = AndroidAppContext.context

    override suspend fun getPhotos(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PhotoItem>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        context.contentResolver.query(uri, proj, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
            ?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += PhotoItem(
                        uri = ContentUris.withAppendedId(uri, id).toString(),
                        filename = c.getStringOrNull(1) ?: "photo",
                        sizeBytes = c.getLongOrNull(2) ?: 0,
                        dateTakenEpochMs = c.getLongOrNull(3) ?: 0,
                        widthPx = 0,
                        heightPx = 0,
                    )
                }
            }
        out
    }

    override suspend fun getVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<VideoItem>()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA,
        )
        context.contentResolver.query(uri, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")
            ?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val data = c.getStringOrNull(5) ?: ""
                    out += VideoItem(
                        uri = ContentUris.withAppendedId(uri, id).toString(),
                        filename = c.getStringOrNull(1) ?: "video",
                        relativePath = File(data).parent ?: "",
                        sizeBytes = c.getLongOrNull(2) ?: 0,
                        dateAddedEpochMs = (c.getLongOrNull(3) ?: 0) * 1000,
                        durationMs = c.getLongOrNull(4) ?: 0,
                        thumbnailUri = null,
                    )
                }
            }
        out
    }

    override suspend fun getAudio(): List<AudioItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<AudioItem>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
        )
        context.contentResolver.query(uri, proj, selection, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
            ?.use { c ->
                while (c.moveToNext()) {
                    out += AudioItem(
                        uri = ContentUris.withAppendedId(uri, c.getLong(0)).toString(),
                        filename = c.getStringOrNull(1) ?: "audio",
                        artist = c.getStringOrNull(2),
                        album = c.getStringOrNull(3),
                        sizeBytes = c.getLongOrNull(4) ?: 0,
                        durationMs = c.getLongOrNull(5) ?: 0,
                    )
                }
            }
        out
    }

    /**
     * A `File`-tree walk of app-accessible storage roots (Section E.2). On
     * API 29+ the broad external dir is scoped away, so we walk the app's own
     * external dir plus the primary storage when it is readable.
     */
    override suspend fun getAllFiles(): List<GenericFile> = withContext(Dispatchers.IO) {
        val roots = buildList {
            context.getExternalFilesDir(null)?.let { add(it) }
            if (Build.VERSION.SDK_INT < 29) {
                val ext = Environment.getExternalStorageDirectory()
                if (ext.canRead()) add(ext)
            }
        }
        val out = mutableListOf<GenericFile>()
        for (root in roots) walk(root, root, out, depth = 0)
        out.sortedByDescending { it.modifiedEpochMs }
    }

    private fun walk(root: File, file: File, out: MutableList<GenericFile>, depth: Int) {
        if (depth > 6) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { walk(root, it, out, depth + 1) }
            return
        }
        out += GenericFile(
            uri = Uri.fromFile(file).toString(),
            filename = file.name,
            relativePath = file.parentFile?.relativeToOrSelf(root)?.path ?: "",
            sizeBytes = file.length(),
            extension = file.extension,
            modifiedEpochMs = file.lastModified(),
        )
    }

    override suspend fun getStorageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        StorageUsage(usedBytes = total - free, totalBytes = total)
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.getLongOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
}

actual fun createMediaLibrary(): MediaLibrary = MediaLibraryAndroid()
