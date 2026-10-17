package com.phonesurvey.core

import kotlin.math.cos
import kotlin.math.sqrt

/** A recorded survey point. Heights in metres; [elevation] is ground elevation after all corrections. */
data class SurveyPoint(
    val id: Long = 0,
    val project: String,
    val name: String,
    val code: String,
    val note: String = "",
    val latitude: Double,
    val longitude: Double,
    val ellipsoidalHeight: Double,
    val antennaHeight: Double,
    val northing: Double,
    val easting: Double,
    val elevation: Double,
    val crsEpsg: Int,
    val quality: FixQuality,
    val satellites: Int,
    val hdop: Double?,
    val sigmaHorizontal: Double?,
    val sigmaVertical: Double?,
    val samples: Int,
    val heightSource: String,
    val timestampMillis: Long,
)

/**
 * Turns GNSS heights into ground elevation:
 * orthometric = ellipsoidal − geoid (national model when loaded, else receiver's built-in),
 * ground = orthometric − antenna height + site offset.
 */
class HeightModel(
    private val geoid: GeoidGrid? = null,
    private val siteOffset: Double = 0.0,
) {
    fun orthometric(latitude: Double, longitude: Double, ellipsoidalHeight: Double, receiverMsl: Double): Pair<Double, String> {
        val n = geoid?.undulation(latitude, longitude)
        return if (n != null) (ellipsoidalHeight - n) to geoid!!.name else receiverMsl to "수신기 지오이드"
    }

    fun groundElevation(latitude: Double, longitude: Double, ellipsoidalHeight: Double, receiverMsl: Double, antennaHeight: Double): Pair<Double, String> {
        val (h, src) = orthometric(latitude, longitude, ellipsoidalHeight, receiverMsl)
        return (h - antennaHeight + siteOffset) to src
    }
}

/** Averages fixes collected while the user occupies a point. */
class FixAverager {
    private val fixes = mutableListOf<GnssFix>()

    val count: Int get() = fixes.size

    fun add(fix: GnssFix) { fixes += fix }

    fun clear() = fixes.clear()

    /** Keeps only fixes of the best quality seen, so a momentary Float doesn't drag a Fix average. */
    private fun bestFixes(): List<GnssFix> {
        val rank = { q: FixQuality ->
            when (q) {
                FixQuality.RTK_FIX -> 0
                FixQuality.RTK_FLOAT -> 1
                FixQuality.DGPS, FixQuality.PPS -> 2
                FixQuality.SINGLE -> 3
                else -> 4
            }
        }
        val best = fixes.minOfOrNull { rank(it.quality) } ?: return emptyList()
        return fixes.filter { rank(it.quality) == best }
    }

    data class Result(
        val fix: GnssFix,
        val samples: Int,
        /** Scatter of the samples, 1-sigma, metres. */
        val spreadHorizontal: Double,
        val spreadVertical: Double,
    )

    fun result(): Result? {
        val use = bestFixes()
        if (use.isEmpty()) return null
        val lat = use.sumOf { it.latitude } / use.size
        val lon = use.sumOf { it.longitude } / use.size
        val msl = use.sumOf { it.mslHeight } / use.size
        val sep = use.sumOf { it.geoidSeparation } / use.size
        val mPerDegLat = 111_132.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        val sh = sqrt(use.sumOf {
            val dn = (it.latitude - lat) * mPerDegLat
            val de = (it.longitude - lon) * mPerDegLon
            dn * dn + de * de
        } / use.size)
        val sv = sqrt(use.sumOf { (it.mslHeight - msl).let { d -> d * d } } / use.size)
        val last = use.last()
        val avg = last.copy(
            latitude = lat,
            longitude = lon,
            mslHeight = msl,
            geoidSeparation = sep,
            hdop = use.mapNotNull { it.hdop }.takeIf { it.isNotEmpty() }?.average(),
            sigmaHorizontal = use.mapNotNull { it.sigmaHorizontal }.takeIf { it.isNotEmpty() }?.average(),
            sigmaVertical = use.mapNotNull { it.sigmaVertical }.takeIf { it.isNotEmpty() }?.average(),
        )
        return Result(avg, use.size, sh, sv)
    }
}

/** Builds a [SurveyPoint] from an averaged fix. */
fun buildPoint(
    project: String,
    name: String,
    code: String,
    note: String,
    result: FixAverager.Result,
    antennaHeight: Double,
    crs: KoreanCrs,
    heightModel: HeightModel,
    nowMillis: Long = System.currentTimeMillis(),
): SurveyPoint {
    val f = result.fix
    val grid = crs.forward(f.latitude, f.longitude)
    val (elev, src) = heightModel.groundElevation(f.latitude, f.longitude, f.ellipsoidalHeight, f.mslHeight, antennaHeight)
    return SurveyPoint(
        project = project,
        name = name,
        code = code,
        note = note,
        latitude = f.latitude,
        longitude = f.longitude,
        ellipsoidalHeight = f.ellipsoidalHeight,
        antennaHeight = antennaHeight,
        northing = grid.northing,
        easting = grid.easting,
        elevation = elev,
        crsEpsg = crs.epsg,
        quality = f.quality,
        satellites = f.satellites,
        hdop = f.hdop,
        sigmaHorizontal = f.sigmaHorizontal ?: result.spreadHorizontal.takeIf { result.samples > 1 },
        sigmaVertical = f.sigmaVertical ?: result.spreadVertical.takeIf { result.samples > 1 },
        samples = result.samples,
        heightSource = src,
        timestampMillis = nowMillis,
    )
}

/** Next point name: "12" → "13", "P009" → "P010", "A" → "A1". */
fun nextPointName(current: String): String {
    val m = Regex("^(.*?)(\\d+)$").find(current) ?: return if (current.isBlank()) "1" else current + "1"
    val (prefix, digits) = m.destructured
    val next = (digits.toLong() + 1).toString().padStart(digits.length, '0')
    return prefix + next
}
