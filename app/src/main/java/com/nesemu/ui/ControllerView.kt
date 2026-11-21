package com.nesemu.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.nesemu.core.Buttons
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * On-screen pad: 8-way D-pad, A, B, turbo A/B, Select, Start, plus rewind (hold),
 * fast-forward (toggle) and menu buttons. Supports multi-touch.
 */
class ControllerView(context: Context, private val listener: Listener) : View(context) {
    interface Listener {
        fun onPad(held: Int, turbo: Int)
        fun onRewind(active: Boolean)
        fun onFastForward()
        fun onMenu()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var opacity = 70
        set(v) { field = v; invalidate() }
    var scale = 100
        set(v) { field = v; requestLayout(); relayout(); invalidate() }
    var haptics = true
    var fastForward = false
        set(v) { field = v; invalidate() }
    var fastSpeed = 3
    /** Online play: rewind and fast-forward are hidden. */
    var netMode = false
        set(v) { field = v; invalidate() }
    /** Status line shown above the picture (e.g. online room and ping). */
    var statusText: String? = null
        set(v) { if (v != field) { field = v; invalidate() } }

    // Geometry
    private var dpadX = 0f; private var dpadY = 0f; private var dpadR = 0f
    private var aX = 0f; private var aY = 0f; private var bX = 0f; private var bY = 0f; private var btnR = 0f
    private var taX = 0f; private var taY = 0f; private var tbX = 0f; private var tbY = 0f; private var turboR = 0f
    private val selectRect = RectF(); private val startRect = RectF()
    private var rewX = 0f; private var ffX = 0f; private var menuX = 0f; private var toolY = 0f; private var toolR = 0f
    private var game = RectF()

    private var state = 0

