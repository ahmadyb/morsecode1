package net.morsecode.media

import java.io.File
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Filesystem-scan-backed [MediaLibrary] (Section E.2, Desktop).
 *
 * There is no MediaStore here, so classification is by extension against the
 * OS's standard Pictures/Videos/Music folders plus a user-configurable
 * "Shared Folder" root for [getAllFiles].
 */
class MediaLibraryDesktop(
    private val sharedFolder: File? = null,
) : MediaLibrary {

    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    private val videoExt = setOf("mp4", "mkv", "webm", "mov", "avi", "flv", "wmv")
    private val audioExt = setOf("mp3", "flac", "aac", "ogg", "wav", "m4a", "opus")

    private val home = File(System.getProperty("user.home"))
    private val pictures = listOf(File(home, "Pictures"), File(home, "Desktop"))
    private val videos = listOf(File(home, "Videos"), File(home, "Movies"))
    private val music = listOf(File(home, "Music"))

    override suspend fun getPhotos(): List<PhotoItem> = withContext(Dispatchers.IO) {
        scan(pictures, imageExt).map { f ->
            PhotoItem(
                uri = f.toURI().toString(),
                filename = f.name,
                sizeBytes = f.length(),
                dateTakenEpochMs = f.lastModified(),
                widthPx = 0,
                heightPx = 0,
            )
        }.sortedByDescending { it.dateTakenEpochMs }
    }

    override suspend fun getVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        scan(videos, videoExt).map { f ->
            VideoItem(
                uri = f.toURI().toString(),
                filename = f.name,
                relativePath = f.parent ?: "",
                sizeBytes = f.length(),
                dateAddedEpochMs = f.lastModified(),
                durationMs = 0,
                thumbnailUri = null,
            )
        }.sortedByDescending { it.dateAddedEpochMs }
    }

    override suspend fun getAudio(): List<AudioItem> = withContext(Dispatchers.IO) {
        scan(music, audioExt).map { f ->
            AudioItem(
                uri = f.toURI().toString(),
                filename = f.name,
                artist = null,
                album = null,
                sizeBytes = f.length(),
                durationMs = 0,
            )
        }.sortedByDescending { it.sizeBytes }
    }

    override suspend fun getAllFiles(): List<GenericFile> = withContext(Dispatchers.IO) {
        val root = sharedFolder ?: home
        val out = mutableListOf<GenericFile>()
        walk(root, root, out, 0)
        out.sortedByDescending { it.modifiedEpochMs }
    }

    override suspend fun getStorageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        val store: FileStore = Files.getFileStore(Paths.get(home.absolutePath))
        StorageUsage(usedBytes = store.totalSpace - store.usableSpace, totalBytes = store.totalSpace)
    }

    private fun scan(roots: List<File>, exts: Set<String>): List<File> =
        roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().maxDepth(4).filter { it.isFile && it.extension.lowercase() in exts }.toList()
        }

    private fun walk(root: File, file: File, out: MutableList<GenericFile>, depth: Int) {
        if (depth > 5) return
        if (file.isDirectory) {
            val name = file.name
            if (name.startsWith(".") || name in setOf("node_modules", "AppData")) return
            file.listFiles()?.forEach { walk(root, it, out, depth + 1) }
            return
        }
        out += GenericFile(
            uri = file.toURI().toString(),
            filename = file.name,
            relativePath = file.parentFile?.relativeToOrSelf(root)?.path ?: "",
            sizeBytes = file.length(),
            extension = file.extension,
            modifiedEpochMs = file.lastModified(),
        )
    }
}

actual fun createMediaLibrary(): MediaLibrary = MediaLibraryDesktop()
