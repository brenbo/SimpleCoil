/*
 * Copyright (C) 2026 Ethan Yonker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplecoil.simplecoil.ui.briefing

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.simplecoil.simplecoil.domain.briefing.SlideArt
import com.simplecoil.simplecoil.domain.loadout.DropKind
import com.simplecoil.simplecoil.domain.team.TeamColors
import com.simplecoil.simplecoil.ui.map.DropOverlay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A briefing slide's picture, drawn and animated in code so it shows on any phone: the blaster
 * (a rifle or a pistol) with the button in question pulsing, a crate dropping in, the zone
 * shrinking, a point filling as it's captured, and so on. Loops for as long as it shows.
 */
class BriefingArtView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var art: SlideArt = SlideArt.TRIGGER
        set(value) {
            field = value
            invalidate()
        }

    /** The player's blaster: a pistol, else a rifle. */
    var pistol = false
        set(value) {
            field = value
            invalidate()
        }

    var drop: DropKind? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 0 to 1, round and round. */
    private var t = 0f
    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = LOOP_MILLIS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private val rect = RectF()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loop.start()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2
        val cy = h / 2
        val s = min(w, h)
        stroke.strokeWidth = 3 * density
        stroke.pathEffect = null
        when (art) {
            SlideArt.TRIGGER, SlideArt.RELOAD, SlideArt.THUMB, SlideArt.POWER -> blaster(canvas, w, h)
            SlideArt.DROP -> crate(canvas, cx, cy, s)
            SlideArt.AMMO -> magazines(canvas, cx, cy, s)
            SlideArt.KILLSTREAK -> chevrons(canvas, cx, cy, s)
            SlideArt.CLASSIC, SlideArt.MARKSMAN -> shot(canvas, cx, cy, s, art == SlideArt.MARKSMAN)
            SlideArt.CAPTURE, SlideArt.HARDPOINT -> capture(canvas, cx, cy, s, art == SlideArt.HARDPOINT)
            SlideArt.ZONE -> zone(canvas, cx, cy, s)
            SlideArt.FLAG -> flag(canvas, cx, cy, s)
            SlideArt.LOOT -> loot(canvas, cx, cy, s)
            SlideArt.BOMB -> bomb(canvas, cx, cy, s)
            SlideArt.INFECTION -> infection(canvas, cx, cy, s)
            SlideArt.TEAMS, SlideArt.FREE_FOR_ALL -> players(canvas, cx, cy, s, art == SlideArt.TEAMS)
            SlideArt.RESPAWN -> respawn(canvas, cx, cy, s)
            SlideArt.NO_RESPAWN -> noRespawn(canvas, cx, cy, s)
            SlideArt.BORDER -> border(canvas, cx, cy, s)
            SlideArt.WIN -> trophy(canvas, cx, cy, s)
        }
    }

    /** A gentle 0..1..0 swell, [times] a loop. */
    private fun pulse(times: Int = 2) = ((1 - cos(2 * PI * times * t)) / 2).toFloat()

    // ---- The blaster: a side view, the button in question pulsing --------------------------

    private fun blaster(canvas: Canvas, w: Float, h: Float) {
        // Laid out in a 100 x 60 box, scaled to fit
        val scale = min(w / 100f, h / 60f) * 0.9f
        val ox = (w - 100 * scale) / 2
        val oy = (h - 60 * scale) / 2
        fun x(v: Float) = ox + v * scale
        fun y(v: Float) = oy + v * scale
        path.reset()
        if (pistol) {
            // Slide, frame and grip
            path.moveTo(x(18f), y(14f)); path.lineTo(x(84f), y(14f)); path.lineTo(x(86f), y(26f)); path.lineTo(x(52f), y(26f))
            path.lineTo(x(46f), y(56f)); path.lineTo(x(30f), y(56f)); path.lineTo(x(34f), y(26f)); path.lineTo(x(18f), y(26f)); path.close()
        } else {
            // Stock, body, barrel and grip
            path.moveTo(x(4f), y(20f)); path.lineTo(x(28f), y(18f)); path.lineTo(x(30f), y(14f)); path.lineTo(x(80f), y(14f))
            path.lineTo(x(80f), y(18f)); path.lineTo(x(97f), y(18f)); path.lineTo(x(97f), y(24f)); path.lineTo(x(72f), y(24f))
            path.lineTo(x(70f), y(30f)); path.lineTo(x(46f), y(30f)); path.lineTo(x(41f), y(52f)); path.lineTo(x(31f), y(52f))
            path.lineTo(x(34f), y(30f)); path.lineTo(x(28f), y(30f)); path.lineTo(x(4f), y(36f)); path.close()
        }
        fill.color = BODY
        canvas.drawPath(path, fill)
        stroke.color = OUTLINE
        canvas.drawPath(path, stroke)
        // Trigger guard
        stroke.color = OUTLINE
        if (pistol) canvas.drawArc(x(46f), y(24f), x(60f), y(38f), 0f, 180f, false, stroke)
        else canvas.drawArc(x(40f), y(28f), x(54f), y(42f), 0f, 180f, false, stroke)
        // Where the button is
        val (bx, by) = when (art) {
            SlideArt.TRIGGER -> if (pistol) 50f to 30f else 44f to 34f
            SlideArt.RELOAD -> if (pistol) 26f to 12f else 34f to 12f
            SlideArt.THUMB -> if (pistol) 40f to 22f else 36f to 24f
            else -> if (pistol) 20f to 20f else 8f to 28f
        }
        val ring = (5 + 7 * pulse(2)) * scale
        fill.color = HIGHLIGHT
        fill.alpha = (110 + 145 * (1 - pulse(2))).toInt()
        canvas.drawCircle(x(bx), y(by), 3.2f * scale, fill)
        fill.alpha = 255
        stroke.color = HIGHLIGHT
        stroke.alpha = (255 * (1 - pulse(2))).toInt().coerceIn(40, 255)
        canvas.drawCircle(x(bx), y(by), ring, stroke)
        stroke.alpha = 255
    }

    // ---- Drops and ammo -------------------------------------------------------------------

    private fun crate(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val kind = drop
        val color = kind?.let { DropOverlay.color(it) } ?: CRATE
        // Falls in, bounces twice, rests
        val fall = (t / 0.35f).coerceAtMost(1f)
        val bounce = if (t > 0.35f) abs(sin((t - 0.35f) / 0.3f * PI.toFloat() * 2)) * (1 - ((t - 0.35f) / 0.65f)).coerceAtLeast(0f) else 0f
        val size = s * 0.32f
        val top = cy - size / 2 - (1 - fall) * s * 0.6f - bounce * s * 0.08f
        // Shadow, growing as it lands
        fill.color = SHADOW
        canvas.drawOval(cx - size * 0.6f * fall, cy + size / 2 + 4 * density, cx + size * 0.6f * fall, cy + size / 2 + 12 * density, fill)
        fill.color = color
        rect.set(cx - size / 2, top, cx + size / 2, top + size)
        canvas.drawRoundRect(rect, 8 * density, 8 * density, fill)
        stroke.color = OUTLINE
        canvas.drawRoundRect(rect, 8 * density, 8 * density, stroke)
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, stroke)
        canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, stroke)
        kind?.let {
            text.color = 0xFFFFFFFF.toInt()
            text.textSize = size * 0.26f
            val label = DropOverlay.label(context, it)
            fill.color = 0xAA000000.toInt()
            val tw = text.measureText(label) / 2 + 6 * density
            canvas.drawRoundRect(cx - tw, rect.centerY() - text.textSize * 0.8f, cx + tw, rect.centerY() + text.textSize * 0.45f,
                4 * density, 4 * density, fill)
            canvas.drawText(label, cx, rect.centerY() + text.textSize * 0.3f, text)
        }
    }

    private fun magazines(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val count = 5
        val shown = (t * (count + 2)).toInt().coerceAtMost(count)
        val mw = s * 0.09f
        val gap = s * 0.04f
        val start = cx - (count * mw + (count - 1) * gap) / 2
        for (i in 0 until count) {
            fill.color = if (i < shown) AMMO else DIM
            rect.set(start + i * (mw + gap), cy - s * 0.18f, start + i * (mw + gap) + mw, cy + s * 0.18f)
            canvas.drawRoundRect(rect, 4 * density, 4 * density, fill)
        }
    }

    private fun chevrons(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val lit = (t * 4).toInt()
        for (i in 0 until 3) {
            stroke.color = if (i < lit) HIGHLIGHT else DIM
            stroke.strokeWidth = 7 * density
            val y = cy + s * 0.18f - i * s * 0.16f
            path.reset()
            path.moveTo(cx - s * 0.2f, y + s * 0.08f); path.lineTo(cx, y - s * 0.04f); path.lineTo(cx + s * 0.2f, y + s * 0.08f)
            canvas.drawPath(path, stroke)
        }
    }

    // ---- Game modes ------------------------------------------------------------------------

    private fun shot(canvas: Canvas, cx: Float, cy: Float, s: Float, distance: Boolean) {
        val ax = cx - s * 0.35f
        val bx = cx + s * 0.35f
        dot(canvas, ax, cy, s * 0.06f, TeamColors.colorOf(2))
        val hit = t > 0.5f
        dot(canvas, bx, cy, s * 0.06f, if (hit) DIM else TeamColors.colorOf(1))
        // The shot crossing
        val p = (t / 0.5f).coerceAtMost(1f)
        stroke.color = HIGHLIGHT
        canvas.drawLine(ax, cy, ax + (bx - ax) * p, cy, stroke)
        if (distance && hit) {
            text.color = HIGHLIGHT
            text.textSize = s * 0.09f
            canvas.drawText("+42 m", cx, cy - s * 0.12f, text)
        } else if (hit) {
            text.color = HIGHLIGHT
            text.textSize = s * 0.09f
            canvas.drawText("+1", bx, cy - s * 0.12f, text)
        }
    }

    private fun capture(canvas: Canvas, cx: Float, cy: Float, s: Float, hardpoint: Boolean) {
        val r = s * if (hardpoint) 0.22f else 0.28f
        // Hardpoint: the point hops to a new spot every half loop
        val ox = if (hardpoint) (if (t < 0.5f) -s * 0.12f else s * 0.12f) else 0f
        val px = cx + ox
        val progress = if (hardpoint) ((t % 0.5f) / 0.5f) else t
        stroke.color = DIM
        canvas.drawCircle(px, cy, r, stroke)
        fill.color = TeamColors.colorOf(1)
        fill.alpha = 110
        rect.set(px - r, cy - r, px + r, cy + r)
        canvas.drawArc(rect, -90f, 360f * progress, true, fill)
        fill.alpha = 255
        stroke.color = TeamColors.colorOf(1)
        canvas.drawArc(rect, -90f, 360f * progress, false, stroke)
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = r * if (hardpoint) 0.55f else 0.7f
        canvas.drawText(if (hardpoint) "HP" else "A", px, cy + text.textSize * 0.35f, text)
        dot(canvas, px - r * 0.3f, cy + r * 0.45f, s * 0.035f, TeamColors.colorOf(1))
    }

    private fun zone(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val outer = s * 0.42f
        val r = outer * (1 - 0.6f * t)
        fill.color = DANGER
        fill.alpha = 60
        canvas.drawCircle(cx, cy, outer, fill)
        fill.color = 0xFF000000.toInt()
        fill.alpha = 255
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = SAFE
        canvas.drawCircle(cx, cy, r, stroke)
        dot(canvas, cx + s * 0.05f, cy - s * 0.04f, s * 0.035f, TeamColors.colorOf(1))
        dot(canvas, cx + outer * 0.75f, cy + outer * 0.3f, s * 0.035f, TeamColors.colorOf(2))
    }

    private fun flag(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        // Carried from the far base to the near one
        val x = cx - s * 0.35f + s * 0.7f * t
        val pole = s * 0.45f
        stroke.color = OUTLINE
        canvas.drawLine(x, cy + pole / 2, x, cy - pole / 2, stroke)
        path.reset()
        val wave = sin(t * PI.toFloat() * 8) * s * 0.03f
        path.moveTo(x, cy - pole / 2)
        path.quadTo(x + s * 0.12f, cy - pole / 2 + wave, x + s * 0.22f, cy - pole / 2 + s * 0.04f)
        path.lineTo(x + s * 0.22f, cy - pole / 2 + s * 0.16f)
        path.quadTo(x + s * 0.12f, cy - pole / 2 + s * 0.12f - wave, x, cy - pole / 2 + s * 0.13f)
        path.close()
        fill.color = TeamColors.colorOf(2)
        canvas.drawPath(path, fill)
        stroke.color = TeamColors.colorOf(2)
        canvas.drawCircle(cx - s * 0.35f, cy + pole / 2, s * 0.07f, stroke)
        stroke.color = TeamColors.colorOf(1)
        canvas.drawCircle(cx + s * 0.35f, cy + pole / 2, s * 0.07f, stroke)
    }

    private fun loot(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        // Zone red, then green as it opens; coins fly into it
        val open = t > 0.4f
        val zone = if (open) SAFE else DANGER
        stroke.color = zone
        if (!open) stroke.pathEffect = DashPathEffect(floatArrayOf(10 * density, 8 * density), 0f)
        canvas.drawCircle(cx + s * 0.25f, cy, s * 0.15f, stroke)
        stroke.pathEffect = null
        text.color = zone
        text.textSize = s * 0.07f
        canvas.drawText("EX", cx + s * 0.25f, cy + text.textSize * 0.35f, text)
        for (i in 0 until 3) {
            val p = ((t - 0.4f - i * 0.12f) / 0.3f).coerceIn(0f, 1f)
            val x = cx - s * 0.3f + (s * 0.55f) * p
            val y = cy + (i - 1) * s * 0.12f * (1 - p)
            fill.color = LOOT
            fill.alpha = if (p >= 1f) 0 else 255
            canvas.drawCircle(x, y, s * 0.045f, fill)
        }
        fill.alpha = 255
    }

    private fun bomb(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val r = s * 0.2f
        fill.color = 0xFF263238.toInt()
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = OUTLINE
        canvas.drawCircle(cx, cy, r, stroke)
        // The light blinks faster as the fuse runs down
        val rate = 2 + (t * 10).toInt()
        val on = ((t * rate * 2).toInt() % 2) == 0
        fill.color = if (on) DANGER else 0xFF5D1010.toInt()
        canvas.drawCircle(cx, cy - r * 0.2f, r * 0.22f, fill)
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = s * 0.08f
        canvas.drawText(String.format(java.util.Locale.ROOT, "0:%02d", (45 * (1 - t)).toInt()), cx, cy + r * 0.6f, text)
    }

    private fun infection(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val n = 7
        val infected = 1 + (t * (n - 1)).toInt()
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            val x = cx + cos(a).toFloat() * s * 0.3f
            val y = cy + sin(a).toFloat() * s * 0.3f
            dot(canvas, x, y, s * 0.05f, if (i < infected) INFECTED else SURVIVOR)
        }
    }

    private fun players(canvas: Canvas, cx: Float, cy: Float, s: Float, teams: Boolean) {
        val positions = listOf(-0.3f to -0.15f, -0.22f to 0.12f, -0.36f to 0.05f, 0.3f to -0.12f, 0.22f to 0.14f, 0.36f to 0.02f)
        positions.forEachIndexed { i, (dx, dy) ->
            val wobble = sin((t * 2 * PI + i).toFloat()) * s * 0.01f
            val color = if (teams) TeamColors.colorOf(if (i < 3) 1 else 2) else FFA_COLORS[i % FFA_COLORS.size]
            dot(canvas, cx + dx * s, cy + dy * s + wobble, s * 0.05f, color)
        }
    }

    private fun respawn(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val r = s * 0.25f
        stroke.color = DIM
        canvas.drawCircle(cx, cy, r, stroke)
        stroke.color = SAFE
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, -90f, 360f * t, false, stroke)
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = r * 0.6f
        canvas.drawText(if (t < 0.95f) "${(10 * (1 - t)).toInt() + 1}" else "GO", cx, cy + text.textSize * 0.35f, text)
    }

    private fun noRespawn(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        dot(canvas, cx, cy, s * 0.12f, DIM)
        stroke.color = DANGER
        stroke.strokeWidth = 8 * density
        val r = s * 0.2f * (0.8f + 0.2f * pulse(1))
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, stroke)
        canvas.drawLine(cx + r, cy - r, cx - r, cy + r, stroke)
    }

    private fun border(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val half = s * 0.3f
        stroke.color = DANGER
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, stroke)
        // A player wandering out and back
        val out = sin(t * 2 * PI).toFloat()
        val x = cx + half * 0.6f + out * half * 0.7f
        dot(canvas, x, cy, s * 0.045f, if (x > cx + half) DANGER else TeamColors.colorOf(1))
        if (x > cx + half) {
            text.color = DANGER
            text.textSize = s * 0.08f
            canvas.drawText("!", x, cy - s * 0.08f, text)
        }
    }

    private fun trophy(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val lift = pulse(1) * s * 0.03f
        fill.color = GOLD
        path.reset()
        path.moveTo(cx - s * 0.16f, cy - s * 0.2f - lift)
        path.lineTo(cx + s * 0.16f, cy - s * 0.2f - lift)
        path.quadTo(cx + s * 0.14f, cy + s * 0.04f - lift, cx, cy + s * 0.06f - lift)
        path.quadTo(cx - s * 0.14f, cy + s * 0.04f - lift, cx - s * 0.16f, cy - s * 0.2f - lift)
        canvas.drawPath(path, fill)
        canvas.drawRect(cx - s * 0.03f, cy + s * 0.05f - lift, cx + s * 0.03f, cy + s * 0.14f - lift, fill)
        canvas.drawRect(cx - s * 0.1f, cy + s * 0.14f - lift, cx + s * 0.1f, cy + s * 0.18f - lift, fill)
        // Sparkles
        for (i in 0 until 4) {
            val a = 2 * PI * (i / 4.0 + t)
            fill.alpha = (255 * pulse(2)).toInt()
            canvas.drawCircle(cx + cos(a).toFloat() * s * 0.3f, cy - s * 0.05f + sin(a).toFloat() * s * 0.22f, s * 0.015f, fill)
        }
        fill.alpha = 255
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(x, y, r, fill)
        stroke.color = 0xFFFFFFFF.toInt()
        stroke.strokeWidth = 2 * density
        canvas.drawCircle(x, y, r, stroke)
        stroke.strokeWidth = 3 * density
    }

    private companion object {
        const val LOOP_MILLIS = 2_400L
        const val BODY = 0xFF37474F.toInt()
        const val OUTLINE = 0xFFB0BEC5.toInt()
        const val HIGHLIGHT = 0xFFFF9800.toInt()
        const val CRATE = 0xFF8D6E63.toInt()
        const val SHADOW = 0x55000000
        const val AMMO = 0xFFFFC107.toInt()
        const val DIM = 0xFF455A64.toInt()
        const val DANGER = 0xFFE53935.toInt()
        const val SAFE = 0xFF69F0AE.toInt()
        const val LOOT = 0xFFFFC107.toInt()
        const val GOLD = 0xFFFFD54F.toInt()
        const val INFECTED = 0xFF76FF03.toInt()
        const val SURVIVOR = 0xFF40C4FF.toInt()
        val FFA_COLORS = intArrayOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt())
    }
}
