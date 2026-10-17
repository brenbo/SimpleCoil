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

/**
 * The imagery's credit on a map: "Data provided by USGS", and the full acknowledgment USGS asks
 * for when it is tapped (map design: Map engine).
 */
object MapCredit {
    fun attach(label: TextView) {
        label.setText(R.string.map_credit_short)
        label.setOnClickListener { show(label.context) }
    }

    fun show(context: Context) {
        AlertDialog.Builder(context, R.style.AppDialogTheme)
            .setTitle(R.string.map_credit_title)
            .setMessage(R.string.map_imagery_credit)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
