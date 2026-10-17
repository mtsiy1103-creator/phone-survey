package com.phonesurvey.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SourceType(val label: String) {
    INTERNAL("휴대폰 내장 GPS"),
    USB("USB 외장 수신기"),
    BLUETOOTH("블루투스 외장 수신기"),
}

data class AppSettings(
    val project: String = "현장1",
    val sourceType: SourceType = SourceType.INTERNAL,
    val baudRate: Int = 115200,
    val bluetoothAddress: String = "",
    val ntripHost: String = "",
    val ntripPort: Int = 2101,
    val ntripMount: String = "",
    val ntripUser: String = "",
    val ntripPassword: String = "",
    val ntripAutoStart: Boolean = true,
    val crsEpsg: Int = 5186,
    val antennaHeight: Double = 1.5,
    val averageSeconds: Int = 5,
    val siteOffset: Double = 0.0,
    val dxfTextHeight: Double = 0.3,
    val geoidName: String = "",
    val nextPointName: String = "1",
    val recentCodes: List<String> = emptyList(),
)

/** Settings persisted in SharedPreferences and observed as a flow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings get() = _state.value

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            project = prefs.getString("project", d.project) ?: d.project,
            sourceType = runCatching { SourceType.valueOf(prefs.getString("sourceType", d.sourceType.name)!!) }.getOrDefault(d.sourceType),
            baudRate = prefs.getInt("baudRate", d.baudRate),
            bluetoothAddress = prefs.getString("bluetoothAddress", d.bluetoothAddress) ?: "",
            ntripHost = prefs.getString("ntripHost", d.ntripHost) ?: "",
            ntripPort = prefs.getInt("ntripPort", d.ntripPort),
            ntripMount = prefs.getString("ntripMount", d.ntripMount) ?: "",
            ntripUser = prefs.getString("ntripUser", d.ntripUser) ?: "",
            ntripPassword = prefs.getString("ntripPassword", d.ntripPassword) ?: "",
            ntripAutoStart = prefs.getBoolean("ntripAutoStart", d.ntripAutoStart),
            crsEpsg = prefs.getInt("crsEpsg", d.crsEpsg),
            antennaHeight = getDouble("antennaHeight", d.antennaHeight),
            averageSeconds = prefs.getInt("averageSeconds", d.averageSeconds),
            siteOffset = getDouble("siteOffset", d.siteOffset),
            dxfTextHeight = getDouble("dxfTextHeight", d.dxfTextHeight),
            geoidName = prefs.getString("geoidName", "") ?: "",
            nextPointName = prefs.getString("nextPointName", d.nextPointName) ?: d.nextPointName,
            recentCodes = (prefs.getString("recentCodes", "") ?: "").split('\n').filter { it.isNotBlank() },
        )
    }

    // Doubles are stored as text so values like 1.234 round-trip exactly.
    private fun getDouble(key: String, default: Double): Double =
        runCatching { prefs.getString(key, null)?.toDoubleOrNull() }.getOrNull() ?: default

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("project", s.project)
            .putString("sourceType", s.sourceType.name)
            .putInt("baudRate", s.baudRate)
            .putString("bluetoothAddress", s.bluetoothAddress)
            .putString("ntripHost", s.ntripHost)
            .putInt("ntripPort", s.ntripPort)
            .putString("ntripMount", s.ntripMount)
            .putString("ntripUser", s.ntripUser)
            .putString("ntripPassword", s.ntripPassword)
            .putBoolean("ntripAutoStart", s.ntripAutoStart)
            .putInt("crsEpsg", s.crsEpsg)
            .putString("antennaHeight", s.antennaHeight.toString())
            .putInt("averageSeconds", s.averageSeconds)
            .putString("siteOffset", s.siteOffset.toString())
            .putString("dxfTextHeight", s.dxfTextHeight.toString())
            .putString("geoidName", s.geoidName)
            .putString("nextPointName", s.nextPointName)
            .putString("recentCodes", s.recentCodes.joinToString("\n"))
            .apply()
    }
}
