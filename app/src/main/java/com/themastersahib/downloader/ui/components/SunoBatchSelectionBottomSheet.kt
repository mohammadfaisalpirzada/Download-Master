package com.themastersahib.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.themastersahib.downloader.data.model.BatchSongItem
import com.themastersahib.downloader.data.model.DownloadFormat
import com.themastersahib.downloader.data.model.SocialPlatform
import com.themastersahib.downloader.data.model.VideoInfo
import com.themastersahib.downloader.ui.theme.AccentCyan
import com.themastersahib.downloader.ui.theme.AccentEmerald
import com.themastersahib.downloader.ui.theme.PrimaryBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SunoBatchSelectionBottomSheet(
    videoInfo: VideoInfo,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onDownloadBatch: (List<Pair<VideoInfo, DownloadFormat>>) -> Unit
) {
    // List of songs with selectable state
    val songsState = remember {
        mutableStateListOf<BatchSongItem>().apply {
            addAll(videoInfo.batchSongs)
        }
    }

    var selectedFormatType by remember { mutableStateOf("MP3") } // "MP3" or "MP4"

    val selectedCount = songsState.count { it.isSelected }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF5500).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFFFF5500),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Suno AI Music Downloader",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${songsState.size} songs found on this page",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action row: Select All / Format Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Select/Deselect All Button
                OutlinedButton(
                    onClick = {
                        val allSelected = songsState.all { it.isSelected }
                        songsState.indices.forEach { index ->
                            songsState[index] = songsState[index].copy(isSelected = !allSelected)
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (songsState.all { it.isSelected }) "Deselect All" else "Select All",
                        fontSize = 11.sp
                    )
                }

                // Format Toggle (MP3 vs MP4)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedFormatType == "MP3",
                        onClick = { selectedFormatType = "MP3" },
                        label = { Text("MP3 Audio", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentEmerald,
                            selectedLabelColor = Color.Black
                        )
                    )

                    FilterChip(
                        selected = selectedFormatType == "MP4",
                        onClick = { selectedFormatType = "MP4" },
                        label = { Text("MP4 Video", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Songs List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .height(300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(songsState, key = { _, item -> item.id }) { index, song ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                songsState[index] = song.copy(isSelected = !song.isSelected)
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (song.isSelected) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = song.isSelected,
                                onCheckedChange = { checked ->
                                    songsState[index] = song.copy(isSelected = checked)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AccentCyan)
                            )

                            if (!song.coverUrl.isNullOrEmpty()) {
                                AsyncImage(
                                    model = song.coverUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (selectedFormatType == "MP3") "320kbps Audio (MP3)" else "1080p Music Video (MP4)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Download Button
            Button(
                onClick = {
                    val selectedSongs = songsState.filter { it.isSelected }
                    if (selectedSongs.isNotEmpty()) {
                        val batchToDownload = selectedSongs.map { song ->
                            val isAudio = selectedFormatType == "MP3"
                            val format = DownloadFormat(
                                id = "suno_${song.id}_$selectedFormatType",
                                label = if (isAudio) "Suno MP3" else "Suno MP4",
                                resolution = if (isAudio) "320kbps" else "1080p",
                                downloadUrl = if (isAudio) song.audioUrl else song.videoUrl,
                                isAudioOnly = isAudio,
                                extension = if (isAudio) "mp3" else "mp4",
                                note = "Suno AI Music"
                            )
                            val singleInfo = VideoInfo(
                                originalUrl = videoInfo.originalUrl,
                                title = song.title,
                                thumbnail = song.coverUrl,
                                author = "Suno AI",
                                durationSeconds = 0L,
                                platform = SocialPlatform.SUNO,
                                formats = listOf(format)
                            )
                            Pair(singleInfo, format)
                        }

                        onDownloadBatch(batchToDownload)
                        onDismiss()
                    }
                },
                enabled = selectedCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Download Selected ($selectedCount Songs as $selectedFormatType)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
