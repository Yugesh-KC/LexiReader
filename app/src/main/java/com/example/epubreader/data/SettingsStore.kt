package com.example.epubreader.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

enum class ReaderTheme(val label: String, val bg: Int, val fg: Int, val muted: Int, val link: Int, val dark: Boolean) {
    Light("Light", 0xFFFFFFFF.toInt(), 0xFF1F1F1F.toInt(), 0xFF757575.toInt(), 0xFF2F63C9.toInt(), false),
    Sepia("Sepia", 0xFFF5ECD9.toInt(), 0xFF4A3B2A.toInt(), 0xFF8A7760.toInt(), 0xFF8A4B14.toInt(), false),
    Dusk("Dusk", 0xFF2B2A33.toInt(), 0xFFDCDAE3.toInt(), 0xFF9A98A6.toInt(), 0xFF9DB7FF.toInt(), true),
    Dark("Dark", 0xFF1A1A1A.toInt(), 0xFFD4D4D4.toInt(), 0xFF8C8C8C.toInt(), 0xFF8AB4F8.toInt(), true),
    Black("Black", 0xFF000000.toInt(), 0xFFBDBDBD.toInt(), 0xFF7A7A7A.toInt(), 0xFF8AB4F8.toInt(), true),
}

/** App-wide light/dark mode for the library and menus (the reading page has its own [ReaderTheme]). */
enum class AppThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

enum class ReaderFont(val label: String, val css: String) {
    Serif("Serif", "Georgia, 'Noto Serif', 'Droid Serif', serif"),
    Sans("Sans", "'Roboto', 'Noto Sans', sans-serif"),
    Literata("Book", "'Literata', 'Bookerly', 'Noto Serif', Georgia, serif"),
    Mono("Mono", "'Roboto Mono', 'Droid Sans Mono', monospace"),
}

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.Light,
    val font: ReaderFont = ReaderFont.Serif,
    val fontSize: Int = 19,
    val lineHeight: Float = 1.6f,
    val margin: Int = 22,
    val justify: Boolean = false,
    val onlineDictionary: Boolean = true,
    val volumeKeys: Boolean = true,
    val tapZones: Boolean = true,
    val keepScreenOn: Boolean = true,
    val pdfZoom: Float = 1f,
    val lastColor: String = "yellow",
    /** true = swipe through screen-sized pages; false = continuous vertical scrolling. */
    val paged: Boolean = true,
    /** true = double tap a word for its meaning (single tap turns pages / opens highlights). */
    val doubleTapDefine: Boolean = true,
    val appTheme: AppThemeMode = AppThemeMode.System,
) {
    fun toJs(): String = JSONObject().apply {
        put("fontSize", fontSize); put("lineHeight", lineHeight.toDouble()); put("margin", margin)
        put("fontFamily", font.css); put("justify", justify); put("dark", theme.dark)
        put("bg", Palette.cssColor(theme.bg)); put("fg", Palette.cssColor(theme.fg))
        put("muted", Palette.cssColor(theme.muted)); put("link", Palette.cssColor(theme.link))
        put("rule", if (theme.dark) "rgba(255,255,255,.14)" else "rgba(0,0,0,.12)")
        put("pdfZoom", pdfZoom.toDouble())
        put("paged", paged)
        put("doubleTap", doubleTapDefine)
    }.toString()
}

class SettingsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    var value by mutableStateOf(load())
        private set

    private fun load(): ReaderSettings {
        val d = ReaderSettings()
        return ReaderSettings(
            theme = runCatching { ReaderTheme.valueOf(prefs.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            font = runCatching { ReaderFont.valueOf(prefs.getString("font", d.font.name)!!) }.getOrDefault(d.font),
            fontSize = prefs.getInt("fontSize", d.fontSize),
            lineHeight = prefs.getFloat("lineHeight", d.lineHeight),
            margin = prefs.getInt("margin", d.margin),
            justify = prefs.getBoolean("justify", d.justify),
            onlineDictionary = prefs.getBoolean("onlineDictionary", d.onlineDictionary),
            volumeKeys = prefs.getBoolean("volumeKeys", d.volumeKeys),
            tapZones = prefs.getBoolean("tapZones", d.tapZones),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            pdfZoom = prefs.getFloat("pdfZoom", d.pdfZoom),
            lastColor = prefs.getString("lastColor", d.lastColor) ?: d.lastColor,
            paged = prefs.getBoolean("paged", d.paged),
            doubleTapDefine = prefs.getBoolean("doubleTapDefine", d.doubleTapDefine),
            appTheme = runCatching { AppThemeMode.valueOf(prefs.getString("appTheme", d.appTheme.name)!!) }.getOrDefault(d.appTheme),
        )
    }

    fun update(f: (ReaderSettings) -> ReaderSettings) {
        val s = f(value)
        value = s
        prefs.edit()
            .putString("theme", s.theme.name).putString("font", s.font.name).putInt("fontSize", s.fontSize)
            .putFloat("lineHeight", s.lineHeight).putInt("margin", s.margin).putBoolean("justify", s.justify)
            .putBoolean("onlineDictionary", s.onlineDictionary).putBoolean("volumeKeys", s.volumeKeys)
            .putBoolean("tapZones", s.tapZones).putBoolean("keepScreenOn", s.keepScreenOn)
            .putFloat("pdfZoom", s.pdfZoom).putString("lastColor", s.lastColor).putBoolean("paged", s.paged)
            .putBoolean("doubleTapDefine", s.doubleTapDefine).putString("appTheme", s.appTheme.name)
            .apply()
    }
}
