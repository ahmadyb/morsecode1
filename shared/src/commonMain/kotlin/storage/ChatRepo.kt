package net.morsecode.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.morsecode.chat.ChatDirection
import net.morsecode.chat.StoredChatMessage
import net.morsecode.storage.db.Chat_message
import net.morsecode.storage.db.MorseCodeDatabase

/**
 * `chat_message` persistence (Sections 13 and G).
 *
 * One conversation thread per `peer_device_id`; the Chat screen renders a
 * thread from [thread] and the destination list from [previews].
 */
class ChatRepo(
    private val db: MorseCodeDatabase,
) {
    private val q get() = db.chatMessageQueries

    suspend fun insert(message: StoredChatMessage) = withContext(Dispatchers.Default) {
        q.insert(
            message_id = message.messageId,
            peer_device_id = message.peerDeviceId,
            text = message.text,
            direction = message.direction.wire,
            sent_at = message.sentAtEpochMs,
            delivered = if (message.delivered) 1L else 0L,
        )
    }

    /** Re-inserts after a retransmit must not create a duplicate bubble. */
    suspend fun insertOrIgnore(message: StoredChatMessage) = withContext(Dispatchers.Default) {
        q.insertOrIgnore(
            message_id = message.messageId,
            peer_device_id = message.peerDeviceId,
            text = message.text,
            direction = message.direction.wire,
            sent_at = message.sentAtEpochMs,
            delivered = if (message.delivered) 1L else 0L,
        )
    }

    suspend fun thread(peerDeviceId: String): List<StoredChatMessage> =
        withContext(Dispatchers.Default) {
            q.selectThread(peerDeviceId).executeAsList().map { it.toStored() }
        }

    suspend fun threadTail(peerDeviceId: String, limit: Long): List<StoredChatMessage> =
        withContext(Dispatchers.Default) {
            q.selectThreadTail(peerDeviceId, limit).executeAsList().map { it.toStored() }
        }

    suspend fun markDelivered(messageId: String) = withContext(Dispatchers.Default) {
        q.markDelivered(messageId)
    }

    suspend fun previews(): List<StoredChatMessage> = withContext(Dispatchers.Default) {
        q.selectConversationPreviews().executeAsList().map { it.toStored() }
    }

    suspend fun countUndelivered(peerDeviceId: String): Long = withContext(Dispatchers.Default) {
        q.countUndelivered(peerDeviceId).executeAsOne()
    }

    suspend fun deleteThread(peerDeviceId: String) = withContext(Dispatchers.Default) {
        q.deleteThread(peerDeviceId)
    }

    private fun Chat_message.toStored(): StoredChatMessage = StoredChatMessage(
        messageId = message_id,
        peerDeviceId = peer_device_id,
        text = text,
        direction = ChatDirection.fromWire(direction),
        sentAtEpochMs = sent_at,
        delivered = delivered != 0L,
    )
}
