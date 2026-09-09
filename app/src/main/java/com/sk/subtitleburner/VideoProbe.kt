package com.sk.subtitleburner

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.content.Context
import kotlin.math.abs

data class VideoMetadata(
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val durationUs: Long,
    val videoTrack: Int,
    val audioTrack: Int?
)

object VideoProbe {
    fun inspect(context: Context, uri: Uri): VideoMetadata {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var videoTrack = -1
            var audioTrack: Int? = null
            var videoFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack == -1) {
                    videoTrack = index
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrack == null) {
                    audioTrack = index
                }
            }
            check(videoTrack >= 0 && videoFormat != null) { "No video track was found." }
            val width = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat!!.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (videoFormat!!.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat!!.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val declaredFps = if (videoFormat!!.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFormat!!.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
            } else 0.0
            val measuredFps = if (declaredFps > 0.0) declaredFps else measureFps(extractor, videoTrack)
            return VideoMetadata(
                mime = videoFormat!!.getString(MediaFormat.KEY_MIME)!!,
                width = width,
                height = height,
                fps = measuredFps.coerceIn(1.0, 240.0),
                durationUs = durationUs,
                videoTrack = videoTrack,
                audioTrack = audioTrack
            )
        } finally {
            extractor.release()
        }
    }

    private fun measureFps(extractor: MediaExtractor, track: Int): Double {
        extractor.selectTrack(track)
        val timestamps = ArrayList<Long>(180)
        val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
        while (timestamps.size < 180) {
            val time = extractor.sampleTime
            if (time < 0) break
            timestamps += time
            extractor.advance()
            buffer.clear()
        }
        extractor.unselectTrack(track)
        if (timestamps.size < 3) return 30.0
        val deltas = timestamps.zipWithNext()
            .map { it.second - it.first }
            .filter { it in 1_000L..1_000_000L }
        if (deltas.isEmpty()) return 30.0
        val median = deltas.sorted()[deltas.size / 2]
        return 1_000_000.0 / median
    }
}
