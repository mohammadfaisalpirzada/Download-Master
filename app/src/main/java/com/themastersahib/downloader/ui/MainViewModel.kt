package com.themastersahib.downloader.ui

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.themastersahib.downloader.data.download.DownloadManagerRepository
import com.themastersahib.downloader.data.download.FileItem
import com.themastersahib.downloader.data.download.StorageHelper
import com.themastersahib.downloader.data.model.DownloadFormat
import com.themastersahib.downloader.data.model.DownloadTask
import com.themastersahib.downloader.data.model.VideoInfo
import com.themastersahib.downloader.data.parser.LinkParserManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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

    val activeTasks: StateFlow<List<DownloadTask>> = downloadRepo.tasks

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _savedFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val savedFiles: StateFlow<List<FileItem>> = _savedFiles.asStateFlow()

    init {
        loadSavedFiles()
    }

    fun onUrlChanged(newUrl: String) {
        _urlInput.value = newUrl
        if (_fetchState.value is FetchState.Error) {
            _fetchState.value = FetchState.Idle
        }
    }

    fun pasteFromClipboard() {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString() ?: ""
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
                _fetchState.value = FetchState.Error(err.localizedMessage ?: "Failed to resolve video")
            }
        }
    }

    fun startDownload(videoInfo: VideoInfo, format: DownloadFormat) {
        downloadRepo.startDownload(videoInfo, format)
        _fetchState.value = FetchState.Idle
        _urlInput.value = ""
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
}
