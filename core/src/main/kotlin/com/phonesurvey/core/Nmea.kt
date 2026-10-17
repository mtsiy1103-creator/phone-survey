package com.phonesurvey.core

/** GGA fix quality indicator. */
enum class FixQuality(val code: Int, val label: String) {
    INVALID(0, "측위 안됨"),
    SINGLE(1, "단독"),
    DGPS(2, "DGPS"),
    PPS(3, "PPS"),
    RTK_FIX(4, "RTK Fix"),
    RTK_FLOAT(5, "RTK Float"),
    ESTIMATED(6, "추정"),
    MANUAL(7, "수동"),
    SIMULATION(8, "시뮬레이션");

    companion object {
        fun of(code: Int): FixQuality = entries.firstOrNull { it.code == code } ?: INVALID
    }
}

/**
 * One position solution. Heights are in metres: [mslHeight] is the receiver's
 * height above its built-in geoid, [geoidSeparation] is that geoid above the
 * ellipsoid, so the ellipsoidal height is their sum.
 */
data class GnssFix(
    val latitude: Double,
    val longitude: Double,
    val mslHeight: Double,
    val geoidSeparation: Double,
    val quality: FixQuality,
    val satellites: Int,
    val hdop: Double?,
    val correctionAge: Double?,
    val utcTime: String?,
    /** 1-sigma horizontal error estimate from GST or the platform, if known. */
    val sigmaHorizontal: Double? = null,
    /** 1-sigma vertical error estimate, if known. */
    val sigmaVertical: Double? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    val ellipsoidalHeight: Double get() = mslHeight + geoidSeparation
}

/** Error estimates from a GST sentence. */
data class GstInfo(val sigmaLat: Double?, val sigmaLon: Double?, val sigmaAlt: Double?) {
    val sigmaHorizontal: Double?
        get() = if (sigmaLat != null && sigmaLon != null) kotlin.math.sqrt(sigmaLat * sigmaLat + sigmaLon * sigmaLon) else null
}

sealed interface NmeaSentence {
    data class Gga(val fix: GnssFix) : NmeaSentence
    data class Gst(val info: GstInfo) : NmeaSentence
}

object Nmea {
    /** Validates the checksum when present. Sentences without one are accepted. */
    fun checksumOk(line: String): Boolean {
        val star = line.lastIndexOf('*')
        if (!line.startsWith("$") || star < 0) return line.startsWith("$")
        if (star + 3 > line.length) return false
        var sum = 0
        for (i in 1 until star) sum = sum xor line[i].code
        val expected = line.substring(star + 1, star + 3).toIntOrNull(16) ?: return false
        return sum == expected
    }

    fun parse(rawLine: String, nowMillis: Long = System.currentTimeMillis()): NmeaSentence? {
        val line = rawLine.trim()
        if (line.length < 7 || !checksumOk(line)) return null
        val body = line.substring(1, line.indexOf('*').let { if (it < 0) line.length else it })
        val f = body.split(',')
        if (f[0].length < 5) return null
        return when (f[0].substring(f[0].length - 3)) {
            "GGA" -> parseGga(f, nowMillis)?.let { NmeaSentence.Gga(it) }
            "GST" -> parseGst(f)?.let { NmeaSentence.Gst(it) }
            else -> null
        }
    }

    private fun parseGga(f: List<String>, nowMillis: Long): GnssFix? {
        if (f.size < 15) return null
        val quality = FixQuality.of(f[6].toIntOrNull() ?: 0)
        if (quality == FixQuality.INVALID) return null
        val lat = parseCoordinate(f[2], f[3]) ?: return null
        val lon = parseCoordinate(f[4], f[5]) ?: return null
        return GnssFix(
            latitude = lat,
            longitude = lon,
            mslHeight = f[9].toDoubleOrNull() ?: 0.0,
            geoidSeparation = f[11].toDoubleOrNull() ?: 0.0,
            quality = quality,
            satellites = f[7].toIntOrNull() ?: 0,
            hdop = f[8].toDoubleOrNull(),
            correctionAge = f[13].toDoubleOrNull(),
            utcTime = f[1].ifEmpty { null },
            timestampMillis = nowMillis,
        )
    }

