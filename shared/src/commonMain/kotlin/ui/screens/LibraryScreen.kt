package net.morsecode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.morsecode.media.DateGrouping
import net.morsecode.media.FileCategory
import net.morsecode.media.FileCategorizer
import net.morsecode.media.GenericFile
import net.morsecode.net.TransferRecord
import net.morsecode.ui.AppState
import net.morsecode.ui.isDesktopPlatform

/**
 * Library: the six Xender-style tabs in order (Section A/E):
 * History | Apps | Photos | Videos | Music | Files.
 *
 * Deliberately none of Xender's ad extras — no "Hot" apps, no AI upsell, no
 * social. Every tab maps to functionality specified in this document.
 *
 * NOTE: the six tab composables are consolidated in this file rather than six
 * files to keep the shared module lean; each is a self-contained composable.
 *
 * The Apps tab is hidden on Desktop (Section D).
 */
@Composable
fun LibraryScreen(app: AppState) {
    val tabs = if (isDesktopPlatform()) {
        listOf("History", "Photos", "Videos", "Music", "Files")
    } else {
        listOf("History", "Apps", "Photos", "Videos", "Music", "Files")
    }
    var selected by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selected) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = selected == i, onClick = { selected = i }, text = { Text(title) })
            }
        }
        when (tabs[selected]) {
            "History" -> HistoryTab(app)
            "Apps" -> AppsTab(app)
            "Photos" -> PhotosTab(app)
            "Videos" -> VideosTab(app)
            "Music" -> MusicTab(app)
            else -> FilesTab(app)
        }
    }
}

// ── History ─────────────────────────────────────────────────────────────────

@Composable
private fun HistoryTab(app: AppState) {
    var sent by remember { mutableStateOf(true) }
    var records by remember { mutableStateOf<List<TransferRecord>>(emptyList()) }

    LaunchedEffect(sent) {
        records = if (sent) app.historyRepo.sent() else app.historyRepo.received()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { sent = true }, enabled = !sent) { Text("Sent") }
            Button(onClick = { sent = false }, enabled = sent) { Text("Received") }
        }
        val groups = DateGrouping.groupByDay(records, { it.updatedAtEpochMs })
        LazyColumn {
            groups.forEach { group ->
                item { DateHeader(group.header) }
                items(group.items, key = { it.transferId + it.fileId }) { r ->
                    HistoryRow(r)
                }
            }
        }
    }
}

@Composable
private fun DateHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun HistoryRow(r: TransferRecord) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.filename, style = MaterialTheme.typography.bodyLarge)
                Text("${r.status.wire} · ${r.direction.wire}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ── Apps (Android only) ─────────────────────────────────────────────────────

@Composable
private fun AppsTab(app: AppState) {
    val library = app.appLibrary
    var apps by remember { mutableStateOf<List<net.morsecode.media.AppInfo>>(emptyList()) }
    val selected = remember { mutableSetOf<String>() }

    LaunchedEffect(library) {
        apps = library?.getInstalledApps(false) ?: emptyList()
    }

    if (library == null) {
        Text("Apps are not available on Desktop.", Modifier.padding(16.dp))
        return
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            items(apps, key = { it.packageName }) { a ->
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selected.contains(a.packageName), onCheckedChange = { on ->
                        if (on) selected += a.packageName else selected -= a.packageName
                    })
                    Column {
                        Text(a.appName)
                        Text("${a.versionName} · ${a.apkSizeBytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        SendBar(selected.size) { /* hand to controller via platform sourceFactory */ }
    }
}

// ── Photos (grid) ───────────────────────────────────────────────────────────

@Composable
private fun PhotosTab(app: AppState) {
    var photos by remember { mutableStateOf<List<net.morsecode.media.PhotoItem>>(emptyList()) }
    LaunchedEffect(Unit) { photos = app.media.getPhotos() }

    val groups = DateGrouping.groupByDay(photos, { it.dateTakenEpochMs })
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        groups.forEach { g ->
            item { DateHeader(g.header) }
            item {
                LazyVerticalGrid(columns = GridCells.Fixed(3), userScrollEnabled = false) {
                    items(g.items) { p ->
                        Card(Modifier.padding(2.dp).size(110.dp)) {
                            Text(
                                p.filename.take(10),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Videos ─────────────────────────────────────────────────────────────────

@Composable
private fun VideosTab(app: AppState) {
    var videos by remember { mutableStateOf<List<net.morsecode.media.VideoItem>>(emptyList()) }
    LaunchedEffect(Unit) { videos = app.media.getVideos() }
    val groups = DateGrouping.groupByDay(videos, { it.dateAddedEpochMs })
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        groups.forEach { g ->
            item { DateHeader(g.header) }
            items(g.items) { v ->
                Card(Modifier.fillMaxWidth().padding(4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(v.filename)
                        Text(v.relativePath, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ── Music ──────────────────────────────────────────────────────────────────

@Composable
private fun MusicTab(app: AppState) {
    var audio by remember { mutableStateOf<List<net.morsecode.media.AudioItem>>(emptyList()) }
    LaunchedEffect(Unit) { audio = app.media.getAudio() }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(audio) { a ->
            Card(Modifier.fillMaxWidth().padding(4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(a.filename)
                    Text("${a.artist ?: "unknown"} · ${a.album ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ── Files ───────────────────────────────────────────────────────────────────

@Composable
private fun FilesTab(app: AppState) {
    var files by remember { mutableStateOf<List<GenericFile>>(emptyList()) }
    var usage by remember { mutableStateOf<net.morsecode.media.StorageUsage?>(null) }
    LaunchedEffect(Unit) {
        files = app.media.getAllFiles()
        usage = app.media.getStorageUsage()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        usage?.let { u ->
            Text("Storage", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { u.fraction }, modifier = Modifier.fillMaxWidth())
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val counts = FileCategorizer.counts(files)
        LazyVerticalGrid(columns = GridCells.Fixed(2), userScrollEnabled = false) {
            items(FileCategory.entries) { cat ->
                Card(Modifier.padding(4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(cat.label, style = MaterialTheme.typography.titleMedium)
                        Text("${counts[cat] ?: 0} files", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SendBar(count: Int, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$count selected", Modifier.weight(1f))
        Button(onClick = onSend, enabled = count > 0) { Text("Send") }
    }
}
