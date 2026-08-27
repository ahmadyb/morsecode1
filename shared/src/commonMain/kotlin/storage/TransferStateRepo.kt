package net.morsecode.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.morsecode.net.TransferDirection
import net.morsecode.net.TransferRecord
import net.morsecode.net.TransferStateStore
import net.morsecode.net.TransferStatus
import net.morsecode.storage.db.MorseCodeDatabase
import net.morsecode.storage.db.Transfer_state

/**
 * SQLDelight-backed [TransferStateStore] (Sections 13 and 6).
 *
 * Implements the interface the sender/receiver code against, so the windowed
 * transfer persists resume state identically on Android and Desktop.
 *
 * Every write goes through `withContext(Dispatchers.Default)` so a SQLite
 * write on the transfer hot path never runs on the UI dispatcher.
 */
class TransferStateRepo(
    private val db: MorseCodeDatabase,
) : TransferStateStore {

    private val q get() = db.transferStateQueries

    override suspend fun begin(record: TransferRecord) = withContext(Dispatchers.Default) {
        q.upsert(
            transfer_id = record.transferId,
            file_id = record.fileId,
            batch_id = record.batchId,
            peer_device_id = record.peerDeviceId,
            filename = record.filename,
            total_chunks = record.totalChunks.toLong(),
            verified_chunks_bitmap = record.verifiedBitmap,
            sha256_full = record.sha256Full,
            status = record.status.wire,
            direction = record.direction.wire,
            updated_at = record.updatedAtEpochMs,
        )
    }

    override suspend fun markChunkVerified(transferId: String, fileId: String, bitmap: String) =
        withContext(Dispatchers.Default) {
            q.updateBitmap(
                verified_chunks_bitmap = bitmap,
                status = TransferStatus.TRANSFERRING.wire,
                updated_at = now(),
                transfer_id = transferId,
                file_id = fileId,
            )
        }

    override suspend fun setStatus(transferId: String, fileId: String, status: TransferStatus) =
        withContext(Dispatchers.Default) {
            q.updateStatus(status.wire, now(), transferId, fileId)
        }

    override suspend fun load(transferId: String, fileId: String): TransferRecord? =
        withContext(Dispatchers.Default) {
            q.selectById(transferId, fileId).executeAsOneOrNull()?.toRecord()
        }

    override suspend fun loadResumable(peerDeviceId: String, direction: TransferDirection): List<TransferRecord> =
        withContext(Dispatchers.Default) {
            q.selectForResume(peerDeviceId, direction.wire).executeAsList().map { it.toRecord() }
        }

    private fun now(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

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
