package com.nesemu.ui

import android.content.Context

enum class VideoFilter(val label: String) {
    SHARP("Keskin piksel"),
    CRT("CRT"),
    SMOOTH("Yumuşak")
}

/** User preferences, backed by SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var videoFilter: VideoFilter
        get() = VideoFilter.entries.getOrElse(prefs.getInt("filter", 0)) { VideoFilter.SHARP }
        set(v) = prefs.edit().putInt("filter", v.ordinal).apply()

    /** Controller opacity, 20..100 %. */
    var padOpacity: Int
        get() = prefs.getInt("padOpacity", 70)
        set(v) = prefs.edit().putInt("padOpacity", v).apply()

    /** Controller size, 70..130 %. */
    var padScale: Int
        get() = prefs.getInt("padScale", 100)
        set(v) = prefs.edit().putInt("padScale", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var autoResume: Boolean
        get() = prefs.getBoolean("autoResume", true)
        set(v) = prefs.edit().putBoolean("autoResume", v).apply()

    /** Fast-forward speed multiplier (2..4). */
    var fastSpeed: Int
        get() = prefs.getInt("fastSpeed", 3)
        set(v) = prefs.edit().putInt("fastSpeed", v).apply()
}
