package com.phonesurvey.core

import java.io.BufferedReader
import java.io.StringReader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectionTest {
    // Reference values from PROJ (pyproj) for the same lat/lon.
    private val cases = listOf(
        Triple(KoreanCrs.CENTRAL, doubleArrayOf(37.5665, 126.9780), doubleArrayOf(198056.3667, 551885.0306)),
        Triple(KoreanCrs.CENTRAL, doubleArrayOf(35.1796, 129.0756), doubleArrayOf(389076.8036, 288993.7560)),
        Triple(KoreanCrs.CENTRAL, doubleArrayOf(33.4996, 126.5312), doubleArrayOf(156437.5196, 100758.4815)),
        Triple(KoreanCrs.CENTRAL, doubleArrayOf(38.0, 127.0), doubleArrayOf(200000.0, 600000.0)),
        Triple(KoreanCrs.WEST, doubleArrayOf(37.5665, 126.9780), doubleArrayOf(374759.2927, 553724.3689)),
        Triple(KoreanCrs.EAST, doubleArrayOf(35.1796, 129.0756), doubleArrayOf(206886.2728, 287022.7228)),
        Triple(KoreanCrs.EAST_SEA, doubleArrayOf(38.2, 128.6), doubleArrayOf(-10238.7281, 624923.5656)),
        Triple(KoreanCrs.UTM_K, doubleArrayOf(37.5665, 126.9780), doubleArrayOf(953901.1653, 1952032.0810)),
    )

    @Test
    fun forwardMatchesProj() {
        for ((crs, ll, en) in cases) {
            val p = crs.forward(ll[0], ll[1])
            assertTrue(abs(p.easting - en[0]) < 0.001, "${crs.epsg} E ${p.easting} vs ${en[0]}")
            assertTrue(abs(p.northing - en[1]) < 0.001, "${crs.epsg} N ${p.northing} vs ${en[1]}")
        }
    }

    @Test
    fun inverseRoundTrips() {
        for ((crs, ll, _) in cases) {
            val (lat, lon) = crs.inverse(crs.forward(ll[0], ll[1]))
            assertTrue(abs(lat - ll[0]) < 1e-9 && abs(lon - ll[1]) < 1e-9, "${crs.epsg} $lat $lon")
        }
    }
}

class NmeaTest {
    @Test
    fun parsesGgaAndGst() {
        var fix: GnssFix? = null
        val asm = NmeaFixAssembler { fix = it }
        asm.accept(withChecksum("GNGST,083000.00,0.9,0.012,0.008,45.0,0.010,0.011,0.020"), 1000)
        asm.accept(withChecksum("GNGGA,083001.00,3733.9900,N,12658.6800,E,4,24,0.6,45.123,M,23.456,M,1.0,0001"), 1500)
        val f = assertNotNull(fix)
        assertEquals(FixQuality.RTK_FIX, f.quality)
        assertEquals(37 + 33.99 / 60, f.latitude, 1e-12)
        assertEquals(126 + 58.68 / 60, f.longitude, 1e-12)
        assertEquals(68.579, f.ellipsoidalHeight, 1e-9)
        assertEquals(24, f.satellites)
        assertEquals(0.020, f.sigmaVertical!!, 1e-12)
        assertEquals(Math.hypot(0.010, 0.011), f.sigmaHorizontal!!, 1e-12)
    }

    @Test
    fun rejectsBadChecksumAndNoFix() {
        assertNull(Nmea.parse("\$GNGGA,083001.00,3733.99,N,12658.68,E,4,24,0.6,45.1,M,23.4,M,1.0,0001*00"))
        assertNull(Nmea.parse(withChecksum("GPGGA,083001.00,,,,,0,00,,,M,,M,,")))
    }

    @Test
    fun builtGgaParsesBack() {
        val fix = GnssFix(37.123456789, 127.987654321, 50.5, 24.2, FixQuality.SINGLE, 12, 0.9, null, "010203.00")
        val s = Nmea.parse(Nmea.buildGga(fix)) as NmeaSentence.Gga
        assertEquals(fix.latitude, s.fix.latitude, 1e-8)
        assertEquals(fix.longitude, s.fix.longitude, 1e-8)
        assertEquals(50.5, s.fix.mslHeight, 1e-6)
    }

    @Test
    fun splitterSkipsBinary() {
        val lines = mutableListOf<String>()
        val sp = NmeaLineSplitter { lines += it }
        val gga = withChecksum("GPGGA,083001.00,3733.99,N,12658.68,E,1,08,1.0,45.1,M,23.4,M,,")
        val bytes = byteArrayOf(0xD3.toByte(), 0x00, 0x13, 0x24, 0x0A) + gga.toByteArray() + "\r\n\$GPG".toByteArray()
        sp.feed(bytes.copyOfRange(0, 10)); sp.feed(bytes.copyOfRange(10, bytes.size))
        assertEquals(listOf(gga), lines)
    }

    private fun withChecksum(body: String): String {
        var sum = 0
        for (c in body) sum = sum xor c.code
        return "$" + body + "*" + String.format("%02X", sum)
    }
}

