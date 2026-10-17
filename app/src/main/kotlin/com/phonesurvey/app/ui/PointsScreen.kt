package com.phonesurvey.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonesurvey.app.SurveyApp
import com.phonesurvey.core.SurveyPoint
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun PointsScreen(app: SurveyApp, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val points by app.points.collectAsState()
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SurveyPoint?>(null) }

    fun exportTo(uri: android.net.Uri?, dxf: Boolean) {
        if (uri == null) return
        scope.launch {
            val error = app.export(uri, dxf)
            snackbar.showSnackbar(error ?: "${points.size}개 점을 ${if (dxf) "DXF" else "CSV"}로 저장했습니다")
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { exportTo(it, false) }
    val dxfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) { exportTo(it, true) }
    val baseName = "${settings.project}_${LocalDate.now()}"

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(settings.project, style = MaterialTheme.typography.titleMedium)
                Text("${points.size}개 점", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { csvLauncher.launch("$baseName.csv") }, enabled = points.isNotEmpty()) { Text("CSV") }
            Button(onClick = { dxfLauncher.launch("$baseName.dxf") }, enabled = points.isNotEmpty()) { Text("DXF") }
        }
        HorizontalDivider()
        if (points.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("측정 화면에서 점을 저장하면 여기에 표시됩니다")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(points.reversed(), key = { it.id }) { p ->
                    PointRow(p) { editing = p }
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { p ->
        EditPointDialog(
            point = p,
            onDismiss = { editing = null },
            onSave = { app.updatePoint(it); editing = null },
            onDelete = { app.deletePoint(p.id); editing = null },
        )
    }
}

@Composable
private fun PointRow(p: SurveyPoint, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(p.code, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
            Text(p.quality.label, color = qualityColor(p.quality), style = MaterialTheme.typography.labelMedium)
        }
        Text(
            "X ${fmt(p.northing)}  Y ${fmt(p.easting)}  H ${fmt(p.elevation)}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun EditPointDialog(point: SurveyPoint, onDismiss: () -> Unit, onSave: (SurveyPoint) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(point.name) }
    var code by remember { mutableStateOf(point.code) }
    var note by remember { mutableStateOf(point.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("점 ${point.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("점번호") }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text("코드명") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("메모") })
                Text(
                    "기계고 ${fmt(point.antennaHeight)}m · ${point.samples}회 평균 · 위성 ${point.satellites}\n" +
                        "수평± ${fmt(point.sigmaHorizontal)}m 수직± ${fmt(point.sigmaVertical)}m · ${point.heightSource}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (confirmDelete) Text("한 번 더 누르면 삭제됩니다", color = Color(0xFFC62828))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(point.copy(name = name.trim(), code = code.trim(), note = note.trim())) }) { Text("저장") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text("삭제", color = Color(0xFFC62828))
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}
