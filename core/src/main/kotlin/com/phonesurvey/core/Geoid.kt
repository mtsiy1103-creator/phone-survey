package com.phonesurvey.core

import java.io.BufferedReader
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Regular latitude/longitude grid of geoid undulations (geoid height above the ellipsoid),
 * interpolated bilinearly. Rows run north to south, columns west to east.
 */
class GeoidGrid(
    val name: String,
    private val latMax: Double,
    private val lonMin: Double,
    private val dLat: Double,
    private val dLon: Double,
    private val rows: Int,
    private val cols: Int,
    private val values: FloatArray,
    private val noData: Float = -9999f,
) {
    init {
        require(values.size == rows * cols) { "격자 크기가 맞지 않습니다 (${values.size} ≠ $rows×$cols)" }
    }

    fun undulation(lat: Double, lon: Double): Double? {
        val r = (latMax - lat) / dLat
        val c = (lon - lonMin) / dLon
        if (r < 0 || c < 0 || r > rows - 1 || c > cols - 1) return null
        val r0 = floor(r).toInt().coerceAtMost(rows - 2).coerceAtLeast(0)
        val c0 = floor(c).toInt().coerceAtMost(cols - 2).coerceAtLeast(0)
        val fr = r - r0
        val fc = c - c0
        val v00 = at(r0, c0) ?: return null
        val v01 = at(r0, c0 + 1) ?: return null
        val v10 = at(r0 + 1, c0) ?: return null
        val v11 = at(r0 + 1, c0 + 1) ?: return null
        return v00 * (1 - fr) * (1 - fc) + v01 * (1 - fr) * fc + v10 * fr * (1 - fc) + v11 * fr * fc
    }

    private fun at(r: Int, c: Int): Double? {
        val v = values[r * cols + c]
        return if (v == noData || v.isNaN()) null else v.toDouble()
    }

    companion object {
        /** Reads ISG format (International Service for the Geoid) or a plain "lat lon N" table. */
        fun read(name: String, reader: BufferedReader): GeoidGrid {
            val lines = reader.readLines()
            return if (lines.any { it.trim().startsWith("begin_of_head") }) readIsg(name, lines) else readXyz(name, lines)
        }

        private fun readIsg(name: String, lines: List<String>): GeoidGrid {
            val start = lines.indexOfFirst { it.trim().startsWith("begin_of_head") }
            val end = lines.indexOfFirst { it.trim().startsWith("end_of_head") }
            require(start >= 0 && end > start) { "ISG 헤더를 찾을 수 없습니다" }
            val head = mutableMapOf<String, String>()
            for (l in lines.subList(start + 1, end)) {
                val i = l.indexOf(':').takeIf { it > 0 } ?: l.indexOf('=').takeIf { it > 0 } ?: continue
                head[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            fun angle(key: String): Double {
                val v = head[key] ?: error("ISG 헤더에 '$key' 항목이 없습니다")
                return parseAngle(v)
            }
            val latMax = angle("lat max")
            val lonMin = angle("lon min")
            val dLat = angle("delta lat")
            val dLon = angle("delta lon")
            val rows = head["nrows"]?.toIntOrNull() ?: ((latMax - angle("lat min")) / dLat).roundToInt() + 1
            val cols = head["ncols"]?.toIntOrNull() ?: ((angle("lon max") - lonMin) / dLon).roundToInt() + 1
            val noData = head["nodata"]?.toFloatOrNull() ?: -9999f
            // Cell-registered ("cell") grids store values at cell centres.
            val cellCentred = head["data format"]?.contains("cell", ignoreCase = true) == true ||
                head["coord type"]?.contains("cell", ignoreCase = true) == true
            val values = FloatArray(rows * cols)
            var k = 0
            for (l in lines.subList(end + 1, lines.size)) {
                for (tok in l.trim().split(Regex("\\s+"))) {
                    if (tok.isEmpty() || k >= values.size) continue
                    values[k++] = tok.toFloatOrNull() ?: noData
                }
            }
            require(k == values.size) { "격자 값 개수가 부족합니다 ($k / ${values.size})" }
            val latTop = if (cellCentred) latMax - dLat / 2 else latMax
            val lonLeft = if (cellCentred) lonMin + dLon / 2 else lonMin
            return GeoidGrid(name, latTop, lonLeft, dLat, dLon, rows, cols, values, noData)
        }

        /** Accepts decimal degrees or d°m's" notation. */
        fun parseAngle(v: String): Double {
            v.toDoubleOrNull()?.let { return it }
            val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(v).map { it.value.toDouble() }.toList()
            require(nums.isNotEmpty()) { "각도를 읽을 수 없습니다: $v" }
            val sign = if (v.trim().startsWith("-")) -1 else 1
            val d = kotlin.math.abs(nums[0])
            val m = nums.getOrElse(1) { 0.0 }
            val s = nums.getOrElse(2) { 0.0 }
            return sign * (d + m / 60 + s / 3600)
        }

        private fun readXyz(name: String, lines: List<String>): GeoidGrid {
            val pts = lines.mapNotNull { l ->
                val t = l.trim().split(Regex("[\\s,;]+"))
                if (t.size < 3) return@mapNotNull null
                val a = t[0].toDoubleOrNull() ?: return@mapNotNull null
                val b = t[1].toDoubleOrNull() ?: return@mapNotNull null
                val n = t[2].toDoubleOrNull() ?: return@mapNotNull null
                // Korea: latitude 32–40, longitude 123–133, so the column order is unambiguous.
                if (a > 90) Triple(b, a, n) else Triple(a, b, n)
            }
            require(pts.size >= 4) { "지오이드 파일에서 값을 읽지 못했습니다" }
            val lats = pts.map { it.first }.distinct().sortedDescending()
            val lons = pts.map { it.second }.distinct().sorted()
            val dLat = lats[0] - lats[1]
            val dLon = lons[1] - lons[0]
            val values = FloatArray(lats.size * lons.size) { -9999f }
            for ((la, lo, n) in pts) {
                val r = ((lats[0] - la) / dLat).roundToInt()
                val c = ((lo - lons[0]) / dLon).roundToInt()
                if (r in lats.indices && c in lons.indices) values[r * lons.size + c] = n.toFloat()
            }
            return GeoidGrid(name, lats[0], lons[0], dLat, dLon, lats.size, lons.size, values)
        }
    }
}
