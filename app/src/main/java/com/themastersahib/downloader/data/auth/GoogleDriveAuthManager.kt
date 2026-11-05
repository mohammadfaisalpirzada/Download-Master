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
import com.google.android.gms.common.api.Scope
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val inputStream: InputStream = contentResolver.openInputStream(fileUri)
                ?: return@withContext Result.failure(Exception("Cannot open local file stream."))

            val fileLength = contentResolver.openFileDescriptor(fileUri, "r")?.statSize ?: -1L

            val metadataJson = if (folderId != null) {
                """{"name": "$fileName", "parents": ["$folderId"], "mimeType": "$mimeType"}"""
            } else {
                """{"name": "$fileName", "mimeType": "$mimeType"}"""
            }

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
            val searchUrl = "https://www.googleapis.com/drive/v3/files?q=name='${folderName}' and mimeType='application/vnd.google-apps.folder' and trashed=false&fields=files(id,name)"
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
            val createJson = """{"name": "$folderName", "mimeType": "application/vnd.google-apps.folder"}"""
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

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(8 * 1024)
                var uploaded = 0L
                var read: Int
                inputStream.use { stream ->
                    while (stream.read(buffer).also { read = it } != -1) {
                        sink.write(buffer, 0, read)
                        uploaded += read
                        if (totalLength > 0) {
                            val percent = ((uploaded * 100) / totalLength).toInt()
                            onProgress(percent)
                        }
                    }
                }
            }
        }
    }
}