class SurveyTest {
    private fun fix(lat: Double, q: FixQuality = FixQuality.RTK_FIX, msl: Double = 30.0) =
        GnssFix(lat, 127.0, msl, 25.0, q, 20, 0.7, 1.0, null)

    @Test
    fun averagerDropsWorseQuality() {
        val a = FixAverager()
        a.add(fix(37.0)); a.add(fix(37.00001, FixQuality.RTK_FLOAT, 99.0)); a.add(fix(37.000002))
        val r = assertNotNull(a.result())
        assertEquals(2, r.samples)
        assertEquals(37.000001, r.fix.latitude, 1e-12)
        assertEquals(30.0, r.fix.mslHeight, 1e-12)
    }

    @Test
    fun groundElevationSubtractsAntennaHeight() {
        val a = FixAverager(); a.add(fix(37.5))
        val p = buildPoint("현장", "1", "맨홀", "", a.result()!!, 1.5, KoreanCrs.CENTRAL, HeightModel(siteOffset = 0.1))
        assertEquals(30.0 - 1.5 + 0.1, p.elevation, 1e-9)
        assertEquals(55.0, p.ellipsoidalHeight, 1e-9)
        assertEquals("수신기 지오이드", p.heightSource)
    }

    @Test
    fun nationalGeoidOverridesReceiver() {
        val isg = """
            begin_of_head ================================================
            model name     : TEST
            lat min        :    37.000000
            lat max        :    38.000000
            lon min        :   126.000000
            lon max        :   128.000000
            delta lat      :     1.000000
            delta lon      :     1.000000
            nrows          :            2
            ncols          :            3
            nodata         :   -9999.0000
            data format    : grid
            end_of_head ==================================================
              26.0 27.0 28.0
              24.0 25.0 26.0
        """.trimIndent()
        val g = GeoidGrid.read("TEST", BufferedReader(StringReader(isg)))
        assertEquals(25.0, g.undulation(37.0, 127.0)!!, 1e-6)
        assertEquals(26.5, g.undulation(37.5, 127.5)!!, 1e-6)
        assertNull(g.undulation(36.9, 127.0))
        val (h, src) = HeightModel(g).orthometric(37.5, 127.5, 56.0, 31.0)
        assertEquals(29.5, h, 1e-6)
        assertEquals("TEST", src)
    }

    @Test
    fun nextNames() {
        assertEquals("13", nextPointName("12"))
        assertEquals("P010", nextPointName("P009"))
        assertEquals("A1", nextPointName("A"))
        assertEquals("1", nextPointName(""))
    }
}

class ExportTest {
    private val point = SurveyPoint(
        project = "p", name = "1", code = "맨홀,우수", note = "메모 \"a\"",
        latitude = 37.5, longitude = 127.0, ellipsoidalHeight = 55.0, antennaHeight = 1.5,
        northing = 544536.1234, easting = 200000.0, elevation = 28.5, crsEpsg = 5186,
        quality = FixQuality.RTK_FIX, satellites = 20, hdop = 0.7, sigmaHorizontal = 0.012, sigmaVertical = 0.02,
        samples = 10, heightSource = "수신기 지오이드", timestampMillis = 0,
    )

    @Test
    fun csvHasBomAndEscapes() {
        val text = CsvExporter.export(listOf(point)).toString(Charsets.UTF_8)
        assertTrue(text.startsWith("﻿점번호,코드,X(북)"))
        assertTrue(text.contains("1,\"맨홀,우수\",544536.123,200000.000,28.500"))
        assertTrue(text.contains("\"메모 \"\"a\"\"\""))
    }

    @Test
    fun dxfIsWellFormedR12() {
        val text = DxfExporter.export(listOf(point)).toString(charset("MS949"))
        val lines = text.split("\r\n").dropLast(1)
        assertEquals(0, lines.size % 2, "group code/value pairs")
        assertTrue(text.contains("AC1009"))
        assertTrue(text.contains("ANSI_949"))
        assertTrue(text.contains("맨홀_우수"))
        assertEquals("EOF", lines.last())
        // POINT uses easting as DXF X.
        val i = lines.indexOf("POINT")
        assertEquals("200000.0000", lines[i + 4])
        assertEquals("544536.1234", lines[i + 6])
    }
}

class NtripTest {
    @Test
    fun requestHasAuth() {
        val r = NtripClient.buildRequest(NtripConfig("h", 2101, "VRS-RTCM31", "user", "pw"), "\$GPGGA,x")
        assertTrue(r.startsWith("GET /VRS-RTCM31 HTTP/1.0\r\n"))
        assertTrue(r.contains("Authorization: Basic dXNlcjpwdw=="))
        assertTrue(r.endsWith("\r\n\r\n"))
    }

    @Test
    fun parsesSourceTable() {
        val t = "SOURCETABLE 200 OK\r\nSTR;VRS-RTCM31;VRS;RTCM 3.1;1004(1);2;GPS+GLO;KOR;KOR;37.00;127.00;1;1;x;none;B;N;0;\r\nENDSOURCETABLE\r\n"
        val m = NtripClient.parseSourceTable(t).single()
        assertEquals("VRS-RTCM31", m.name)
        assertTrue(m.requiresGga)
    }
}
