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

import android.content.Context
import com.simplecoil.simplecoil.R

/**
 * Text written in code (the briefing's slides) is in American spelling; on a phone set to an
 * English that spells the British way (UK, Canada, Australia...), it's shown that way instead.
 * Which phones those are comes from resources (R.bool.british_spelling), like the strings.
 */
object Spelling {

    private val british = mapOf(
        "color" to "colour", "colors" to "colours", "colored" to "coloured",
        "meter" to "metre", "meters" to "metres", "kilometer" to "kilometre", "kilometers" to "kilometres",
        "center" to "centre", "centered" to "centred", "gray" to "grey",
        "canceled" to "cancelled", "favorite" to "favourite", "favorites" to "favourites",
        "armor" to "armour", "defense" to "defence", "offense" to "offence",
    )

    private val words = Regex("\\b(" + british.keys.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    /** [text] as this phone spells it. */
    fun local(context: Context, text: String): String =
        if (!context.resources.getBoolean(R.bool.british_spelling)) text else british(text)

    /** [text] in British spelling, keeping each word's capitals. */
    fun british(text: String): String = words.replace(text) { match ->
        val word = match.value
        val swapped = british.getValue(word.lowercase())
        when {
            word.all { !it.isLetter() || it.isUpperCase() } -> swapped.uppercase()
            word[0].isUpperCase() -> swapped.replaceFirstChar { it.uppercase() }
            else -> swapped
        }
    }
}
