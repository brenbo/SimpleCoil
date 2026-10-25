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

import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.animation.doOnEnd
import androidx.core.graphics.toColorInt
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.domain.map.AutoZoom

/**
 * The map's auto zoom button and its menu (map design: Auto zoom), shared by the player's map
 * and the server's: the button shows the mode in use, orange while it zooms by itself; a tap
 * stretches it down into a pill of the [modes] to pick from.
 */
object ZoomMenu {
    private const val ACTIVE_COLOR = "#FF6A1A"
    private const val OPEN_MILLIS = 180L

    /** Each mode's icon and name. */
    fun look(zoom: AutoZoom) = when (zoom) {
        AutoZoom.OFF -> R.drawable.ic_map_zoom_off to R.string.map_zoom_off
        AutoZoom.YOU -> R.drawable.ic_map_zoom_you to R.string.map_zoom_you
        AutoZoom.PLAYERS -> R.drawable.ic_map_zoom_players to R.string.map_zoom_players
        AutoZoom.FIELD -> R.drawable.ic_map_zoom_field to R.string.map_zoom_field
    }

    /** Shows [zoom] on [button]. */
    fun style(button: ImageButton, zoom: AutoZoom) {
        val (icon, description) = look(zoom)
        button.setImageResource(icon)
        button.contentDescription = button.context.getString(description)
        // Orange while it is zooming by itself
        if (zoom == AutoZoom.OFF) button.clearColorFilter() else button.setColorFilter(ACTIVE_COLOR.toColorInt())
    }

    /**
     * The modes to pick from, over [anchor]: the round button stretches down into a pill of
     * icons, each as the button shows it, the one in use ([current]) on a dark-orange dot.
     */
    fun show(anchor: View, modes: List<AutoZoom>, current: AutoZoom, onPick: (AutoZoom) -> Unit) {
        val context = anchor.context
        val dp = context.resources.displayMetrics.density
        val size = anchor.width
        val full = size * modes.size
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_zoom_menu)
        }
        val popup = PopupWindow(column, size, full, true).apply {
            elevation = anchor.elevation
            animationStyle = 0
        }
        for (zoom in modes) {
            column.addView(ImageButton(context).apply {
                style(this, zoom)
                if (zoom == current) setBackgroundResource(R.drawable.bg_zoom_selected)
                else setBackgroundResource(android.R.color.transparent)
                val pad = (8 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener {
                    onPick(zoom)
                    popup.dismiss()
                }
            }, LinearLayout.LayoutParams(size, size))
        }
        // Right over the button, then grown down out of it
        val at = IntArray(2)
        anchor.getLocationInWindow(at)
        column.clipBounds = Rect(0, 0, size, size)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0], at[1])
        ValueAnimator.ofInt(size, full).apply {
            duration = OPEN_MILLIS
            interpolator = DecelerateInterpolator()
            addUpdateListener { column.clipBounds = Rect(0, 0, size, it.animatedValue as Int) }
            doOnEnd { column.clipBounds = null }
            start()
        }
    }
}
