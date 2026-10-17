package com.phonesurvey.app.gnss

import android.content.Context
import com.phonesurvey.app.data.AppSettings
import com.phonesurvey.app.data.SourceType
import com.phonesurvey.core.GnssFix
import com.phonesurvey.core.Nmea
import com.phonesurvey.core.NtripClient
import com.phonesurvey.core.NtripConfig
import com.phonesurvey.core.NtripStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.thread

sealed interface Connection {
    data object Disconnected : Connection
    data object Connecting : Connection
    data class Connected(val description: String) : Connection
    data class Failed(val message: String) : Connection
}

data class GnssState(
    val connection: Connection = Connection.Disconnected,
    val fix: GnssFix? = null,
    val ntrip: NtripStatus? = null,
    val acceptsCorrections: Boolean = false,
)

/** Owns the active position source and the NTRIP correction stream. */
class GnssController(private val context: Context) {
    private val _state = MutableStateFlow(GnssState())
    val state: StateFlow<GnssState> = _state.asStateFlow()

    private val _fixes = MutableSharedFlow<GnssFix>(extraBufferCapacity = 64)
    val fixes: SharedFlow<GnssFix> = _fixes.asSharedFlow()

    private var source: PositionSource? = null
    private var ntrip: NtripClient? = null

    fun connect(settings: AppSettings) {
        disconnect()
        _state.update { it.copy(connection = Connection.Connecting, fix = null) }
        val listener = object : SourceListener {
            override fun onFix(fix: GnssFix) {
                _state.update { it.copy(fix = fix) }
                _fixes.tryEmit(fix)
            }

            override fun onConnected(description: String) {
                _state.update { it.copy(connection = Connection.Connected(description)) }
                if (settings.ntripAutoStart && source?.acceptsCorrections == true && settings.ntripHost.isNotBlank()) {
                    startNtrip(settings)
                }
            }

            override fun onError(message: String) {
                _state.update { it.copy(connection = Connection.Failed(message)) }
            }
        }
        val s = when (settings.sourceType) {
            SourceType.INTERNAL -> InternalGnssSource(context, listener)
            SourceType.USB -> UsbGnssSource(context, settings.baudRate, listener)
            SourceType.BLUETOOTH -> BluetoothGnssSource(context, settings.bluetoothAddress, listener)
        }
        source = s
        _state.update { it.copy(acceptsCorrections = s.acceptsCorrections) }
        s.start()
    }

    fun disconnect() {
        stopNtrip()
        source?.stop()
        source = null
        _state.update { GnssState() }
    }

    fun startNtrip(settings: AppSettings) {
        stopNtrip()
        val target = source?.takeIf { it.acceptsCorrections } ?: return
        val client = NtripClient(
            config = NtripConfig(settings.ntripHost.trim(), settings.ntripPort, settings.ntripMount.trim(), settings.ntripUser, settings.ntripPassword),
            ggaProvider = { _state.value.fix?.let { Nmea.buildGga(it) } },
            onRtcm = { data, n -> target.writeCorrections(data, n) },
            onStatus = { status -> _state.update { it.copy(ntrip = status) } },
        )
        ntrip = client
        thread(name = "ntrip", isDaemon = true) { client.run() }
    }

    fun stopNtrip() {
        ntrip?.stop()
        ntrip = null
        _state.update { it.copy(ntrip = null) }
    }
}
