package com.phonesurvey.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonesurvey.app.SurveyApp
import com.phonesurvey.app.gnss.Connection
import com.phonesurvey.core.FixQuality
import com.phonesurvey.core.KoreanCrs
import com.phonesurvey.core.NtripStatus
import kotlinx.coroutines.launch

@Composable
fun MeasureScreen(app: SurveyApp, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val settings by app.settings.state.collectAsState()
    val gnss by app.gnss.state.collectAsState()
    val progress by app.measuring.collectAsState()
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(settings.nextPointName) }
    var code by remember { mutableStateOf(settings.recentCodes.firstOrNull() ?: "") }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(settings.nextPointName) { name = settings.nextPointName }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Connection and fix status
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val q = gnss.fix?.quality
                    Box(Modifier.size(14.dp).background(qualityColor(q), CircleShape))
                    Spacer(Modifier.size(8.dp))
                    Text(q?.label ?: "신호 없음", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = qualityColor(q))
                    Spacer(Modifier.weight(1f))
                    when (gnss.connection) {
                        is Connection.Connected, Connection.Connecting ->
                            OutlinedButton(onClick = { app.gnss.disconnect() }) { Text("연결 해제") }
                        else -> Button(onClick = { app.gnss.connect(app.settings.current) }) { Text("연결") }
                    }
                }
                Text(
                    when (val c = gnss.connection) {
                        Connection.Disconnected -> "${settings.sourceType.label} · 연결 안 됨"
                        Connection.Connecting -> "${settings.sourceType.label} · 연결 중…"
                        is Connection.Connected -> c.description
                        is Connection.Failed -> "오류: ${c.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                gnss.fix?.let { f ->
                    Text(
                        "위성 ${f.satellites}  HDOP ${fmt(f.hdop, 1)}  수평± ${fmt(f.sigmaHorizontal, 3)}m  수직± ${fmt(f.sigmaVertical, 3)}m" +
                            (f.correctionAge?.let { "  보정 ${fmt(it, 0)}초" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (gnss.acceptsCorrections) NtripRow(app, gnss.ntrip, settings.ntripHost.isNotBlank())
            }
        }

        // Live position
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val f = gnss.fix
                val crs = KoreanCrs.fromEpsg(settings.crsEpsg)
                val grid = f?.let { crs.forward(it.latitude, it.longitude) }
                val elev = f?.let {
                    app.heightModel().groundElevation(it.latitude, it.longitude, it.ellipsoidalHeight, it.mslHeight, settings.antennaHeight)
                }
                Text(crs.label, style = MaterialTheme.typography.labelMedium)
                CoordLine("X (북)", fmt(grid?.northing))
                CoordLine("Y (동)", fmt(grid?.easting))
                CoordLine("지반고", fmt(elev?.first))
                Text(
                    "위도 ${fmt(f?.latitude, 8)}  경도 ${fmt(f?.longitude, 8)}  높이기준: ${elev?.second ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Point input
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("점번호") }, singleLine = true, modifier = Modifier.weight(1f))
            NumberField(
                "기계고", settings.antennaHeight,
                { v -> app.settings.update { it.copy(antennaHeight = v) } },
                Modifier.weight(1f), suffix = "m",
            )
        }
        OutlinedTextField(code, { code = it }, label = { Text("코드명") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (settings.recentCodes.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(settings.recentCodes) { c -> AssistChip(onClick = { code = c }, label = { Text(c) }) }
            }
        }
        OutlinedTextField(note, { note = it }, label = { Text("메모") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        val p = progress
        if (p != null) {
            LinearProgressIndicator(
                progress = { (p.elapsedSeconds.toFloat() / p.totalSeconds).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("측정 중… ${p.elapsedSeconds}/${p.totalSeconds}초, ${p.samples}회 수신")
        }
        Button(
            onClick = {
                if (gnss.fix?.quality != FixQuality.RTK_FIX && gnss.acceptsCorrections) {
                    scope.launch { snackbar.showSnackbar("RTK Fix 상태가 아닙니다. 정확도가 낮을 수 있습니다") }
                }
                app.scope.launch {
                    val error = app.measurePoint(name, code, note)
                    if (error != null) snackbar.showSnackbar(error)
                    else {
                        note = ""
                        snackbar.showSnackbar("저장했습니다")
                    }
                }
            },
            enabled = p == null && gnss.fix != null && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("측정 저장 (${settings.averageSeconds}초 평균)", fontSize = 18.sp)
        }
    }
}

@Composable
private fun CoordLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NtripRow(app: SurveyApp, status: NtripStatus?, configured: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (status) {
                null -> if (configured) "보정정보: 꺼짐" else "보정정보: 설정에서 NTRIP 입력"
                NtripStatus.Connecting -> "보정정보: 연결 중…"
                is NtripStatus.Streaming -> "보정정보 수신 중 (${status.bytesReceived / 1024} KB)"
                is NtripStatus.Error -> "보정정보 오류: ${status.message}"
                NtripStatus.Stopped -> "보정정보: 중지됨"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (configured) {
            if (status == null || status == NtripStatus.Stopped) {
                OutlinedButton(onClick = { app.gnss.startNtrip(app.settings.current) }) { Text("보정 시작") }
            } else {
                OutlinedButton(onClick = { app.gnss.stopNtrip() }) { Text("보정 중지") }
            }
        }
    }
}
