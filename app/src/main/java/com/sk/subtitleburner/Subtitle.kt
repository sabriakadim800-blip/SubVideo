package com.sk.subtitleburner

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.Locale
import java.util.regex.Pattern

data class SubtitleCue(
    val startUs: Long,
    val endUs: Long,
    val lines: List<String>
)

object SrtParser {
    private val timePattern = Pattern.compile(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )

    fun parse(input: InputStream): List<SubtitleCue> {
        val bytes = input.readBytes()
        val text = decode(bytes).removePrefix("\uFEFF")
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("""\n[ \t]*\n"""))
        return blocks.mapNotNull { parseBlock(it) }
            .sortedBy { it.startUs }
    }

    private fun parseBlock(block: String): SubtitleCue? {
        val lines = block.lines().map { it.trimEnd() }
        val timingIndex = lines.indexOfFirst { timePattern.matcher(it).find() }
        if (timingIndex == -1) return null
        val matcher = timePattern.matcher(lines[timingIndex])
        if (!matcher.find()) return null
        val start = timestampToUs(matcher, 1)
        val end = timestampToUs(matcher, 5)
        if (end <= start) return null
        val caption = lines.drop(timingIndex + 1)
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .filter { it.isNotBlank() }
        if (caption.isEmpty()) return null
        return SubtitleCue(start, end, caption)
    }

    private fun timestampToUs(m: java.util.regex.Matcher, offset: Int): Long {
        val hours = m.group(offset).toLong()
        val minutes = m.group(offset + 1).toLong()
        val seconds = m.group(offset + 2).toLong()
        val rawMillis = m.group(offset + 3)
        val millis = when (rawMillis.length) {
            1 -> rawMillis.toLong() * 100
            2 -> rawMillis.toLong() * 10
            else -> rawMillis.take(3).toLong()
        }
        return ((hours * 3600 + minutes * 60 + seconds) * 1000 + millis) * 1000
    }

    private fun decode(bytes: ByteArray): String {
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
            else -> String(bytes, Charsets.UTF_8)
        }
    }
}

fun List<SubtitleCue>.captionAt(timeUs: Long): SubtitleCue? {
    return firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }
}

fun formatDuration(us: Long): String {
    val totalSeconds = (us / 1_000_000L).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}