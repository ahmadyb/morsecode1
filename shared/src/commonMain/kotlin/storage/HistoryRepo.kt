package net.morsecode.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.morsecode.net.TransferDirection
import net.morsecode.net.TransferRecord
import net.morsecode.net.TransferStatus
import net.morsecode.storage.db.MorseCodeDatabase
import net.morsecode.storage.db.Transfer_state

/**
 * History tab data source (Section E.4).
 *
 * History is derived from `transfer_state` — a completed/failed/cancelled row
 * *is* the history entry, so there is no second table to keep in sync. Sent and
 * Received are just the `direction` filter.
 *
 * Day bucketing happens in Kotlin ([net.morsecode.media.DateGrouping]), not in
 * SQL, because SQLite has no timezone support and the section headers must
 * respect the device's local day boundary.
 */
class HistoryRepo(
    private val db: MorseCodeDatabase,
) {
    private val q get() = db.transferStateQueries

    suspend fun sent(): List<TransferRecord> = withContext(Dispatchers.Default) {
        q.selectHistory(TransferDirection.SENT.wire).executeAsList().map { it.toRecord() }
    }

    suspend fun received(): List<TransferRecord> = withContext(Dispatchers.Default) {
        q.selectHistory(TransferDirection.RECEIVED.wire).executeAsList().map { it.toRecord() }
    }

    suspend fun all(): List<TransferRecord> = withContext(Dispatchers.Default) {
        q.selectHistoryAll().executeAsList().map { it.toRecord() }
    }

    /** The per-device breakdown of one batch (Section 7). */
    suspend fun byBatch(batchId: String): List<TransferRecord> = withContext(Dispatchers.Default) {
        q.selectByBatch(batchId).executeAsList().map { it.toRecord() }
    }

    private fun Transfer_state.toRecord(): TransferRecord = TransferRecord(
        transferId = transfer_id,
        fileId = file_id,
        batchId = batch_id,
        peerDeviceId = peer_device_id,
        filename = filename,
        totalChunks = total_chunks.toInt(),
        verifiedBitmap = verified_chunks_bitmap,
        sha256Full = sha256_full,
        status = TransferStatus.fromWire(status),
        direction = TransferDirection.fromWire(direction),
        updatedAtEpochMs = updated_at,
    )
}
