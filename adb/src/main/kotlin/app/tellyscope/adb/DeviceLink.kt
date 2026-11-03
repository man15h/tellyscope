// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One device's connection lifecycle: connect, stream stats while online, reconnect on its own
 * after the TV sleeps or drops off Wi-Fi. NOT_ALLOWED and NEEDS_PAIRING stop the loop until
 * [connect] is called again: every try puts a fresh "Allow debugging?" prompt on the TV.
 *
 * A device that let this phone in before and then refuses it has taken the key back (revoked, Remove
 * all other access from another build, a reset): that's a logout, stamped in [loggedOutAt]. [knownGood]
 * marks a device remembered from an earlier launch, which counts as let in before. After
 * `connect(asked = true)` the user is watching the prompt, so a refusal is a plain NOT_ALLOWED until
 * the TV lets the phone in again.
 */
class DeviceLink(
    val host: String,
    val port: Int,
    private val scope: CoroutineScope,
    private val retryEvery: Duration = 5.seconds,
    private val monitorFor: (AdbShell, StatsGate) -> MonitorSource = { s, gate -> DeviceMonitor(s, gate = gate) },
    private val open: suspend (String, Int) -> ConnectResult = { h, p -> Connector.connect(h, p) },
    knownGood: Boolean = false,
) {
    private val _state = MutableStateFlow(DeviceState.FOUND)
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    /** Last reading; kept while offline so the page can show it greyed out with "last seen". */
    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats.asStateFlow()

    /** Pauses the stats while a change runs on this TV, and sets their refresh rates. */
    val gate = StatsGate()

    private val current = AtomicReference<AdbSession?>(null)
    val session: AdbSession? get() = current.get()
    @Volatile var lastSeenMillis: Long? = null
        private set

    /**
     * Epoch millis of the last logout; null once the TV lets this phone in, or refuses it after
     * `connect(asked = true)`. Kept through a reconnect attempt, so it only means "logged out" while
     * [state] is NOT_ALLOWED. Always written before [state], so a reader that reads [state] first
     * never sees a fresh NOT_ALLOWED with a stale value.
     */
    private val _loggedOutAt = MutableStateFlow<Long?>(null)
    val loggedOutAt: StateFlow<Long?> = _loggedOutAt.asStateFlow()

    /** The TV let this phone in before, so a refusal now means it took the key back. */
    @Volatile private var allowedBefore = knownGood

    private var job: Job? = null

    /**
     * Starts over. The old session is let go at once, so nothing reads through it while the new one
     * opens. [asked]: the user pressed connect, see the class doc.
     */
    fun connect(asked: Boolean = false) {
        job?.cancel()
        current.set(null)
        if (asked) allowedBefore = false
        // Set here, not in the loop: nobody may see the old state once connect has returned.
        _state.value = _state.value.on(DeviceEvent.Connect)
        job = scope.launch { loop() }
    }

    /** Logged out [at]: stays at NOT_ALLOWED without trying, so the TV gets no prompt until [connect]. */
    fun holdLoggedOut(at: Long) {
        job?.cancel()
        job = null
        current.set(null)
        allowedBefore = false
        _loggedOutAt.value = at
        _state.value = DeviceState.NOT_ALLOWED
    }

    /**
     * The phone left the network, so an online TV is out of reach now, though a read on the old
     * connection can take its socket timeout to fail. Offline at once; the loop retries as usual.
     */
    fun lanLost() {
        if (_state.value != DeviceState.ONLINE) return
        job?.cancel()
        current.getAndSet(null)?.close()
        _state.value = _state.value.on(DeviceEvent.Lost)
        job = scope.launch {
            delay(retryEvery)
            loop()
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _state.value = _state.value.on(DeviceEvent.Disconnected)
    }

    private suspend fun loop() {
        while (true) {
            gate.awaitResumed()
            when (val r = open(host, port)) {
                is ConnectResult.NotAllowed -> {
                    _loggedOutAt.value = if (allowedBefore) System.currentTimeMillis() else null
                    allowedBefore = false
                    _state.value = _state.value.on(DeviceEvent.AuthTimedOut)
                    return
                }
                is ConnectResult.NeedsPairing -> {
                    _state.value = _state.value.on(DeviceEvent.NeedsPairing)
                    return
                }
                is ConnectResult.Unreachable -> _state.value = _state.value.on(DeviceEvent.Unreachable)
                is ConnectResult.Online -> {
                    allowedBefore = true
                    _loggedOutAt.value = null
                    online(r.session)
                    _state.value = _state.value.on(DeviceEvent.Lost)
                }
            }
            delay(retryEvery)
        }
    }

    /** Streams stats until the link drops. The session is closed on every exit, cancellation included. */
    private suspend fun online(s: AdbSession) {
        current.set(s)
        _state.value = _state.value.on(DeviceEvent.Authorized)
        try {
            monitorFor(s, gate).stats().collect {
                _stats.value = it
                lastSeenMillis = System.currentTimeMillis()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Link dropped mid-read: the caller marks it offline and retries.
        } finally {
            // A cancelled loop gets here after connect has started over: leave the new session alone.
            current.compareAndSet(s, null)
            s.close()
        }
    }
}
