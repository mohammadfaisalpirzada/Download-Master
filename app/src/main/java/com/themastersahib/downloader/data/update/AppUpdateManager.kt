package com.themastersahib.downloader.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.themastersahib.downloader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?
)

class AppUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val releasesApiUrl = "https://api.github.com/repos/mohammadfaisalpirzada/Download-Master/releases/latest"

    suspend fun checkForUpdates(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(releasesApiUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "DownloadMaster-AndroidApp")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP error ${response.code}"))
                }

                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
                val json = JsonParser.parseString(body).asJsonObject

                val tagName = json.get("tag_name")?.asString ?: ""
                val releaseNotes = json.get("body")?.asString ?: "Bug fixes and performance improvements."

                var apkUrl: String? = null
                val assets = json.getAsJsonArray("assets")
                if (assets != null) {
                    for (asset in assets) {
                        val assetObj = asset.asJsonObject
                        val name = assetObj.get("name")?.asString ?: ""
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = assetObj.get("browser_download_url")?.asString
                            break
                        }
                    }
                }

                val currentVersion = BuildConfig.VERSION_NAME
                val cleanTag = tagName.removePrefix("v").trim()
                val cleanCurrent = currentVersion.removePrefix("v").trim()

                val hasUpdate = isNewerVersion(cleanTag, cleanCurrent) && !apkUrl.isNullOrEmpty()

                Result.success(
                    UpdateInfo(
                        hasUpdate = hasUpdate,
                        latestVersion = tagName,
                        releaseNotes = releaseNotes,
                        apkDownloadUrl = apkUrl
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (e: Exception) {
            // Ignored
        }
        return false
    }

    suspend fun downloadAndInstallApk(
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "DownloadMaster-AndroidApp")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to download APK: ${response.code}"))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("Empty APK body"))
                val totalLength = body.contentLength()

                val updateDir = File(context.cacheDir, "updates")
                if (!updateDir.exists()) updateDir.mkdirs()
                val apkFile = File(updateDir, "DownloadMaster-update.apk")
                if (apkFile.exists()) apkFile.delete()

                var downloaded = 0L
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)

                outputStream.use { out ->
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalLength > 0) {
                            val progress = ((downloaded * 100) / totalLength).toInt()
                            onProgress(progress)
                        }
                    }
                    out.flush()
                }

                // Trigger Android Package Installer Intent
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                context.startActivity(installIntent)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
