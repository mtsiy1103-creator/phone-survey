package com.phonesurvey.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Planar grid coordinate. Korean survey convention: X = northing, Y = easting. */
data class GridPoint(val northing: Double, val easting: Double)

/**
 * Korean plane coordinate systems on the GRS80 ellipsoid (Korea 2000 datum).
 * WGS84 and Korea 2000 (ITRF2000) differ by a few centimetres at most, so GNSS
 * latitude/longitude can be projected directly for reference-grade work.
 */
enum class KoreanCrs(
    val epsg: Int,
    val label: String,
    val lat0: Double,
    val lon0: Double,
    val k0: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
) {
    CENTRAL(5186, "중부원점 (EPSG:5186)", 38.0, 127.0, 1.0, 200000.0, 600000.0),
    WEST(5185, "서부원점 (EPSG:5185)", 38.0, 125.0, 1.0, 200000.0, 600000.0),
    EAST(5187, "동부원점 (EPSG:5187)", 38.0, 129.0, 1.0, 200000.0, 600000.0),
    EAST_SEA(5188, "동해원점 (EPSG:5188)", 38.0, 131.0, 1.0, 200000.0, 600000.0),
    UTM_K(5179, "UTM-K (EPSG:5179)", 38.0, 127.5, 0.9996, 1000000.0, 2000000.0);

    val projection: TransverseMercator by lazy {
        TransverseMercator(lat0, lon0, k0, falseEasting, falseNorthing)
    }

    fun forward(latDeg: Double, lonDeg: Double): GridPoint = projection.forward(latDeg, lonDeg)

    fun inverse(p: GridPoint): Pair<Double, Double> = projection.inverse(p)

    companion object {
        fun fromEpsg(epsg: Int): KoreanCrs = entries.firstOrNull { it.epsg == epsg } ?: CENTRAL
    }
}

/**
 * Transverse Mercator using Krüger's series in the third flattening (4th order),
 * accurate to well under a millimetre within the extent of Korea.
 */
class TransverseMercator(
    lat0Deg: Double,
    private val lon0Deg: Double,
    private val k0: Double,
    private val falseEasting: Double,
    private val falseNorthing: Double,
    a: Double = GRS80_A,
    f: Double = GRS80_F,
) {
    private val e = sqrt(f * (2 - f))
    private val kA: Double
    private val alpha: DoubleArray
    private val beta: DoubleArray
    private val xi0: Double

    init {
        val n = f / (2 - f)
        val n2 = n * n
        val n3 = n2 * n
        val n4 = n3 * n
        kA = k0 * a / (1 + n) * (1 + n2 / 4 + n4 / 64)
        alpha = doubleArrayOf(
            n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180,
            13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440,
            61 * n3 / 240 - 103 * n4 / 140,
            49561 * n4 / 161280,
        )
        beta = doubleArrayOf(
            n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360,
            n2 / 48 + n3 / 15 - 437 * n4 / 1440,
            17 * n3 / 480 - 37 * n4 / 840,
            4397 * n4 / 161280,
        )
        xi0 = gaussXiEta(Math.toRadians(lat0Deg), 0.0).first
    }

    private fun gaussXiEta(phi: Double, dLambda: Double): Pair<Double, Double> {
        val sinPhi = sin(phi)
        val t = sinh(atanh(sinPhi) - e * atanh(e * sinPhi))
        val xiP = atan2(t, cos(dLambda))
        val etaP = atanh(sin(dLambda) / sqrt(1 + t * t))
        var xi = xiP
        var eta = etaP
        for (j in 1..4) {
            val a = alpha[j - 1]
            xi += a * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += a * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        return xi to eta
    }

    fun forward(latDeg: Double, lonDeg: Double): GridPoint {
        val (xi, eta) = gaussXiEta(Math.toRadians(latDeg), Math.toRadians(lonDeg - lon0Deg))
        return GridPoint(
            northing = falseNorthing + kA * (xi - xi0),
            easting = falseEasting + kA * eta,
        )
    }

    /** Returns (latitude, longitude) in degrees. */
    fun inverse(p: GridPoint): Pair<Double, Double> {
        val xi = (p.northing - falseNorthing) / kA + xi0
        val eta = (p.easting - falseEasting) / kA
        var xiP = xi
        var etaP = eta
        for (j in 1..4) {
            val b = beta[j - 1]
            xiP -= b * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= b * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xiP) / cosh(etaP))
        val lambda = atan2(sinh(etaP), cos(xiP))
        // Conformal latitude back to geodetic latitude by fixed-point iteration.
        var phi = chi
        repeat(10) {
            val es = e * sin(phi)
            val next = 2 * atan(tan(PI / 4 + chi / 2) * ((1 + es) / (1 - es)).pow(e / 2)) - PI / 2
            if (abs(next - phi) < 1e-14) {
                phi = next
                return Math.toDegrees(phi) to lon0Deg + Math.toDegrees(lambda)
            }
            phi = next
        }
        return Math.toDegrees(phi) to lon0Deg + Math.toDegrees(lambda)
    }

    private fun atanh(x: Double): Double = 0.5 * ln((1 + x) / (1 - x))

    companion object {
        const val GRS80_A = 6378137.0
        const val GRS80_F = 1 / 298.257222101
    }
}
