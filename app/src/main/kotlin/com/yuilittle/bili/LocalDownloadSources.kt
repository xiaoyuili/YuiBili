package com.yuilittle.bili

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File

@UnstableApi
internal fun DownloadManager.Task.localMediaSources(context: Context, metadata: MediaMetadata? = null): List<MediaSource> {
    if (state != DownloadManager.STATE_COMPLETED) return emptyList()
    val library = destination == DownloadDestination.LIBRARY
    val dash = !library && mpdPath.isNotBlank()
    val uris = when {
        library -> {
            if (libraryVersion.isNotEmpty() && libraryVersion == android.provider.MediaStore.getVersion(context)) {
                publicUris.map { DownloadMediaLibrary.itemUri(it, type == DownloadManager.TYPE_AUDIO) ?: return emptyList() }
            } else libraryNames.map { DownloadMediaLibrary.find(context, type == DownloadManager.TYPE_AUDIO, id, it) ?: return emptyList() }
        }
        dash -> listOf(Uri.fromFile(File(mpdPath)))
        audioPath.isNotBlank() -> listOf(Uri.fromFile(File(audioPath)))
        else -> singlePaths.map { Uri.fromFile(File(it)) }
    }
    val factory = DefaultDataSource.Factory(context)
    val progressive = ProgressiveMediaSource.Factory(factory)
    return uris.map { uri ->
        val item = MediaItem.Builder().setUri(uri).setMediaId("local:$id")
            .setMimeType(if (dash) MimeTypes.APPLICATION_MPD else if (type == DownloadManager.TYPE_AUDIO) MimeTypes.AUDIO_MP4 else MimeTypes.VIDEO_MP4)
            .apply { metadata?.let { setMediaMetadata(it) } }.build()
        if (dash) DashMediaSource.Factory(factory).createMediaSource(item) else progressive.createMediaSource(item)
    }
}
