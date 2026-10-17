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
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.domain.loadout.Drop
import com.simplecoil.simplecoil.domain.loadout.DropKind
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Projection
import java.util.Locale

/**
 * The drops on the play screen's map (map design: Drops): a crate for each, drawn over the map
 * like the player markers. Until it lands it is faded, with a countdown under it.
 */
class DropOverlay(private val context: Context, private val layer: FrameLayout) {

    private class Marker(val view: LinearLayout, val countdown: TextView, var drop: Drop)

    private val markers = HashMap<String, Marker>()
    private val bitmaps = HashMap<DropKind, Bitmap>()

    /** Shows [drops] as they stand at [serverNow]. */
    fun update(drops: List<Drop>, serverNow: Long) {
        val ids = drops.map { it.id }.toSet()
        markers.keys.filter { it !in ids }.forEach { layer.removeView(markers.remove(it)?.view) }
        for (drop in drops) {
            val marker = markers.getOrPut(drop.id) { create(drop) }
            marker.drop = drop
            val landed = drop.isLanded(serverNow)
            marker.view.alpha = if (landed) 1f else PENDING_ALPHA
            if (landed) {
                marker.countdown.visibility = TextView.GONE
            } else {
                val seconds = ((drop.landsAt - serverNow + 999) / 1000).coerceAtLeast(0)
                marker.countdown.text = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                marker.countdown.visibility = TextView.VISIBLE
            }
        }
    }

    /** Puts every crate where its drop is on screen; every frame. */
    fun place(projection: Projection) {
        for (marker in markers.values) {
            val spot = projection.toScreenLocation(LatLng(marker.drop.point.latitude, marker.drop.point.longitude))
            // The crate's middle on the spot; the countdown hangs below
            marker.view.translationX = spot.x - marker.view.width / 2f
            marker.view.translationY = spot.y - CRATE_DP / 2 * context.resources.displayMetrics.density
        }
    }

    fun clear() {
        layer.removeAllViews()
        markers.clear()
    }

    private fun create(drop: Drop): Marker {
        val image = ImageView(context).apply { setImageBitmap(bitmaps.getOrPut(drop.kind) { MapIcons.drop(context, color(drop.kind), label(context, drop.kind)) }) }
        val countdown = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(3f, 0f, 1f, 0xCC000000.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(image)
            addView(countdown)
        }
        layer.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Marker(view, countdown, drop)
    }

    companion object {
        private const val PENDING_ALPHA = 0.55f
        private const val CRATE_DP = 40f

        fun color(kind: DropKind): Int = when (kind) {
            DropKind.SUPPLY -> 0xFFFF6D00.toInt()
            DropKind.BURST -> 0xFFFFB300.toInt()
            DropKind.AMMO, DropKind.SCAVENGE -> 0xFF6D4C41.toInt()
            DropKind.MEDKIT -> 0xFFE53935.toInt()
            DropKind.EXTRA_LIFE -> 0xFF43A047.toInt()
            DropKind.OVERSHIELD -> 0xFF00ACC1.toInt()
            DropKind.UAV -> 0xFF7E57C2.toInt()
        }

        /** The few letters on a crate. */
        fun label(context: Context, kind: DropKind): String = context.getString(when (kind) {
            DropKind.SUPPLY -> R.string.drop_label_supply
            DropKind.BURST -> R.string.drop_label_burst
            DropKind.AMMO, DropKind.SCAVENGE -> R.string.drop_label_ammo
            DropKind.MEDKIT -> R.string.drop_label_medkit
            DropKind.EXTRA_LIFE -> R.string.drop_label_life
            DropKind.OVERSHIELD -> R.string.drop_label_shield
            DropKind.UAV -> R.string.drop_label_uav
        })

        /** What a drop gives, in words. */
        fun name(context: Context, kind: DropKind): String = context.getString(when (kind) {
            DropKind.SUPPLY -> R.string.drop_name_supply
            DropKind.BURST -> R.string.drop_name_burst
            DropKind.AMMO -> R.string.drop_name_ammo
            DropKind.SCAVENGE -> R.string.drop_name_scavenge
            DropKind.MEDKIT -> R.string.drop_name_medkit
            DropKind.EXTRA_LIFE -> R.string.drop_name_life
            DropKind.OVERSHIELD -> R.string.drop_name_shield
            DropKind.UAV -> R.string.drop_name_uav
        })
    }
}
