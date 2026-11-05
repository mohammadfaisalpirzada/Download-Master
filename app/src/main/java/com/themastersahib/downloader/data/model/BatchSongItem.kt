package com.themastersahib.downloader.data.model

data class BatchSongItem(
    val id: String,
    val title: String,
    val audioUrl: String,
    val videoUrl: String,
    val coverUrl: String? = null,
    val isSelected: Boolean = true
)
