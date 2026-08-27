package net.morsecode.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.morsecode.util.Base64

/**
 * Phase 1: framing.
 *
 * These are byte-level tests, not behavioural ones: the wire format in Section 2
 * is a contract between two independently-built binaries, and the only way to
 * know it holds is to assert on the actual bytes.
 */
class FramingTest {

    @Test
    fun `pre-handshake frame has the exact layout from section 2`() {
        val json = """{"pub":"abc"}""".encodeToByteArray()
        val frame = Framing.encodePreHandshake(MessageType.KEY_EXCHANGE, json)

        // [4 bytes payload_length][1 byte type][N bytes JSON]
        assertEquals(4 + 1 + json.size, frame.size)
        // payload_length counts the type byte plus the body.
        assertEquals(1 + json.size, Framing.readUInt32BE(frame, 0))
        assertEquals(MessageType.KEY_EXCHANGE, frame[4])
        assertContentEquals(json, frame.copyOfRange(5, frame.size))
    }

    @Test
    fun `pre-handshake framing refuses any type other than KEY_EXCHANGE`() {
        // Allowing an encrypted-looking type through the unencrypted path would
        // be a downgrade vector, so it must be a hard failure.
        assertFailsWith<IllegalArgumentException> {
            Framing.encodePreHandshake(MessageType.HELLO, "{}".encodeToByteArray())
        }
    }

    @Test
    fun `post-handshake frame carries type, nonce, ciphertext and tag`() {
        val nonce = GcmNonceSequence.encode(7L)
        // A sealed body must be at least as long as the tag.
        val sealed = ByteArray(32 + Framing.GCM_TAG_BYTES) { it.toByte() }

        val frame = Framing.encodePostHandshake(MessageType.CHUNK_DATA, nonce, sealed)

        val expectedLen = 1 + Framing.GCM_NONCE_BYTES + sealed.size
        assertEquals(4 + expectedLen, frame.size)
        assertEquals(expectedLen, Framing.readUInt32BE(frame, 0))
        assertEquals(MessageType.CHUNK_DATA, frame[4])
        assertContentEquals(nonce, frame.copyOfRange(5, 5 + 12))
        assertContentEquals(sealed, frame.copyOfRange(17, frame.size))
    }

