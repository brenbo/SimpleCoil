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

package com.simplecoil.simplecoil.ui

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * Phones stay upright, as the screens are laid out for that. A tablet or a Chromebook is free:
 * locked to portrait in the manifest, ChromeOS would show the app as a phone-sized window in the
 * middle of the screen.
 */
object ScreenOrientation {
    /** The smallest width, in dp, of a tablet or Chromebook rather than a phone. */
    const val LARGE_SCREEN_DP = 600

    @android.annotation.SuppressLint("SourceLockedOrientationActivity") // phones only, on purpose
    fun portraitOnPhones(activity: Activity) {
        val phone = activity.resources.configuration.smallestScreenWidthDp < LARGE_SCREEN_DP
        val wanted = if (phone) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (activity.requestedOrientation != wanted) activity.requestedOrientation = wanted
    }
}
