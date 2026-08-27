package net.morsecode.media

/**
 * Section E.3 — extension-based categorisation.
 *
 * Categories are NON-EXCLUSIVE filtered views, matching Xender's real
 * behaviour: a 60 MB `.docx` appears in both Documents and Big Files. So a file
 * maps to a *set* of categories, and a tab asks for "everything whose set
 * contains X".
 *
 * Pure and platform-free — this is exactly the logic the spec says must be
 * unit-testable independent of any data source (Phase 9).
 */
object FileCategorizer {

    /** Anything above this is a "Big File" regardless of extension. */
    const val BIG_FILE_THRESHOLD_BYTES: Long = 50L * 1024 * 1024

    val DOCUMENT_EXTENSIONS = setOf("doc", "docx", "ppt", "pptx", "xls", "xlsx", "wps", "pdf", "odt")
    val EBOOK_EXTENSIONS = setOf("umd", "ebk", "txt", "chm", "epub", "mobi")
    val APK_EXTENSIONS = setOf("apk")
    val ARCHIVE_EXTENSIONS = setOf("7z", "rar", "zip", "iso", "tar", "gz")

    /**
     * The full, non-exclusive set of categories a file belongs to.
     *
     * Extension comparison is case-insensitive and ignores a leading dot, so
     * `PDF` and `.pdf` both land in Documents.
     */
    fun categoriesOf(file: GenericFile): Set<FileCategory> {
        val ext = file.extension.removePrefix(".").lowercase()
        return buildSet {
            if (ext in DOCUMENT_EXTENSIONS) add(FileCategory.DOCUMENTS)
            if (ext in EBOOK_EXTENSIONS) add(FileCategory.EBOOKS)
            if (ext in APK_EXTENSIONS) add(FileCategory.APKS)
            if (ext in ARCHIVE_EXTENSIONS) add(FileCategory.ARCHIVES)
            if (file.sizeBytes > BIG_FILE_THRESHOLD_BYTES) add(FileCategory.BIG_FILES)
        }
    }

    /** The Files tab's drill-in: every file whose set contains [category]. */
    fun filesIn(category: FileCategory, all: List<GenericFile>): List<GenericFile> =
        all.filter { category in categoriesOf(it) }

    /** Live counts for the category cards (Section E.4). */
    fun counts(all: List<GenericFile>): Map<FileCategory, Int> =
        FileCategory.entries.associateWith { cat -> all.count { cat in categoriesOf(it) } }
}
