package net.morsecode.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bandwidth throttling (PROTOCOL SPECIFICATION, Section 12).
 *
 * A GLOBAL token bucket shared across all concurrent transfers. "Global" is
 * the important word: Section 7 allows up to six recipients in flight at once,
 * and a per-transfer limiter would let a six-way broadcast use six times the
 * configured budget. One instance is shared by every [TransferSender].
 *
 * Enforcement is a non-blocking `delay()` taken *before* the chunk is written,
 * exactly as the spec requires — never a blocking sleep on the transport
 * thread, and never a drop.
 *
 * The bucket is allowed to go into debt (negative token count). That is what
 * makes it correct under concurrency: each caller adds its own debt and then
 * waits for its own share, so N simultaneous callers are paced in aggregate
 * rather than each independently concluding it may burst.
 */
class BandwidthThrottle(
    /**
     * Monotonic nanosecond clock. Injectable so tests can advance time
     * deterministically instead of sleeping in real time — the difference
     * between a 2 ms test and a 30 second one.
     *
     * The default measures elapsed time from a single origin captured once at
     * construction. Calling `markNow().elapsedNow()` inline instead would create
     * a fresh mark on every call and therefore always report ~0, which would
     * make the bucket never refill and the limiter never actually limit — a bug
     * that is invisible until a transfer saturates a link.
     */
    private val nanoTime: () -> Long = MonotonicNanos,
    private val sleeper: suspend (milliseconds: Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    /**
     * `null` means unlimited — the default, matching the spec's
     * "Limit transfer speed (default OFF)".
     */
    var maxBandwidthKbps: Long? = null

    private val mutex = Mutex()
    private var tokens: Double = 0.0
    private var lastRefillNanos: Long? = null

    /**
     * Burst allowance in seconds.
     *
     * One second of the configured rate. Without a burst allowance the first
     * chunk of every transfer stalls, and with an unbounded one the limiter
     * takes a full second to converge after a burst. One second is the usual
     * compromise and is what `tc` uses by default.
     */
    private val burstSeconds: Double = 1.0

    /**
     * Blocks until [bytes] may be sent under the configured limit.
     *
     * Returns immediately when throttling is off. Never throws on cancellation
     * of its own accord — [sleeper] is `delay`, which is cancellable, so a
     * cancelled transfer stops waiting rather than leaking a parked coroutine.
     */
    suspend fun acquire(bytes: Int) {
        if (bytes <= 0) return
        val rateKbps = maxBandwidthKbps ?: return
        if (rateKbps <= 0) return

        // kbit/s -> bytes/ns.
        val bytesPerNano = (rateKbps * 1000.0) / 8.0 / 1_000_000_000.0
        if (bytesPerNano <= 0.0) return

        val waitNanos = mutex.withLock {
            val now = nanoTime()
            val previous = lastRefillNanos
            if (previous != null) {
                val elapsed = (now - previous).coerceAtLeast(0L)
                tokens = minOf(burstSeconds * rateKbps * 1000.0 / 8.0, tokens + elapsed * bytesPerNano)
            } else {
                // First call: start with a full burst bucket so the first chunk
                // of a transfer is not penalised for the app having been idle.
                tokens = burstSeconds * rateKbps * 1000.0 / 8.0
            }
            lastRefillNanos = now

            tokens -= bytes
            if (tokens >= 0.0) 0L else ((-tokens) / bytesPerNano).toLong() + 1L
        }

        if (waitNanos > 0) {
            sleeper((waitNanos / 1_000_000L).coerceAtLeast(1L))
        }
    }

    /** Clears accumulated debt, e.g. when the user raises the limit mid-transfer. */
    suspend fun reset() {
        mutex.withLock {
            tokens = 0.0
            lastRefillNanos = null
        }
    }

    companion object {
        /** A throttle that never limits. Useful as a no-op default in tests. */
        val UNLIMITED = BandwidthThrottle()
    }
}

/**
 * A monotonic nanosecond clock for [BandwidthThrottle].
 *
 * `TimeSource.Monotonic` in the common stdlib has no "give me the current
 * nanotime" accessor — only marks and the durations between them. So the origin
 * is captured once, at file-facility initialisation, and every reading is the
 * elapsed duration since. Monotonic, allocation-free per call, and identical in
 * behaviour to `System.nanoTime()` on the JVM targets this ships to.
 */
private val THROTTLE_CLOCK_ORIGIN = kotlin.time.TimeSource.Monotonic.markNow()

internal val MonotonicNanos: () -> Long = { THROTTLE_CLOCK_ORIGIN.elapsedNow().inWholeNanoseconds }
