package com.phonesurvey.app

import android.app.Application
import android.net.Uri
import com.phonesurvey.app.data.PointStore
import com.phonesurvey.app.data.SettingsStore
import com.phonesurvey.app.gnss.GnssController
import com.phonesurvey.core.CsvExporter
import com.phonesurvey.core.DxfExporter
import com.phonesurvey.core.FixAverager
import com.phonesurvey.core.GeoidGrid
import com.phonesurvey.core.HeightModel
import com.phonesurvey.core.KoreanCrs
import com.phonesurvey.core.SurveyPoint
import com.phonesurvey.core.buildPoint
import com.phonesurvey.core.nextPointName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Progress of the point currently being measured. */
data class MeasureProgress(val samples: Int, val elapsedSeconds: Int, val totalSeconds: Int)

class SurveyApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var gnss: GnssController
        private set
    private lateinit var store: PointStore

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _points = MutableStateFlow<List<SurveyPoint>>(emptyList())
    val points: StateFlow<List<SurveyPoint>> = _points.asStateFlow()

    private val _projects = MutableStateFlow<List<String>>(emptyList())
    val projects: StateFlow<List<String>> = _projects.asStateFlow()

    private val _measuring = MutableStateFlow<MeasureProgress?>(null)
    val measuring: StateFlow<MeasureProgress?> = _measuring.asStateFlow()

    private var geoid: GeoidGrid? = null
    private val geoidFile get() = File(filesDir, "geoid.dat")

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        gnss = GnssController(this)
        store = PointStore(this)
        scope.launch(Dispatchers.IO) {
            if (geoidFile.exists()) geoid = runCatching { loadGeoid(geoidFile, settings.current.geoidName) }.getOrNull()
        }
        scope.launch {
            settings.state.distinctUntilChangedBy { it.project }.collect { reloadPoints() }
        }
    }

    fun reloadPoints() {
        scope.launch {
            val project = settings.current.project
            val (pts, projs) = withContext(Dispatchers.IO) { store.list(project) to store.projects() }
            _points.value = pts
            _projects.value = (listOf(project) + projs).distinct()
        }
    }

    fun heightModel() = HeightModel(geoid, settings.current.siteOffset)

    /**
     * Averages fixes for the configured time and saves the point.
     * Returns an error message, or null on success.
     */
    suspend fun measurePoint(name: String, code: String, note: String): String? {
        if (_measuring.value != null) return "이미 측정 중입니다"
        if (gnss.state.value.fix == null) return "위치 신호가 없습니다"
        val s = settings.current
        val averager = FixAverager()
        val total = s.averageSeconds.coerceAtLeast(1)
        val start = System.currentTimeMillis()
        _measuring.value = MeasureProgress(0, 0, total)
        try {
            withTimeoutOrNull(total * 1000L) {
                gnss.fixes.collect { fix ->
                    averager.add(fix)
                    _measuring.value = MeasureProgress(averager.count, ((System.currentTimeMillis() - start) / 1000).toInt(), total)
                }
            }
        } finally {
            _measuring.value = null
        }
        val result = averager.result() ?: return "측정하는 동안 위치를 받지 못했습니다"
        val point = buildPoint(s.project, name.trim(), code.trim(), note.trim(), result, s.antennaHeight, KoreanCrs.fromEpsg(s.crsEpsg), heightModel())
        withContext(Dispatchers.IO) { store.insert(point) }
        settings.update {
            it.copy(
                nextPointName = nextPointName(name.trim()),
                recentCodes = if (code.isBlank()) it.recentCodes else (listOf(code.trim()) + it.recentCodes).distinct().take(12),
            )
        }
        reloadPoints()
        return null
    }

    fun updatePoint(p: SurveyPoint) {
        scope.launch {
            withContext(Dispatchers.IO) { store.update(p) }
            reloadPoints()
        }
    }

    fun deletePoint(id: Long) {
        scope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            reloadPoints()
        }
    }

    suspend fun export(uri: Uri, dxf: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = if (dxf) DxfExporter.export(points.value, settings.current.dxfTextHeight) else CsvExporter.export(points.value)
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("파일을 열 수 없습니다")
        }.exceptionOrNull()?.let { it.message ?: "저장 실패" }
    }

    /** Copies a geoid grid file (ISG or lat/lon/N table) into app storage and activates it. */
    suspend fun importGeoid(uri: Uri, displayName: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(filesDir, "geoid.tmp")
            contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: error("파일을 열 수 없습니다")
            val grid = loadGeoid(tmp, displayName)
            tmp.renameTo(geoidFile)
            geoid = grid
            settings.update { it.copy(geoidName = displayName) }
        }.exceptionOrNull()?.let { it.message ?: "지오이드 파일을 읽지 못했습니다" }
    }

    fun clearGeoid() {
        geoid = null
        geoidFile.delete()
        settings.update { it.copy(geoidName = "") }
    }

    private fun loadGeoid(file: File, name: String): GeoidGrid =
        file.bufferedReader().use { GeoidGrid.read(name.ifBlank { "지오이드" }, it) }
}
