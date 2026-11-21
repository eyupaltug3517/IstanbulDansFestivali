package com.nesemu.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Colors and small view helpers shared by every screen. */
object Theme {
    val BG = Color.rgb(13, 13, 20)
    val SURFACE = Color.rgb(26, 26, 38)
    val SURFACE_HI = Color.rgb(38, 38, 54)
    val TEXT = Color.rgb(240, 240, 248)
    val TEXT_DIM = Color.rgb(150, 150, 172)
    val ACCENT = Color.rgb(230, 57, 70)       // console red
    val ACCENT_2 = Color.rgb(255, 196, 0)     // coin gold
    val GOOD = Color.rgb(80, 200, 120)

    val TITLE_FONT: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun dp(context: Context, v: Float) = v * context.resources.displayMetrics.density
    fun dpi(context: Context, v: Float) = dp(context, v).toInt()

    fun rounded(color: Int, radius: Float, strokeColor: Int = 0, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    /** Rounded background with a touch ripple. */
    fun clickable(context: Context, color: Int, radiusDp: Float = 14f): Drawable {
        val r = dp(context, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), rounded(color, r), rounded(Color.WHITE, r))
    }

    /** Placeholder cover: a gradient picked from the title, used until a screenshot exists. */
    fun coverGradient(title: String, radius: Float): GradientDrawable {
        val hues = floatArrayOf(350f, 20f, 45f, 140f, 190f, 220f, 260f, 300f)
        val h = hues[Math.floorMod(title.hashCode(), hues.size)]
        val c1 = Color.HSVToColor(floatArrayOf(h, 0.75f, 0.55f))
        val c2 = Color.HSVToColor(floatArrayOf((h + 40) % 360, 0.85f, 0.25f))
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { cornerRadius = radius }
    }

    fun label(context: Context, text: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** A pill-shaped button. */
    fun pillButton(context: Context, text: String, color: Int = SURFACE_HI, textColor: Int = TEXT, onClick: (View) -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(textColor)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val h = dpi(context, 18f); val v = dpi(context, 12f)
            setPadding(h, v, h, v)
            background = clickable(context, color, 24f)
            setOnClickListener(onClick)
        }

    /** Removes "(U) [!]"-style dump tags and the extension from a ROM file name. */
    fun prettyTitle(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val cleaned = base.replace(Regex("\\s*[\\[(][^\\])]*[\\])]"), "").replace('_', ' ').trim()
        return cleaned.ifEmpty { base }
    }
}
