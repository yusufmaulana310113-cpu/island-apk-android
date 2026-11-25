package id.island.dynamic

import android.content.Context
import android.graphics.Color

/** Pengaturan persisten (SharedPreferences). Service memantau perubahan & menerapkannya langsung. */
data class Settings(
    var scalePct: Int = 100,          // 60..140
    var yOffset: Int = 0,             // dp, geser turun dari tepi atas
    var autoHide: Boolean = false,
    var hideDelay: Int = 4,           // detik
    var bg: Int = Color.BLACK,
    var coverRound: Boolean = false,
    var showWeather: Boolean = true,
    var notchMode: Boolean = false,   // true = tepi atas rata + "telinga" ala notch
    var showNotifs: Boolean = true,
    var enabled: Boolean = false,
) {
    val scale: Float get() = scalePct / 100f

    fun save(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("scale", scalePct).putInt("yoff", yOffset)
            .putBoolean("auto_hide", autoHide).putInt("hide_delay", hideDelay)
            .putInt("bg", bg).putBoolean("cover_round", coverRound)
            .putBoolean("weather", showWeather).putBoolean("notch", notchMode)
            .putBoolean("notifs", showNotifs).putBoolean("enabled", enabled)
            .apply()
    }

    companion object {
        const val PREFS = "dynamic_island"
        val BG_PRESETS = intArrayOf(
            Color.rgb(0, 0, 0), Color.rgb(30, 30, 36), Color.rgb(10, 24, 54),
            Color.rgb(38, 18, 60), Color.rgb(8, 42, 30), Color.rgb(60, 14, 22),
        )

        fun load(c: Context): Settings {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Settings(
                scalePct = p.getInt("scale", 100).coerceIn(60, 140),
                yOffset = p.getInt("yoff", 0).coerceIn(0, 80),
                autoHide = p.getBoolean("auto_hide", false),
                hideDelay = p.getInt("hide_delay", 4).coerceIn(2, 30),
                bg = p.getInt("bg", Color.BLACK) or (0xFF shl 24),
                coverRound = p.getBoolean("cover_round", false),
                showWeather = p.getBoolean("weather", true),
                notchMode = p.getBoolean("notch", false),
                showNotifs = p.getBoolean("notifs", true),
                enabled = p.getBoolean("enabled", false),
            )
        }
    }
}
