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
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import com.simplecoil.simplecoil.domain.mode.ObjectiveState
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Projection
import kotlin.math.max

/**
 * The capture points on the play screen's map (map design: Domination, Hardpoint), drawn over
 * the map like the drops: a circle the point's real size in its holder's colour, a ring filling
 * as a team takes it, and its letter. In Hardpoint only the live point shows, and the next one
 * dashed shortly before it opens.
 */
class ObjectiveOverlay(private val context: Context, private val layer: FrameLayout, private val colorOf: (team: Int) -> Int) {

    private val views = HashMap<String, PointView>()
    private var radiusMeters = 10.0

    /** Shows [objectives], each [radius] metres in radius. */
    fun update(objectives: List<ObjectiveState>, radius: Double) {
        radiusMeters = radius
        val shown = objectives.filter { it.active || it.next }
        val ids = shown.map { it.id }.toSet()
        views.keys.filter { it !in ids }.forEach { layer.removeView(views.remove(it)) }
        for (o in shown) {
            val view = views.getOrPut(o.id) { PointView(context).also { layer.addView(it, FrameLayout.LayoutParams(0, 0)) } }
            view.state = o
            view.invalidate()
        }
    }

    /** Puts every point where it is, at its size on screen; every frame. */
    fun place(projection: Projection) {
        for (view in views.values) {
            val o = view.state ?: continue
            val spot = projection.toScreenLocation(LatLng(o.point.latitude, o.point.longitude))
            val metersPerPixel = projection.getMetersPerPixelAtLatitude(o.point.latitude)
            // Never smaller than a tap target, so a point stays visible zoomed out
            val radiusPx = max((radiusMeters / metersPerPixel).toFloat(), MIN_RADIUS_DP * context.resources.displayMetrics.density)
            val size = (radiusPx * 2).toInt()
            val params = view.layoutParams
            if (params.width != size) {
                params.width = size
                params.height = size
                view.layoutParams = params
            }
            view.translationX = spot.x - radiusPx
            view.translationY = spot.y - radiusPx
        }
    }

    fun clear() {
        layer.removeAllViews()
        views.clear()
    }

    private inner class PointView(context: Context) : View(context) {
        var state: ObjectiveState? = null
        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f * density; strokeCap = Paint.Cap.ROUND }
        private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f * density, 0f, 1f * density, 0xCC000000.toInt())
        }
        private val dashes = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            val o = state ?: return
            val r = width / 2f
            val color = if (o.owner == 0) NEUTRAL else colorOf(o.owner)
            val faded = o.next && !o.active
            fill.color = (color and 0x00FFFFFF) or (if (faded) 0x22000000 else 0x55000000)
            canvas.drawCircle(r, r, r - edge.strokeWidth, fill)
            edge.color = if (o.contested) CONTESTED else color
            edge.pathEffect = if (faded) dashes else null
            canvas.drawCircle(r, r, r - edge.strokeWidth, edge)
            if (o.capturing != 0 && o.progress > 0) {
                ring.color = colorOf(o.capturing)
                val inset = ring.strokeWidth
                box.set(inset, inset, width - inset, height - inset)
                canvas.drawArc(box, -90f, (o.progress * 360).toFloat(), false, ring)
            }
            letter.textSize = (r * 0.9f).coerceIn(12f * density, 28f * density)
            canvas.drawText(o.id, r, r - (letter.descent() + letter.ascent()) / 2, letter)
        }
    }

    private companion object {
        const val MIN_RADIUS_DP = 14f
        const val NEUTRAL = 0xFFECEFF1.toInt()
        const val CONTESTED = 0xFFFFEB3B.toInt()
    }
}