    private fun parseGst(f: List<String>): GstInfo? {
        if (f.size < 9) return null
        return GstInfo(f[6].toDoubleOrNull(), f[7].toDoubleOrNull(), f[8].toDoubleOrNull())
    }

    /** ddmm.mmmm / dddmm.mmmm with hemisphere to signed decimal degrees. */
    fun parseCoordinate(value: String, hemisphere: String): Double? {
        val dot = value.indexOf('.').let { if (it < 0) value.length else it }
        if (dot < 3) return null
        val degrees = value.substring(0, dot - 2).toIntOrNull() ?: return null
        val minutes = value.substring(dot - 2).toDoubleOrNull() ?: return null
        val dd = degrees + minutes / 60.0
        return when (hemisphere) {
            "S", "W" -> -dd
            else -> dd
        }
    }

    /** Builds a GGA sentence, used to report our position to an NTRIP caster (needed for VRS). */
    fun buildGga(fix: GnssFix): String {
        fun coord(v: Double, degDigits: Int): String {
            val a = kotlin.math.abs(v)
            val d = a.toInt()
            val m = (a - d) * 60.0
            return String.format(java.util.Locale.US, "%0${degDigits}d%010.7f", d, m)
        }
        val time = fix.utcTime ?: java.time.LocalTime.now(java.time.ZoneOffset.UTC)
            .let { String.format(java.util.Locale.US, "%02d%02d%02d.00", it.hour, it.minute, it.second) }
        val body = String.format(
            java.util.Locale.US,
            "GPGGA,%s,%s,%s,%s,%s,%d,%02d,%.1f,%.3f,M,%.3f,M,,",
            time,
            coord(fix.latitude, 2), if (fix.latitude >= 0) "N" else "S",
            coord(fix.longitude, 3), if (fix.longitude >= 0) "E" else "W",
            fix.quality.code.coerceAtLeast(1), fix.satellites.coerceAtMost(99),
            fix.hdop ?: 1.0, fix.mslHeight, fix.geoidSeparation,
        )
        var sum = 0
        for (c in body) sum = sum xor c.code
        return "$" + body + "*" + String.format("%02X", sum)
    }
}

/**
 * Splits a byte stream into NMEA lines while skipping binary data (e.g. RTCM echoes)
 * that some receivers interleave on the same port.
 */
class NmeaLineSplitter(private val onLine: (String) -> Unit) {
    private val buf = StringBuilder()

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) {
            val c = bytes[i].toInt() and 0xFF
            when {
                c == '$'.code -> { buf.setLength(0); buf.append('$') }
                c == '\n'.code || c == '\r'.code -> {
                    if (buf.length > 6) onLine(buf.toString())
                    buf.setLength(0)
                }
                buf.isNotEmpty() && c in 0x20..0x7E -> {
                    buf.append(c.toChar())
                    if (buf.length > 120) buf.setLength(0)
                }
                buf.isNotEmpty() -> buf.setLength(0)
            }
        }
    }
}

/** Combines GGA and the most recent GST into fixes. */
class NmeaFixAssembler(private val onFix: (GnssFix) -> Unit) {
    private var lastGst: GstInfo? = null
    private var lastGstMillis = 0L

    fun accept(line: String, nowMillis: Long = System.currentTimeMillis()) {
        when (val s = Nmea.parse(line, nowMillis)) {
            is NmeaSentence.Gst -> { lastGst = s.info; lastGstMillis = nowMillis }
            is NmeaSentence.Gga -> {
                val gst = lastGst?.takeIf { nowMillis - lastGstMillis < 3000 }
                onFix(s.fix.copy(sigmaHorizontal = gst?.sigmaHorizontal, sigmaVertical = gst?.sigmaAlt))
            }
            null -> Unit
        }
    }
}
