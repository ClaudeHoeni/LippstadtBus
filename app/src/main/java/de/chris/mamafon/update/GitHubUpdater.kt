package de.chris.mamafon.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val apkUrl: String,
    val apkName: String
)

object GitHubUpdater {
    private const val REPO_OWNER = "ClaudeHoeni"
    private const val REPO_NAME = "MamaFon"

    fun getCurrentVersionName(context: Context): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val apiUrl = URL("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest")
            val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MamaFon-Updater")
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (conn.responseCode != 200) return@withContext null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val assets = json.optJSONArray("assets") ?: return@withContext null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk") && url.isNotBlank()) {
                    return@withContext ReleaseInfo(
                        tagName = tag,
                        apkUrl = url,
                        apkName = name
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun downloadAndInstallApk(
        context: Context,
        release: ReleaseInfo,
        onStatus: (String) -> Unit
    ) {
        try {
            val updatesDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.parentFile, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()
            val targetFile = File(updatesDir, release.apkName)
            if (targetFile.exists()) targetFile.delete()

            val request = DownloadManager.Request(Uri.parse(release.apkUrl))
                .setTitle("MamaFon Update ${release.tagName}")
                .setDescription("Lade neue APK-Version von GitHub...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(targetFile))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            onStatus("Lade Update ${release.tagName} herunter...")

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (id == downloadId) {
                        try { ctx?.unregisterReceiver(this) } catch (_: Exception) {}
                        if (targetFile.exists() && targetFile.length() > 0) {
                            onStatus("Starte Installation von ${release.tagName}...")
                            installApkFile(context, targetFile)
                        } else {
                            onStatus("Update-Download fehlgeschlagen.")
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        } catch (e: Exception) {
            onStatus("Update-Fehler: ${e.localizedMessage ?: "Unbekannt"}")
        }
    }

    fun installApkFile(context: Context, apkFile: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}