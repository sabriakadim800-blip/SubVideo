package com.sk.subtitleburner

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * XML-backed screen controller.
 *
 * The activity owns only UI state and file-picking. Heavy MediaExtractor,
 * subtitle parsing, and MediaCodec work stay off the main thread.
 */
class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var videoUri: Uri? = null
    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var metadata: VideoMetadata? = null
    private var lastOutput: File? = null
    private var lastOutputUri: Uri? = null
    private var pendingExportAfterPermission = false

    private lateinit var videoButton: Button
    private lateinit var subtitleButton: Button
    private lateinit var videoInfo: TextView
    private lateinit var subtitleInfo: TextView
    private lateinit var fontSpinner: Spinner
    private lateinit var sizeSeekBar: SeekBar
    private lateinit var sizeLabel: TextView
    private lateinit var colorSpinner: Spinner
    private lateinit var subtitlePreview: TextView
    private lateinit var videoPreview: VideoView
    private lateinit var previewEmptyText: TextView
    private lateinit var previewSubtitle: TextView
    private lateinit var previewWatermark: TextView
    private lateinit var watermarkCheck: CheckBox
    private lateinit var renderButton: Button
    private lateinit var exportProgress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var shareButton: Button

    private val fontNames = FontCatalog.names
    private val colorNames = listOf("أبيض", "أصفر دافئ", "سماوي", "رمادي هادئ")
    private val colorValues = listOf(
        Color.WHITE,
        Color.rgb(255, 222, 126),
        Color.rgb(151, 232, 255),
        Color.rgb(224, 228, 233)
    )
    private val previewHandler = Handler(Looper.getMainLooper())
    private val previewTicker = object : Runnable {
        override fun run() {
            updateVideoPreviewOverlay()
            previewHandler.postDelayed(this, 120L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        configureControls()
        updateRenderButton()
    }

    private fun bindViews() {
        videoButton = findViewById(R.id.video_button)
        subtitleButton = findViewById(R.id.subtitle_button)
        videoInfo = findViewById(R.id.video_info)
        subtitleInfo = findViewById(R.id.subtitle_info)
        fontSpinner = findViewById(R.id.font_spinner)
        sizeSeekBar = findViewById(R.id.size_seekbar)
        sizeLabel = findViewById(R.id.size_label)
        colorSpinner = findViewById(R.id.color_spinner)
        subtitlePreview = findViewById(R.id.subtitle_preview)
        videoPreview = findViewById(R.id.video_preview)
        previewEmptyText = findViewById(R.id.preview_empty_text)
        previewSubtitle = findViewById(R.id.preview_subtitle)
        previewWatermark = findViewById(R.id.preview_watermark)
        watermarkCheck = findViewById(R.id.watermark_check)
        renderButton = findViewById(R.id.render_button)
        exportProgress = findViewById(R.id.export_progress)
        statusText = findViewById(R.id.status_text)
        shareButton = findViewById(R.id.share_button)
    }

    private fun configureControls() {
        videoButton.setOnClickListener { openVideoPicker() }
        subtitleButton.setOnClickListener { openSubtitlePicker() }
        renderButton.setOnClickListener { startExport() }
        shareButton.setOnClickListener { shareLastExport() }
        previewSubtitle.maxWidth =
            (resources.displayMetrics.widthPixels * 0.88f).roundToInt()
        videoPreview.setMediaController(MediaController(this))
        videoPreview.setOnPreparedListener { player ->
            player.isLooping = false
            player.start()
            previewEmptyText.visibility = View.GONE
            updateVideoPreviewOverlay()
        }
        watermarkCheck.setOnCheckedChangeListener { _, _ -> updateVideoPreviewOverlay() }

        fontSpinner.adapter = spinnerAdapter(fontNames)
        colorSpinner.adapter = spinnerAdapter(colorNames)
        fontSpinner.onItemSelectedListener = simpleItemSelected {
            updateSubtitlePreview()
            updateVideoPreviewOverlay()
        }
        colorSpinner.onItemSelectedListener = simpleItemSelected {
            updateSubtitlePreview()
            updateVideoPreviewOverlay()
        }
        sizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sizeLabel.text = "$progress بكسل"
                updateSubtitlePreview()
                updateVideoPreviewOverlay()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateSubtitlePreview()
    }

    private fun simpleItemSelected(action: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = action()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

    private fun updateSubtitlePreview() {
        if (!::subtitlePreview.isInitialized) return
        val fontName = fontNames.getOrElse(fontSpinner.selectedItemPosition) { fontNames.first() }
        val selectedTypeface = FontCatalog.loadTypeface(assets, fontName)
        subtitlePreview.typeface = selectedTypeface
        previewSubtitle.typeface = selectedTypeface
        previewWatermark.typeface = FontCatalog.loadTypeface(assets, "المراي — Almarai")
        subtitlePreview.textSize = sizeSeekBar.progress.toFloat()
        previewSubtitle.textSize = (sizeSeekBar.progress * 0.72f).coerceAtLeast(14f)
        val selectedColor = colorValues.getOrElse(colorSpinner.selectedItemPosition) { Color.WHITE }
        subtitlePreview.setTextColor(selectedColor)
        previewSubtitle.setTextColor(selectedColor)
    }

    private fun updateVideoPreviewOverlay() {
        if (!::videoPreview.isInitialized) return
        previewWatermark.visibility =
            if (videoUri != null && watermarkCheck.isChecked) View.VISIBLE else View.GONE
        val currentPositionUs = try {
            videoPreview.currentPosition.toLong() * 1000L
        } catch (_: IllegalStateException) {
            0L
        }
        val cue = subtitleCues.captionAt(currentPositionUs)
        previewSubtitle.text = cue?.lines?.joinToString("\n").orEmpty()
        previewSubtitle.visibility = if (cue == null) View.GONE else View.VISIBLE
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun openVideoPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            },
            REQUEST_VIDEO
        )
    }

    private fun openSubtitlePicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/x-subrip",
                        "text/srt",
                        "text/plain",
                        "application/octet-stream"
                    )
                )
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            },
            REQUEST_SUBTITLE
        )
    }

    @Deprecated("Uses the platform document picker for API 26 compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        persistReadPermission(uri)
        when (requestCode) {
            REQUEST_VIDEO -> inspectVideo(uri)
            REQUEST_SUBTITLE -> importSubtitle(uri)
        }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // A few document providers expose only a one-session read grant.
        }
    }

    private fun inspectVideo(uri: Uri) {
        videoUri = null
        metadata = null
        videoInfo.text = "جارٍ فحص الأبعاد ومعدل الإطارات…"
        statusText.text = "جارٍ فحص الفيديو المحدد…"
        updateRenderButton()

        worker.execute {
            try {
                val inspected = VideoProbe.inspect(this, uri)
                videoUri = uri
                metadata = inspected
                mainHandler.post {
                    videoInfo.text = buildString {
                        append("${inspected.renderWidth} × ${inspected.renderHeight}")
                        append("  •  ${formatFps(inspected.fps)} إطار/ث")
                        append("  •  ${formatDuration(inspected.durationUs)}")
                    }
                    statusText.text =
                        "تم فحص المصدر. سيحافظ التصدير على ${inspected.renderWidth} × ${inspected.renderHeight} دون تدوير خاطئ."
                    videoPreview.setVideoURI(uri)
                    videoPreview.seekTo(1)
                    previewEmptyText.visibility = View.VISIBLE
                    updateVideoPreviewOverlay()
                    updateRenderButton()
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    videoInfo.text = "لم يتم اختيار فيديو"
                    statusText.text = "تعذر فحص الفيديو المحدد. تأكد من أن الملف صالح ومدعوم."
                    updateRenderButton()
                }
            }
        }
    }

    private fun importSubtitle(uri: Uri) {
        subtitleCues = emptyList()
        val displayName = queryDisplayName(uri)
        subtitleInfo.text = "جارٍ قراءة ${displayName ?: "ملف الترجمة"}…"
        statusText.text = "جارٍ تحليل ترميز وتوقيت ملف SRT…"
        updateRenderButton()
        worker.execute {
            try {
                val cues = contentResolver.openInputStream(uri)?.use { SrtParser.parse(it) }
                    ?: error("تعذر فتح الملف من موفر المستندات")
                check(cues.isNotEmpty()) { "لم يتم العثور على أسطر ترجمة صالحة" }
                subtitleCues = cues
                mainHandler.post {
                    subtitleInfo.text =
                        "${displayName ?: "ملف SRT"}  •  ${cues.size} سطر  •  النهاية ${formatDuration(cues.last().endUs)}"
                    statusText.text = "تم تحميل ملف الترجمة وتوقيته بنجاح."
                    updateRenderButton()
                }
            } catch (error: Throwable) {
                subtitleCues = emptyList()
                mainHandler.post {
                    subtitleInfo.text = "لم يتم اختيار ملف ترجمة"
                    statusText.text = "تعذر قراءة ملف SRT. تأكد من احتوائه على توقيتات صحيحة."
                    updateRenderButton()
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun updateRenderButton() {
        renderButton.isEnabled = videoUri != null &&
            metadata != null &&
            subtitleCues.isNotEmpty()
    }

    private fun startExport() {
        val sourceUri = videoUri ?: return
        val sourceMetadata = metadata ?: return
        if (subtitleCues.isEmpty()) return
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExportAfterPermission = true
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERMISSION
            )
            statusText.text = "اسمح للتطبيق بحفظ الفيديو في المعرض."
            return
        }

        setExportRunning(true)
        statusText.text = "جارٍ تجهيز محرّك التصدير…"

        val moviesDirectory = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: filesDir
        val output = File(
            moviesDirectory,
            "subtitle_burned_${System.currentTimeMillis()}.mp4"
        )
        lastOutput = output
        lastOutputUri = null
        val options = BurnOptions(
            fontName = fontNames[fontSpinner.selectedItemPosition],
            fontSizePx = sizeSeekBar.progress,
            textColor = colorValues[colorSpinner.selectedItemPosition],
            includeWatermark = watermarkCheck.isChecked
        )

        worker.execute {
            try {
                VideoBurner(
                    context = this,
                    inputUri = sourceUri,
                    metadata = sourceMetadata,
                    cues = subtitleCues,
                    options = options,
                    output = output
                ) { percent, message ->
                    mainHandler.post {
                        exportProgress.progress = percent
                        statusText.text = message
                    }
                }.render()
                val galleryUri = publishVideoToGallery(output)
                lastOutputUri = galleryUri
                output.delete()

                mainHandler.post {
                    setExportRunning(false)
                    shareButton.visibility = View.VISIBLE
                    statusText.text = "اكتمل التصدير. تم حفظ الفيديو فعليًا في المعرض داخل مجلد الأفلام."
                }
            } catch (error: Throwable) {
                output.delete()
                mainHandler.post {
                    setExportRunning(false)
                    statusText.text = "فشل التصدير. تأكد من دعم الجهاز لترميز الفيديو ثم حاول مرة أخرى."
                }
            }
        }
    }

    private fun setExportRunning(running: Boolean) {
        videoButton.isEnabled = !running
        subtitleButton.isEnabled = !running
        fontSpinner.isEnabled = !running
        sizeSeekBar.isEnabled = !running
        colorSpinner.isEnabled = !running
        watermarkCheck.isEnabled = !running
        renderButton.isEnabled = !running && videoUri != null &&
            metadata != null && subtitleCues.isNotEmpty()
        exportProgress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) shareButton.visibility = View.GONE
    }

    private fun shareLastExport() {
        val file = lastOutput ?: return
        val savedUri = lastOutputUri
        if (savedUri == null && !file.exists()) {
            statusText.text = "لم يعد التصدير الأخير متاحًا."
            shareButton.visibility = View.GONE
            return
        }
        val uri = savedUri ?: FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("video", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "مشاركة الفيديو المحروق"
            )
        )
    }

    private fun publishVideoToGallery(source: File): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/حارق الترجمة"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val galleryUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("تعذر إنشاء ملف الفيديو في المعرض")
            try {
                contentResolver.openOutputStream(galleryUri)?.use { output ->
                    FileInputStream(source).use { input -> input.copyTo(output) }
                } ?: error("تعذر نسخ الفيديو إلى المعرض")
                contentResolver.update(
                    galleryUri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null
                )
                return galleryUri
            } catch (error: Throwable) {
                contentResolver.delete(galleryUri, null, null)
                throw error
            }
        }

        val publicMovies = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES
        )
        val outputDirectory = File(publicMovies, "حارق الترجمة").apply { mkdirs() }
        val publicFile = File(outputDirectory, source.name)
        source.inputStream().use { input ->
            publicFile.outputStream().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(
            this,
            arrayOf(publicFile.absolutePath),
            arrayOf("video/mp4"),
            null
        )
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", publicFile)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && pendingExportAfterPermission) {
            pendingExportAfterPermission = false
            startExport()
        } else {
            pendingExportAfterPermission = false
            statusText.text = "لم يتم منح إذن الحفظ في المعرض."
        }
    }

    private fun formatFps(value: Double): String {
        return if (value == value.roundToInt().toDouble()) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.3f", value)
        }
    }

    override fun onDestroy() {
        previewHandler.removeCallbacks(previewTicker)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        previewHandler.post(previewTicker)
    }

    override fun onPause() {
        previewHandler.removeCallbacks(previewTicker)
        super.onPause()
    }

    companion object {
        private const val REQUEST_VIDEO = 41
        private const val REQUEST_SUBTITLE = 42
        private const val REQUEST_STORAGE_PERMISSION = 43
    }
}