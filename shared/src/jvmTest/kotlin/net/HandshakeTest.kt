package net.morsecode.net

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 2: key exchange and the HELLO flow (Section 4).
 *
 * Both halves run concurrently over an in-memory pipe, so these tests exercise
 * the real handshake sequence rather than a mocked one.
 */
class HandshakeTest {

    private val aliceIdentity = DeviceIdentity(
        deviceId = "alice-device-id",
        deviceName = "Alice's Pixel",
        deviceType = DeviceType.ANDROID,
        appVersion = "1.0.0",
    )
    private val bobIdentity = DeviceIdentity(
        deviceId = "bob-device-id",
        deviceName = "Bob's Desktop",
        deviceType = DeviceType.WINDOWS,
        appVersion = "1.0.0",
    )

    private fun initiator(
        pairingToken: String? = null,
        trusted: Set<String> = emptySet(),
    ) = HandshakeCoordinator(
        crypto = TestCrypto,
        identity = aliceIdentity,
        isTrustedDevice = { it in trusted },
    )

    private fun responder(
        activeToken: String? = null,
        trusted: Set<String> = emptySet(),
        scope: AutoAcceptScope = AutoAcceptScope.OFF,
        prompt: suspend (Hello) -> Boolean = { true },
    ) = HandshakeCoordinator(
        crypto = TestCrypto,
        identity = bobIdentity,
        activePairingToken = { activeToken },
        isTrustedDevice = { it in trusted },
        autoAcceptScope = { scope },
        promptUser = prompt,
    )

    private fun runHandshake(
        pairingToken: String? = null,
        responderToken: String? = null,
        trustedOnResponder: Set<String> = emptySet(),
        scope: AutoAcceptScope = AutoAcceptScope.OFF,
        prompt: suspend (Hello) -> Boolean = { true },
    ): Pair<HandshakeOutcome, HandshakeOutcome> = runBlocking {
        withTimeout(10_000) {
            val (transportA, transportB) = InMemoryTransport.pair()
            coroutineScope {
                val a = async { initiator(pairingToken).initiate(transportA, pairingToken) }
                val b = async {
                    responder(responderToken, trustedOnResponder, scope, prompt).respond(transportB)
                }
                a.await() to b.await()
            }
        }
    }

    @Test
    fun `a plain handshake succeeds and both sides learn each other's identity`() {
        val (a, b) = runHandshake()

        assertIs<HandshakeOutcome.Success>(a, "initiator expected success, got $a")
        assertIs<HandshakeOutcome.Success>(b, "responder expected success, got $b")

        assertEquals(bobIdentity.deviceId, a.peer.deviceId)
        assertEquals(bobIdentity.deviceName, a.peer.deviceName)
        assertEquals(DeviceType.WINDOWS, a.peer.deviceType)
        assertEquals(aliceIdentity.deviceId, b.peer.deviceId)
        assertTrue(a.connection.isInitiator)
        assertFalse(b.connection.isInitiator)
    }

    @Test
    fun `the established channel actually carries encrypted traffic both ways`() {
        val (a, b) = runHandshake()
        val initiator = (a as HandshakeOutcome.Success).connection
        val responder = (b as HandshakeOutcome.Success).connection

        runBlocking {
            withTimeout(5_000) {
                initiator.sendJson(MessageType.PING, Ping(1234L))
                val received = responder.receive()
                assertIs<ReceivedMessage.Payload>(received)
                assertEquals(MessageType.PING, received.type)
                assertEquals(1234L, MessageJson.decodeFromBytes<Ping>(received.plaintext).sentAtEpochMs)

                responder.sendJson(MessageType.PONG, Pong(1234L))
                val reply = initiator.receive()
                assertIs<ReceivedMessage.Payload>(reply)
                assertEquals(MessageType.PONG, reply.type)
            }
        }
    }

