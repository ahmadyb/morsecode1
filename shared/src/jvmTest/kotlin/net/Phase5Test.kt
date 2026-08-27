package net.morsecode.net

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.morsecode.util.Ids

/**
 * Phase 5: broadcast (Section 7) and rooms (Section 8).
 *
 * The new logic being tested is the concurrency cap and the room relay rule —
 * the per-recipient transfer itself is already covered by Phase 4's tests, so
 * here [BroadcastCoordinator] is exercised with an injected send function that
 * only measures parallelism.
 */
class Phase5Test {

    private fun recipient(id: String): Recipient = Recipient(
        deviceId = id,
        deviceName = "device-$id",
        host = "10.0.0.1",
        port = 53317,
    )

    private fun file(id: String, bytes: Long = 4096): OutgoingFile = OutgoingFile(
        manifest = FileManifestEntry(
            fileId = id,
            filename = "$id.bin",
            sizeBytes = bytes,
            mimeType = "application/octet-stream",
            sha256Full = "00".repeat(32),
            chunkSize = 1024,
            totalChunks = ((bytes + 1023) / 1024).toInt(),
        ),
        newSource = { ByteArrayChunkSource(ByteArray(bytes.toInt()), 1024) },
    )

    // ── Ids ──────────────────────────────────────────────────────────────────

    @Test
    fun `uuid has the version-4 and variant-1 nibbles`() {
        val id = Ids.uuid(ByteArray(16) { 0x11 })
        assertEquals(36, id.length)
        assertEquals('4', id[14], "version nibble must be 4")
        assertTrue(id[19] in setOf('8', '9', 'a', 'b'), "variant nibble must be 10xx, got ${id[19]}")
    }

    @Test
    fun `uuid is built from the supplied random bytes`() {
        // With all-zero random bytes the only non-zero digits are the forced
        // version/variant nibbles.
        val id = Ids.uuid(ByteArray(16))
        assertTrue(id.startsWith("00000000-0000-4000-"))
        assertTrue(id.endsWith("-000000000000"))
    }

    @Test
    fun `hex token is lowercase and double length`() {
        val token = Ids.hex(byteArrayOf(0x00, 0xAB.toByte(), 0xFF.toByte()))
        assertEquals("00abff", token)
    }

    // ── BroadcastCoordinator ─────────────────────────────────────────────────

