package com.example.gptmobileai.debug

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ExportService(
    private val context: Context,
    private val eventBus: TelemetryCollector
) {
    fun exportToJson(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val events = eventBus.getEvents()

        val eventEntries = events.joinToString(",\n") { event ->
            "    \"${event.javaClass.simpleName}\": \"$event\""
        }

        return """{
  "exportedAt": "$timestamp",
  "events": [
+$eventEntries
  ]
}"""
    }

    fun exportToCsv(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val events = eventBus.getEvents()

        val header = "timestamp,event_type,data\n"
        val rows = events.joinToString("\n") { event ->
            val timestamp = dateFormat.format(Date())
            val eventType = event.javaClass.simpleName
            val data = event.toString().replace("\"", "\"\"")
            "\"$timestamp\",\"$eventType\",\"$data\""
        }
        return header + rows
    }

    fun shareExport(format: String) {
        val content = when (format.lowercase()) {
            "json" -> exportToJson()
            "csv" -> exportToCsv()
            else -> exportToJson()
        }

        val extension = if (format.lowercase() == "csv") "csv" else "json"
        val file = File(context.cacheDir, "aetherion_max_export.$extension")
        file.writeText(content)

        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            android.net.Uri.fromFile(file)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (extension == "csv") "text/csv" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AETHERION MAX Diagnostics Export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Share AETHERION MAX Export").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