    @Test
    fun `a protocol version mismatch is rejected with the specified reason`() {
        val (a, b) = runBlocking {
            withTimeout(10_000) {
                val (transportA, transportB) = InMemoryTransport.pair()
                coroutineScope {
                    val client = async {
                        // protoVersion 999 will not match the responder's.
                        HandshakeCoordinator(TestCrypto, aliceIdentity, protoVersion = 999)
                            .initiate(transportA)
                    }
                    val server = async { responder().respond(transportB) }
                    client.await() to server.await()
                }
            }
        }

        assertIs<HandshakeOutcome.Rejected>(a)
        assertEquals(RejectReason.PROTOCOL_VERSION_MISMATCH, a.reason)
        assertIs<HandshakeOutcome.Rejected>(b)
        assertEquals(RejectReason.PROTOCOL_VERSION_MISMATCH, b.reason)
    }

    @Test
    fun `a wrong pairing token is rejected`() {
        val (a, _) = runHandshake(pairingToken = "wrong-token", responderToken = "right-token")
        assertIs<HandshakeOutcome.Rejected>(a)
        assertEquals(RejectReason.INVALID_PAIRING_TOKEN, a.reason)
    }

    @Test
    fun `the correct pairing token is accepted`() {
        val (a, _) = runHandshake(pairingToken = "shared-token", responderToken = "shared-token")
        assertIs<HandshakeOutcome.Success>(a)
    }

    @Test
    fun `a trusted device bypasses the token requirement entirely`() {
        // Section 9: a device_id already in TrustedDeviceRepo is pre-verified
        // regardless of pairing_token.
        val (a, b) = runHandshake(
            pairingToken = null,
            responderToken = "some-active-token",
            trustedOnResponder = setOf(aliceIdentity.deviceId),
        )
        assertIs<HandshakeOutcome.Success>(a)
        assertIs<HandshakeOutcome.Success>(b)
        assertTrue(b.peer.isTrusted, "responder must record that this peer was trusted")
    }

    @Test
    fun `declining the prompt produces user_declined`() {
        val (a, _) = runHandshake(prompt = { false })
        assertIs<HandshakeOutcome.Rejected>(a)
        assertEquals(RejectReason.USER_DECLINED, a.reason)
    }

    @Test
    fun `auto-accept ALL skips the prompt`() {
        var prompted = false
        val (a, _) = runHandshake(scope = AutoAcceptScope.ALL, prompt = { prompted = true; true })
        assertIs<HandshakeOutcome.Success>(a)
        assertFalse(prompted, "auto-accept must not prompt the user")
    }

    @Test
    fun `auto-accept TRUSTED_ONLY does not accept an untrusted device`() {
        val (a, _) = runHandshake(scope = AutoAcceptScope.TRUSTED_ONLY)
        assertIs<HandshakeOutcome.Rejected>(a)
    }

    @Test
    fun `the responder sees the initiator's HELLO fields`() {
        var seen: Hello? = null
        runHandshake(prompt = { seen = it; true })
        val hello = seen
        assertTrue(hello != null, "responder must have been shown the HELLO")
        assertEquals(aliceIdentity.deviceId, hello!!.deviceId)
        assertEquals(aliceIdentity.deviceName, hello.deviceName)
        assertEquals(DeviceType.ANDROID, hello.deviceType)
        assertEquals(PROTO_VERSION, hello.protoVersion)
    }

    @Test
    fun `constant-time comparison accepts equal tokens and rejects unequal ones`() {
        assertTrue(HandshakeCoordinator.constantTimeEquals("abc123", "abc123"))
        assertFalse(HandshakeCoordinator.constantTimeEquals("abc123", "abc124"))
        assertFalse(HandshakeCoordinator.constantTimeEquals("abc123", "abc12"))
        assertFalse(HandshakeCoordinator.constantTimeEquals("", "abc123"))
        assertTrue(HandshakeCoordinator.constantTimeEquals("", ""))
    }

    @Test
    fun `a peer that closes without a KEY_EXCHANGE fails cleanly`() {
        val outcome = runBlocking {
            withTimeout(5_000) {
                val (transportA, transportB) = InMemoryTransport.pair()
                transportB.close()
                responder().respond(transportA)
            }
        }
        assertIs<HandshakeOutcome.Failure>(outcome)
    }
}
