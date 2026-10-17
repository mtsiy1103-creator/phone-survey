package com.phonesurvey.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.phonesurvey.core.FixQuality
import com.phonesurvey.core.SurveyPoint

/** SQLite storage for survey points, grouped by project (현장). */
class PointStore(context: Context) : SQLiteOpenHelper(context, "survey.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project TEXT NOT NULL,
                name TEXT NOT NULL,
                code TEXT NOT NULL,
                note TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                ellipsoidal_height REAL NOT NULL,
                antenna_height REAL NOT NULL,
                northing REAL NOT NULL,
                easting REAL NOT NULL,
                elevation REAL NOT NULL,
                crs_epsg INTEGER NOT NULL,
                quality INTEGER NOT NULL,
                satellites INTEGER NOT NULL,
                hdop REAL,
                sigma_h REAL,
                sigma_v REAL,
                samples INTEGER NOT NULL,
                height_source TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX points_project ON points(project)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(p: SurveyPoint): Long = writableDatabase.insert("points", null, values(p))

    fun update(p: SurveyPoint) {
        writableDatabase.update("points", values(p), "id = ?", arrayOf(p.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("points", "id = ?", arrayOf(id.toString()))
    }

    fun list(project: String): List<SurveyPoint> =
        readableDatabase.query("points", null, "project = ?", arrayOf(project), null, null, "id").use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    fun projects(): List<String> =
        readableDatabase.rawQuery("SELECT project, MAX(timestamp) t FROM points GROUP BY project ORDER BY t DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun values(p: SurveyPoint) = ContentValues().apply {
        put("project", p.project)
        put("name", p.name)
        put("code", p.code)
        put("note", p.note)
        put("latitude", p.latitude)
        put("longitude", p.longitude)
        put("ellipsoidal_height", p.ellipsoidalHeight)
        put("antenna_height", p.antennaHeight)
        put("northing", p.northing)
        put("easting", p.easting)
        put("elevation", p.elevation)
        put("crs_epsg", p.crsEpsg)
        put("quality", p.quality.code)
        put("satellites", p.satellites)
        put("hdop", p.hdop)
        put("sigma_h", p.sigmaHorizontal)
        put("sigma_v", p.sigmaVertical)
        put("samples", p.samples)
        put("height_source", p.heightSource)
        put("timestamp", p.timestampMillis)
    }

    private fun read(c: Cursor): SurveyPoint {
        fun d(col: String) = c.getDouble(c.getColumnIndexOrThrow(col))
        fun dn(col: String) = c.getColumnIndexOrThrow(col).let { if (c.isNull(it)) null else c.getDouble(it) }
        fun i(col: String) = c.getInt(c.getColumnIndexOrThrow(col))
        fun s(col: String) = c.getString(c.getColumnIndexOrThrow(col))
        return SurveyPoint(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            project = s("project"),
            name = s("name"),
            code = s("code"),
            note = s("note"),
            latitude = d("latitude"),
            longitude = d("longitude"),
            ellipsoidalHeight = d("ellipsoidal_height"),
            antennaHeight = d("antenna_height"),
            northing = d("northing"),
            easting = d("easting"),
            elevation = d("elevation"),
            crsEpsg = i("crs_epsg"),
            quality = FixQuality.of(i("quality")),
            satellites = i("satellites"),
            hdop = dn("hdop"),
            sigmaHorizontal = dn("sigma_h"),
            sigmaVertical = dn("sigma_v"),
            samples = i("samples"),
            heightSource = s("height_source"),
            timestampMillis = c.getLong(c.getColumnIndexOrThrow("timestamp")),
        )
    }
}