    @Test
    fun `concurrent recipients never exceed the cap`() = runBlocking {
        withTimeout(20_000) {
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)

            val coordinator = BroadcastCoordinator(
                crypto = TestCrypto,
                identity = DeviceIdentity("me", "Me", DeviceType.ANDROID, "1.0"),
                maxConcurrent = 2,
                nowMillis = { 0L },
                connect = { r ->
                    val pipe = InMemoryTransport.pair()
                    HandshakeOutcome.Success(
                        SecureConnection(pipe.first, TestCrypto.sessionCipher(deterministicBytes(1, 32)), PeerIdentity(r.deviceId, r.deviceName, "android", "1", 1, false), true),
                        PeerIdentity(r.deviceId, r.deviceName, "android", "1", 1, false),
                    )
                },
                sendTo = { _, _, files, _ ->
                    val cur = active.incrementAndGet()
                    maxActive.updateAndGet { m -> maxOf(m, cur) }
                    delay(80) // hold the slot long enough for overlap to be observable
                    active.decrementAndGet()
                    files.map { f -> SendFileResult(f.manifest.fileId, TransferStatus.COMPLETED, f.manifest.totalChunks, f.manifest.totalChunks, 80) }
                },
            )

            val recipients = (1..5).map { recipient("r$it") }
            val result = coordinator.sendBatch(listOf(file("0".repeat(32))), recipients)

            assertTrue(maxActive.get() <= 2, "observed ${maxActive.get()} concurrent; cap is 2")
            assertTrue(maxActive.get() >= 2, "with 5 recipients and cap 2 we should saturate the cap")
            assertEquals(5, result.recipients.size)
            assertTrue(result.allSucceeded)
        }
    }

    @Test
    fun `default cap is six`() {
        assertEquals(6, BroadcastCoordinator.MAX_CONCURRENT_RECIPIENTS)
    }

    @Test
    fun `a rejected recipient is recorded without failing the batch`() = runBlocking {
        withTimeout(10_000) {
            val coordinator = BroadcastCoordinator(
                crypto = TestCrypto,
                identity = DeviceIdentity("me", "Me", DeviceType.ANDROID, "1.0"),
                nowMillis = { 0L },
                connect = { r ->
                    if (r.deviceId == "bad") HandshakeOutcome.Rejected("user_declined")
                    else {
                        val pipe = InMemoryTransport.pair()
                        HandshakeOutcome.Success(
                            SecureConnection(pipe.first, TestCrypto.sessionCipher(deterministicBytes(2, 32)), PeerIdentity(r.deviceId, r.deviceName, "android", "1", 1, false), true),
                            PeerIdentity(r.deviceId, r.deviceName, "android", "1", 1, false),
                        )
                    }
                },
                sendTo = { _, _, files, _ ->
                    files.map { f -> SendFileResult(f.manifest.fileId, TransferStatus.COMPLETED, 1, 1, 0) }
                },
            )

            val result = coordinator.sendBatch(
                listOf(file("1".repeat(32))),
                listOf(recipient("good"), recipient("bad")),
            )

            assertEquals(2, result.recipients.size)
            val bad = result.recipients.first { it.deviceId == "bad" }
            assertFalse(bad.succeeded)
            assertNotNull(bad.errorCode)
            assertTrue(bad.errorCode!!.startsWith("rejected:"))
            assertTrue(result.recipients.first { it.deviceId == "good" }.succeeded)
            assertFalse(result.allSucceeded)
        }
    }

    @Test
    fun `progress counts reflect queued active and completed`() = runBlocking {
        withTimeout(10_000) {
            val coordinator = BroadcastCoordinator(
                crypto = TestCrypto,
                identity = DeviceIdentity("me", "Me", DeviceType.ANDROID, "1.0"),
                nowMillis = { 0L },
                connect = { r ->
                    val pipe = InMemoryTransport.pair()
                    HandshakeOutcome.Success(
                        SecureConnection(pipe.first, TestCrypto.sessionCipher(deterministicBytes(3, 32)), PeerIdentity(r.deviceId, r.deviceName, "android", "1", 1, false), true),
                        PeerIdentity(r.deviceId, r.deviceName, "android", "1", 1, false),
                    )
                },
                sendTo = { _, _, files, _ ->
                    delay(20)
                    files.map { f -> SendFileResult(f.manifest.fileId, TransferStatus.COMPLETED, 1, 1, 20) }
                },
            )
            coordinator.sendBatch(listOf(file("2".repeat(32), 2048)), (1..3).map { recipient("p$it") })

            val final = coordinator.progress.value
            assertEquals(3, final.totalRecipients)
            assertEquals(3, final.completed)
            assertEquals(0, final.active)
            assertEquals(0, final.queued)
            // 3 recipients x 2048 bytes, all completed.
            assertEquals(3 * 2048L, final.bytesTransferred)
            assertEquals(3 * 2048L, final.totalBytes)
        }
    }

    // ── RoomManager ──────────────────────────────────────────────────────────

    @Test
    fun `create room produces a v4 id and a hex token`() {
        val manager = RoomManager(TestCrypto)
        val room = manager.createRoom("192.168.1.10", 53317)

        assertEquals(36, room.roomId.length)
        assertEquals('4', room.roomId[14])
        assertTrue(room.roomToken.length >= 16, "room token should carry real entropy")
        assertTrue(room.roomToken.all { it in "0123456789abcdef" })
        assertTrue(manager.isCreator)

        val qr = room.qrPayload()
        assertEquals(1, qr.v)
        assertEquals(room.roomId, qr.roomId)
        assertEquals(room.roomToken, qr.roomToken)
        assertEquals("192.168.1.10", qr.creatorIp)
        assertEquals(53317, qr.creatorPort)
    }

    @Test
    fun `a join announce is relayed to the newcomer and every existing member`() = runBlocking {
        val manager = RoomManager(TestCrypto)
        manager.createRoom("10.0.0.1", 53317)

        val sent = mutableListOf<Pair<String, RoomMemberList>>()
        val relay: suspend (RoomMember, RoomMemberList) -> Unit = { member, list ->
            sent += member.deviceId to list
        }

        val a = RoomMember("a", "A", "android", "10.0.0.2", 1)
        val b = RoomMember("b", "B", "windows", "10.0.0.3", 1)

        // First join: the list broadcast goes to just the newcomer.
        manager.handleAnnounce(RoomAnnounce(manager.room!!.roomId, "tok", a), relay)
        assertEquals(1, sent.size)
        assertEquals("a", sent[0].first)
        assertEquals(listOf("a"), sent[0].second.members.map { it.deviceId })

        // Second join: both the existing member and the newcomer get the
        // updated two-member list.
        manager.handleAnnounce(RoomAnnounce(manager.room!!.roomId, "tok", b), relay)
        val lastTwo = sent.takeLast(2)
        assertEquals(setOf("a", "b"), lastTwo.map { it.first }.toSet())
        lastTwo.forEach { assertEquals(listOf("a", "b"), it.second.members.map { m -> m.deviceId }) }

        assertEquals(2, manager.memberCount)
    }

    @Test
    fun `a duplicate announce does not add the member twice`() = runBlocking {
        val manager = RoomManager(TestCrypto)
        manager.createRoom("10.0.0.1", 53317)
        val a = RoomMember("a", "A", "android", "10.0.0.2", 1)

        manager.handleAnnounce(RoomAnnounce(manager.room!!.roomId, "tok", a)) { _, _ -> }
        manager.handleAnnounce(RoomAnnounce(manager.room!!.roomId, "tok", a)) { _, _ -> }

        assertEquals(1, manager.memberCount, "re-announcing must not duplicate a member")
    }

    @Test
    fun `an announce for a different room is rejected`() = runBlocking {
        val manager = RoomManager(TestCrypto)
        manager.createRoom("10.0.0.1", 53317)
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            manager.handleAnnounce(RoomAnnounce("other-room", "tok", RoomMember("a", "A", "android", "1", 1))) { _, _ -> }
        }
        assertTrue(ex.message!!.contains("other-room"))
    }

    @Test
    fun `a non-creator cannot maintain a roster`() {
        val manager = RoomManager(TestCrypto)
        kotlin.test.assertFailsWith<IllegalStateException> {
            manager.memberJoined(RoomMember("a", "A", "android", "1", 1))
        }
    }

    @Test
    fun `leaving the room clears members and creatorship`() {
        val manager = RoomManager(TestCrypto)
        manager.createRoom("10.0.0.1", 53317)
        manager.applyMemberList(listOf(RoomMember("a", "A", "android", "1", 1)))
        manager.leaveRoom()
        assertFalse(manager.isCreator)
        assertEquals(0, manager.memberCount)
    }

    @Test
    fun `member applies the creator's authoritative list`() {
        val member = RoomManager(TestCrypto)
        assertFalse(member.isCreator)
        member.applyMemberList(listOf(
            RoomMember("creator", "C", "android", "10.0.0.1", 1),
            RoomMember("me", "M", "windows", "10.0.0.9", 1),
        ))
        assertEquals(2, member.memberCount)
        assertEquals(2, member.recipientsForSend().size)
    }
}
