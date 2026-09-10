package com.sk.subtitleburner

import android.content.res.AssetManager
import android.graphics.*
import android.opengl.*
import android.view.Surface
import android.graphics.SurfaceTexture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A small GLES compositor:
 * decoder Surface -> external OES texture -> encoder input Surface
 * with a transparent Canvas bitmap drawn as a second alpha texture.
 */
class FrameComposer(
    private val width: Int,
    private val height: Int,
    encoderSurface: Surface,
    private val options: BurnOptions,
    assets: AssetManager,
    private val rotationDegrees: Int
) {
    private val eglDisplay: EGLDisplay
    private val eglContext: EGLContext
    private val eglSurface: EGLSurface
    private val textureId: Int
    private val overlayTextureId: Int
    private val surfaceTexture: SurfaceTexture
    val decoderSurface: Surface
    private var frameLatch = CountDownLatch(0)
    private var frameAvailable = false
    private val videoProgram: Int
    private val overlayProgram: Int
    private val subtitleTypeface = FontCatalog.loadTypeface(assets, options.fontName)
    private val watermarkTypeface = FontCatalog.loadTypeface(assets, "المراي — Almarai")
    private var released = false

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display." }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "Unable to initialize EGL." }
        val config = chooseConfig(eglDisplay)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context." }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create encoder EGL surface." }
        makeCurrent()

        textureId = createExternalTexture()
        overlayTextureId = createTexture2d()
        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(this) {
                frameAvailable = true
                frameLatch.countDown()
            }
        }
        decoderSurface = Surface(surfaceTexture)
        videoProgram = createProgram(VIDEO_VERTEX, VIDEO_FRAGMENT)
        overlayProgram = createProgram(OVERLAY_VERTEX, OVERLAY_FRAGMENT)
    }

    fun awaitFrame() {
        val readyImmediately = synchronized(this) {
            if (frameAvailable) {
                frameAvailable = false
                true
            } else {
                frameLatch = CountDownLatch(1)
                false
            }
        }
        if (!readyImmediately) frameLatch.await(2, TimeUnit.SECONDS)
    }

    fun draw(timestampUs: Long, cue: SubtitleCue?) {
        makeCurrent()
        surfaceTexture.updateTexImage()
        val transform = FloatArray(16)
        surfaceTexture.getTransformMatrix(transform)
        val rotationTransform = FloatArray(16)
        val combinedTransform = FloatArray(16)
        android.opengl.Matrix.setIdentityM(rotationTransform, 0)
        android.opengl.Matrix.translateM(rotationTransform, 0, 0.5f, 0.5f, 0f)
        android.opengl.Matrix.rotateM(
            rotationTransform,
            0,
            rotationDegrees.toFloat(),
            0f,
            0f,
            1f
        )
        android.opengl.Matrix.translateM(rotationTransform, 0, -0.5f, -0.5f, 0f)
        android.opengl.Matrix.multiplyMM(combinedTransform, 0, transform, 0, rotationTransform, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawVideo(combinedTransform)
        uploadOverlay(makeOverlay(cue))
        drawOverlay()
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampUs * 1000L)
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "Could not submit rendered frame." }
    }

    fun release() {
        if (released) return
        released = true
        decoderSurface.release()
        surfaceTexture.release()
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
    }

    private fun drawVideo(transform: FloatArray) {
        GLES20.glUseProgram(videoProgram)
        val position = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
        val matrix = GLES20.glGetUniformLocation(videoProgram, "uTexMatrix")
        GLES20.glUniformMatrix4fv(matrix, 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        drawQuad(position, texCoord, VIDEO_TEXTURE_COORDS)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun drawOverlay() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(overlayProgram)
        val position = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        drawQuad(position, texCoord, OVERLAY_TEXTURE_COORDS)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawQuad(position: Int, texCoord: Int, coords: FloatArray) {
        val positions = java.nio.ByteBuffer.allocateDirect(QUAD_POSITIONS.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        positions.put(QUAD_POSITIONS).position(0)
        val textures = java.nio.ByteBuffer.allocateDirect(coords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        textures.put(coords).position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, textures)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun makeOverlay(cue: SubtitleCue?): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (cue != null) {
            val text = cue.lines.joinToString("\n")
            val safeWidth = (width * 0.88f).roundToInt().coerceAtLeast(2)
            val densityScale = width / 1280f
            val requestedSize = options.fontSizePx * densityScale
            val minimumSize = (width * 0.018f).coerceIn(18f, 32f)
            val maximumSize = (width * 0.085f).coerceAtLeast(minimumSize)
            var textSize = requestedSize.coerceIn(minimumSize, maximumSize)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = subtitleTypeface
                textLocale = Locale("ar")
                this.textSize = textSize
                textAlign = Paint.Align.CENTER
                style = Paint.Style.FILL
                color = options.textColor
                setShadowLayer(3f * densityScale, 0f, 2f * densityScale, Color.BLACK)
            }
            var wrappedLines = wrapLines(text, fillPaint, safeWidth.toFloat())
            val maximumSubtitleHeight = (height * 0.34f).roundToInt()
            while (
                wrappedLines.size * fillPaint.textSize * 1.18f > maximumSubtitleHeight &&
                textSize > minimumSize
            ) {
                textSize = (textSize - 1f).coerceAtLeast(minimumSize)
                fillPaint.textSize = textSize
                wrappedLines = wrapLines(text, fillPaint, safeWidth.toFloat())
            }

            val strokePaint = Paint(fillPaint).apply {
                typeface = subtitleTypeface
                textLocale = Locale("ar")
                style = Paint.Style.STROKE
                strokeWidth = maxOf(2f, textSize * 0.08f)
                color = Color.argb(220, 0, 0, 0)
                clearShadowLayer()
            }
            val lineHeight = fillPaint.textSize * 1.18f
            val baselineStart = height - (height * 0.12f) -
                (wrappedLines.size - 1) * lineHeight
            wrappedLines.forEachIndexed { index, line ->
                val baseline = baselineStart + index * lineHeight
                canvas.drawText(line, width / 2f, baseline, strokePaint)
                canvas.drawText(line, width / 2f, baseline, fillPaint)
            }
        }
        if (options.includeWatermark) {
            val scale = width / 1280f
            val watermark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = watermarkTypeface
                isFakeBoldText = true
                textLocale = Locale("ar")
                textSize = 24f * scale
                textAlign = Paint.Align.RIGHT
                color = Color.WHITE
                setShadowLayer(3f * scale, 0f, 1f * scale, Color.BLACK)
            }
            val right = width - 34f * scale
            val top = 42f * scale
            val bounds = RectF(right - watermark.measureText("ترجمة فريق S.K") - 20f * scale, top - 28f * scale, right + 10f * scale, top + 12f * scale)
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
            canvas.drawRoundRect(bounds, 10f * scale, 10f * scale, background)
            canvas.drawText("ترجمة فريق S.K", right - 4f * scale, top, watermark)
        }
        return bitmap
    }

    private fun wrapLines(
        text: String,
        paint: Paint,
        maxWidth: Float
    ): List<String> {
        val result = ArrayList<String>()
        text.split('\n').forEach { paragraph ->
            val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return@forEach
            var current = ""
            words.forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    if (current.isNotEmpty()) result += current
                    if (paint.measureText(word) <= maxWidth) {
                        current = word
                    } else {
                        val pieces = splitLongWord(word, paint, maxWidth)
                        if (pieces.isNotEmpty()) {
                            result.addAll(pieces.dropLast(1))
                            current = pieces.last()
                        }
                    }
                }
            }
            if (current.isNotEmpty()) result += current
        }
        return result.ifEmpty { listOf(text) }
    }

    private fun splitLongWord(
        word: String,
        paint: Paint,
        maxWidth: Float
    ): List<String> {
        val pieces = ArrayList<String>()
        var remaining = word
        while (remaining.isNotEmpty()) {
            var length = 1
            while (
                length < remaining.length &&
                paint.measureText(remaining.substring(0, length + 1)) <= maxWidth
            ) {
                length++
            }
            pieces += remaining.substring(0, length)
            remaining = remaining.substring(length)
        }
        return pieces
    }

    private fun uploadOverlay(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        bitmap.recycle()
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "Unable to make EGL context current." }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] == 1) {
            "No compatible EGL configuration."
        }
        return configs[0]!!
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun createTexture2d(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        return textures[0]
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    companion object {
        private val QUAD_POSITIONS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        // SurfaceTexture already supplies the camera/decoder transform, including
        // the usual vertical correction. Flipping these coordinates as well
        // makes the decoded video appear upside down.
        private val VIDEO_TEXTURE_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        // Bitmap uploads have a top-left origin, so the overlay needs its own
        // vertical flip to remain upright over the video.
        private val OVERLAY_TEXTURE_COORDS = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        private const val VIDEO_VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        private const val VIDEO_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """
        private const val OVERLAY_VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord.xy; }
        """
        private const val OVERLAY_FRAGMENT = """
            precision mediump float;
            uniform sampler2D sTexture;
            varying vec2 vTexCoord;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """
    }
}