package com.taptrack.app.utils

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import com.taptrack.app.AppContainer
import com.taptrack.app.data.remote.putNullable
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PipeDiameter(
    val inches: Int,
    val mm: Int,
    val label: String,
    val hexColor: String
) {
    val colorInt: Int
        get() = android.graphics.Color.parseColor(hexColor)

    val composeColor: Color
        get() = Color(colorInt)

    companion object {
        val INCH_2 = PipeDiameter(2, 56, "2 in. HDPE Pipe", "#DC2626")
        val INCH_3 = PipeDiameter(3, 79, "3 in. HDPE Pipe", "#FACC15")
        val INCH_4 = PipeDiameter(4, 97, "4 in. HDPE Pipe", "#4ADE80")
        val INCH_6 = PipeDiameter(6, 141, "6 in. HDPE Pipe", "#FB923C")
        val INCH_8 = PipeDiameter(8, 199, "8 in. HDPE Pipe", "#A78BFA")
        val INCH_10 = PipeDiameter(10, 247, "10 in. HDPE Pipe", "#1E3A8A")

        val ALL = listOf(INCH_2, INCH_3, INCH_4, INCH_6, INCH_8, INCH_10)
    }
}

val STANDARD_PIPE_DIAMETERS = PipeDiameter.ALL

object KmlExporter {

    private fun kmlColor(hex: String): String {
        val clean = hex.removePrefix("#")
        return if (clean.length == 6) {
            val r = clean.substring(0, 2)
            val g = clean.substring(2, 4)
            val b = clean.substring(4, 6)
            "ff$b$g$r"
        } else {
            "ff0000ff"
        }
    }

    fun exportPipelineToKml(
        context: Context,
        name: String,
        diameter: PipeDiameter,
        points: List<GeoPoint>,
        distanceMeters: Double
    ): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dateIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val fileName = "AsBuilt_${diameter.inches}in_${timeStamp}.kml"

        // 1. Save in internal app files
        val dir = File(context.filesDir, "as_built_kml").apply { mkdirs() }
        val file = File(dir, fileName)
        val coordString = points.joinToString(" ") { "${it.longitude},${it.latitude},${it.altitude}" }

        val kmlContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <name>${name.ifBlank { "As-Built ${diameter.label}" }}</name>
                <Placemark>
                  <name>${name.ifBlank { diameter.label }}</name>
                  <ExtendedData>
                    <SimpleData name="diameter">${diameter.mm}.000000000000000</SimpleData>
                    <SimpleData name="diameter_in">${diameter.inches}</SimpleData>
                    <SimpleData name="material">HDPE</SimpleData>
                    <SimpleData name="length_m">${"%.1f".format(Locale.US, distanceMeters)}</SimpleData>
                    <SimpleData name="points_count">${points.size}</SimpleData>
                    <SimpleData name="survey_date">$dateIso</SimpleData>
                  </ExtendedData>
                  <LineStyle>
                    <color>${kmlColor(diameter.hexColor)}</color>
                    <width>6</width>
                  </LineStyle>
                  <LineString>
                    <tessellate>1</tessellate>
                    <coordinates>$coordString</coordinates>
                  </LineString>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()

        file.writeText(kmlContent, Charsets.UTF_8)

