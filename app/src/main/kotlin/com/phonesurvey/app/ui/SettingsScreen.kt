package com.phonesurvey.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.phonesurvey.app.SurveyApp
import com.phonesurvey.app.data.SourceType
import com.phonesurvey.core.KoreanCrs
import com.phonesurvey.core.NtripClient
import com.phonesurvey.core.NtripMountpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val baudRates = listOf(9600, 38400, 57600, 115200, 230400, 460800, 921600)

@Composable
fun SettingsScreen(app: SurveyApp, snackbar: SnackbarHostState, requestBluetooth: () -> Unit, modifier: Modifier = Modifier) {
    val s by app.settings.state.collectAsState()
    val projects by app.projects.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun update(change: (com.phonesurvey.app.data.AppSettings) -> com.phonesurvey.app.data.AppSettings) = app.settings.update(change)

    var mountpoints by remember { mutableStateOf<List<NtripMountpoint>>(emptyList()) }
    var bondedDevices by remember { mutableStateOf(bondedDevices(context)) }

    val geoidLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val error = app.importGeoid(uri, displayName(context, uri))
            snackbar.showSnackbar(error ?: "지오이드 모델을 적용했습니다")
        }
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Section("현장")
        var projectText by remember(s.project) { mutableStateOf(s.project) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(projectText, { projectText = it }, label = { Text("현장 이름") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { if (projectText.isNotBlank()) update { it.copy(project = projectText.trim()) } },
                enabled = projectText.isNotBlank() && projectText.trim() != s.project,
            ) { Text("변경") }
        }
        if (projects.size > 1) {
            Picker("기존 현장", s.project, projects, { it }) { p -> update { it.copy(project = p) } }
        }

        Section("위치 소스")
        SourceType.entries.forEach { t ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = s.sourceType == t, onClick = {
                    update { it.copy(sourceType = t) }
                    if (t == SourceType.BLUETOOTH) {
                        requestBluetooth()
                        bondedDevices = bondedDevices(context)
                    }
                }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.sourceType == t, onClick = null)
                Text(t.label, Modifier.padding(start = 8.dp))
            }
        }
        when (s.sourceType) {
            SourceType.USB -> Picker("통신 속도 (baud)", s.baudRate, baudRates, { it.toString() }) { b -> update { it.copy(baudRate = b) } }
            SourceType.BLUETOOTH -> {
                if (bondedDevices.isEmpty()) {
                    Text("휴대폰 블루투스 설정에서 수신기를 먼저 페어링하세요.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { requestBluetooth(); bondedDevices = bondedDevices(context) }) { Text("목록 새로고침") }
                } else {
                    val current = bondedDevices.firstOrNull { it.second == s.bluetoothAddress }
                    Picker("수신기", current, bondedDevices, { it?.first ?: "선택하세요" }) { d ->
                        if (d != null) update { it.copy(bluetoothAddress = d.second) }
                    }
                }
            }
            SourceType.INTERNAL -> Text(
                "내장 GPS는 보통 2~5m 오차가 있어 참고용입니다. cm급 정확도는 외장 RTK 수신기와 보정정보가 필요합니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("소스를 바꾼 뒤에는 측정 화면에서 다시 연결하세요.", style = MaterialTheme.typography.bodySmall)

        Section("보정정보 (NTRIP)")
        Text(
            "국토지리정보원 GNSS 데이터센터에 가입하고 VRS 이용 신청을 하면 받는 접속 정보를 입력하세요. 외장 수신기에서만 사용됩니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(s.ntripHost, { v -> update { it.copy(ntripHost = v) } }, label = { Text("주소") }, singleLine = true, modifier = Modifier.weight(2f))
            IntField("포트", s.ntripPort, { v -> update { it.copy(ntripPort = v) } }, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(s.ntripMount, { v -> update { it.copy(ntripMount = v) } }, label = { Text("마운트포인트") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { NtripClient.fetchSourceTable(s.ntripHost.trim(), s.ntripPort, s.ntripUser, s.ntripPassword) }
                        }
                        result.onSuccess {
                            mountpoints = it
                            if (it.isEmpty()) snackbar.showSnackbar("마운트포인트 목록이 비어 있습니다")
                        }.onFailure { snackbar.showSnackbar("목록을 불러오지 못했습니다: ${it.message ?: ""}") }
                    }
                },
                enabled = s.ntripHost.isNotBlank(),
            ) { Text("목록") }
        }
        if (mountpoints.isNotEmpty()) {
            Picker("마운트포인트 선택", mountpoints.firstOrNull { it.name == s.ntripMount }, mountpoints, { it?.let { m -> "${m.name} (${m.format})" } ?: "선택하세요" }) { m ->
                if (m != null) update { it.copy(ntripMount = m.name) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(s.ntripUser, { v -> update { it.copy(ntripUser = v) } }, label = { Text("아이디") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(
                s.ntripPassword, { v -> update { it.copy(ntripPassword = v) } }, label = { Text("비밀번호") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.weight(1f),
            )
        }
        SwitchRow("수신기 연결 시 보정 자동 시작", s.ntripAutoStart) { v -> update { it.copy(ntripAutoStart = v) } }

        Section("좌표계 · 높이")
        Picker("평면 좌표계", KoreanCrs.fromEpsg(s.crsEpsg), KoreanCrs.entries.toList(), { it.label }) { c -> update { it.copy(crsEpsg = c.epsg) } }
        Text(
            "국가 지오이드 모델 파일(ISG 형식 또는 위도 경도 지오이드고 표)을 넣으면 표고를 그 모델로 계산합니다. 없으면 수신기 내장 모델을 쓰며 수십 cm 차이가 날 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (s.geoidName.isBlank()) "지오이드: 수신기 내장" else "지오이드: ${s.geoidName}", Modifier.weight(1f))
            OutlinedButton(onClick = { geoidLauncher.launch(arrayOf("*/*")) }) { Text("파일 선택") }
            if (s.geoidName.isNotBlank()) TextButton(onClick = { app.clearGeoid() }) { Text("해제") }
        }
        NumberField(
            "현장 높이 보정값 (기지점 표고 − 측정 표고)", s.siteOffset,
            { v -> update { it.copy(siteOffset = v) } }, Modifier.fillMaxWidth(), suffix = "m", allowNegative = true,
        )

        Section("측정 · 내보내기")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField("평균 시간", s.averageSeconds, { v -> if (v in 1..600) update { it.copy(averageSeconds = v) } }, Modifier.weight(1f), suffix = "초")
            NumberField("DXF 글자 높이", s.dxfTextHeight, { v -> if (v > 0) update { it.copy(dxfTextHeight = v) } }, Modifier.weight(1f), suffix = "m")
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Picker(label: String, selected: T, options: List<T>, text: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(text(selected)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); open = false })
                }
            }
        }
    }
}

/** Paired Bluetooth devices as (name, address). */
@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<Pair<String, String>> {
    if (Build.VERSION.SDK_INT >= 31 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) return emptyList()
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return emptyList()
    return runCatching { adapter.bondedDevices.map { (it.name ?: it.address) to it.address } }.getOrDefault(emptyList())
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "지오이드"
