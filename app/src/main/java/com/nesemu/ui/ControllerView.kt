package com.nesemu.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.nesemu.core.Buttons
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** On-screen NES pad: 8-way D-pad, A, B, Select and Start with multi-touch. */
class ControllerView(context: Context, private val onChange: (Int) -> Unit) : View(context) {
    private val density = resources.displayMetrics.density

    private var dpadX = 0f; private var dpadY = 0f; private var dpadR = 0f
    private var aX = 0f; private var aY = 0f
    private var bX = 0f; private var bY = 0f
    private var btnR = 0f
    private val selectRect = RectF()
    private val startRect = RectF()

    private var state = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 220, 40, 50) }
    private val redPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 70, 80) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.argb(120, 255, 255, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val game = ScreenLayout.gameRect(w, h, density)
        if (ScreenLayout.isPortrait(w, h)) {
            val areaTop = game.bottom
            val area = h - areaTop
            dpadR = min(w * 0.2f, area * 0.27f)
            dpadX = w * 0.06f + dpadR
            dpadY = areaTop + area * 0.42f
            btnR = dpadR * 0.45f
            aX = w - w * 0.06f - btnR
            aY = dpadY - btnR * 0.7f
            bX = aX - btnR * 2.5f
            bY = dpadY + btnR * 0.7f
            val pillW = 64f * density; val pillH = 28f * density
            val py = h - area * 0.13f
            selectRect.set(w / 2f - pillW * 1.2f, py - pillH / 2, w / 2f - pillW * 0.2f, py + pillH / 2)
            startRect.set(w / 2f + pillW * 0.2f, py - pillH / 2, w / 2f + pillW * 1.2f, py + pillH / 2)
        } else {
            val side = game.left
            dpadR = min(side * 0.36f, h * 0.2f).coerceAtLeast(48f * density)
            dpadX = maxOf(side / 2f, dpadR + 16f * density)
            dpadY = h * 0.58f
            btnR = dpadR * 0.45f
            val cx = w - dpadX
            aX = cx + btnR * 1.25f
            aY = dpadY - btnR * 0.7f
            bX = cx - btnR * 1.25f
            bY = dpadY + btnR * 0.7f
            val pillW = min(64f * density, side * 0.4f).coerceAtLeast(44f * density)
            val pillH = 26f * density
            val py = h - pillH
            selectRect.set(dpadX - pillW / 2, py - pillH / 2, dpadX + pillW / 2, py + pillH / 2)
            startRect.set(cx - pillW / 2, py - pillH / 2, cx + pillW / 2, py + pillH / 2)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // D-pad: a cross made of two rounded bars.
        val arm = dpadR * 0.36f
        val up = state and Buttons.UP != 0; val down = state and Buttons.DOWN != 0
        val left = state and Buttons.LEFT != 0; val right = state and Buttons.RIGHT != 0
        val r = 6f * density
        canvas.drawRoundRect(dpadX - dpadR, dpadY - arm, dpadX + dpadR, dpadY + arm, r, r, fill)
        canvas.drawRoundRect(dpadX - arm, dpadY - dpadR, dpadX + arm, dpadY + dpadR, r, r, fill)
        if (up) canvas.drawRoundRect(dpadX - arm, dpadY - dpadR, dpadX + arm, dpadY - arm, r, r, pressed)
        if (down) canvas.drawRoundRect(dpadX - arm, dpadY + arm, dpadX + arm, dpadY + dpadR, r, r, pressed)
        if (left) canvas.drawRoundRect(dpadX - dpadR, dpadY - arm, dpadX - arm, dpadY + arm, r, r, pressed)
        if (right) canvas.drawRoundRect(dpadX + arm, dpadY - arm, dpadX + dpadR, dpadY + arm, r, r, pressed)
        canvas.drawCircle(dpadX, dpadY, dpadR * 1.08f, stroke)

        text.textSize = btnR * 0.8f
        drawRound(canvas, aX, aY, "A", state and Buttons.A != 0)
        drawRound(canvas, bX, bY, "B", state and Buttons.B != 0)

        text.textSize = 13f * density
        drawPill(canvas, selectRect, "SELECT", state and Buttons.SELECT != 0)
        drawPill(canvas, startRect, "START", state and Buttons.START != 0)
    }

    private fun drawRound(c: Canvas, x: Float, y: Float, label: String, on: Boolean) {
        c.drawCircle(x, y, btnR, if (on) redPressed else red)
        c.drawText(label, x, y - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawPill(c: Canvas, rect: RectF, label: String, on: Boolean) {
        val r = rect.height() / 2
        c.drawRoundRect(rect, r, r, if (on) pressed else fill)
        c.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    private fun hit(x: Float, y: Float): Int {
        var b = 0
        val dx = x - dpadX; val dy = y - dpadY
        val dist = hypot(dx, dy)
        if (dist < dpadR * 1.35f && dist > dpadR * 0.18f) {
            // 8 sectors of 45°, 0° = right, angles grow downwards.
            val deg = Math.toDegrees(atan2(dy, dx).toDouble())
            val sector = (((deg + 360 + 22.5) % 360) / 45).toInt()
            b = b or when (sector) {
                0 -> Buttons.RIGHT
                1 -> Buttons.RIGHT or Buttons.DOWN
                2 -> Buttons.DOWN
                3 -> Buttons.DOWN or Buttons.LEFT
                4 -> Buttons.LEFT
                5 -> Buttons.LEFT or Buttons.UP
                6 -> Buttons.UP
                else -> Buttons.UP or Buttons.RIGHT
            }
        }
        val reach = btnR * 1.25f
        val da = hypot(x - aX, y - aY); val db = hypot(x - bX, y - bY)
        // A touch between A and B presses both, which is handy for many games.
        if (da < reach) b = b or Buttons.A
        if (db < reach) b = b or Buttons.B
        val slop = 10f * density
        if (x > selectRect.left - slop && x < selectRect.right + slop && y > selectRect.top - slop && y < selectRect.bottom + slop) b = b or Buttons.SELECT
        if (x > startRect.left - slop && x < startRect.right + slop && y > startRect.top - slop && y < startRect.bottom + slop) b = b or Buttons.START
        return b
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var newState = 0
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until event.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) continue
                newState = newState or hit(event.getX(i), event.getY(i))
            }
        }
        if (newState != state) {
            val newlyPressed = newState and state.inv()
            state = newState
            if (newlyPressed != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onChange(state)
            invalidate()
        }
        return true
    }
}
