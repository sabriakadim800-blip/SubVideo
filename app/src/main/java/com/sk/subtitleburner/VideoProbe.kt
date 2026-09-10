package com.sk.subtitleburner

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.content.Context
import kotlin.math.roundToInt

data class VideoMetadata(
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val durationUs: Long,
    val videoTrack: Int,
    val audioTrack: Int?,
    val rotation: Int,
    val pixelAspectRatio: Double
) {
    /**
     * Dimensions used by the encoder after applying container rotation and
     * non-square pixel aspect ratio metadata.
     */
    val renderWidth: Int
        get() {
            val orientedWidth = if (rotation == 90 || rotation == 270) height else width
            val orientedHeight = if (rotation == 90 || rotation == 270) width else height
            return evenDimension(orientedWidth * pixelAspectRatio, orientedHeight)
        }

    val renderHeight: Int
        get() {
            val orientedHeight = if (rotation == 90 || rotation == 270) width else height
            return evenDimension(orientedHeight.toDouble(), orientedHeight)
        }

    private fun evenDimension(value: Double, reference: Int): Int {
        val rounded = value.roundToInt().coerceAtLeast(2)
        val even = if (rounded % 2 == 0) rounded else rounded + 1
        return even.coerceAtMost(reference * 4)
    }
}

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
            check(videoTrack >= 0 && videoFormat != null) { "لم يتم العثور على مسار فيديو." }
            val width = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat!!.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (videoFormat!!.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat!!.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val declaredFps = if (videoFormat!!.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFormat!!.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
            } else 0.0
            val rotation = if (videoFormat!!.containsKey(MediaFormat.KEY_ROTATION)) {
                ((videoFormat!!.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360
            } else 0
            val pixelAspectRatio = if (
                videoFormat!!.containsKey(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH) &&
                videoFormat!!.containsKey(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT)
            ) {
                val aspectWidth = videoFormat!!.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH)
                val aspectHeight = videoFormat!!.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT)
                if (aspectWidth > 0 && aspectHeight > 0) {
                    aspectWidth.toDouble() / aspectHeight.toDouble()
                } else {
                    1.0
                }
            } else {
                1.0
            }
            val measuredFps = if (declaredFps > 0.0) declaredFps else measureFps(extractor, videoTrack)
            return VideoMetadata(
                mime = videoFormat!!.getString(MediaFormat.KEY_MIME)!!,
                width = width,
                height = height,
                fps = measuredFps.coerceIn(1.0, 240.0),
                durationUs = durationUs,
                videoTrack = videoTrack,
                audioTrack = audioTrack,
                rotation = rotation,
                pixelAspectRatio = pixelAspectRatio
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