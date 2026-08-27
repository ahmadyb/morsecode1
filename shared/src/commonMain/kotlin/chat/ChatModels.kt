package net.morsecode.chat

/** Values stored in `chat_message.direction`. */
enum class ChatDirection(val wire: String) {
    SENT("sent"),
    RECEIVED("received");

    companion object {
        fun fromWire(value: String): ChatDirection =
            entries.firstOrNull { it.wire == value } ?: SENT
    }
}

/** A persisted row from `chat_message`. */
data class StoredChatMessage(
    val messageId: String,
    val peerDeviceId: String,
    val text: String,
    val direction: ChatDirection,
    val sentAtEpochMs: Long,
    val delivered: Boolean,
)

/** One row per peer for the conversation list on the Chat destination. */
data class ConversationPreview(
    val peerDeviceId: String,
    val peerName: String?,
    val lastText: String,
    val lastSentAtEpochMs: Long,
)
