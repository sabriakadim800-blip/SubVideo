package com.sk.subtitleburner

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
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

    private lateinit var videoButton: Button
    private lateinit var subtitleButton: Button
    private lateinit var videoInfo: TextView
    private lateinit var subtitleInfo: TextView
    private lateinit var fontSpinner: Spinner
    private lateinit var sizeSeekBar: SeekBar
    private lateinit var sizeLabel: TextView
    private lateinit var colorSpinner: Spinner
    private lateinit var subtitlePreview: TextView
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

        fontSpinner.adapter = spinnerAdapter(fontNames)
        colorSpinner.adapter = spinnerAdapter(colorNames)
        fontSpinner.onItemSelectedListener = simpleItemSelected { updateSubtitlePreview() }
        colorSpinner.onItemSelectedListener = simpleItemSelected { updateSubtitlePreview() }
        sizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sizeLabel.text = "$progress بكسل"
                updateSubtitlePreview()
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
        subtitlePreview.typeface = FontCatalog.loadTypeface(assets, fontName)
        subtitlePreview.textSize = sizeSeekBar.progress.toFloat()
        subtitlePreview.setTextColor(colorValues.getOrElse(colorSpinner.selectedItemPosition) { Color.WHITE })
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
                        append("${inspected.width} × ${inspected.height}")
                        append("  •  ${formatFps(inspected.fps)} إطار/ث")
                        append("  •  ${formatDuration(inspected.durationUs)}")
                    }
                    statusText.text =
                        "تم فحص المصدر. سيحافظ التصدير على ${inspected.width} × ${inspected.height}."
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

        setExportRunning(true)
        statusText.text = "جارٍ تجهيز محرّك التصدير…"

        val moviesDirectory = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: filesDir
        val output = File(
            moviesDirectory,
            "subtitle_burned_${System.currentTimeMillis()}.mp4"
        )
        lastOutput = output
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

                mainHandler.post {
                    setExportRunning(false)
                    shareButton.visibility = View.VISIBLE
                    statusText.text = "اكتمل التصدير. تم حفظ الفيديو في مجلد الأفلام."
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
        if (!file.exists()) {
            statusText.text = "لم يعد التصدير الأخير متاحًا."
            shareButton.visibility = View.GONE
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "مشاركة الفيديو المحروق"
            )
        )
    }

    private fun formatFps(value: Double): String {
        return if (value == value.roundToInt().toDouble()) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.3f", value)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_VIDEO = 41
        private const val REQUEST_SUBTITLE = 42
    }
}