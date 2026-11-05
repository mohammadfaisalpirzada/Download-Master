package com.themastersahib.downloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.themastersahib.downloader.data.model.TaskStatus
import com.themastersahib.downloader.ui.FetchState
import com.themastersahib.downloader.ui.MainViewModel
import com.themastersahib.downloader.ui.components.FormatSelectionBottomSheet
import com.themastersahib.downloader.ui.components.UpdateDialog
import com.themastersahib.downloader.ui.components.VideoPlayerDialog
import com.themastersahib.downloader.ui.screens.DownloadsScreen
import com.themastersahib.downloader.ui.screens.GalleryScreen
import com.themastersahib.downloader.ui.screens.HomeScreen
import com.themastersahib.downloader.ui.screens.SettingsScreen
import com.themastersahib.downloader.ui.theme.DownloadMasterTheme

sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    object Home : Screen("home", R.string.tab_home, Icons.Default.Home)
    object Downloads : Screen("downloads", R.string.tab_downloads, Icons.Default.Download)
    object Gallery : Screen("gallery", R.string.tab_gallery, Icons.Default.Folder)
    object Settings : Screen("settings", R.string.tab_settings, Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIncomingShareIntent(intent)

        setContent {
            DownloadMasterTheme {
                val fetchState by viewModel.fetchState.collectAsState()
                val urlInput by viewModel.urlInput.collectAsState()
                val activeTasks by viewModel.activeTasks.collectAsState()
                val savedFiles by viewModel.savedFiles.collectAsState()
                val detectedClipboardUrl by viewModel.detectedClipboardUrl.collectAsState()
                val maxConcurrency by viewModel.maxConcurrency.collectAsState()
                val availableUpdate by viewModel.availableUpdate.collectAsState()
                val isDownloadingUpdate by viewModel.isDownloadingUpdate.collectAsState()
                val updateProgress by viewModel.updateProgress.collectAsState()
                val updateStatusMessage by viewModel.updateStatusMessage.collectAsState()

                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
                var activePlayerUri by remember { mutableStateOf<Uri?>(null) }

                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                // In-App Auto Update Dialog
                availableUpdate?.let { update ->
                    UpdateDialog(
                        updateInfo = update,
                        isDownloading = isDownloadingUpdate,
                        downloadProgress = updateProgress,
                        onConfirmUpdate = { viewModel.performUpdate() },
                        onDismiss = { viewModel.dismissUpdateDialog() }
                    )
                }

                // Format selection bottom sheet
                if (fetchState is FetchState.Success) {
                    val info = (fetchState as FetchState.Success).info
                    FormatSelectionBottomSheet(
                        videoInfo = info,
                        sheetState = sheetState,
                        onDismiss = { viewModel.dismissFormatSheet() },
                        onDownloadSelected = { format ->
                            viewModel.startDownload(info, format)
                            currentScreen = Screen.Downloads
                        }
                    )
                }

                // In-app Video / Audio Player Dialog
                activePlayerUri?.let { uri ->
                    VideoPlayerDialog(
                        mediaUri = uri,
                        onDismiss = { activePlayerUri = null }
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        val downloadingCount = activeTasks.count { it.status == TaskStatus.DOWNLOADING }
                        val screens = listOf(Screen.Home, Screen.Downloads, Screen.Gallery, Screen.Settings)

                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.navigationBarsPadding()
                        ) {
                            screens.forEach { screen ->
                                NavigationBarItem(
                                    selected = currentScreen.route == screen.route,
                                    onClick = {
                                        currentScreen = screen
                                        if (screen == Screen.Gallery) {
                                            viewModel.loadSavedFiles()
                                        }
                                    },
                                    icon = {
                                        if (screen == Screen.Downloads && downloadingCount > 0) {
                                            BadgedBox(badge = { Badge { Text(downloadingCount.toString()) } }) {
                                                Icon(screen.icon, contentDescription = null)
                                            }
                                        } else {
                                            Icon(screen.icon, contentDescription = null)
                                        }
                                    },
                                    label = { Text(stringResource(screen.titleRes)) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentScreen) {
                            Screen.Home -> HomeScreen(
                                urlInput = urlInput,
                                detectedClipboardUrl = detectedClipboardUrl,
                                fetchState = fetchState,
                                onUrlChanged = { viewModel.onUrlChanged(it) },
                                onPasteClicked = { viewModel.pasteFromClipboard() },
                                onFetchClicked = { viewModel.fetchVideoInfo() },
                                onAcceptClipboard = { viewModel.onAcceptClipboardPrompt() },
                                onDismissClipboard = { viewModel.onDismissClipboardPrompt() }
                            )
                            Screen.Downloads -> DownloadsScreen(
                                tasks = activeTasks,
                                onCancelTask = { viewModel.cancelTask(it) },
                                onPlayMedia = { activePlayerUri = it },
                                onSaveToGoogleDrive = { uri, isAudio ->
                                    viewModel.saveToGoogleDrive(
                                        this@MainActivity,
                                        uri,
                                        if (isAudio) "audio/mpeg" else "video/mp4"
                                    )
                                }
                            )
                            Screen.Gallery -> GalleryScreen(
                                savedFiles = savedFiles,
                                onRefresh = { viewModel.loadSavedFiles() },
                                onPlayMedia = { activePlayerUri = it },
                                onSaveToGoogleDrive = { uri, isAudio ->
                                    viewModel.saveToGoogleDrive(
                                        this@MainActivity,
                                        uri,
                                        if (isAudio) "audio/mpeg" else "video/mp4"
                                    )
                                }
                            )
                            Screen.Settings -> SettingsScreen(
                                maxConcurrency = maxConcurrency,
                                updateStatusMessage = updateStatusMessage,
                                onConcurrencyChanged = { viewModel.setMaxConcurrency(it) },
                                onCheckUpdates = { viewModel.checkForUpdates(silent = false) },
                                onClearUpdateStatus = { viewModel.clearUpdateStatusMessage() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkClipboardForVideoLink()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                viewModel.onUrlChanged(sharedText)
                viewModel.fetchVideoInfo(sharedText)
            }
        }
    }
}
