package com.nesemu.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Draws emulator frames. [drawFrame] is called from the emulation thread. */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val bitmap = Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val lock = Any()
    private var ready = false
    private var dest = RectF()

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) {
            dest = ScreenLayout.gameRect(width, height, resources.displayMetrics.density)
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
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, null, dest, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
