package com.themastersahib.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.themastersahib.downloader.data.model.TaskStatus
import com.themastersahib.downloader.ui.FetchState
import com.themastersahib.downloader.ui.MainViewModel
import com.themastersahib.downloader.ui.components.FormatSelectionBottomSheet
import com.themastersahib.downloader.ui.components.SunoBatchSelectionBottomSheet
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

        // Only on a fresh start: a recreated activity must not re-run an old shared link.
        if (savedInstanceState == null) {
            handleIncomingShareIntent(intent)
            requestRuntimePermissions()
        }

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
                val googleUser by viewModel.googleUser.collectAsState()
                val isSyncingToDrive by viewModel.isSyncingToDrive.collectAsState()
                val driveSyncProgress by viewModel.driveSyncProgress.collectAsState()
                val driveSyncMessage by viewModel.driveSyncMessage.collectAsState()
                val autoSyncToDrive by viewModel.autoSyncToDrive.collectAsState()

                val googleSignInLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    viewModel.onGoogleSignInResult(result.data)
                }

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

                // Format selection bottom sheet or Suno Batch Selection Sheet
                if (fetchState is FetchState.Success) {
                    val info = (fetchState as FetchState.Success).info
                    if (info.isBatch) {
                        SunoBatchSelectionBottomSheet(
                            videoInfo = info,
                            sheetState = sheetState,
                            onDismiss = { viewModel.dismissFormatSheet() },
                            onDownloadBatch = { batch ->
                                viewModel.startBatchDownload(batch)
                                currentScreen = Screen.Downloads
                            }
                        )
                    } else {
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
                                    label = {
                                        Text(
                                            text = stringResource(screen.titleRes),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 11.sp
                                        )
                                    }
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
                                onRetryTask = { viewModel.retryTask(it) },
                                onPlayMedia = { activePlayerUri = it },
                                onSaveToGoogleDrive = { uri, isAudio ->
                                    saveToDrive(uri, isAudio, googleUser != null)
                                }
                            )
                            Screen.Gallery -> GalleryScreen(
                                savedFiles = savedFiles,
                                onRefresh = { viewModel.loadSavedFiles() },
                                onPlayMedia = { activePlayerUri = it },
                                onSaveToGoogleDrive = { uri, isAudio ->
                                    saveToDrive(uri, isAudio, googleUser != null)
                                }
                            )
                            Screen.Settings -> SettingsScreen(
                                maxConcurrency = maxConcurrency,
                                updateStatusMessage = updateStatusMessage,
                                googleUser = googleUser,
                                isSyncingToDrive = isSyncingToDrive,
                                driveSyncProgress = driveSyncProgress,
                                driveSyncMessage = driveSyncMessage,
                                autoSyncToDrive = autoSyncToDrive,
                                onConcurrencyChanged = { viewModel.setMaxConcurrency(it) },
                                onCheckUpdates = { viewModel.checkForUpdates(silent = false) },
                                onClearUpdateStatus = { viewModel.clearUpdateStatusMessage() },
                                onSignInGoogle = { googleSignInLauncher.launch(viewModel.getGoogleSignInIntent()) },
                                onSignOutGoogle = { viewModel.signOutGoogle() },
                                onToggleAutoSync = { viewModel.toggleAutoSyncToDrive(it) },
                                onClearDriveMessage = { viewModel.clearDriveSyncMessage() }
                            )
                        }
                    }
                }
            }
        }
    }

    // Android 10+ only lets the focused window read the clipboard, so onResume is too early.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) viewModel.checkClipboardForVideoLink()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /** Notifications (Android 13+) for download progress, storage (Android 9 and older) for saving. */
    private fun requestRuntimePermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun saveToDrive(uri: Uri, isAudio: Boolean, signedIn: Boolean) {
        if (signedIn) {
            viewModel.syncFileToUserGoogleDrive(uri, isAudio)
        } else {
            viewModel.saveToGoogleDrive(this, uri, if (isAudio) "audio/*" else "video/*")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!sharedText.isNullOrEmpty()) {
                viewModel.onUrlChanged(sharedText)
                viewModel.fetchVideoInfo(sharedText)
            }
        }
    }
}
