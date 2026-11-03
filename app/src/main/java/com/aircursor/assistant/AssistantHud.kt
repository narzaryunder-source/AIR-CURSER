package com.aircursor.assistant

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The assistant's orb: a glowing core with turning rings that breathe with your voice.
 * Cyan while listening, violet while thinking, green when it answers, amber on a problem.
 */
@SuppressLint("ViewConstructor")
class AssistantOrb(context: Context) : View(context) {
    enum class State { LISTENING, THINKING, ANSWER, PROBLEM }

    var state = State.LISTENING
        set(v) {
            field = v
            invalidate()
        }

    /** Voice level 0..1 (from the microphone). */
    var level = 0f
        set(v) {
            field = field * 0.6f + v.coerceIn(0f, 1f) * 0.4f
        }

    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    private fun color(): Int = when (state) {
        State.LISTENING -> 0xFF35E0FF.toInt()
        State.THINKING -> 0xFFA77BFF.toInt()
        State.ANSWER -> 0xFF3DF2A6.toInt()
        State.PROBLEM -> 0xFFFFB547.toInt()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * 0.92f
        val col = color()
        val speed = if (state == State.THINKING) 3f else 1f
        val pulse = 1f + level * 0.18f + (if (state == State.LISTENING) 0.03f * sin(phase * 2 * PI * 3).toFloat() else 0f)

        // glow + core
        core.shader = RadialGradient(cx, cy, r * pulse, intArrayOf(withAlpha(col, 235), withAlpha(col, 90), Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * pulse, core)
        core.shader = RadialGradient(cx - r * 0.12f, cy - r * 0.12f, r * 0.34f, intArrayOf(Color.WHITE, withAlpha(col, 200)),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.3f * (1f + level * 0.25f), core)

        // three rings turning at different speeds
        val rings = floatArrayOf(0.58f, 0.74f, 0.9f)
        for ((i, k) in rings.withIndex()) {
            val rr = r * k
            box.set(cx - rr, cy - rr, cx + rr, cy + rr)
            ring.strokeWidth = r * (0.05f - i * 0.012f)
            ring.color = withAlpha(col, 210 - i * 50)
            val start = (phase * 360f * speed * (if (i % 2 == 0) 1 else -1) * (1 + i * 0.5f)) % 360f
            val sweep = 70f + 40f * i + level * 60f
            c.drawArc(box, start, sweep, false, ring)
            c.drawArc(box, start + 180f, sweep * 0.6f, false, ring)
        }
        // small satellites
        dot.color = withAlpha(Color.WHITE, 220)
        for (i in 0 until 3) {
            val a = (phase * 2 * PI * speed + i * 2 * PI / 3).toFloat()
            val rr = r * 0.9f
            c.drawCircle(cx + rr * cos(a), cy + rr * sin(a), r * 0.035f, dot)
        }
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
}
