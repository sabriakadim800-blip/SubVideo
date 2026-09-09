package com.sk.subtitleburner

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

data class BurnOptions(
    val fontName: String,
    val fontSizePx: Int,
    val textColor: Int,
    val includeWatermark: Boolean
)

class VideoBurner(
    private val context: Context,
    private val inputUri: Uri,
    private val metadata: VideoMetadata,
    private val cues: List<SubtitleCue>,
    private val options: BurnOptions,
    private val output: File,
    private val progress: (Int, String) -> Unit
) {
    fun render() {
        require(Build.VERSION.SDK_INT >= 26) { "Android 8.0 or newer is required." }
        output.parentFile?.mkdirs()
        val extractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var composer: FrameComposer? = null
        try {
            extractor.setDataSource(context, inputUri, null)
            audioExtractor.setDataSource(context, inputUri, null)
            extractor.selectTrack(metadata.videoTrack)
            val videoFormat = extractor.getTrackFormat(metadata.videoTrack)
            val audioFormat = metadata.audioTrack?.let { audioExtractor.getTrackFormat(it) }
            metadata.audioTrack?.let { audioExtractor.selectTrack(it) }

            val encoderFormat = MediaFormat.createVideoFormat("video/avc", metadata.width, metadata.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, chooseBitrate())
                setInteger(MediaFormat.KEY_FRAME_RATE, metadata.fps.roundToInt().coerceIn(1, 240))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val encoderInstance = MediaCodec.createEncoderByType("video/avc")
            encoder = encoderInstance
            encoderInstance.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoderInstance.createInputSurface()
            encoderInstance.start()
            val composerInstance = FrameComposer(metadata.width, metadata.height, inputSurface, options)
            composer = composerInstance
            val decoderSurface = composerInstance.decoderSurface
            val decoderInstance = MediaCodec.createDecoderByType(metadata.mime)
            decoder = decoderInstance
            decoderInstance.configure(videoFormat, decoderSurface, null, 0)
            decoderInstance.start()

            val muxerInstance = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = muxerInstance
            val audioTrack = audioFormat?.let { muxerInstance.addTrack(it) }
            var videoTrack = -1
            var muxerStarted = false
            var inputDone = false
            var outputDone = false
            var lastPercent = -1
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoderInstance.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoderInstance.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoderInstance.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoderInstance.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoderInstance.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val timestampUs = bufferInfo.presentationTimeUs.coerceAtLeast(0)
                        decoderInstance.releaseOutputBuffer(outputIndex, true)
                        if (!endOfStream) {
                            composerInstance.awaitFrame()
                            composerInstance.draw(timestampUs, cues.captionAt(timestampUs))
                            if (muxerStarted) {
                                val percent = if (metadata.durationUs > 0) {
                                    min(99, (timestampUs * 100 / metadata.durationUs).toInt())
                                } else 0
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    progress(percent, "Rendering frame $percent%…")
                                }
                            }
                        } else {
                            outputDone = true
                        }
                    }
                }

                while (true) {
                    val encodedIndex = encoderInstance.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        encodedIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        encodedIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder changed format twice." }
                            videoTrack = muxerInstance.addTrack(encoderInstance.outputFormat)
                            muxerInstance.start()
                            muxerStarted = true
                            progress(1, "Video encoder ready…")
                        }
                        encodedIndex >= 0 -> {
                            val encoded = encoderInstance.getOutputBuffer(encodedIndex)
                            if (encoded != null && bufferInfo.size > 0 && muxerStarted &&
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxerInstance.writeSampleData(videoTrack, encoded, bufferInfo)
                            }
                            encoderInstance.releaseOutputBuffer(encodedIndex, false)
                        }
                    }
                }
            }

            // Signal the encoder after the decoder reaches EOS, then drain its tail.
            // The input surface is persistent, so EOS must be sent explicitly
            // after the decoder has delivered its final frame.
            encoderInstance.signalEndOfInputStream()
            var encoderDone = false
            while (!encoderDone) {
                when (val encodedIndex = encoderInstance.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            videoTrack = muxerInstance.addTrack(encoderInstance.outputFormat)
                            muxerInstance.start()
                            muxerStarted = true
                        }
                    }
                    else -> if (encodedIndex >= 0) {
                        val encoded = encoderInstance.getOutputBuffer(encodedIndex)
                        if (encoded != null && bufferInfo.size > 0 && muxerStarted &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxerInstance.writeSampleData(videoTrack, encoded, bufferInfo)
                        }
                        encoderDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoderInstance.releaseOutputBuffer(encodedIndex, false)
                    }
                }
            }

            // Copy compressed audio packets without decoding or changing their timestamps.
            // Each track remains monotonic, and MediaMuxer keeps the original audio codec.
            if (muxerStarted && audioTrack != null) {
                val sourceAudioFormat = audioFormat ?: error("Audio format disappeared.")
                val audioInfo = MediaCodec.BufferInfo()
                val audioCapacity = if (sourceAudioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    sourceAudioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024)
                } else {
                    1024 * 1024
                }
                val audioBuffer = ByteBuffer.allocate(audioCapacity)
                while (true) {
                    audioBuffer.clear()
                    val size = audioExtractor.readSampleData(audioBuffer, 0)
                    if (size < 0) break
                    audioInfo.offset = 0
                    audioInfo.size = size
                    audioInfo.presentationTimeUs = audioExtractor.sampleTime
                    audioInfo.flags = audioExtractor.sampleFlags
                    muxerInstance.writeSampleData(audioTrack, audioBuffer, audioInfo)
                    audioExtractor.advance()
                }
            }
            if (muxerStarted) progress(100, "Export complete.")
        } finally {
            composer?.release()
            decoder?.stopSafely()
            encoder?.stopSafely()
            if (muxer != null) {
                try { muxer.stop() } catch (_: IllegalStateException) { }
                muxer.release()
            }
            extractor.release()
            audioExtractor.release()
        }
    }

    private fun chooseBitrate(): Int {
        val pixelsPerSecond = metadata.width.toLong() * metadata.height.toLong() * metadata.fps
        return pixelsPerSecond
            .times(0.10)
            .roundToInt()
            .coerceIn(1_500_000, 20_000_000)
    }

    private fun MediaCodec.stopSafely() {
        try { stop() } catch (_: IllegalStateException) { }
        release()
    }
}
