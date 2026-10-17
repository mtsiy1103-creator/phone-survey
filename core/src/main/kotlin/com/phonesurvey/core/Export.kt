package com.phonesurvey.core

import java.nio.charset.Charset
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))

private fun fmt(v: Double?, digits: Int): String = v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: ""

object CsvExporter {
    val header = listOf(
        "점번호", "코드", "X(북)", "Y(동)", "표고", "위도", "경도", "타원체고", "기계고",
        "측위상태", "위성수", "HDOP", "수평오차", "수직오차", "평균횟수", "높이기준", "좌표계", "측정시각", "메모",
    )

    /** UTF-8 with BOM so Excel opens Korean text correctly. */
    fun export(points: List<SurveyPoint>): ByteArray {
        val sb = StringBuilder("﻿")
        sb.append(header.joinToString(",")).append("\r\n")
        for (p in points) {
            val row = listOf(
                p.name, p.code,
                fmt(p.northing, 3), fmt(p.easting, 3), fmt(p.elevation, 3),
                fmt(p.latitude, 9), fmt(p.longitude, 9), fmt(p.ellipsoidalHeight, 3), fmt(p.antennaHeight, 3),
                p.quality.label, p.satellites.toString(), fmt(p.hdop, 1),
                fmt(p.sigmaHorizontal, 3), fmt(p.sigmaVertical, 3), p.samples.toString(),
                p.heightSource, "EPSG:${p.crsEpsg}", timeFormat.format(Instant.ofEpochMilli(p.timestampMillis)), p.note,
            )
            sb.append(row.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/**
 * AutoCAD R12 ASCII DXF, the version every CAD program reads. Each point becomes a POINT
 * on a layer named after its code, with point name, elevation and code as TEXT beside it.
 * Written in CP949 with $DWGCODEPAGE ANSI_949 so Korean codes display in Korean CAD.
 */
object DxfExporter {
    private val charset: Charset = Charset.forName("MS949")

    fun export(points: List<SurveyPoint>, textHeight: Double = 0.3): ByteArray {
        val w = DxfWriter()
        val layers = (points.map { layerName(it.code) } + listOf("PT_NO", "PT_ELEV", "PT_CODE")).distinct()

        w.pair(0, "SECTION"); w.pair(2, "HEADER")
        w.pair(9, "\$ACADVER"); w.pair(1, "AC1009")
        w.pair(9, "\$DWGCODEPAGE"); w.pair(3, "ANSI_949")
        if (points.isNotEmpty()) {
            w.pair(9, "\$EXTMIN"); w.xyz(points.minOf { it.easting }, points.minOf { it.northing }, points.minOf { it.elevation })
            w.pair(9, "\$EXTMAX"); w.xyz(points.maxOf { it.easting }, points.maxOf { it.northing }, points.maxOf { it.elevation })
        }
        w.pair(9, "\$PDMODE"); w.pair(70, "35")
        w.pair(9, "\$PDSIZE"); w.pair(40, fmt(textHeight * 0.6, 3))
        w.pair(0, "ENDSEC")

        w.pair(0, "SECTION"); w.pair(2, "TABLES")
        w.pair(0, "TABLE"); w.pair(2, "LAYER"); w.pair(70, layers.size.toString())
        layers.forEachIndexed { i, name ->
            w.pair(0, "LAYER"); w.pair(2, name); w.pair(70, "0")
            w.pair(62, layerColor(name, i).toString()); w.pair(6, "CONTINUOUS")
        }
        w.pair(0, "ENDTAB")
        w.pair(0, "ENDSEC")

        w.pair(0, "SECTION"); w.pair(2, "ENTITIES")
        val gap = textHeight * 0.6
        for (p in points) {
            val layer = layerName(p.code)
            w.pair(0, "POINT"); w.pair(8, layer); w.xyz(p.easting, p.northing, p.elevation)
            text(w, "PT_NO", p.easting + gap, p.northing + gap, p.elevation, textHeight, p.name)
            text(w, "PT_ELEV", p.easting + gap, p.northing - gap - textHeight, p.elevation, textHeight, fmt(p.elevation, 3))
            if (p.code.isNotBlank()) {
                text(w, "PT_CODE", p.easting + gap, p.northing - 2 * (gap + textHeight), p.elevation, textHeight * 0.8, p.code)
            }
        }
        w.pair(0, "ENDSEC")
        w.pair(0, "EOF")
        return w.toString().toByteArray(charset)
    }

    private fun text(w: DxfWriter, layer: String, x: Double, y: Double, z: Double, h: Double, value: String) {
        w.pair(0, "TEXT"); w.pair(8, layer); w.xyz(x, y, z); w.pair(40, fmt(h, 3)); w.pair(1, value)
    }

    /** DXF layer names can't contain <>/\":;?*|=` and should stay short. */
    fun layerName(code: String): String {
        val clean = code.trim().replace(Regex("[<>/\\\\\":;?*|=`,]"), "_")
        return if (clean.isEmpty()) "PT_NOCODE" else clean.take(31)
    }

    private fun layerColor(name: String, index: Int): Int = when (name) {
        "PT_NO" -> 7
        "PT_ELEV" -> 3
        "PT_CODE" -> 8
        else -> listOf(1, 2, 4, 5, 6, 30, 140, 200, 40, 90)[index % 10]
    }

    private class DxfWriter {
        private val sb = StringBuilder()
        fun pair(code: Int, value: String) {
            sb.append(code.toString().padStart(3)).append("\r\n").append(value).append("\r\n")
        }
        fun xyz(x: Double, y: Double, z: Double) {
            pair(10, fmt(x, 4)); pair(20, fmt(y, 4)); pair(30, fmt(z, 4))
        }
        override fun toString() = sb.toString()
    }
}
