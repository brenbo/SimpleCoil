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

package com.simplecoil.simplecoil.ui.game

import android.content.Context
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.simplecoil.simplecoil.R

/** About SimpleCoil (the terms, permissions and open source licenses), from the home screen's info button. */
object AboutDialog {

    fun show(context: Context) {
        val page = WebView(context).apply {
            // The dialog's own color under the page, so it doesn't flash white while it loads
            setBackgroundColor(ContextCompat.getColor(context, R.color.sc_surface))
            loadUrl("file:///android_asset/simplecoil.html")
        }
        AlertDialog.Builder(context, R.style.AppDialogTheme)
            .setTitle(R.string.info_sheet_title)
            .setView(page)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
