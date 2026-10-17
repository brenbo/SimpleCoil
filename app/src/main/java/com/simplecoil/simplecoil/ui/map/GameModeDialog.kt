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
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.databinding.DialogGameModeBinding
import com.simplecoil.simplecoil.domain.map.DistanceUnits
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.mode.GameMode
import com.simplecoil.simplecoil.domain.mode.ObjectiveRules
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The game mode sheet on the dedicated server (map design: Game modes, Dedicated server
 * settings): pick a mode and its defaults load; the common settings show, the rest under
 * Advanced; a changed setting is marked with a dot; Reset puts the mode's defaults back.
 */
object GameModeDialog {

    fun show(context: Context, config: FieldConfig, onSave: (FieldConfig) -> Unit) {
        val d = DialogGameModeBinding.inflate(LayoutInflater.from(context))
        val units = config.rules.units
        val feet = units == DistanceUnits.FEET
        val unit = if (feet) "ft" else "m"
        val areaUnit = if (feet) "sq ft" else "m²"
        val areaFactor = if (feet) SQUARE_FEET_PER_SQUARE_METER else 1.0
        var mode = config.mode
        val start = config.objectiveRules
        // Each mode keeps its own settings: switching to another and back finds them as they were
        val remembered = config.modeRules.toMutableMap()
        if (config.mode.hasObjectives) remembered[config.mode] = start

        fun put(field: EditText, value: Int) = field.setText(String.format(Locale.ROOT, "%d", value))
        fun fill(r: ObjectiveRules) {
            put(d.modeToWinEt, r.scoreToWin)
            put(d.modePointsEt, r.pointCount)
            put(d.modeCaptureEt, r.captureSeconds)
            put(d.modeMoveEt, r.moveSeconds)
            put(d.modeRadiusEt, units.fromMeters(r.radiusMeters).roundToInt())
            put(d.modePerTickEt, r.pointsPerTick)
            put(d.modeTickEt, r.tickSeconds)
            d.modeFasterSwitch.isChecked = r.moreCaptureFaster
            put(d.modeShowNextEt, r.showNextSeconds)
            put(d.modeSmallEt, (r.smallFieldSquareMeters * areaFactor).roundToInt())
            put(d.modeLargeEt, (r.largeFieldSquareMeters * areaFactor).roundToInt())
        }
        fun int(field: EditText, fallback: Int) = field.text?.toString()?.toIntOrNull() ?: fallback
        fun read(): ObjectiveRules = ObjectiveRules(
            pointCount = int(d.modePointsEt, start.pointCount),
            smallFieldSquareMeters = d.modeSmallEt.text?.toString()?.toDoubleOrNull()?.div(areaFactor) ?: start.smallFieldSquareMeters,
            largeFieldSquareMeters = d.modeLargeEt.text?.toString()?.toDoubleOrNull()?.div(areaFactor) ?: start.largeFieldSquareMeters,
            radiusMeters = d.modeRadiusEt.text?.toString()?.toDoubleOrNull()?.let { units.toMeters(it) } ?: start.radiusMeters,
            captureSeconds = int(d.modeCaptureEt, start.captureSeconds),
            moreCaptureFaster = d.modeFasterSwitch.isChecked,
            pointsPerTick = int(d.modePerTickEt, start.pointsPerTick),
            tickSeconds = int(d.modeTickEt, start.tickSeconds),
            scoreToWin = int(d.modeToWinEt, start.scoreToWin),
            moveSeconds = int(d.modeMoveEt, start.moveSeconds),
            showNextSeconds = int(d.modeShowNextEt, start.showNextSeconds),
        ).clamped()

        // Each setting's label, and how to tell if it differs from the mode's default
        class Row(val label: TextView, val base: String, val changed: (ObjectiveRules, ObjectiveRules) -> Boolean)
        val rows = listOf(
            Row(d.modeToWinLabel, context.getString(R.string.mode_score_to_win)) { a, b -> a.scoreToWin != b.scoreToWin },
            Row(d.modePointsLabel, context.getString(R.string.mode_points)) { a, b -> a.pointCount != b.pointCount },
            Row(d.modeCaptureLabel, context.getString(R.string.mode_capture_seconds)) { a, b -> a.captureSeconds != b.captureSeconds },
            Row(d.modeMoveLabel, context.getString(R.string.mode_move_seconds)) { a, b -> a.moveSeconds != b.moveSeconds },
            Row(d.modeRadiusLabel, context.getString(R.string.mode_radius, unit)) { a, b -> (a.radiusMeters - b.radiusMeters).let { it > 0.5 || it < -0.5 } },
            Row(d.modePerTickLabel, context.getString(R.string.mode_points_per_tick)) { a, b -> a.pointsPerTick != b.pointsPerTick },
            Row(d.modeTickLabel, context.getString(R.string.mode_tick_seconds)) { a, b -> a.tickSeconds != b.tickSeconds },
            Row(d.modeShowNextLabel, context.getString(R.string.mode_show_next)) { a, b -> a.showNextSeconds != b.showNextSeconds },
            Row(d.modeSmallLabel, context.getString(R.string.mode_small_field, areaUnit)) { a, b -> (a.smallFieldSquareMeters - b.smallFieldSquareMeters).let { it > 1 || it < -1 } },
            Row(d.modeLargeLabel, context.getString(R.string.mode_large_field, areaUnit)) { a, b -> (a.largeFieldSquareMeters - b.largeFieldSquareMeters).let { it > 1 || it < -1 } },
        )
        fun markChanged() {
            val now = read()
            val defaults = ObjectiveRules.defaultsFor(mode)
            for (row in rows) row.label.text = if (row.changed(now, defaults)) context.getString(R.string.mode_changed, row.base) else row.base
            d.modeFasterSwitch.text = if (now.moreCaptureFaster != defaults.moreCaptureFaster) {
                context.getString(R.string.mode_changed, context.getString(R.string.mode_more_faster))
            } else {
                context.getString(R.string.mode_more_faster)
            }
        }

        // Only what applies: no settings for Classic, the move timer only for Hardpoint
        fun showMode() {
            val domination = mode == GameMode.DOMINATION
            val hardpoint = mode == GameMode.HARDPOINT
            d.modeAboutTv.setText(when (mode) {
                GameMode.CLASSIC -> R.string.mode_about_classic
                GameMode.DOMINATION -> R.string.mode_about_domination
                GameMode.HARDPOINT -> R.string.mode_about_hardpoint
            })
            d.modeSettings.isVisible = mode.hasObjectives
            // Hardpoint moves between spots chosen by field size: one is live at a time
            d.modePointsLabel.isVisible = domination
            d.modePointsEt.isVisible = domination
            d.modeCaptureLabel.isVisible = domination
            d.modeCaptureEt.isVisible = domination
            d.modeFasterSwitch.isVisible = domination
            d.modeMoveLabel.isVisible = hardpoint
            d.modeMoveEt.isVisible = hardpoint
            d.modeShowNextLabel.isVisible = hardpoint
            d.modeShowNextEt.isVisible = hardpoint
        }

        d.modeGroup.check(when (mode) {
            GameMode.CLASSIC -> R.id.mode_classic
            GameMode.DOMINATION -> R.id.mode_domination
            GameMode.HARDPOINT -> R.id.mode_hardpoint
        })
        fill(start)
        showMode()
        markChanged()
        d.modeGroup.setOnCheckedChangeListener { _, id ->
            // Picking a mode loads its settings as last saved, or its defaults
            if (mode.hasObjectives) remembered[mode] = read()
            mode = when (id) {
                R.id.mode_domination -> GameMode.DOMINATION
                R.id.mode_hardpoint -> GameMode.HARDPOINT
                else -> GameMode.CLASSIC
            }
            fill(remembered[mode] ?: ObjectiveRules.defaultsFor(mode))
            showMode()
            markChanged()
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = markChanged()
        }
        listOf(d.modeToWinEt, d.modePointsEt, d.modeCaptureEt, d.modeMoveEt, d.modeRadiusEt, d.modePerTickEt, d.modeTickEt,
            d.modeShowNextEt, d.modeSmallEt, d.modeLargeEt).forEach { it.addTextChangedListener(watcher) }
        d.modeFasterSwitch.setOnCheckedChangeListener { _, _ -> markChanged() }
        d.modeAdvancedTv.setOnClickListener {
            d.modeAdvanced.isVisible = !d.modeAdvanced.isVisible
            d.modeAdvancedTv.setText(if (d.modeAdvanced.isVisible) R.string.mode_advanced_open else R.string.mode_advanced_closed)
        }

        val dialog = AlertDialog.Builder(context, R.style.AppDialogTheme)
            .setView(d.root)
            .setPositiveButton(R.string.field_dialog_save) { _, _ ->
                if (mode.hasObjectives && config.border == null) {
                    Toast.makeText(context.applicationContext, R.string.mode_needs_border, Toast.LENGTH_LONG).show()
                }
                val rules = if (mode.hasObjectives) read() else config.objectiveRules
                if (mode.hasObjectives) remembered[mode] = rules
                onSave(config.copy(mode = mode, objectiveRules = rules, modeRules = remembered.toMap()))
            }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .create()
        d.modeResetButton.setOnClickListener {
            fill(ObjectiveRules.defaultsFor(mode))
            markChanged()
        }
        dialog.show()
    }

    private const val SQUARE_FEET_PER_SQUARE_METER = 10.7639
}
