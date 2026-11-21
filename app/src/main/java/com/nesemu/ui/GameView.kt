package com.nesemu.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Draws emulator frames. [drawFrame] is called from the emulation thread. */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val bitmap = Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888)
    private val paint = Paint()
    private val scanlinePaint = Paint()
    private val bgPaint = Paint()
    private val lock = Any()
    private var ready = false
    private var surfW = 0
    private var surfH = 0
    private var dest = RectF()

    /** Darkens the lower part of each of the 240 lines for a CRT look; sized to the screen. */
    private var scanlines: Bitmap? = null

    private fun buildScanlines(h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(1, maxOf(1, h), Bitmap.Config.ARGB_8888)
        for (y in 0 until bmp.height) {
            val pos = (y + 0.5f) * 240f / bmp.height
            val frac = pos - pos.toInt() // 0..1 within one NES line
            bmp.setPixel(0, y, if (frac > 0.6f) Color.argb(110, 0, 0, 0) else Color.TRANSPARENT)
        }
        return bmp
    }

    @Volatile var filter = VideoFilter.SHARP

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) {
            surfW = width; surfH = height
            dest = ScreenLayout.gameRect(width, height, resources.displayMetrics.density)
            scanlines = buildScanlines(dest.height().toInt())
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.rgb(14, 14, 22), Color.rgb(24, 22, 36)), null, Shader.TileMode.CLAMP
            )
            ready = true
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) { ready = false }
    }

    fun drawFrame(pixels: IntArray) {
        synchronized(lock) {
            if (!ready) return
            bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 240)
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                render(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun render(canvas: Canvas) {
        canvas.drawRect(0f, 0f, surfW.toFloat(), surfH.toFloat(), bgPaint)
        paint.isFilterBitmap = filter == VideoFilter.SMOOTH
        canvas.drawBitmap(bitmap, null, dest, paint)
        val lines = scanlines
        if (filter == VideoFilter.CRT && lines != null) {
            canvas.drawBitmap(lines, null, dest, scanlinePaint)
        }
    }
}
