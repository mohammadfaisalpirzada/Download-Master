package com.themastersahib.downloader.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.themastersahib.downloader.data.auth.GoogleDriveAuthManager
import com.themastersahib.downloader.data.auth.GoogleUser
import com.themastersahib.downloader.data.download.DownloadManagerRepository
import com.themastersahib.downloader.data.download.FileItem
import com.themastersahib.downloader.data.download.StorageHelper
import com.themastersahib.downloader.data.model.DownloadFormat
import com.themastersahib.downloader.data.model.DownloadTask
import com.themastersahib.downloader.data.model.VideoInfo
import com.themastersahib.downloader.data.parser.LinkParserManager
import com.themastersahib.downloader.data.update.AppUpdateManager
import com.themastersahib.downloader.data.update.UpdateInfo
import android.provider.OpenableColumns
import com.themastersahib.downloader.MasterDownloaderApp
import com.themastersahib.downloader.data.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class FetchState {
    object Idle : FetchState()
    object Loading : FetchState()
    data class Success(val info: VideoInfo) : FetchState()
    data class Error(val message: String) : FetchState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val parserManager = LinkParserManager()
    // Shared with the foreground service, so downloads survive the screen being closed.
    private val downloadRepo: DownloadManagerRepository =
        (application as MasterDownloaderApp).downloadRepository
    private val uiPrefs = application.getSharedPreferences("dm_ui_settings", Context.MODE_PRIVATE)

    /** Drive uploads run one at a time so the shared progress state stays meaningful. */
    private val driveUploadLock = Mutex()
    private val handledCompletedTaskIds = HashSet<String>()
    private val updateManager = AppUpdateManager(application)
    private val authManager = GoogleDriveAuthManager(application)

    val activeTasks: StateFlow<List<DownloadTask>> = downloadRepo.tasks
    val maxConcurrency: StateFlow<Int> = downloadRepo.maxConcurrency

    // Google Drive User Auth & Sync State
    private val _googleUser = MutableStateFlow<GoogleUser?>(null)
    val googleUser: StateFlow<GoogleUser?> = _googleUser.asStateFlow()

    private val _isSyncingToDrive = MutableStateFlow(false)
    val isSyncingToDrive: StateFlow<Boolean> = _isSyncingToDrive.asStateFlow()

    private val _driveSyncProgress = MutableStateFlow(0)
    val driveSyncProgress: StateFlow<Int> = _driveSyncProgress.asStateFlow()

    private val _driveSyncMessage = MutableStateFlow<String?>(null)
    val driveSyncMessage: StateFlow<String?> = _driveSyncMessage.asStateFlow()

    private val _autoSyncToDrive = MutableStateFlow(uiPrefs.getBoolean(PREF_AUTO_SYNC, false))
    val autoSyncToDrive: StateFlow<Boolean> = _autoSyncToDrive.asStateFlow()

    // In-app Auto Update State
    private val _availableUpdate = MutableStateFlow<UpdateInfo?>(null)
    val availableUpdate: StateFlow<UpdateInfo?> = _availableUpdate.asStateFlow()

    private val _isDownloadingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate: StateFlow<Boolean> = _isDownloadingUpdate.asStateFlow()

    private val _updateProgress = MutableStateFlow(0)
    val updateProgress: StateFlow<Int> = _updateProgress.asStateFlow()

    private val _updateStatusMessage = MutableStateFlow<String?>(null)
    val updateStatusMessage: StateFlow<String?> = _updateStatusMessage.asStateFlow()

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _savedFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val savedFiles: StateFlow<List<FileItem>> = _savedFiles.asStateFlow()

    // Clipboard auto-detect without locking the user
    private val _detectedClipboardUrl = MutableStateFlow<String?>(null)
    val detectedClipboardUrl: StateFlow<String?> = _detectedClipboardUrl.asStateFlow()

    private var lastDismissedUrl: String? = null
    private var fetchJob: Job? = null

    private companion object {
        const val PREF_AUTO_SYNC = "auto_sync_to_drive"
    }

    init {
        loadSavedFiles()
        checkForUpdates(silent = true)
        loadGoogleUser()
        observeCompletedDownloads()
    }

    /** Refreshes the gallery and runs the optional Drive auto-sync when a download finishes. */
    private fun observeCompletedDownloads() {
        // Downloads that finished before this screen existed were already handled.
        downloadRepo.tasks.value
            .filter { it.status == TaskStatus.COMPLETED }
            .forEach { handledCompletedTaskIds.add(it.id) }

        viewModelScope.launch {
            downloadRepo.tasks.collect { tasks ->
                val newlyCompleted = tasks.filter {
                    it.status == TaskStatus.COMPLETED && handledCompletedTaskIds.add(it.id)
                }
                // Forget tasks that left the list so the set cannot grow forever.
                val liveIds = tasks.mapTo(HashSet()) { it.id }
                handledCompletedTaskIds.retainAll(liveIds)

                if (newlyCompleted.isEmpty()) return@collect
                loadSavedFiles()
                if (_autoSyncToDrive.value && _googleUser.value != null) {
                    newlyCompleted.forEach { task ->
                        task.localFilePath?.let { syncFileToUserGoogleDrive(Uri.parse(it), task.isAudioOnly) }
                    }
                }
            }
        }
    }

    private fun loadGoogleUser() {
        _googleUser.value = authManager.getLastSignedInUser()
    }

    fun getGoogleSignInIntent(): Intent {
        return authManager.googleSignInClient.signInIntent
    }

    fun onGoogleSignInResult(data: Intent?) {
        val result = authManager.handleSignInResult(data)
        result.onSuccess { user ->
            _googleUser.value = user
            _driveSyncMessage.value = "Connected as ${user.displayName} (${user.email})"
        }.onFailure { err ->
            _driveSyncMessage.value = "Sign-in error: ${err.localizedMessage}"
        }
    }

    fun signOutGoogle() {
        authManager.signOut {
            _googleUser.value = null
            _autoSyncToDrive.value = false
            uiPrefs.edit().putBoolean(PREF_AUTO_SYNC, false).apply()
            _driveSyncMessage.value = "Signed out of Google Drive"
        }
    }

    fun toggleAutoSyncToDrive(enabled: Boolean) {
        if (enabled && _googleUser.value == null) {
            _driveSyncMessage.value = "Please sign in with Google first to enable auto-sync."
            return
        }
        _autoSyncToDrive.value = enabled
        uiPrefs.edit().putBoolean(PREF_AUTO_SYNC, enabled).apply()
    }

    fun clearDriveSyncMessage() {
        _driveSyncMessage.value = null
    }

    fun syncFileToUserGoogleDrive(fileUri: Uri, isAudio: Boolean) {
        if (_googleUser.value == null) {
            _driveSyncMessage.value = "Please sign in with Google first to sync files to your Drive."
            return
        }

        viewModelScope.launch {
            driveUploadLock.withLock {
                // Upload under the real file name and type instead of a generic placeholder.
                val (fileName, mimeType) = withContext(Dispatchers.IO) { describeFile(fileUri, isAudio) }

                _isSyncingToDrive.value = true
                _driveSyncProgress.value = 0
                _driveSyncMessage.value = "Syncing $fileName to your Google Drive..."
                try {
                    val result = authManager.uploadToUserDrive(fileUri, fileName, mimeType) { progress ->
                        _driveSyncProgress.value = progress
                    }
                    result.onSuccess {
                        _driveSyncMessage.value = "Successfully synced to your personal Google Drive!"
                    }.onFailure { err ->
                        _driveSyncMessage.value = "Google Drive sync error: ${err.localizedMessage ?: "unknown error"}"
                    }
                } finally {
                    _isSyncingToDrive.value = false
                }
            }
        }
    }

    private fun describeFile(fileUri: Uri, isAudio: Boolean): Pair<String, String> {
        val resolver = getApplication<Application>().contentResolver
        var name: String? = null
        try {
            resolver.query(fileUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0)
            }
        } catch (_: Exception) {
        }
        val detectedType = try { resolver.getType(fileUri) } catch (_: Exception) { null }
        val mimeType = detectedType ?: if (isAudio) "audio/mp4" else "video/mp4"
        val fallback = "DownloadMaster_${System.currentTimeMillis()}.${if (isAudio) "m4a" else "mp4"}"
        return Pair(name?.takeIf { it.isNotBlank() } ?: fallback, mimeType)
    }

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            val result = updateManager.checkForUpdates()
            result.onSuccess { info ->
                if (info.hasUpdate) {
                    _availableUpdate.value = info
                } else if (!silent) {
                    _updateStatusMessage.value = "You are already using the latest version."
                }
            }.onFailure {
                if (!silent) {
                    _updateStatusMessage.value = "Failed to check for updates: ${it.localizedMessage}"
                }
            }
        }
    }

    fun clearUpdateStatusMessage() {
        _updateStatusMessage.value = null
    }

    fun dismissUpdateDialog() {
        _availableUpdate.value = null
    }

    fun performUpdate() {
        val update = _availableUpdate.value ?: return
        val apkUrl = update.apkDownloadUrl ?: return

        viewModelScope.launch {
            _isDownloadingUpdate.value = true
            _updateProgress.value = 0
            val result = updateManager.downloadAndInstallApk(apkUrl) { progress ->
                _updateProgress.value = progress
            }
            _isDownloadingUpdate.value = false
            if (result.isFailure) {
                _updateStatusMessage.value = "Update failed: ${result.exceptionOrNull()?.localizedMessage}"
            } else {
                _availableUpdate.value = null
            }
        }
    }

    fun onUrlChanged(newUrl: String) {
        _urlInput.value = newUrl
        if (_fetchState.value is FetchState.Error) {
            _fetchState.value = FetchState.Idle
        }
    }

    fun setMaxConcurrency(limit: Int) {
        downloadRepo.setMaxConcurrency(limit)
    }

    fun checkClipboardForVideoLink() {
        try {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val rawText = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                    if ((rawText.startsWith("http://") || rawText.startsWith("https://")) &&
                        rawText != lastDismissedUrl &&
                        rawText != _urlInput.value
                    ) {
                        _detectedClipboardUrl.value = rawText
                    }
                }
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    fun onAcceptClipboardPrompt() {
        val url = _detectedClipboardUrl.value ?: return
        _urlInput.value = url
        _detectedClipboardUrl.value = null
        fetchVideoInfo(url)
    }

    fun onDismissClipboardPrompt() {
        lastDismissedUrl = _detectedClipboardUrl.value
        _detectedClipboardUrl.value = null
    }

    fun pasteFromClipboard() {
        try {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                    if (text.startsWith("http://") || text.startsWith("https://")) {
                        _urlInput.value = text
                        fetchVideoInfo(text)
                    } else if (text.isNotEmpty()) {
                        _urlInput.value = text
                    }
                }
            }
        } catch (e: Exception) {
            // Some devices throw when the clipboard is locked or holds an unreadable item.
        }
    }

    fun fetchVideoInfo(url: String = _urlInput.value) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _fetchState.value = FetchState.Error("Please enter or paste a video URL")
            return
        }

        // A newer request replaces an older one, so a slow old answer cannot overwrite it.
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _fetchState.value = FetchState.Loading
            val result = parserManager.resolve(trimmed)
            result.onSuccess { info ->
                _fetchState.value = FetchState.Success(info)
            }.onFailure { err ->
                _fetchState.value = FetchState.Error(err.localizedMessage ?: "Failed to resolve video details")
            }
        }
    }

    fun startDownload(videoInfo: VideoInfo, format: DownloadFormat) {
        downloadRepo.startDownload(videoInfo, format)
        _fetchState.value = FetchState.Idle
        _urlInput.value = ""
        _detectedClipboardUrl.value = null
    }

    fun startBatchDownload(items: List<Pair<VideoInfo, DownloadFormat>>) {
        items.forEach { (info, format) ->
            downloadRepo.startDownload(info, format)
        }
        _fetchState.value = FetchState.Idle
        _urlInput.value = ""
        _detectedClipboardUrl.value = null
    }

    fun retryTask(taskId: String) {
        downloadRepo.retryTask(taskId)
    }

    fun dismissFormatSheet() {
        fetchJob?.cancel()
        _fetchState.value = FetchState.Idle
    }

    fun cancelTask(taskId: String) {
        downloadRepo.cancelTask(taskId)
    }

    fun loadSavedFiles() {
        viewModelScope.launch {
            // MediaStore queries hit the disk: keep them off the main thread.
            _savedFiles.value = withContext(Dispatchers.IO) {
                StorageHelper.getSavedFiles(getApplication())
            }
        }
    }

    fun saveToGoogleDrive(context: Context, fileUri: Uri, mimeType: String = "video/mp4") {
        try {
            // Target Google Drive directly if installed, or fallback to Drive-compatible chooser
            val driveIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, fileUri)
                setPackage("com.google.android.apps.docs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (driveIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(driveIntent)
            } else {
                val chooserIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(chooserIntent, "Save / Upload to Google Drive"))
            }
        } catch (e: Exception) {
            try {
                val generalIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(generalIntent, "Save File"))
            } catch (inner: Exception) {
                _driveSyncMessage.value = "No app available to share this file."
            }
        }
    }
}
