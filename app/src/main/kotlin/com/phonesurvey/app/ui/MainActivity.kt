package com.phonesurvey.app.ui

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.phonesurvey.app.SurveyApp

class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Surveying means long stretches of standing on a point; keep the screen awake.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        val app = application as SurveyApp
        setContent {
            MaterialTheme(colorScheme = surveyColors) {
                MainScreen(app, requestBluetooth = {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                    }
                })
            }
        }
    }
}

private val surveyColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    secondary = Color(0xFF00695C),
    tertiary = Color(0xFFF9A825),
)

private enum class Tab(val label: String) { MEASURE("측정"), POINTS("목록"), PLAN("도면"), SETTINGS("설정") }

@Composable
private fun MainScreen(app: SurveyApp, requestBluetooth: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.MEASURE -> Icons.Filled.MyLocation
                                    Tab.POINTS -> Icons.AutoMirrored.Filled.List
                                    Tab.PLAN -> Icons.Filled.Map
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.MEASURE -> MeasureScreen(app, snackbar, modifier)
            Tab.POINTS -> PointsScreen(app, snackbar, modifier)
            Tab.PLAN -> PlanScreen(app, modifier)
            Tab.SETTINGS -> SettingsScreen(app, snackbar, requestBluetooth, modifier)
        }
    }
}
