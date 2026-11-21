package com.nesemu.ui

import android.graphics.RectF

/** Shared geometry so the game picture and the touch controls never overlap. */
object ScreenLayout {
    /** Height reserved for the menu button in portrait mode. */
    fun topBar(density: Float) = 52f * density

    fun isPortrait(w: Int, h: Int) = h > w

    fun gameRect(w: Int, h: Int, density: Float): RectF {
        return if (isPortrait(w, h)) {
            val top = topBar(density)
            val gh = w * 240f / 256f
            RectF(0f, top, w.toFloat(), top + gh)
        } else {
            val gw = h * 256f / 240f
            val left = (w - gw) / 2f
            RectF(left, 0f, left + gw, h.toFloat())
        }
    }
}