    // Paints
    private val dpadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 58, 72) }
    private val dpadPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 120, 140) }
    private val dpadCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(44, 44, 56) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(70, 255, 255, 255) }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val redPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val turboPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 34, 20) }
    private val turboPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(110, 84, 10) }
    private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.ACCENT_2 }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 58, 72) }
    private val pillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 120, 140) }
    private val toolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 40, 40, 56) }
    private val toolActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 0, 0, 0) }
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    private fun relayout() {
        val w = width; val h = height
        if (w == 0 || h == 0) return
        val s = scale / 100f
        game = ScreenLayout.gameRect(w, h, density)
        toolR = dp(20f)
        if (ScreenLayout.isPortrait(w, h)) {
            val areaTop = game.bottom
            val area = h - areaTop
            dpadR = min(w * 0.2f, area * 0.26f) * s
            dpadX = w * 0.07f + dpadR
            dpadY = areaTop + area * 0.47f
            btnR = dpadR * 0.42f
            aX = w - w * 0.07f - btnR
            aY = dpadY - btnR * 0.6f
            bX = aX - btnR * 2.45f
            bY = dpadY + btnR * 0.6f
            toolY = ScreenLayout.topBar(density) / 2
            val pillY = h - area * 0.11f
            layoutPills(w / 2f - dp(48f), w / 2f + dp(48f), pillY, s)
        } else {
            val side = game.left
            dpadR = (min(side * 0.38f, h * 0.21f).coerceAtLeast(dp(52f))) * s
            dpadX = maxOf(side / 2f, dpadR + dp(20f))
            dpadY = h * 0.6f
            btnR = dpadR * 0.42f
            val cx = w - dpadX
            aX = cx + btnR * 1.2f
            aY = dpadY - btnR * 0.6f
            bX = cx - btnR * 1.2f
            bY = dpadY + btnR * 0.6f
            toolY = dp(30f)
            layoutPills(dpadX, cx, h - dp(28f), s)
        }
        turboR = btnR * 0.62f
        val gap = dp(10f)
        taX = aX; taY = aY - btnR - turboR - gap
        tbX = bX; tbY = bY - btnR - turboR - gap
        rewX = dp(30f); ffX = dp(82f); menuX = w - dp(30f)
        ring.strokeWidth = dp(2f)
        gold.strokeWidth = dp(2.5f)
        redPaint.shader = RadialGradient(0f, -btnR * 0.4f, btnR * 1.4f, Color.rgb(240, 80, 90), Color.rgb(150, 20, 32), Shader.TileMode.CLAMP)
        redPressedPaint.shader = RadialGradient(0f, -btnR * 0.4f, btnR * 1.4f, Color.rgb(255, 140, 140), Color.rgb(200, 40, 50), Shader.TileMode.CLAMP)
    }

    private fun layoutPills(selectX: Float, startX: Float, y: Float, s: Float) {
        val pw = dp(76f) * s; val ph = dp(28f) * s
        selectRect.set(selectX - pw / 2, y - ph / 2, selectX + pw / 2, y + ph / 2)
        startRect.set(startX - pw / 2, y - ph / 2, startX + pw / 2, y + ph / 2)
    }

    override fun onDraw(canvas: Canvas) {
        drawBadge(canvas)
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), opacity * 255 / 100)
        drawDpad(canvas)
        drawFace(canvas, aX, aY, "A", state and Buttons.A != 0)
        drawFace(canvas, bX, bY, "B", state and Buttons.B != 0)
        drawTurbo(canvas, taX, taY, "A", state and TURBO_A != 0)
        drawTurbo(canvas, tbX, tbY, "B", state and TURBO_B != 0)
        text.textSize = selectRect.height() * 0.46f
        drawPill(canvas, selectRect, "SELECT", state and Buttons.SELECT != 0)
        drawPill(canvas, startRect, "START", state and Buttons.START != 0)
        canvas.restoreToCount(save)
        drawTools(canvas)
    }

    private fun drawDpad(c: Canvas) {
        val arm = dpadR * 0.35f
        val r = dp(8f)
        c.drawCircle(dpadX, dpadY, dpadR * 1.1f, ring)
        c.drawRoundRect(dpadX - dpadR, dpadY - arm, dpadX + dpadR, dpadY + arm, r, r, dpadPaint)
        c.drawRoundRect(dpadX - arm, dpadY - dpadR, dpadX + arm, dpadY + dpadR, r, r, dpadPaint)
        if (state and Buttons.UP != 0) c.drawRoundRect(dpadX - arm, dpadY - dpadR, dpadX + arm, dpadY, r, r, dpadPressed)
        if (state and Buttons.DOWN != 0) c.drawRoundRect(dpadX - arm, dpadY, dpadX + arm, dpadY + dpadR, r, r, dpadPressed)
        if (state and Buttons.LEFT != 0) c.drawRoundRect(dpadX - dpadR, dpadY - arm, dpadX, dpadY + arm, r, r, dpadPressed)
        if (state and Buttons.RIGHT != 0) c.drawRoundRect(dpadX, dpadY - arm, dpadX + dpadR, dpadY + arm, r, r, dpadPressed)
        c.drawCircle(dpadX, dpadY, arm * 0.55f, dpadCenter)
        // Arrow hints on each arm.
        val t = arm * 0.45f; val d = dpadR * 0.72f
        glyph.alpha = 110
        triangle(c, dpadX, dpadY - d, t, 0); triangle(c, dpadX, dpadY + d, t, 2)
        triangle(c, dpadX - d, dpadY, t, 3); triangle(c, dpadX + d, dpadY, t, 1)
        glyph.alpha = 255
    }

    /** Small filled triangle pointing up (0), right (1), down (2) or left (3). */
    private fun triangle(c: Canvas, x: Float, y: Float, size: Float, dir: Int) {
        path.reset()
        when (dir) {
            0 -> { path.moveTo(x, y - size); path.lineTo(x + size, y + size * 0.6f); path.lineTo(x - size, y + size * 0.6f) }
            1 -> { path.moveTo(x + size, y); path.lineTo(x - size * 0.6f, y + size); path.lineTo(x - size * 0.6f, y - size) }
            2 -> { path.moveTo(x, y + size); path.lineTo(x - size, y - size * 0.6f); path.lineTo(x + size, y - size * 0.6f) }
            else -> { path.moveTo(x - size, y); path.lineTo(x + size * 0.6f, y - size); path.lineTo(x + size * 0.6f, y + size) }
        }
        path.close()
        c.drawPath(path, glyph)
    }

    private fun drawFace(c: Canvas, x: Float, y: Float, label: String, on: Boolean) {
        c.save()
        c.translate(x, y)
        c.drawCircle(0f, 0f, btnR + dp(3f), ring)
        c.drawCircle(0f, 0f, if (on) btnR * 0.94f else btnR, if (on) redPressedPaint else redPaint)
        text.textSize = btnR * 0.75f
        c.drawText(label, 0f, -(text.descent() + text.ascent()) / 2, text)
        c.restore()
    }

    private fun drawTurbo(c: Canvas, x: Float, y: Float, label: String, on: Boolean) {
        c.drawCircle(x, y, turboR, if (on) turboPressed else turboPaint)
        c.drawCircle(x, y, turboR, gold)
        text.color = Theme.ACCENT_2
        text.textSize = turboR * 0.7f
        c.drawText(label, x, y + text.textSize * 0.15f, text)
        text.textSize = turboR * 0.3f
        c.drawText("TURBO", x, y + turboR * 0.62f, text)
        text.color = Color.WHITE
    }

    private fun drawPill(c: Canvas, rect: RectF, label: String, on: Boolean) {
        val r = rect.height() / 2
        c.drawRoundRect(rect, r, r, if (on) pillPressed else pill)
        c.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawTools(c: Canvas) {
        if (netMode) { drawMenuTool(c); return }
        // Rewind: two left-pointing triangles.
        c.drawCircle(rewX, toolY, toolR, if (state and REWIND != 0) toolActive else toolPaint)
        val t = toolR * 0.32f
        triangle(c, rewX - t * 0.7f, toolY, t, 3); triangle(c, rewX + t * 0.7f, toolY, t, 3)
        // Fast-forward: two right-pointing triangles.
        c.drawCircle(ffX, toolY, toolR, if (fastForward) toolActive else toolPaint)
        triangle(c, ffX - t * 0.7f, toolY, t, 1); triangle(c, ffX + t * 0.7f, toolY, t, 1)
        drawMenuTool(c)
    }

    private fun drawMenuTool(c: Canvas) {
        // Menu: three bars.
        c.drawCircle(menuX, toolY, toolR, if (state and MENU != 0) toolActive else toolPaint)
        val bw = toolR * 0.5f; val bh = dp(2.2f)
        for (i in -1..1) c.drawRoundRect(menuX - bw, toolY + i * toolR * 0.3f - bh, menuX + bw, toolY + i * toolR * 0.3f + bh, bh, bh, glyph)
    }

    private fun drawBadge(c: Canvas) {
        val msg = when {
            statusText != null -> statusText!!
            state and REWIND != 0 -> "◀◀  Geri sarılıyor"
            fastForward -> "▶▶  ${fastSpeed}x hız"
            else -> return
        }
        text.textSize = dp(14f)
        val tw = text.measureText(msg)
        val cx = game.centerX(); val cy = game.top + dp(26f)
        val r = RectF(cx - tw / 2 - dp(14f), cy - dp(16f), cx + tw / 2 + dp(14f), cy + dp(16f))
        c.drawRoundRect(r, dp(16f), dp(16f), badgeBg)
        c.drawText(msg, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }

    // ---- Touch ----

    private fun inCircle(x: Float, y: Float, cx: Float, cy: Float, r: Float) = hypot(x - cx, y - cy) < r

    private fun hit(x: Float, y: Float): Int {
        var b = 0
        val dx = x - dpadX; val dy = y - dpadY
        val dist = hypot(dx, dy)
        if (dist < dpadR * 1.35f && dist > dpadR * 0.15f) {
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
        // A touch between A and B presses both, handy in many games.
        if (inCircle(x, y, aX, aY, btnR * 1.25f)) b = b or Buttons.A
        if (inCircle(x, y, bX, bY, btnR * 1.25f)) b = b or Buttons.B
        if (inCircle(x, y, taX, taY, turboR * 1.2f)) b = b or TURBO_A
        if (inCircle(x, y, tbX, tbY, turboR * 1.2f)) b = b or TURBO_B
        val slop = dp(10f)
        if (x > selectRect.left - slop && x < selectRect.right + slop && y > selectRect.top - slop && y < selectRect.bottom + slop) b = b or Buttons.SELECT
        if (x > startRect.left - slop && x < startRect.right + slop && y > startRect.top - slop && y < startRect.bottom + slop) b = b or Buttons.START
        val toolHit = toolR * 1.3f
        if (!netMode && inCircle(x, y, rewX, toolY, toolHit)) b = b or REWIND
        if (!netMode && inCircle(x, y, ffX, toolY, toolHit)) b = b or FFWD
        if (inCircle(x, y, menuX, toolY, toolHit)) b = b or MENU
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
            val pressed = newState and state.inv()
            val old = state
            state = newState
            if (pressed != 0 && haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            listener.onPad(
                newState and 0xFF,
                (if (newState and TURBO_A != 0) Buttons.A else 0) or (if (newState and TURBO_B != 0) Buttons.B else 0)
            )
            if ((old xor newState) and REWIND != 0) listener.onRewind(newState and REWIND != 0)
            if (pressed and FFWD != 0) listener.onFastForward()
            if (pressed and MENU != 0) listener.onMenu()
            invalidate()
        }
        return true
    }

    /** Releases everything, e.g. when the menu opens over the pad. */
    fun releaseAll() {
        if (state == 0) return
        if (state and REWIND != 0) listener.onRewind(false)
        state = 0
        listener.onPad(0, 0)
        invalidate()
    }

    companion object {
        private const val TURBO_A = 1 shl 8
        private const val TURBO_B = 1 shl 9
        private const val REWIND = 1 shl 10
        private const val FFWD = 1 shl 11
        private const val MENU = 1 shl 12
    }
}
