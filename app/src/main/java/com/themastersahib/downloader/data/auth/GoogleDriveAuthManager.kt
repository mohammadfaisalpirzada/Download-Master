package com.themastersahib.downloader.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class GoogleUser(
    val id: String,
    val displayName: String,
    val email: String,
    val photoUrl: String?
)

class GoogleDriveAuthManager(private val context: Context) {

    private val driveScope = Scope("https://www.googleapis.com/auth/drive.file")

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestProfile()
        .requestScopes(driveScope)
        .build()

    val googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(context, gso)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun getLastSignedInUser(): GoogleUser? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return GoogleUser(
            id = account.id ?: "",
            displayName = account.displayName ?: "Google User",
            email = account.email ?: "",
            photoUrl = account.photoUrl?.toString()
        )
    }

    fun handleSignInResult(data: Intent?): Result<GoogleUser> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                Result.success(
                    GoogleUser(
                        id = account.id ?: "",
                        displayName = account.displayName ?: "Google User",
                        email = account.email ?: "",
                        photoUrl = account.photoUrl?.toString()
                    )
                )
            } else {
                Result.failure(Exception("Google Sign-In returned null account"))
            }
        } catch (e: ApiException) {
            val friendlyMsg = when (e.statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR -> {
                    "Google Sign-In Error 10 (Configuration Required):\n" +
                    "Android OAuth Client ID missing in Google Cloud Console.\n" +
                    "Project: themastersahib (528622804634)\n" +
                    "Package Name: com.themastersahib.downloader\n" +
                    "SHA-1: 91:00:8C:17:30:14:29:C6:F4:EB:7B:92:1C:12:EF:9B:D5:6D:65:01"
                }
                CommonStatusCodes.SIGN_IN_REQUIRED -> "Please select a Google Account to sign in."
                CommonStatusCodes.NETWORK_ERROR -> "Network error during Google Sign-In. Please check your internet connection."
                else -> "Google Sign-In Error (${e.statusCode}): ${e.localizedMessage ?: "Unknown error"}"
            }
            Result.failure(Exception(friendlyMsg))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut(onComplete: () -> Unit) {
        googleSignInClient.signOut().addOnCompleteListener {
            onComplete()
        }
    }

    suspend fun uploadToUserDrive(
        fileUri: Uri,
        fileName: String,
        mimeType: String,
        onProgress: (Int) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val account: GoogleSignInAccount = GoogleSignIn.getLastSignedInAccount(context)
                ?: return@withContext Result.failure(Exception("No Google account connected. Please sign in first."))

            val androidAccount = account.account
                ?: return@withContext Result.failure(Exception("Unable to retrieve Android account."))

            // Fetch OAuth Access Token
            val token = GoogleAuthUtil.getToken(
                context,
                androidAccount,
                "oauth2:https://www.googleapis.com/auth/drive.file"
            )

            // Step 1: Ensure "Download Master" folder exists in user's Drive
            val folderId = getOrCreateDriveFolder(token, "Download Master")

            // Step 2: Upload file directly into user's folder
            val contentResolver = context.contentResolver
            // The descriptor is only needed for the size: close it right away.
            val fileLength = try {
                contentResolver.openFileDescriptor(fileUri, "r")?.use { it.statSize } ?: -1L
            } catch (_: Exception) {
                -1L
            }

            // Built with Gson so quotes in a file name cannot break the JSON.
            val metadataJson = com.google.gson.JsonObject().apply {
                addProperty("name", fileName)
                addProperty("mimeType", mimeType)
                if (folderId != null) {
                    add("parents", com.google.gson.JsonArray().apply { add(folderId) })
                }
            }.toString()

            // Opened last, so no earlier failure can leak it; the request body closes it.
            val inputStream: InputStream = contentResolver.openInputStream(fileUri)
                ?: return@withContext Result.failure(Exception("Cannot open local file stream."))

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "metadata",
                    "metadata.json",
                    metadataJson.toRequestBody("application/json; charset=UTF-8".toMediaType())
                )
                .addFormDataPart(
                    "file",
                    fileName,
                    createProgressRequestBody(inputStream, fileLength, mimeType, onProgress)
                )
                .build()

            val uploadRequest = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,webViewLink")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            httpClient.newCall(uploadRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: ""
                    val json = JsonParser.parseString(respBody).asJsonObject
                    val webViewLink = json.get("webViewLink")?.asString ?: "Uploaded to your Google Drive"
                    Result.success(webViewLink)
                } else {
                    Result.failure(Exception("Google Drive upload error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun getOrCreateDriveFolder(token: String, folderName: String): String? {
        try {
            // Check existing folder
            val searchUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter(
                    "q",
                    "name='${folderName.replace("'", "\\'")}' and mimeType='application/vnd.google-apps.folder' and trashed=false"
                )
                .addQueryParameter("fields", "files(id,name)")
                .build()
            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(searchRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    val files = json.getAsJsonArray("files")
                    if (files != null && files.size() > 0) {
                        return files[0].asJsonObject.get("id")?.asString
                    }
                }
            }

            // Create folder
            val createJson = com.google.gson.JsonObject().apply {
                addProperty("name", folderName)
                addProperty("mimeType", "application/vnd.google-apps.folder")
            }.toString()
            val createRequest = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .header("Authorization", "Bearer $token")
                .post(createJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()

            httpClient.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    return json.get("id")?.asString
                }
            }
        } catch (e: Exception) {
            // Handled
        }
        return null
    }

    private fun createProgressRequestBody(
        inputStream: InputStream,
        totalLength: Long,
        contentType: String,
        onProgress: (Int) -> Unit
    ): RequestBody {
        return object : RequestBody() {
            override fun contentType() = contentType.toMediaType()

            override fun contentLength(): Long = totalLength

            // The stream can be read once only, so OkHttp must not silently retry the upload.
            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(8 * 1024)
                var uploaded = 0L
                var lastPercent = -1
                var read: Int
                inputStream.use { stream ->
                    while (stream.read(buffer).also { read = it } != -1) {
                        sink.write(buffer, 0, read)
                        uploaded += read
                        if (totalLength > 0) {
                            val percent = ((uploaded * 100) / totalLength).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }
    }
}
