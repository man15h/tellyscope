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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One device's connection lifecycle: connect, stream stats while online, reconnect on its own
 * after the TV sleeps or drops off Wi-Fi. NOT_ALLOWED and NEEDS_PAIRING stop the loop until
 * [connect] is called again. A device that was online before gets [notAllowedLimit] tries first,
 * since a TV waking up can stall the handshake exactly like an unanswered prompt; past that it
 * stops, because each try puts a fresh "Allow debugging?" prompt on the TV (revoked access, a
 * reset, or Deny). [knownGood] marks a device remembered from an earlier launch, which counts as
 * online before.
 */
class DeviceLink(
    val host: String,
    val port: Int,
    private val scope: CoroutineScope,
    private val retryEvery: Duration = 5.seconds,
    private val monitorFor: (AdbShell, StatsGate) -> MonitorSource = { s, gate -> DeviceMonitor(s, gate = gate) },
    private val open: suspend (String, Int) -> ConnectResult = { h, p -> Connector.connect(h, p) },
    private val knownGood: Boolean = false,
    private val notAllowedLimit: Int = 3,
) {
    private val _state = MutableStateFlow(DeviceState.FOUND)
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    /** Last reading; kept while offline so the page can show it greyed out with "last seen". */
    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats.asStateFlow()

    /** Pauses the stats while a change runs on this TV, and sets their refresh rates. */
    val gate = StatsGate()

    @Volatile var session: AdbSession? = null
        private set
    @Volatile var lastSeenMillis: Long? = null
        private set

    private var job: Job? = null

    fun connect() {
        job?.cancel()
        job = scope.launch { loop() }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _state.value = _state.value.on(DeviceEvent.Disconnected)
    }

    private suspend fun loop() {
        _state.value = _state.value.on(DeviceEvent.Connect)
        var wasOnline = knownGood
        var notAllowed = 0
        while (true) {
            val r = open(host, port)
            notAllowed = if (r is ConnectResult.NotAllowed) notAllowed + 1 else 0
            when (r) {
                is ConnectResult.NotAllowed -> if (!wasOnline || notAllowed >= notAllowedLimit) {
                    _state.value = _state.value.on(DeviceEvent.AuthTimedOut)
                    return
                }
                is ConnectResult.NeedsPairing -> {
                    _state.value = _state.value.on(DeviceEvent.NeedsPairing)
                    return
                }
                is ConnectResult.Unreachable -> _state.value = _state.value.on(DeviceEvent.Unreachable)
                is ConnectResult.Online -> {
                    wasOnline = true
                    online(r.session)
                    _state.value = _state.value.on(DeviceEvent.Lost)
                }
            }
            delay(retryEvery)
        }
    }

    /** Streams stats until the link drops. The session is closed on every exit, cancellation included. */
    private suspend fun online(s: AdbSession) {
        session = s
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
            session = null
            s.close()
        }
    }
}
