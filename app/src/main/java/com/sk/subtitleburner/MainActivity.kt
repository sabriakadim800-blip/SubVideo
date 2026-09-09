package com.sk.subtitleburner

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var videoUri: Uri? = null
    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var metadata: VideoMetadata? = null
    private var lastOutput: File? = null
    private lateinit var videoInfo: TextView
    private lateinit var subtitleInfo: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var renderButton: Button
    private lateinit var fontSpinner: Spinner
    private lateinit var sizeSeek: SeekBar
    private lateinit var sizeLabel: TextView
    private lateinit var colorSpinner: Spinner
    private lateinit var watermarkCheck: CheckBox
    private lateinit var shareButton: Button

    private val fontNames = listOf(
        "Elegant English",
        "American Sans",
        "Cinematic Serif",
        "Movie Sans",
        "Classic Arabic"
    )
    private val colors = listOf("White", "Warm yellow", "Cyan", "Soft gray")
    private val colorValues = listOf(Color.WHITE, Color.rgb(255, 222, 126), Color.rgb(151, 232, 255), Color.rgb(224, 228, 233))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(248, 249, 251))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(34))
        }
        scroll.addView(content)

        val title = TextView(this).apply {
            text = "SUBTITLE BURNER"
            textSize = 12f
            letterSpacing = 0.18f
            setTextColor(Color.rgb(163, 63, 40))
        }
        content.addView(title)
        content.addView(TextView(this).apply {
            text = "Make every frame speak."
            textSize = 30f
            setTextColor(Color.rgb(24, 33, 43))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(5))
        })
        content.addView(TextView(this).apply {
            text = "Burn subtitles and a professional S.K watermark without stretching the original video."
            textSize = 15f
            setTextColor(Color.rgb(101, 114, 127))
            setPadding(0, 0, 0, dp(18))
        })

        val mediaCard = card()
        mediaCard.addView(sectionLabel("01  MEDIA"))
        val videoButton = actionButton("Choose video")
        mediaCard.addView(videoButton)
        videoInfo = infoText("No video selected")
        mediaCard.addView(videoInfo)
        val subtitleButton = actionButton("Import .srt subtitle")
        mediaCard.addView(subtitleButton)
        subtitleInfo = infoText("No subtitle selected")
        mediaCard.addView(subtitleInfo)
        videoButton.setOnClickListener { pickVideo() }
        subtitleButton.setOnClickListener { pickSubtitle() }
        content.addView(mediaCard)

        val styleCard = card()
        styleCard.addView(sectionLabel("02  CUSTOMIZE"))
        styleCard.addView(label("Font style"))
        fontSpinner = Spinner(this)
        fontSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fontNames)
        styleCard.addView(fontSpinner, lp())
        styleCard.addView(label("Font size"))
        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sizeSeek = SeekBar(this).apply { min = 16; max = 64; progress = 34 }
        sizeLabel = TextView(this).apply { text = "34 px"; textSize = 14f; setTextColor(Color.DKGRAY) }
        sizeRow.addView(sizeSeek, LinearLayout.LayoutParams(0, dp(44), 1f))
        sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(dp(58), -2))
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sizeLabel.text = "$progress px"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        styleCard.addView(sizeRow)
        styleCard.addView(label("Subtitle color"))
        colorSpinner = Spinner(this)
        colorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colors)
        styleCard.addView(colorSpinner, lp())
        styleCard.addView(TextView(this).apply {
            text = "Subtitles are rendered with a readable outline and safe lower-screen padding."
            textSize = 12f
            setTextColor(Color.rgb(101, 114, 127))
            setPadding(0, dp(6), 0, dp(8))
        })
        content.addView(styleCard)

        val watermarkCard = card()
        watermarkCard.addView(sectionLabel("03  WATERMARK"))
        watermarkCheck = CheckBox(this).apply {
            text = "Add  ترجمة فريق S.K"
            textSize = 16f
            isChecked = true
            setTextColor(Color.rgb(24, 33, 43))
        }
        watermarkCard.addView(watermarkCheck)
        watermarkCard.addView(TextView(this).apply {
            text = "Placed in the upper-right corner with a subtle translucent backing."
            textSize = 12f
            setTextColor(Color.rgb(101, 114, 127))
            setPadding(dp(48), 0, 0, dp(8))
        })
        content.addView(watermarkCard)

        val exportCard = card()
        exportCard.addView(sectionLabel("04  EXPORT"))
        renderButton = actionButton("Burn subtitles and export")
        renderButton.setTextColor(Color.WHITE)
        renderButton.setBackgroundColor(Color.rgb(216, 91, 56))
        renderButton.setOnClickListener { startRender() }
        exportCard.addView(renderButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        exportCard.addView(progress, lp())
        status = infoText("Ready when your video and subtitle are selected.")
        exportCard.addView(status)
        shareButton = actionButton("Share last export").apply {
            visibility = View.GONE
            setOnClickListener { shareOutput() }
        }
        exportCard.addView(shareButton)
        content.addView(exportCard)
        return scroll
    }

    private fun pickVideo() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_VIDEO)
    }

    private fun pickSubtitle() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "text/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_SUBTITLE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers grant a one-shot permission only.
        }
        when (requestCode) {
            REQUEST_VIDEO -> inspectVideo(uri)
            REQUEST_SUBTITLE -> loadSubtitle(uri)
        }
    }

    private fun inspectVideo(uri: Uri) {
        status.text = "Inspecting video dimensions and frame rate…"
        worker.execute {
            try {
                val result = VideoProbe.inspect(this, uri)
                videoUri = uri
                metadata = result
                mainHandler.post {
                    videoInfo.text = "${result.width} × ${result.height}  •  ${formatFps(result.fps)} fps  •  ${formatDuration(result.durationUs)}"
                    status.text = "Source inspected. The export will keep ${result.width} × ${result.height}."
                    updateButtonState()
                }
            } catch (error: Throwable) {
                mainHandler.post { status.text = "Could not inspect video: ${error.message ?: "unsupported file"}" }
            }
        }
    }

    private fun loadSubtitle(uri: Uri) {
        try {
            val cues = contentResolver.openInputStream(uri)?.use { SrtParser.parse(it) } ?: emptyList()
            check(cues.isNotEmpty()) { "No valid subtitle cues found." }
            subtitleCues = cues
            subtitleInfo.text = "${cues.size} cues imported  •  ${formatDuration(cues.last().endUs)}"
            status.text = "Subtitle timing and encoding loaded accurately."
            updateButtonState()
        } catch (error: Throwable) {
            subtitleCues = emptyList()
            subtitleInfo.text = "No subtitle selected"
            status.text = "Could not parse subtitle: ${error.message ?: "invalid SRT"}"
        }
    }

    private fun startRender() {
        val uri = videoUri ?: return
        val meta = metadata ?: return
        renderButton.isEnabled = false
        shareButton.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Preparing the surface encoder…"
        val output = File(getExternalFilesDir("Movies"), "subtitle_burned_${System.currentTimeMillis()}.mp4")
        lastOutput = output
        val options = BurnOptions(
            fontName = fontNames[fontSpinner.selectedItemPosition],
            fontSizePx = sizeSeek.progress,
            textColor = colorValues[colorSpinner.selectedItemPosition],
            includeWatermark = watermarkCheck.isChecked
        )
        worker.execute {
            try {
                VideoBurner(this, uri, meta, subtitleCues, options, output) { percent, message ->
                    mainHandler.post {
                        progress.progress = percent
                        status.text = message
                    }
                }.render()
                mainHandler.post {
                    progress.visibility = View.GONE
                    renderButton.isEnabled = true
                    shareButton.visibility = View.VISIBLE
                    status.text = "Export complete. Saved to the app's Movies folder."
                }
            } catch (error: Throwable) {
                output.delete()
                mainHandler.post {
                    progress.visibility = View.GONE
                    renderButton.isEnabled = true
                    status.text = "Export failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    private fun shareOutput() {
        val file = lastOutput ?: return
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share burned video"))
    }

    private fun updateButtonState() {
        renderButton.isEnabled = videoUri != null && metadata != null
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(16))
        setBackgroundColor(Color.WHITE)
        val params = LinearLayout.LayoutParams(-1, -2)
        params.setMargins(0, 0, 0, dp(13))
        layoutParams = params
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.12f
        setTextColor(Color.rgb(163, 63, 40))
        setPadding(0, 0, 0, dp(11))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(101, 114, 127))
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun actionButton(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setPadding(dp(12), dp(2), dp(12), dp(2))
        layoutParams = LinearLayout.LayoutParams(-1, dp(47)).apply { setMargins(0, 0, 0, dp(5)) }
    }

    private fun infoText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.rgb(101, 114, 127))
        setPadding(dp(4), 0, 0, dp(9))
    }

    private fun lp() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(3)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun formatFps(value: Double) = if (value == value.roundToInt().toDouble()) "${value.roundToInt()}" else String.format("%.3f", value)

    companion object {
        private const val REQUEST_VIDEO = 41
        private const val REQUEST_SUBTITLE = 42
    }
}
