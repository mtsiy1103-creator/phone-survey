package com.phonesurvey.app.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.phonesurvey.core.FixQuality
import com.phonesurvey.core.GnssFix
import com.phonesurvey.core.Nmea
import com.phonesurvey.core.NmeaFixAssembler
import com.phonesurvey.core.NmeaLineSplitter
import com.phonesurvey.core.NmeaSentence
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/** Callbacks a position source reports through. May be called from any thread. */
interface SourceListener {
    fun onFix(fix: GnssFix)
    fun onConnected(description: String)
    fun onError(message: String)
}

interface PositionSource {
    /** Starts asynchronously; results arrive through the listener. */
    fun start()
    fun stop()
    /** True when correction data can be sent to this source (external receivers only). */
    val acceptsCorrections: Boolean
    fun writeCorrections(data: ByteArray, length: Int) = Unit
}

/** The phone's own GNSS chip through the Android location API. */
class InternalGnssSource(private val context: Context, private val listener: SourceListener) : PositionSource {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var satellitesUsed = 0
    private var lastGga: GnssFix? = null

    override val acceptsCorrections = false

    // A full object rather than a lambda: before Android 11 the other callbacks have no default implementation.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = listener.onFix(toFix(location))
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = listener.onError("휴대폰 위치(GPS)가 꺼졌습니다")
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    // Many chips also emit NMEA; its GGA carries the receiver's geoid separation.
    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        (Nmea.parse(message) as? NmeaSentence.Gga)?.let { lastGga = it.fix }
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("위치 권한이 필요합니다")
            return
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            listener.onError("휴대폰 위치(GPS)가 꺼져 있습니다")
            return
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        lm.registerGnssStatusCallback(statusCallback, handler)
        lm.addNmeaListener(nmeaListener, handler)
        listener.onConnected("휴대폰 내장 GPS")
    }

    override fun stop() {
        lm.removeUpdates(locationListener)
        lm.unregisterGnssStatusCallback(statusCallback)
        lm.removeNmeaListener(nmeaListener)
    }

    private fun toFix(loc: Location): GnssFix {
        val ellipsoidal = loc.altitude
        val gga = lastGga?.takeIf { System.currentTimeMillis() - it.timestampMillis < 5000 }
        val separation = when {
            Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> ellipsoidal - loc.mslAltitudeMeters
            gga != null -> gga.geoidSeparation
            else -> 0.0
        }
        return GnssFix(
            latitude = loc.latitude,
            longitude = loc.longitude,
            mslHeight = ellipsoidal - separation,
            geoidSeparation = separation,
            quality = gga?.quality ?: FixQuality.SINGLE,
            satellites = satellitesUsed,
            hdop = gga?.hdop,
            correctionAge = null,
            utcTime = null,
            // Android reports 68% confidence radius, roughly 1 sigma.
            sigmaHorizontal = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            sigmaVertical = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
        )
    }
}

/** Shared NMEA handling for external receivers. */
private class NmeaPipe(listener: SourceListener) {
    private val assembler = NmeaFixAssembler { listener.onFix(it) }
    private val splitter = NmeaLineSplitter { assembler.accept(it) }
    fun feed(data: ByteArray, length: Int) = splitter.feed(data, length)
}

/** External receiver on a USB-serial adapter (USB-C OTG). */
class UsbGnssSource(
    private val context: Context,
    private val baudRate: Int,
    private val listener: SourceListener,
) : PositionSource {
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var permissionReceiver: BroadcastReceiver? = null
    private val pipe = NmeaPipe(listener)

    override val acceptsCorrections = true

    override fun start() {
        val driver = findDriver()
        if (driver == null) {
            listener.onError("USB 수신기를 찾을 수 없습니다. 케이블(OTG)을 확인하세요")
            return
        }
        if (usb.hasPermission(driver.device)) open(driver) else requestPermission(driver)
    }

    private fun findDriver(): UsbSerialDriver? {
        UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()?.let { return it }
        // Receivers with native USB (u-blox, Quectel and others) show up as CDC-ACM with vendor IDs the prober doesn't list.
        return usb.deviceList.values.firstOrNull { it.isCdcAcm() }?.let { CdcAcmSerialDriver(it) }
    }

    private fun UsbDevice.isCdcAcm(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == 2 || getInterface(it).interfaceClass == 10 }

    private fun requestPermission(driver: UsbSerialDriver) {
        val action = context.packageName + ".USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                context.unregisterReceiver(this)
                permissionReceiver = null
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(driver)
                else listener.onError("USB 사용 권한이 거부되었습니다")
            }
        }
        permissionReceiver = receiver
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
        usb.requestPermission(driver.device, pi)
    }

    private fun open(driver: UsbSerialDriver) {
        try {
            val connection = usb.openDevice(driver.device) ?: throw IOException("USB 장치를 열 수 없습니다")
            val p = driver.ports.first()
            p.open(connection)
            p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { p.setDTR(true); p.setRTS(true) }
            port = p
            io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) = pipe.feed(data, data.size)
                override fun onRunError(e: Exception) = listener.onError("USB 연결 끊김: ${e.message ?: ""}")
            }).also { it.start() }
            listener.onConnected("USB ${driver.device.productName ?: driver.device.deviceName} @ $baudRate")
        } catch (e: Exception) {
            listener.onError(e.message ?: "USB 연결 실패")
            stop()
        }
    }

    override fun stop() {
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
        io?.stop()
        io = null
        runCatching { port?.close() }
        port = null
    }

    override fun writeCorrections(data: ByteArray, length: Int) {
        val p = port ?: return
        try {
            p.write(if (length == data.size) data else data.copyOf(length), 1000)
        } catch (e: IOException) {
            listener.onError("보정정보 전송 실패: ${e.message ?: ""}")
        }
    }
}

/** External receiver over Bluetooth Classic serial (SPP). */
class BluetoothGnssSource(
    private val context: Context,
    private val address: String,
    private val listener: SourceListener,
) : PositionSource {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var running = false
    private val pipe = NmeaPipe(listener)

    override val acceptsCorrections = true

    @SuppressLint("MissingPermission")
    override fun start() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError("블루투스 권한이 필요합니다")
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("블루투스가 꺼져 있습니다")
            return
        }
        if (address.isBlank()) {
            listener.onError("설정에서 블루투스 수신기를 선택하세요")
            return
        }
        running = true
        thread(name = "bt-gnss") {
            try {
                val device = adapter.getRemoteDevice(address)
                adapter.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = s
                s.connect()
                output = s.outputStream
                listener.onConnected("블루투스 ${device.name ?: address}")
                val input = s.inputStream
                val buf = ByteArray(2048)
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    pipe.feed(buf, n)
                }
                if (running) listener.onError("블루투스 연결이 끊겼습니다")
            } catch (e: Exception) {
                if (running) listener.onError("블루투스 연결 실패: ${e.message ?: ""}")
            }
        }
    }

    override fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    override fun writeCorrections(data: ByteArray, length: Int) {
        try {
            output?.write(data, 0, length)
        } catch (e: IOException) {
            listener.onError("보정정보 전송 실패: ${e.message ?: ""}")
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
