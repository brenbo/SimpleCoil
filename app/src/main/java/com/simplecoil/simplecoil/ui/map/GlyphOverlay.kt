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
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.simplecoil.simplecoil.domain.map.LatLon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Projection

/**
 * Small markers drawn over the play map, like the drops (map design: Capture the Flag): a symbol
 * in a colour on a spot, such as a team's flag.
 */
class GlyphOverlay(
    private val context: Context,
    private val layer: FrameLayout,
    /** The symbol's size. */
    private val textSp: Float = GLYPH_SP,
    /** A label under its point (a name), rather than a symbol standing on it. */
    private val below: Boolean = false,
) {

    data class Glyph(val id: String, val point: LatLon, val text: String, val color: Int)

    private val views = HashMap<String, Pair<TextView, Glyph>>()

    fun update(glyphs: List<Glyph>) {
        val ids = glyphs.map { it.id }.toSet()
        views.keys.filter { it !in ids }.forEach { layer.removeView(views.remove(it)?.first) }
        for (glyph in glyphs) {
            val view = views[glyph.id]?.first ?: TextView(context).apply {
                textSize = textSp
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 1f, 0xDD000000.toInt())
                layer.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            view.text = glyph.text
            view.setTextColor(glyph.color)
            views[glyph.id] = view to glyph
        }
    }

    /** Every frame: each glyph stands on its spot, its foot on the point. */
    fun place(projection: Projection) {
        for ((view, glyph) in views.values) {
            val spot = projection.toScreenLocation(LatLng(glyph.point.latitude, glyph.point.longitude))
            view.translationX = spot.x - view.width / 2f
            view.translationY = if (below) spot.y + LABEL_GAP_DP * context.resources.displayMetrics.density else spot.y - view.height * 0.85f
        }
    }

    fun clear() {
        layer.removeAllViews()
        views.clear()
    }

    private companion object {
        const val GLYPH_SP = 26f
        const val LABEL_GAP_DP = 8f
    }
}
