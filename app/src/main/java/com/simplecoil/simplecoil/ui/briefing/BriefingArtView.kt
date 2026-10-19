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

    /**
     * The Recoil blaster from the side, muzzle to the left, as in the box photos: white body,
     * grey frame, orange tips and trigger, the green power button. The button the slide is about
     * pulses where it really is: the trigger; reload at the bottom of the grip; the weapon switch
     * at the back of the pistol, or three-quarters of the way down the rifle's barrel; power, the
     * green button on the pistol's side or the rifle's stock.
     */
    private fun blaster(canvas: Canvas, w: Float, h: Float) {
        // Laid out in a box of boxW x boxH units, scaled to fit
        val boxW = if (pistol) 106f else 140f
        val boxH = if (pistol) 80f else 44f
        val scale = min(w / boxW, h / boxH) * 0.92f
        val ox = (w - boxW * scale) / 2
        val oy = (h - boxH * scale) / 2
        fun x(v: Float) = ox + v * scale
        fun y(v: Float) = oy + v * scale
        fun shape(color: Int, vararg points: Float) {
            path.reset()
            path.moveTo(x(points[0]), y(points[1]))
            var i = 2
            while (i < points.size) {
                path.lineTo(x(points[i]), y(points[i + 1]))
                i += 2
            }
            path.close()
            fill.color = color
            canvas.drawPath(path, fill)
            stroke.color = EDGE
            stroke.strokeWidth = 1.6f * density
            canvas.drawPath(path, stroke)
        }
        fun box(color: Int, l: Float, t: Float, r: Float, b: Float, round: Float = 1.5f) {
            rect.set(x(l), y(t), x(r), y(b))
            fill.color = color
            canvas.drawRoundRect(rect, round * scale, round * scale, fill)
            stroke.color = EDGE
            stroke.strokeWidth = 1.6f * density
            canvas.drawRoundRect(rect, round * scale, round * scale, stroke)
        }
        fun stripe(x0: Float, y0: Float, x1: Float, y1: Float) {
            stroke.color = WHITE
            stroke.strokeWidth = 1.4f * scale
            canvas.drawLine(x(x0), y(y0), x(x1), y(y1), stroke)
        }
        // From the frame, round under the trigger and into the grip (drawn first: the grip covers its end)
        fun guard(width: Float, x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float) {
            path.reset()
            path.moveTo(x(x0), y(y0))
            path.quadTo(x(cx), y(cy), x(x1), y(y1))
            stroke.color = GREY
            stroke.strokeWidth = width * scale
            canvas.drawPath(path, stroke)
        }

        val power: Pair<Float, Float>
        if (pistol) {
            // The frame loop from the trigger guard down to the grip's foot
            shape(GREY, 30f, 42f, 36f, 42f, 50f, 70f, 66f, 70f, 66f, 75f, 46f, 75f)
            // Trigger guard, curving round into the grip, then the grip and its orange foot (where reload is)
            guard(2.6f, 37f, 41f, 46f, 58f, 68f, 51f)
            shape(GREY, 56f, 40f, 78f, 40f, 92f, 72f, 70f, 72f)
            shape(ORANGE, 70f, 71f, 92f, 71f, 94f, 77f, 72f, 77f)
            // Trigger
            shape(ORANGE, 46f, 40f, 50f, 40f, 49f, 50f, 46f, 49f)
            // Lower frame and the white body
            box(GREY, 14f, 36f, 84f, 42f, 1f)
            box(WHITE, 12f, 14f, 90f, 37f, 3f)
            // Rail on top
            box(GREY, 24f, 10f, 86f, 14f, 1f)
            for (i in 0 until 8) box(GREY_DARK, 27f + i * 7.5f, 8.5f, 30f + i * 7.5f, 10.5f, 0.5f)
            // Orange muzzle, with its white stripes, and the orange back
            shape(ORANGE, 4f, 15f, 16f, 13f, 18f, 37f, 6f, 35f)
            stripe(10f, 16f, 11.5f, 34f)
            stripe(13.5f, 15.5f, 15f, 34.5f)
            box(ORANGE, 88f, 13f, 96f, 33f, 2f)
            // The weapon switch, on the back
            box(ORANGE, 90f, 18f, 95f, 26f, 1f)
            power = 74f to 25f
        } else {
            // Trigger guard, curving round into the grip; the grip, raked back, its foot, and the
            // frame loop back to the stock
            guard(2.2f, 72f, 26f, 76f, 38f, 90f, 34f)
            shape(GREY, 84f, 25f, 97f, 25f, 104f, 41f, 91f, 41f)
            shape(ORANGE, 90f, 40f, 105f, 40f, 106f, 44f, 91f, 44f)
            shape(WHITE, 101f, 38f, 120f, 25f, 122f, 28f, 104f, 41f)
            // Trigger
            shape(ORANGE, 79f, 25f, 83f, 25f, 83f, 33f, 79f, 32f)
            // Lower rail with its teeth, and the body
            box(GREY, 6f, 24f, 70f, 28f, 1f)
            for (i in 0 until 8) if (i != 3 && i != 4) box(GREY_DARK, 9f + i * 8f, 27.5f, 12f + i * 8f, 29.5f, 0.5f)
            // The orange weapon switch, under the barrel near the front
            box(ORANGE, 34f, 25f, 43f, 29.5f, 1.5f)
            box(WHITE, 10f, 8f, 108f, 26f, 2f)
            // The window in the body
            box(GREY, 58f, 13f, 80f, 18f, 2f)
            // Top rail and sights
            box(GREY, 10f, 5f, 108f, 8f, 1f)
            box(GREY_DARK, 14f, 2.5f, 17f, 5f, 0.5f)
            box(GREY_DARK, 76f, 1.5f, 82f, 5f, 0.5f)
            // Stock, its orange end and the butt
            box(WHITE, 106f, 7f, 134f, 25f, 2f)
            box(ORANGE, 118f, 24f, 134f, 32f, 2f)
            shape(GREY, 132f, 4f, 139f, 6f, 139f, 30f, 132f, 28f)
            // The orange front, about a fifth of its length, with its white stripes
            shape(ORANGE, 2f, 7f, 31f, 7f, 28f, 26f, 2f, 26f)
            stripe(14f, 9f, 10f, 24f)
            stripe(19f, 9f, 15f, 24f)
            stripe(24f, 9f, 20f, 24f)
            power = 123f to 15f
        }
        // The green power button
        fill.color = GREEN_RING
        canvas.drawCircle(x(power.first), y(power.second), 3.2f * scale, fill)
        fill.color = GREEN
        canvas.drawCircle(x(power.first), y(power.second), 2f * scale, fill)

        // Where the button is
        val (bx, by) = when (art) {
            SlideArt.TRIGGER -> if (pistol) 48f to 45f else 81f to 30f
            SlideArt.RELOAD -> if (pistol) 83f to 77f else 98f to 44f
            SlideArt.THUMB -> if (pistol) 92.5f to 22f else 38.5f to 27f
            else -> power
        }
        // It glows in its own colour: green for power, orange for the rest
        val glow = if (art == SlideArt.POWER) GREEN else ORANGE
        val ring = (4 + 6 * pulse(2)) * scale
        stroke.strokeWidth = 3 * density
        fill.color = glow
        fill.alpha = (110 + 145 * (1 - pulse(2))).toInt()
        canvas.drawCircle(x(bx), y(by), 2.4f * scale, fill)
        fill.alpha = 255
        stroke.color = glow
        stroke.alpha = (255 * (1 - pulse(2))).toInt().coerceIn(40, 255)
        canvas.drawCircle(x(bx), y(by), ring, stroke)
        stroke.alpha = 255
        stroke.strokeWidth = 3 * density
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
        const val OUTLINE = 0xFFB0BEC5.toInt()
        const val HIGHLIGHT = 0xFFFF9800.toInt()

        // The blaster, in its own colours, and the pulse that marks its button (cyan: the blaster has orange on it)
        const val WHITE = 0xFFECEFF1.toInt()
        const val GREY = 0xFF9AA4AA.toInt()
        const val GREY_DARK = 0xFF6B757B.toInt()
        const val ORANGE = 0xFFF26B1D.toInt()
        const val EDGE = 0xFF2B3238.toInt()
        const val GREEN = 0xFF43A047.toInt()
        const val GREEN_RING = 0xFF1B5E20.toInt()
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
