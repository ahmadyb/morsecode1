package net.morsecode.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.morsecode.util.Ids

/** What "Create Room" produces (Section 8). */
data class RoomInfo(
    val roomId: String,
    val roomToken: String,
    val creatorIp: String,
    val creatorPort: Int,
) {
    /** QR payload: `{v:1, room_id, room_token, creator_ip, creator_port}`. */
    fun qrPayload(): RoomQrPayload = RoomQrPayload(
        roomId = roomId,
        roomToken = roomToken,
        creatorIp = creatorIp,
        creatorPort = creatorPort,
    )
}

/**
 * Room-based sharing (PROTOCOL SPECIFICATION, Section 8).
 *
 * Rooms are deliberately ephemeral: member state lives only in memory
 * ([_members]) and in mDNS presence. There is no persistence anywhere — when
 * the creator leaves, [leaveRoom] drops everything and the room ceases to
 * exist. That is the spec's "creator leaving ends the room" requirement made
 * structural rather than a cleanup task.
 *
 * This class holds the membership model and the relay rule; the socket/mDNS
 * wiring is injected so the rule is unit-testable.
 *
 * ── THE RELAY RULE ──────────────────────────────────────────────────────────
 * A new member sends ROOM_ANNOUNCE. The creator adds them, then sends the
 * *updated* ROOM_MEMBER_LIST to **every** member — the newcomer and the existing
 * members alike. Existing members must see the newcomer appear, and the newcomer
 * must see the whole roster including themselves; a single broadcast of the
 * post-join list satisfies both with no special-casing.
 *
 * ── OPEN JOIN ───────────────────────────────────────────────────────────────
 * There is no approval step. [handleAnnounce] never consults a token or asks a
 * user; the presence of a well-formed ROOM_ANNOUNCE is sufficient. The
 * `room_token` in the QR exists so a joiner can prove it scanned the right
 * room, not so the creator can vet them.
 */
class RoomManager(
    private val crypto: CryptoProvider,
) {
    private val _members = MutableStateFlow<List<RoomMember>>(emptyList())
    val members: StateFlow<List<RoomMember>> = _members.asStateFlow()

    /** Non-null only while this device is the room's creator. */
    var room: RoomInfo? = null
        private set

    val isCreator: Boolean get() = room != null

    val memberCount: Int get() = _members.value.size

    /** "Create Room". Idempotent only in the sense that it replaces any prior room. */
    fun createRoom(creatorIp: String, creatorPort: Int): RoomInfo {
        val info = RoomInfo(
            roomId = Ids.uuid(crypto.randomBytes(16)),
            roomToken = Ids.hex(crypto.randomBytes(16)),
            creatorIp = creatorIp,
            creatorPort = creatorPort,
        )
        room = info
        _members.value = emptyList()
        return info
    }

    /**
     * Creator-side: a member announced itself.
     *
     * @return the updated member list (the value also published to [members]).
     * @throws IllegalStateException if this device is not the creator — only
     *   the creator owns the authoritative roster; a member that receives an
     *   announce must route it to the creator, not apply it.
     */
    fun memberJoined(member: RoomMember): List<RoomMember> {
        check(isCreator) { "only the room creator maintains the member list" }
        val current = _members.value
        if (current.none { it.deviceId == member.deviceId }) {
            _members.value = current + member
        }
        return _members.value
    }

    /** Member-side: the creator pushed the authoritative roster. */
    fun applyMemberList(list: List<RoomMember>) {
        _members.value = list
    }

    /**
     * Creator-side: process a ROOM_ANNOUNCE and broadcast the updated list.
     *
     * [sendList] is how the message reaches each member; it is injected so a
     * test can record who was told what without a socket.
     *
     * @return the post-join member list.
     */
    suspend fun handleAnnounce(
        announce: RoomAnnounce,
        sendList: suspend (RoomMember, RoomMemberList) -> Unit,
    ): List<RoomMember> {
        val current = room ?: throw IllegalStateException("handleAnnounce on a non-creator")
        require(announce.roomId == current.roomId) {
            "announce for room ${announce.roomId} but this room is ${current.roomId}"
        }

        val updated = memberJoined(announce.member)
        val list = RoomMemberList(roomId = current.roomId, members = updated)

        // The single broadcast described in the class doc: newcomer + everyone
        // already present, so all rosters converge on the same list.
        for (member in updated) sendList(member, list)
        return updated
    }

    /**
     * The recipients for "Send to Room" (Section 8): every current member,
     * converted to connection targets. The 6-concurrent cap is enforced by the
     * caller's [BroadcastCoordinator], not here.
     */
    fun recipientsForSend(): List<RoomMember> = _members.value.toList()

    /** Creator leaving ends the room; a member leaving just clears local state. */
    fun leaveRoom() {
        room = null
        _members.value = emptyList()
    }
}
