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
    private val downloadRepo = DownloadManagerRepository(application)
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

    private val _autoSyncToDrive = MutableStateFlow(false)
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

    init {
        loadSavedFiles()
        checkForUpdates(silent = true)
        loadGoogleUser()
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
            _driveSyncMessage.value = "Signed out of Google Drive"
        }
    }

    fun toggleAutoSyncToDrive(enabled: Boolean) {
        _autoSyncToDrive.value = enabled
    }

    fun clearDriveSyncMessage() {
        _driveSyncMessage.value = null
    }

    fun syncFileToUserGoogleDrive(fileUri: Uri, fileName: String, mimeType: String = "video/mp4") {
        if (_googleUser.value == null) {
            _driveSyncMessage.value = "Please sign in with Google first to sync files to your Drive."
            return
        }

        viewModelScope.launch {
            _isSyncingToDrive.value = true
            _driveSyncProgress.value = 0
            _driveSyncMessage.value = "Syncing $fileName to your Google Drive..."

            val result = authManager.uploadToUserDrive(fileUri, fileName, mimeType) { progress ->
                _driveSyncProgress.value = progress
            }

            _isSyncingToDrive.value = false
            result.onSuccess { link ->
                _driveSyncMessage.value = "Successfully synced to your personal Google Drive!"
            }.onFailure { err ->
                _driveSyncMessage.value = "Google Drive sync error: ${err.localizedMessage}"
            }
        }
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
    }

    fun fetchVideoInfo(url: String = _urlInput.value) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _fetchState.value = FetchState.Error("Please enter or paste a video URL")
            return
        }

        viewModelScope.launch {
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
        _fetchState.value = FetchState.Idle
    }

    fun cancelTask(taskId: String) {
        downloadRepo.cancelTask(taskId)
    }

    fun loadSavedFiles() {
        viewModelScope.launch {
            val list = StorageHelper.getSavedFiles(getApplication())
            _savedFiles.value = list
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
            val generalIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(generalIntent, "Save File"))
        }
    }
}
