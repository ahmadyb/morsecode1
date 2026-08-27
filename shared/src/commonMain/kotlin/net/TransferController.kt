package net.morsecode.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred

/**
 * The runtime glue between the protocol layer and the UI.
 *
 * Owns: the listening server, the mDNS [Discovery], the accept loop (responder
 * handshake + [TransferReceiver]), and outbound sends via
 * [BroadcastCoordinator]. One instance per app session, constructed by the
 * platform entry point.
 *
 * The incoming-transfer prompt is modelled as a [CompletableDeferred] surfaced
 * through [pendingRequest]; ReceiveScreen completes it with accept/decline, so
 * the handshake blocks exactly until the user decides (Section 5).
 */
class TransferController(
    private val identity: DeviceIdentity,
    private val crypto: CryptoProvider,
    private val store: TransferStateStore,
    private val discovery: Discovery,
    private val isTrusted: (String) -> Boolean,
    private val autoAcceptScope: () -> AutoAcceptScope,
    private val sinkFactory: suspend (TransferRequest, FileManifestEntry) -> ChunkSink?,
    private val sourceFactory: (FileManifestEntry) -> ChunkSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: JvmTransferServer? = null
    private var acceptJob: Job? = null

    val boundPort = MutableStateFlow<Int?>(null)

    private val _pendingRequest = MutableStateFlow<PendingIncoming?>(null)
    val pendingRequest: StateFlow<PendingIncoming?> = _pendingRequest.asStateFlow()

    private val _receiveProgress = MutableStateFlow<TransferProgress?>(null)
    val receiveProgress: StateFlow<TransferProgress?> = _receiveProgress.asStateFlow()

    private val _batchProgress = MutableStateFlow<BatchProgress?>(null)
    val batchProgress: StateFlow<BatchProgress?> = _batchProgress.asStateFlow()

    /** A transfer the user has not yet accepted or declined. */
    data class PendingIncoming(
        val hello: Hello,
        val decision: CompletableDeferred<Boolean>,
    )

    /** Binds the server, starts discovery, and runs the accept loop. */
    fun start() {
        scope.launch {
            val srv = JvmTransferServer.bind(DEFAULT_PORT)
            server = srv
            boundPort.value = srv.port
            discovery.start(identity, srv.port)

            acceptJob = launch {
                while (true) {
                    val transport = srv.accept() ?: break
                    launch { handleIncoming(transport) }
                }
            }
        }
    }

    private suspend fun handleIncoming(transport: SocketTransport) {
        val coordinator = HandshakeCoordinator(
            crypto = crypto,
            identity = identity,
            activePairingToken = { activePairingToken },
            isTrustedDevice = isTrusted,
            autoAcceptScope = autoAcceptScope,
            promptUser = { hello ->
                val deferred = CompletableDeferred<Boolean>()
                _pendingRequest.value = PendingIncoming(hello, deferred)
                val answer = deferred.await()
                _pendingRequest.value = null
                answer
            },
        )
        when (val outcome = coordinator.respond(transport)) {
            is HandshakeOutcome.Success -> {
                val receiver = TransferReceiver(
                    connection = outcome.connection,
                    crypto = crypto,
                    store = store,
                    sinkFactory = sinkFactory,
                    decisionPolicy = { req ->
                        TransferResponse(
                            transferId = req.transferId,
                            decision = TransferDecision.ACCEPT_ALL,
                            acceptedFileIds = req.files.map { it.fileId },
                        )
                    },
                    nowMillis = { now() },
                )
                scope.launch { receiver.progress.collect { _receiveProgress.value = it } }
                receiver.receiveLoop()
            }

            else -> transport.close()
        }
    }

    fun acceptIncoming() {
        _pendingRequest.value?.decision?.complete(true)
    }

    fun declineIncoming() {
        _pendingRequest.value?.decision?.complete(false)
    }

    /** Optional pairing token advertised in HELLO / QR (Section 4). */
    var activePairingToken: String? = null

    /**
     * Sends [files] to [recipients] (one user action -> one batch, Section 7).
     */
    suspend fun send(recipients: List<Recipient>, files: List<FileManifestEntry>): BatchResult {
        val coordinator = BroadcastCoordinator(
            crypto = crypto,
            identity = identity,
            store = store,
            nowMillis = { now() },
            connect = { r ->
                val transport = JvmSocketTransport.connect(r.host, r.port)
                HandshakeCoordinator(
                    crypto = crypto,
                    identity = identity,
                    isTrustedDevice = isTrusted,
                ).initiate(transport, r.pairingToken, r.trusted)
            },
            sendTo = { connection, _, fs, batchId ->
                val sender = TransferSender(
                    connection = connection,
                    crypto = crypto,
                    store = store,
                    nowMillis = { now() },
                )
                sender.manifestBatchId = batchId
                fs.map { sender.sendFile(IdSeed(), it, sourceFactory(it)) }
            },
        )
        scope.launch { coordinator.progress.collect { _batchProgress.value = it } }
        return coordinator.sendBatch(
            files.map { OutgoingFile(it) { sourceFactory(it) } },
            recipients,
        )
    }

    /** A fresh transfer id: UUID-shaped hex from the crypto RNG. */
    private fun IdSeed(): String = net.morsecode.util.Ids.uuid(crypto.randomBytes(16))

    fun stop() {
        acceptJob?.cancel()
        scope.launch { server?.close() }
        discovery.stop()
        scope.cancel()
    }

    private fun now(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}
