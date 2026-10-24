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
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.domain.map.MapTilerImagery

/**
 * The imagery's credit on a map (map design: Map engine): "© MapTiler © OpenStreetMap
 * contributors" for MapTiler's imagery, "Data provided by USGS" for USGS's, and the full credit
 * when it is tapped.
 */
object MapCredit {
    /** Shows the credit for MapTiler's imagery if [mapTiler], otherwise USGS's. */
    fun attach(label: TextView, mapTiler: Boolean) {
        if (mapTiler) label.text = MapTilerImagery.CREDIT else label.setText(R.string.map_credit_short)
        label.setOnClickListener { show(label.context, mapTiler) }
    }

    /** Whether a field photo's credit line, [credit], is MapTiler's. */
    fun isMapTiler(credit: String): Boolean = credit.contains("MapTiler")

    fun show(context: Context, mapTiler: Boolean) {
        AlertDialog.Builder(context, R.style.AppDialogTheme)
            .setTitle(R.string.map_credit_title)
            .setMessage(if (mapTiler) R.string.map_imagery_credit_maptiler else R.string.map_imagery_credit)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
