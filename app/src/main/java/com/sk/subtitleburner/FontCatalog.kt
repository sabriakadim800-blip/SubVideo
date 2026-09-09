package com.sk.subtitleburner

import android.content.res.AssetManager
import android.graphics.Typeface

data class SubtitleFont(
    val label: String,
    val assetPath: String
)

object FontCatalog {
    val options = listOf(
        SubtitleFont("أميري — Amiri", "fonts/Amiri-Regular.ttf"),
        SubtitleFont("المراي — Almarai", "fonts/Almarai-Regular.ttf"),
        SubtitleFont("نوتو كوفي عربي — Noto Kufi Arabic", "fonts/NotoKufiArabic-Regular.ttf")
    )

    val names: List<String> = options.map { it.label }

    fun loadTypeface(assets: AssetManager, label: String): Typeface {
        val font = options.firstOrNull { it.label == label } ?: options.first()
        return Typeface.createFromAsset(assets, font.assetPath)
    }
}