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

package com.simplecoil.simplecoil.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.simplecoil.simplecoil.domain.team.TeamColors

/**
 * The map's markers, drawn at the screen's density rather than loaded from old bitmaps (map
 * design: Map engine): flat team colours, a white rim and a soft shadow, so they read clearly on
 * aerial photos. Bitmaps made at the screen's density show at their dp size on the map.
 */
object MapIcons {
    /** Free for all, and anything without a team colour. */
    const val NEUTRAL = 0xFFFF5252.toInt()

    /** Spawns anyone may use. */
    const val ANYONE = 0xFFFFFFFF.toInt()

    /** A team's colour (1-4), or [fallback]. */
    fun teamColor(team: Int, fallback: Int = NEUTRAL): Int =
        if (team in 1..4) TeamColors.colorOf(team) else fallback

    /** You: a navigation arrow, pointing up (the map turns it to your heading). */
    fun you(context: Context, color: Int): Bitmap = draw(context, 36f) { c, d ->
        val r = 15f * d
        val cx = 18f * d
        val cy = 18f * d
        c.drawCircle(cx, cy, r, fill(color, shadow = d))
        c.drawCircle(cx, cy, r, stroke(Color.WHITE, 2.5f * d))
        val arrow = Path().apply {
            moveTo(cx, cy - 9f * d)
            lineTo(cx + 7f * d, cy + 8f * d)
            lineTo(cx, cy + 4f * d)
            lineTo(cx - 7f * d, cy + 8f * d)
            close()
        }
        c.drawPath(arrow, fill(Color.WHITE))
    }

    /** Another player: a dot for a teammate, a diamond for an enemy. */
    fun player(context: Context, color: Int, enemy: Boolean): Bitmap = draw(context, 26f) { c, d ->
        val cx = 13f * d
        val cy = 13f * d
        if (enemy) {
            val diamond = Path().apply {
                moveTo(cx, cy - 10f * d)
                lineTo(cx + 10f * d, cy)
                lineTo(cx, cy + 10f * d)
                lineTo(cx - 10f * d, cy)
                close()
            }
            c.drawPath(diamond, fill(color, shadow = d))
            c.drawPath(diamond, stroke(Color.WHITE, 2f * d))
        } else {
            c.drawCircle(cx, cy, 9f * d, fill(color, shadow = d))
            c.drawCircle(cx, cy, 9f * d, stroke(Color.WHITE, 2f * d))
        }
    }

    /** A spawn's badge: the team's colour with a white respawn sign. */
    fun spawnBadge(context: Context, color: Int): Bitmap = draw(context, 30f) { c, d ->
        val cx = 15f * d
        val cy = 15f * d
        c.drawCircle(cx, cy, 12f * d, fill(color, shadow = d))
        c.drawCircle(cx, cy, 12f * d, stroke(Color.WHITE, 2f * d))
        // A circular arrow: most of a ring, and its head
        val ink = if (ColorUtils.calculateLuminance(color) > 0.6) Color.parseColor("#263238") else Color.WHITE
        val ring = RectF(cx - 6f * d, cy - 6f * d, cx + 6f * d, cy + 6f * d)
        c.drawArc(ring, -60f, 290f, false, stroke(ink, 2.2f * d))
        val head = Path().apply {
            moveTo(cx + 6.8f * d, cy - 9.5f * d)
            lineTo(cx + 7.2f * d, cy - 2.5f * d)
            lineTo(cx + 1f * d, cy - 5.5f * d)
            close()
        }
        c.drawPath(head, fill(ink))
    }

    /** The spawn's area: a soft disc with a ring, drawn [ZONE_DP] across and scaled to its real size. */
    fun spawnZone(context: Context, color: Int): Bitmap = draw(context, ZONE_DP) { c, d ->
        val half = ZONE_DP / 2 * d
        c.drawCircle(half, half, half - 2f * d, fill(ColorUtils.setAlphaComponent(color, 70)))
        c.drawCircle(half, half, half - 2f * d, stroke(ColorUtils.setAlphaComponent(color, 230), 3f * d))
    }

    /** A drop: a crate in the drop's colour, labelled with what it gives. */
    fun drop(context: Context, color: Int, label: String): Bitmap = draw(context, 40f) { c, d ->
        val box = RectF(4f * d, 6f * d, 36f * d, 34f * d)
        c.drawRoundRect(box, 7f * d, 7f * d, fill(color, shadow = d))
        c.drawRoundRect(box, 7f * d, 7f * d, stroke(Color.WHITE, 2f * d))
        val ink = if (ColorUtils.calculateLuminance(color) > 0.6) Color.parseColor("#263238") else Color.WHITE
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = ink
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = (if (label.length > 3) 9f else 11f) * d
        }
        c.drawText(label, box.centerX(), box.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    /** The zone bitmap's size in dp, which the map scales to the spawn's real size. */
    const val ZONE_DP = 128f

    private fun draw(context: Context, sizeDp: Float, paint: (Canvas, Float) -> Unit): Bitmap {
        val density = context.resources.displayMetrics.density
        val px = (sizeDp * density).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(px, px)
        bitmap.density = context.resources.displayMetrics.densityDpi
        paint(Canvas(bitmap), density)
        return bitmap
    }

    private fun fill(color: Int, shadow: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
        if (shadow > 0) setShadowLayer(2.5f * shadow, 0f, 1f * shadow, 0x66000000)
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
}
