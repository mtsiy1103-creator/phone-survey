package com.phonesurvey.app.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.phonesurvey.app.SurveyApp
import com.phonesurvey.core.KoreanCrs
import com.phonesurvey.core.SurveyPoint
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

private val codePalette = listOf(
    Color(0xFFD32F2F), Color(0xFF1976D2), Color(0xFF388E3C), Color(0xFF7B1FA2),
    Color(0xFFF57C00), Color(0xFF0097A7), Color(0xFF5D4037), Color(0xFFC2185B),
)

private fun codeColor(code: String) = codePalette[(code.hashCode() and 0x7fffffff) % codePalette.size]

@Composable
fun PlanScreen(app: SurveyApp, modifier: Modifier = Modifier) {
    val points by app.points.collectAsState()
    val gnss by app.gnss.state.collectAsState()
    val settings by app.settings.state.collectAsState()
    val crs = KoreanCrs.fromEpsg(settings.crsEpsg)
    val here = gnss.fix?.let { crs.forward(it.latitude, it.longitude) }

    // View state: world coordinate at the screen centre and pixels per metre.
    var centerE by remember { mutableStateOf(Double.NaN) }
    var centerN by remember { mutableStateOf(Double.NaN) }
    var scale by remember { mutableFloatStateOf(10f) }
    var viewW by remember { mutableFloatStateOf(1f) }
    var viewH by remember { mutableFloatStateOf(1f) }

    fun fit(pts: List<SurveyPoint>) {
        if (pts.isEmpty()) return
        val minE = pts.minOf { it.easting }; val maxE = pts.maxOf { it.easting }
        val minN = pts.minOf { it.northing }; val maxN = pts.maxOf { it.northing }
        centerE = (minE + maxE) / 2
        centerN = (minN + maxN) / 2
        val span = max(max(maxE - minE, maxN - minN), 5.0)
        scale = (minOf(viewW, viewH) * 0.8f / span).toFloat()
    }

    LaunchedEffect(points.isNotEmpty(), viewW) {
        if (centerE.isNaN()) {
            if (points.isNotEmpty()) fit(points) else here?.let { centerE = it.easting; centerN = it.northing }
        }
    }

    val labelPaint = remember {
        Paint().apply { isAntiAlias = true; textSize = 34f; color = android.graphics.Color.BLACK }
    }
    val smallPaint = remember {
        Paint().apply { isAntiAlias = true; textSize = 28f; color = android.graphics.Color.DKGRAY }
    }

    Box(modifier.fillMaxSize().background(Color(0xFFFAFAF5))) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (centerE.isNaN()) return@detectTransformGestures
                        // Keep the world point under the fingers fixed while zooming.
                        val wx = centerE + (centroid.x - viewW / 2) / scale
                        val wy = centerN - (centroid.y - viewH / 2) / scale
                        scale = (scale * zoom).coerceIn(0.01f, 2000f)
                        centerE = wx - (centroid.x - viewW / 2) / scale - pan.x / scale
                        centerN = wy + (centroid.y - viewH / 2) / scale + pan.y / scale
                    }
                },
        ) {
            if (centerE.isNaN()) return@Canvas
            fun toScreen(e: Double, n: Double) = Offset(
                (size.width / 2 + (e - centerE) * scale).toFloat(),
                (size.height / 2 - (n - centerN) * scale).toFloat(),
            )
            for (p in points) {
                val o = toScreen(p.easting, p.northing)
                if (o.x < -200 || o.y < -200 || o.x > size.width + 200 || o.y > size.height + 200) continue
                val c = codeColor(p.code)
                drawCircle(c, radius = 9f, center = o)
                drawCircle(Color.White, radius = 9f, center = o, style = Stroke(2f))
                drawIntoCanvas {
                    labelPaint.color = c.toArgb()
                    it.nativeCanvas.drawText(p.name, o.x + 14f, o.y - 6f, labelPaint)
                    if (scale > 3f) {
                        it.nativeCanvas.drawText("${fmt(p.elevation, 2)} ${p.code}", o.x + 14f, o.y + 26f, smallPaint)
                    }
                }
            }
            here?.let {
                val o = toScreen(it.easting, it.northing)
                drawCircle(Color(0xFF1565C0), radius = 12f, center = o)
                drawCircle(Color.White, radius = 12f, center = o, style = Stroke(3f))
            }
            // Scale bar: a round length close to a quarter of the width.
            val target = size.width / 4 / scale
            val pow10 = 10.0.pow(floor(log10(target.toDouble())))
            val nice = listOf(1.0, 2.0, 5.0, 10.0).map { it * pow10 }.last { it <= target }
            val px = (nice * scale).toFloat()
            val y0 = size.height - 40f
            drawLine(Color.Black, Offset(30f, y0), Offset(30f + px, y0), strokeWidth = 4f)
            drawIntoCanvas {
                it.nativeCanvas.drawText(if (nice >= 1) "${nice.toInt()} m" else "${fmt(nice, 2)} m", 30f, y0 - 12f, smallPaint)
            }
        }

        if (points.isEmpty() && here == null) {
            Text("저장된 점이 없습니다", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyLarge)
        }

        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            SmallFloatingActionButton(
                onClick = { here?.let { centerE = it.easting; centerN = it.northing } },
                modifier = Modifier.padding(bottom = 12.dp),
            ) { Icon(Icons.Filled.MyLocation, contentDescription = "현재 위치") }
            FloatingActionButton(onClick = { fit(points) }) { Icon(Icons.Filled.ZoomOutMap, contentDescription = "전체 보기") }
        }
    }
}
