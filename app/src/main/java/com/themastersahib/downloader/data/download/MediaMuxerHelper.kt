package com.themastersahib.downloader.data.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Remuxes (no re-encoding) tracks into an MP4 / M4A container with the platform MediaMuxer.
 * Only codecs the MP4 muxer accepts are supported (H.264 / H.265 video, AAC audio);
 * anything else (VP9, AV1, Opus) makes the call return false.
 */
object MediaMuxerHelper {

    private const val MIN_BUFFER_BYTES = 2 * 1024 * 1024

    private val MUXABLE_VIDEO = setOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaFormat.MIMETYPE_VIDEO_MPEG4,
        MediaFormat.MIMETYPE_VIDEO_H263
    )
    private val MUXABLE_AUDIO = setOf(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaFormat.MIMETYPE_AUDIO_AMR_NB,
        MediaFormat.MIMETYPE_AUDIO_AMR_WB
    )

    private class Source(val file: File, val wantVideo: Boolean)

    private class Track(val extractor: MediaExtractor, val muxerTrack: Int) {
        var finished = false
    }

    /** Combines the video track of [videoFile] with the audio track of [audioFile]. */
    fun mux(videoFile: File, audioFile: File, outputFile: File): Boolean {
        return remux(listOf(Source(videoFile, true), Source(audioFile, false)), outputFile)
    }

    /** Saves only the audio track of [sourceFile] (for example an MP4 video) as an M4A file. */
    fun extractAudio(sourceFile: File, outputFile: File): Boolean {
        return remux(listOf(Source(sourceFile, false)), outputFile)
    }

    private fun remux(sources: List<Source>, outputFile: File): Boolean {
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var success = false

        try {
            if (outputFile.exists()) outputFile.delete()
            outputFile.parentFile?.mkdirs()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val tracks = mutableListOf<Track>()
            var bufferSize = MIN_BUFFER_BYTES

            for (source in sources) {
                val extractor = MediaExtractor()
                extractors.add(extractor)
                extractor.setDataSource(source.file.absolutePath)

                var selected = false
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    val matches = if (source.wantVideo) mime in MUXABLE_VIDEO else mime in MUXABLE_AUDIO
                    if (!matches) continue

                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    extractor.selectTrack(i)
                    tracks.add(Track(extractor, muxer.addTrack(format)))
                    selected = true
                    break
                }
                // The wanted track is missing or uses a codec the MP4 muxer cannot store.
                if (!selected) return false
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // Interleave samples by timestamp so the result streams and seeks properly.
            while (true) {
                val next = tracks
                    .filter { !it.finished }
                    .minByOrNull { it.extractor.sampleTime }
                    ?: break

                val size = next.extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    next.finished = true
                    continue
                }
                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.presentationTimeUs = next.extractor.sampleTime
                bufferInfo.flags =
                    if (next.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                muxer.writeSampleData(next.muxerTrack, buffer, bufferInfo)
                if (!next.extractor.advance()) next.finished = true
            }

            muxer.stop()
            success = outputFile.exists() && outputFile.length() > 0L
            return success
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            extractors.forEach { try { it.release() } catch (_: Exception) {} }
            try { muxer?.release() } catch (_: Exception) {}
            if (!success) {
                try { outputFile.delete() } catch (_: Exception) {}
            }
        }
    }
}
