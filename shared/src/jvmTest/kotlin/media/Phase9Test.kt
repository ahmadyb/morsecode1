package net.morsecode.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * Phase 9: categorisation and grouping are pure commonMain logic and are
 * verified here with no platform data source, exactly as the spec requires
 * before the Library UI (Phase 11) is built on top of them.
 */
class Phase9Test {

    private fun file(name: String, ext: String, size: Long): GenericFile = GenericFile(
        uri = "file:///$name",
        filename = "$name.$ext",
        relativePath = "",
        sizeBytes = size,
        extension = ext,
        modifiedEpochMs = 0,
    )

    // ── FileCategorizer ─────────────────────────────────────────────────────

    @Test
    fun `a large docx appears in Documents and Big Files simultaneously`() {
        val big = file("report", "docx", 60L * 1024 * 1024)
        val cats = FileCategorizer.categoriesOf(big)
        assertTrue(FileCategory.DOCUMENTS in cats)
        assertTrue(FileCategory.BIG_FILES in cats, "categories are non-exclusive")
    }

    @Test
    fun `a small docx is Documents but not Big Files`() {
        val cats = FileCategorizer.categoriesOf(file("memo", "docx", 1024))
        assertTrue(FileCategory.DOCUMENTS in cats)
        assertFalse(FileCategory.BIG_FILES in cats)
    }

    @Test
    fun `the 50 MiB boundary is exclusive`() {
        val at = file("at", "bin", 50L * 1024 * 1024)
        val over = file("over", "bin", 50L * 1024 * 1024 + 1)
        assertFalse(FileCategory.BIG_FILES in FileCategorizer.categoriesOf(at), "exactly 50MiB is not big")
        assertTrue(FileCategory.BIG_FILES in FileCategorizer.categoriesOf(over))
    }

    @Test
    fun `extension matching is case-insensitive and dot-tolerant`() {
        assertTrue(FileCategory.DOCUMENTS in FileCategorizer.categoriesOf(file("a", "PDF", 1)))
        assertTrue(FileCategory.ARCHIVES in FileCategorizer.categoriesOf(file("b", ".zip", 1)))
        assertTrue(FileCategory.APKS in FileCategorizer.categoriesOf(file("c", "APK", 1)))
    }

    @Test
    fun `each extension family lands in its category`() {
        assertEquals(setOf(FileCategory.EBOOKS), FileCategorizer.categoriesOf(file("book", "epub", 1)))
        assertEquals(setOf(FileCategory.ARCHIVES), FileCategorizer.categoriesOf(file("arc", "rar", 1)))
        assertEquals(setOf(FileCategory.APKS), FileCategorizer.categoriesOf(file("app", "apk", 1)))
    }

    @Test
    fun `an unknown small extension has no category`() {
        assertTrue(FileCategorizer.categoriesOf(file("data", "xyz", 1)).isEmpty())
    }

    @Test
    fun `filesIn returns the filtered view and counts are live`() {
        val all = listOf(
            file("a", "docx", 1),
            file("b", "docx", 60L * 1024 * 1024),
            file("c", "apk", 1),
        )
        assertEquals(2, FileCategorizer.filesIn(FileCategory.DOCUMENTS, all).size)
        assertEquals(1, FileCategorizer.filesIn(FileCategory.BIG_FILES, all).size)
        assertEquals(1, FileCategorizer.filesIn(FileCategory.APKS, all).size)

        val counts = FileCategorizer.counts(all)
        assertEquals(2, counts[FileCategory.DOCUMENTS])
        assertEquals(1, counts[FileCategory.BIG_FILES])
        assertEquals(0, counts[FileCategory.EBOOKS])
    }

    // ── DateGrouping ────────────────────────────────────────────────────────

    private val utc = TimeZone.UTC

    private fun day(y: Int, m: Int, d: Int): Long = LocalDate(y, m, d).atStartOfDayIn(utc).toEpochMilliseconds()

    @Test
    fun `items group by local day newest first with formatted headers`() {
        val items = listOf(
            "aug26" to (day(2026, 8, 26) + 1000),
            "aug26b" to (day(2026, 8, 26) + 2000),
            "aug27" to (day(2026, 8, 27) + 500),
        )
        val groups = DateGrouping.groupByDay(items, { it.second }, utc)

        assertEquals(2, groups.size)
        assertEquals("August 27, 2026", groups[0].header, "newest day first")
        assertEquals(listOf("aug27"), groups[0].items.map { it.first })
        assertEquals("August 26, 2026", groups[1].header)
        assertEquals(listOf("aug26", "aug26b"), groups[1].items.map { it.first }, "input order preserved within a day")
    }

    @Test
    fun `23h50 and 00h10 twenty minutes apart land in different groups`() {
        val late = day(2026, 8, 26) + 23 * 3600_000 + 50 * 60_000
        val early = day(2026, 8, 27) + 10 * 60_000
        val groups = DateGrouping.groupByDay(listOf("late" to late, "early" to early), { it.second }, utc)
        assertEquals(2, groups.size, "the day boundary must split them")
    }

    @Test
    fun `header uses the full month name`() {
        assertEquals("January 1, 2026", DateGrouping.headerFor(LocalDate(2026, 1, 1)))
        assertEquals("December 31, 2025", DateGrouping.headerFor(LocalDate(2025, 12, 31)))
    }

    @Test
    fun `an empty list yields no groups`() {
        assertTrue(DateGrouping.groupByDay(emptyList<Pair<String, Long>>(), { it.second }).isEmpty())
    }
}