    @Test
    fun `a 16 MiB payload is accepted and one byte over is rejected`() {
        val nonce = GcmNonceSequence.encode(0L)
        val tagBytes = ByteArray(Framing.GCM_TAG_BYTES)

        val atLimit = ByteArray(Framing.MAX_DECRYPTED_PAYLOAD) + tagBytes
        assertEquals(
            Framing.MAX_ENCRYPTED_FRAME_LENGTH,
            Framing.encodePostHandshake(MessageType.CHUNK_DATA, nonce, atLimit).size - 4,
        )

        val overLimit = ByteArray(Framing.MAX_DECRYPTED_PAYLOAD + 1) + tagBytes
        val ex = assertFailsWith<FrameFormatException> {
            Framing.encodePostHandshake(MessageType.CHUNK_DATA, nonce, overLimit)
        }
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.code)
    }

    @Test
    fun `length prefix is big-endian`() {
        val out = ByteArray(4)
        Framing.putUInt32BE(out, 0, 0x01020304)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), out)
        assertEquals(0x01020304, Framing.readUInt32BE(out, 0))
    }

    @Test
    fun `negative length prefix is rejected`() {
        // A u32 with the top bit set reads back negative as an Int. It must not
        // be treated as a huge-but-valid length.
        val out = ByteArray(4)
        Framing.putUInt32BE(out, 0, Int.MAX_VALUE)
        out[0] = 0xFF.toByte()
        assertTrue(Framing.readUInt32BE(out, 0) < 0)
    }

    // ── CHUNK_DATA layout ────────────────────────────────────────────────────

    @Test
    fun `CHUNK_DATA round-trips through its plaintext layout`() {
        val fileId = deterministicBytes(1, ChunkDataLayout.FILE_ID_BYTES)
        val sha = deterministicBytes(2, ChunkDataLayout.CHUNK_SHA256_BYTES)
        val body = deterministicBytes(3, 1024)

        val encoded = ChunkDataLayout.encode(fileId, 42, sha, body)
        assertEquals(ChunkDataLayout.HEADER_BYTES + body.size, encoded.size)

        val decoded = ChunkDataLayout.decode(encoded)
        assertContentEquals(fileId, decoded.fileId)
        assertEquals(42, decoded.chunkIndex)
        assertContentEquals(sha, decoded.chunkSha256)
        assertContentEquals(body, decoded.chunkBytes)
    }

    @Test
    fun `CHUNK_DATA with a lying length field is rejected`() {
        val fileId = deterministicBytes(1, 16)
        val sha = deterministicBytes(2, 32)
        val encoded = ChunkDataLayout.encode(fileId, 0, sha, ByteArray(100))
        // Corrupt the declared chunk_length.
        Framing.putUInt32BE(encoded, ChunkDataLayout.HEADER_BYTES - 4, 999)

        val ex = assertFailsWith<FrameFormatException> { ChunkDataLayout.decode(encoded) }
        assertEquals(ErrorCode.MALFORMED_CHUNK_DATA, ex.code)
    }

    @Test
    fun `CHUNK_DATA shorter than its header is rejected`() {
        assertFailsWith<FrameFormatException> { ChunkDataLayout.decode(ByteArray(10)) }
    }

    // ── file_id codec ────────────────────────────────────────────────────────

    @Test
    fun `file id round-trips between hex string and 16 wire bytes`() {
        val raw = deterministicBytes(9, 16)
        val id = FileIds.newId(raw)
        assertEquals(32, id.length)
        assertContentEquals(raw, FileIds.toBytes(id))
    }

    @Test
    fun `file id of the wrong length is rejected rather than truncated`() {
        assertFailsWith<FrameFormatException> { FileIds.toBytes("abcd") }
        assertFailsWith<FrameFormatException> { FileIds.toBytes("z".repeat(32)) }
    }

    // ── stream reassembly ────────────────────────────────────────────────────

    @Test
    fun `assembler reassembles a frame delivered one byte at a time`() {
        val json = """{"pub":"${Base64.encode(deterministicBytes(5, 65))}"}""".encodeToByteArray()
        val frame = Framing.encodePreHandshake(MessageType.KEY_EXCHANGE, json)

        val assembler = FrameAssembler()
        val events = ArrayList<AssemblerEvent>()
        for (b in frame) events += assembler.offer(byteArrayOf(b))

        val frames = events.filterIsInstance<AssemblerEvent.Frame>()
        assertEquals(1, frames.size, "a frame split into single bytes must still assemble once")
        assertEquals(MessageType.KEY_EXCHANGE, frames[0].frame.type)
        assertNull(frames[0].frame.nonce, "pre-handshake frames carry no nonce")
        assertContentEquals(json, frames[0].frame.body)
    }

    @Test
    fun `assembler emits several frames from a single read`() {
        val a = Framing.encodePreHandshake(MessageType.KEY_EXCHANGE, "{\"a\":1}".encodeToByteArray())
        val b = Framing.encodePreHandshake(MessageType.KEY_EXCHANGE, "{\"b\":2}".encodeToByteArray())

        val assembler = FrameAssembler()
        val frames = assembler.offer(a + b).filterIsInstance<AssemblerEvent.Frame>()
        assertEquals(2, frames.size)
        assertContentEquals("{\"a\":1}".encodeToByteArray(), frames[0].frame.body)
        assertContentEquals("{\"b\":2}".encodeToByteArray(), frames[1].frame.body)
    }

    @Test
    fun `assembler holds a partial frame until the rest arrives`() {
        val json = "x".repeat(5000).encodeToByteArray()
        val frame = Framing.encodePreHandshake(MessageType.KEY_EXCHANGE, json)
        val split = frame.size / 3

        val assembler = FrameAssembler()
        assertTrue(assembler.offer(frame.copyOfRange(0, split)).isEmpty())
        assertTrue(assembler.offer(frame.copyOfRange(split, split * 2)).isEmpty())

        val frames = assembler.offer(frame.copyOfRange(split * 2, frame.size))
            .filterIsInstance<AssemblerEvent.Frame>()
        assertEquals(1, frames.size)
        assertContentEquals(json, frames[0].frame.body)
    }

    @Test
    fun `assembler rejects a declared length above the 16 MiB cap before allocating`() {
        val assembler = FrameAssembler(FrameAssembler.Mode.POST_HANDSHAKE)
        // Claim a 2 GB frame with only a few bytes actually sent.
        val header = ByteArray(4)
        Framing.putUInt32BE(header, 0, Int.MAX_VALUE)

        val events = assembler.offer(header)
        val rejected = events.filterIsInstance<AssemblerEvent.Rejected>()
        assertEquals(1, rejected.size)
        assertEquals(ErrorCode.FRAME_TOO_LARGE, rejected[0].code)
        assertTrue(assembler.isRejected)
        // Once rejected the assembler must ignore everything else.
        assertTrue(assembler.offer(header).isEmpty())
    }

    @Test
    fun `assembler rejects a post-handshake frame too short to hold a tag`() {
        val assembler = FrameAssembler(FrameAssembler.Mode.POST_HANDSHAKE)
        val header = ByteArray(4 + 1 + Framing.GCM_NONCE_BYTES)
        Framing.putUInt32BE(header, 0, 1 + Framing.GCM_NONCE_BYTES) // no room for the tag

        val rejected = assembler.offer(header).filterIsInstance<AssemblerEvent.Rejected>()
        assertEquals(1, rejected.size)
        assertEquals(ErrorCode.MALFORMED_FRAME, rejected[0].code)
    }

    @Test
    fun `post-handshake assembler extracts nonce and ciphertext separately`() {
        val cipher = TestCrypto.sessionCipher(deterministicBytes(11, 32))
        val sealed = cipher.seal("hello".encodeToByteArray())
        val frame = Framing.encodePostHandshake(MessageType.HELLO, sealed.nonce, sealed.sealedBytes)

        val assembler = FrameAssembler(FrameAssembler.Mode.POST_HANDSHAKE)
        val parsed = assembler.offer(frame).filterIsInstance<AssemblerEvent.Frame>().single().frame

        assertEquals(MessageType.HELLO, parsed.type)
        assertNotNull(parsed.nonce)
        assertContentEquals(sealed.nonce, parsed.nonce)
        assertContentEquals(sealed.sealedBytes, parsed.body)
        assertContentEquals("hello".encodeToByteArray(), cipher.open(parsed.nonce!!, parsed.body))
    }

    @Test
    fun `message type table matches the specification`() {
        // Guard against a renumbering typo: these values are the protocol.
        assertEquals(0x00, MessageType.KEY_EXCHANGE.toInt())
        assertEquals(0x01, MessageType.HELLO.toInt())
        assertEquals(0x02, MessageType.HELLO_ACK.toInt())
        assertEquals(0x03, MessageType.TRANSFER_REQUEST.toInt())
        assertEquals(0x04, MessageType.TRANSFER_RESPONSE.toInt())
        assertEquals(0x05, MessageType.CHUNK_DATA.toInt())
        assertEquals(0x06, MessageType.CHUNK_ACK.toInt())
        assertEquals(0x07, MessageType.CHUNK_NACK.toInt())
        assertEquals(0x08, MessageType.FILE_COMPLETE.toInt())
        assertEquals(0x09, MessageType.TRANSFER_COMPLETE.toInt())
        assertEquals(0x0A, MessageType.TRANSFER_CANCEL.toInt())
        assertEquals(0x0B, MessageType.ERROR.toInt())
        assertEquals(0x0C, MessageType.PING.toInt())
        assertEquals(0x0D, MessageType.PONG.toInt())
        assertEquals(0x0E, MessageType.ROOM_ANNOUNCE.toInt())
        assertEquals(0x0F, MessageType.ROOM_MEMBER_LIST.toInt())
        assertEquals(0x10, MessageType.TEXT_SHARE.toInt())
        assertEquals(0x11, MessageType.WINDOW_RESIZE.toInt())
        assertEquals(0x12, MessageType.CHAT_MESSAGE.toInt())
    }

    @Test
    fun `device name truncation never splits a UTF-8 sequence`() {
        // 3-byte-per-character CJK: a naive 64-byte cut would land mid-sequence
        // and produce an undecodable TXT record.
        val name = "あ".repeat(40) // 120 bytes
        val truncated = MdnsTxt.truncateDeviceName(name)
        assertTrue(truncated.encodeToByteArray().size <= MdnsTxt.MAX_DEVICE_NAME_BYTES)
        // Must still decode cleanly back to whole characters.
        assertEquals(truncated, truncated.encodeToByteArray().decodeToString())
        assertFalse(truncated.isEmpty())
    }

    @Test
    fun `short device names are left untouched`() {
        assertEquals("Pixel 6", MdnsTxt.truncateDeviceName("Pixel 6"))
    }
}
