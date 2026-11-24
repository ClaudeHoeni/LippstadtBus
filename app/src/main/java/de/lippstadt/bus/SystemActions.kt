package de.lippstadt.bus

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.FileProvider
import de.lippstadt.bus.data.sanitizeTranscriptWords
import java.io.File
import java.util.Calendar
import java.util.Locale

/** Aktionen, die andere Apps starten (Wecker, Karte, Installer). */
object SystemActions {

    fun setDepartureAlarm(context: Context, departureMillis: Long, minutesBefore: Int, label: String): String {
        val cal = Calendar.getInstance(Locale.GERMANY).apply {
            timeInMillis = departureMillis - minutesBefore * 60_000L
        }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val time = String.format(Locale.GERMANY, "%02d:%02d", hour, minute)

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, sanitizeTranscriptWords(label))
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return try {
            context.startActivity(intent)
            "Wecker auf $time Uhr ($minutesBefore Min. vor Abfahrt) gestellt."
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                "Uhr-App geöffnet – bitte Wecker auf $time Uhr stellen."
            } catch (_: Exception) {
                "Keine Uhr-App gefunden (Erinnerung: $time Uhr)."
            }
        } catch (_: SecurityException) {
            "Wecker konnte nicht gestellt werden (Zielzeit: $time Uhr)."
        }
    }

    fun openInMaps(context: Context, lat: Double?, lon: Double?, label: String): Boolean {
        val clean = Uri.encode(sanitizeTranscriptWords(label))
        val uri = if (lat != null && lon != null) {
            Uri.parse("geo:$lat,$lon?q=$lat,$lon($clean)")
        } else {
            Uri.parse("geo:0,0?q=$clean")
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Öffnet Google Maps. Mit [searchKeyword] (z. B. "Supermarkt") zeigt Google alle passenden Orte
     * in der Nähe, sonst nur das Ziel.
     */
    fun openGoogleMaps(context: Context, lat: Double, lon: Double, label: String, searchKeyword: String?): Boolean {
        val q = if (searchKeyword != null) Uri.encode(searchKeyword) else "$lat,$lon(${Uri.encode(sanitizeTranscriptWords(label))})"
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$q"))
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(geo)
            true
        } catch (_: ActivityNotFoundException) {
            val web = if (searchKeyword != null) {
                "https://www.google.com/maps/search/${Uri.encode(searchKeyword)}/@$lat,$lon,15z"
            } else {
                "https://www.google.com/maps/search/?api=1&query=$lat,$lon"
            }
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }

    /** true, wenn die App APKs installieren darf; sonst wird die Einstellungsseite geöffnet. */
    fun ensureInstallPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (context.packageManager.canRequestPackageInstalls()) return true
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
        }
        return false
    }

    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