        // 2. Also copy to public Downloads/TapTrack/AsBuilt so surveyor can access it directly in "My Files"
        runCatching {
            val pubDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "TapTrack/AsBuilt"
            ).apply { mkdirs() }
            File(pubDir, fileName).writeText(kmlContent, Charsets.UTF_8)
        }

        return file
    }

    fun saveKml(
        context: Context,
        pipeName: String,
        diameter: PipeDiameter,
        points: List<GeoPoint>,
        totalDistanceM: Double
    ): File = exportPipelineToKml(context, pipeName, diameter, points, totalDistanceM)

    /** Uploads the surveyed pipeline to Supabase storage (media bucket) and inserts a row into public.as_built_pipelines */
    suspend fun uploadToSupabase(
        container: AppContainer,
        pipeName: String,
        diameter: PipeDiameter,
        points: List<GeoPoint>,
        distanceMeters: Double,
        file: File
    ): Result<String> = runCatching {
        val projectId = container.session.projectId
            ?: throw IllegalStateException("No active project in session")
        val dateIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val storagePath = "$projectId/as_built/${file.name}"

        // 1. Upload KML file to Supabase storage 'media' bucket
        container.api.uploadFile(
            bucket = "media",
            path = storagePath,
            file = file,
            contentType = "application/vnd.google-earth.kml+xml"
        )

        // 2. Build coordinates array
        val coordsJson = JSONArray().apply {
            points.forEach { pt ->
                put(
                    JSONObject()
                        .put("lat", pt.latitude)
                        .put("lng", pt.longitude)
                        .put("alt", pt.altitude)
                )
            }
        }

        // 3. Insert record into public.as_built_pipelines table
        val row = JSONObject()
            .put("project_id", projectId)
            .put("name", pipeName.ifBlank { diameter.label })
            .put("diameter_inches", diameter.inches)
            .put("diameter_mm", diameter.mm)
            .put("material", "HDPE")
            .put("pipe_label", diameter.label)
            .put("color", diameter.hexColor)
            .put("length_meters", distanceMeters)
            .put("points_count", points.size)
            .put("coordinates", coordsJson)
            .put("storage_path", storagePath)
            .put("survey_date", dateIso)
            .putNullable("surveyor_name", container.session.fullName.ifBlank { null })

        val inserted = container.api.insert("as_built_pipelines", row)
        inserted.optString("id", file.name)
    }

    /** Upload an existing KML file from disk to Supabase if parsed */
    suspend fun uploadExistingFileToSupabase(
        container: AppContainer,
        file: File
    ): Result<String> = runCatching {
        val projectId = container.session.projectId
            ?: throw IllegalStateException("No active project in session")
        val storagePath = "$projectId/as_built/${file.name}"

        // Upload KML file to storage
        container.api.uploadFile(
            bucket = "media",
            path = storagePath,
            file = file,
            contentType = "application/vnd.google-earth.kml+xml"
        )

        // Parse basic details from KML
        val content = file.readText()
        val diameterInMatch = Regex("""<SimpleData name="diameter_in">(\d+)</SimpleData>""").find(content)
        val diameterInches = diameterInMatch?.groupValues?.get(1)?.toIntOrNull() ?: 4
        val diameterObj = STANDARD_PIPE_DIAMETERS.find { it.inches == diameterInches } ?: PipeDiameter.INCH_4

        val lengthMatch = Regex("""<SimpleData name="length_m">([0-9.]+)</SimpleData>""").find(content)
        val lengthMeters = lengthMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        val coordMatch = Regex("""<coordinates>([^<]+)</coordinates>""").find(content)
        val coordsJson = JSONArray()
        var ptCount = 0
        if (coordMatch != null) {
            val tokens = coordMatch.groupValues[1].trim().split(Regex("""\s+"""))
            for (token in tokens) {
                val parts = token.split(",")
                if (parts.size >= 2) {
                    val lng = parts[0].toDoubleOrNull() ?: continue
                    val lat = parts[1].toDoubleOrNull() ?: continue
                    val alt = if (parts.size > 2) parts[2].toDoubleOrNull() ?: 0.0 else 0.0
                    coordsJson.put(JSONObject().put("lat", lat).put("lng", lng).put("alt", alt))
                    ptCount++
                }
            }
        }

        val dateIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.lastModified()))

        val row = JSONObject()
            .put("project_id", projectId)
            .put("name", file.nameWithoutExtension)
            .put("diameter_inches", diameterObj.inches)
            .put("diameter_mm", diameterObj.mm)
            .put("material", "HDPE")
            .put("pipe_label", diameterObj.label)
            .put("color", diameterObj.hexColor)
            .put("length_meters", lengthMeters)
            .put("points_count", ptCount)
            .put("coordinates", coordsJson)
            .put("storage_path", storagePath)
            .put("survey_date", dateIso)
            .putNullable("surveyor_name", container.session.fullName.ifBlank { null })

        val inserted = container.api.insert("as_built_pipelines", row)
        inserted.optString("id", file.name)
    }

    /** Copy any existing files in app private storage to public Downloads if missing */
    fun ensurePublicCopies(context: Context) {
        runCatching {
            val privDir = File(context.filesDir, "as_built_kml")
            if (!privDir.exists()) return
            val pubDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "TapTrack/AsBuilt"
            ).apply { mkdirs() }
            privDir.listFiles { f -> f.isFile && f.extension.equals("kml", ignoreCase = true) }?.forEach { f ->
                val dest = File(pubDir, f.name)
                if (!dest.exists()) {
                    f.copyTo(dest, overwrite = false)
                }
            }
        }
    }

    fun shareKml(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.google-earth.kml+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TEXT, "As-Built Pipeline Survey KML: ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share As-Built KML"))
    }

    fun listSavedSurveys(context: Context): List<File> {
        ensurePublicCopies(context)
        val dir = File(context.filesDir, "as_built_kml")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.equals("kml", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
