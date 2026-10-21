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
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioButton
import com.simplecoil.simplecoil.ui.Spelling
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.databinding.DialogGameModeBinding
import com.simplecoil.simplecoil.domain.briefing.Briefing
import com.simplecoil.simplecoil.domain.briefing.SlideArt
import com.simplecoil.simplecoil.domain.map.DistanceUnits
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.mode.BattleRoyaleRules
import com.simplecoil.simplecoil.domain.mode.BombRules
import com.simplecoil.simplecoil.domain.mode.ExtractionRules
import com.simplecoil.simplecoil.domain.mode.InfectionRules
import com.simplecoil.simplecoil.domain.mode.FlagRules
import com.simplecoil.simplecoil.domain.mode.GameMode
import com.simplecoil.simplecoil.domain.mode.MarksmanRules
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

        // Battle Royale and Marksman
        fun fillBattleRoyale(r: BattleRoyaleRules) {
            put(d.brPhasesEt, r.phases)
            put(d.brFirstWaitEt, r.firstWaitSeconds / 60)
            put(d.brShrinkEt, r.shrinkSeconds)
            put(d.brPauseEt, r.pauseSeconds)
            put(d.brFinalEt, units.fromMeters(r.finalDiameterMeters).roundToInt())
            put(d.brDamageEt, r.damagePerTick)
            put(d.brTickEt, r.tickSeconds)
            d.brRisesSwitch.isChecked = r.damageRises
            put(d.brRespawnEt, r.respawnUntilPhase)
        }
        val brStart = config.battleRoyale
        fun readBattleRoyale(): BattleRoyaleRules = BattleRoyaleRules(
            phases = int(d.brPhasesEt, brStart.phases),
            firstWaitSeconds = d.brFirstWaitEt.text?.toString()?.toIntOrNull()?.times(60) ?: brStart.firstWaitSeconds,
            shrinkSeconds = int(d.brShrinkEt, brStart.shrinkSeconds),
            pauseSeconds = int(d.brPauseEt, brStart.pauseSeconds),
            finalDiameterMeters = d.brFinalEt.text?.toString()?.toDoubleOrNull()?.let { units.toMeters(it) } ?: brStart.finalDiameterMeters,
            damagePerTick = int(d.brDamageEt, brStart.damagePerTick),
            tickSeconds = int(d.brTickEt, brStart.tickSeconds),
            damageRises = d.brRisesSwitch.isChecked,
            respawnUntilPhase = int(d.brRespawnEt, brStart.respawnUntilPhase),
        ).clamped()
        fun fillMarksman(r: MarksmanRules) {
            put(d.marksmanMinimumEt, units.fromMeters(r.minimumMeters).roundToInt())
            put(d.marksmanToWinEt, units.fromMeters(r.distanceToWinMeters).roundToInt())
        }
        fun readMarksman(): MarksmanRules = MarksmanRules(
            minimumMeters = d.marksmanMinimumEt.text?.toString()?.toDoubleOrNull()?.let { units.toMeters(it) } ?: config.marksman.minimumMeters,
            distanceToWinMeters = d.marksmanToWinEt.text?.toString()?.toDoubleOrNull()?.let { units.toMeters(it) } ?: config.marksman.distanceToWinMeters,
        ).clamped()
        d.brFinalLabel.text = context.getString(R.string.br_final, unit)
        d.marksmanMinimumLabel.text = context.getString(R.string.marksman_minimum, unit)
        d.marksmanToWinLabel.text = context.getString(R.string.marksman_to_win, unit)

        fun fillFlag(r: FlagRules) {
            put(d.ctfToWinEt, r.capturesToWin)
            put(d.ctfShownEt, r.carrierShownEverySeconds)
            put(d.ctfReturnsEt, r.droppedReturnsSeconds)
            d.ctfOwnHomeSwitch.isChecked = r.ownFlagHomeToScore
            d.ctfOneFlagSwitch.isChecked = r.oneFlag
            // With one flag there's no flag of your own to have home
            d.ctfOwnHomeSwitch.isVisible = !r.oneFlag
            put(d.ctfRadiusEt, units.fromMeters(r.baseRadiusMeters).roundToInt())
        }
        fun readFlag(): FlagRules = FlagRules(
            capturesToWin = int(d.ctfToWinEt, config.flag.capturesToWin),
            carrierShownEverySeconds = int(d.ctfShownEt, config.flag.carrierShownEverySeconds),
            droppedReturnsSeconds = int(d.ctfReturnsEt, config.flag.droppedReturnsSeconds),
            ownFlagHomeToScore = d.ctfOwnHomeSwitch.isChecked,
            oneFlag = d.ctfOneFlagSwitch.isChecked,
            baseRadiusMeters = d.ctfRadiusEt.text?.toString()?.toDoubleOrNull()?.let { units.toMeters(it) } ?: config.flag.baseRadiusMeters,
        ).clamped()
        fun fillExtraction(r: ExtractionRules) {
            put(d.exLootEt, r.lootPoints)
            put(d.exCollectEt, r.collectSeconds)
            put(d.exOpensEt, r.opensAfterMinutes)
            put(d.exZonesEt, r.zones)
            put(d.exToWinEt, r.lootToWin)
            put(d.exRadiusEt, units.fromMeters(r.radiusMeters).roundToInt())
        }
        fun readExtraction(): ExtractionRules = ExtractionRules(
            lootPoints = int(d.exLootEt, config.extraction.lootPoints),
            collectSeconds = int(d.exCollectEt, config.extraction.collectSeconds),
            opensAfterMinutes = int(d.exOpensEt, config.extraction.opensAfterMinutes),
            zones = int(d.exZonesEt, config.extraction.zones),
            lootToWin = int(d.exToWinEt, config.extraction.lootToWin),
            radiusMeters = d.exRadiusEt.text?.toString()?.toDoubleOrNull()?.let { units.toMeters(it) } ?: config.extraction.radiusMeters,
        ).clamped()
        fun fillBomb(r: BombRules) {
            put(d.sndrToWinEt, r.roundsToWin)
            put(d.sndrRoundEt, r.roundSeconds)
            put(d.sndrPlantEt, r.plantSeconds)
            put(d.sndrDefuseEt, r.defuseSeconds)
            put(d.sndrFuseEt, r.fuseSeconds)
            put(d.sndrSitesEt, r.sites)
            d.sndrSwapSwitch.isChecked = r.swapSides
            put(d.sndrBetweenEt, r.betweenRoundsSeconds)
            put(d.sndrRadiusEt, units.fromMeters(r.siteRadiusMeters).roundToInt())
        }
        fun readBomb(): BombRules = BombRules(
            roundsToWin = int(d.sndrToWinEt, config.bomb.roundsToWin),
            roundSeconds = int(d.sndrRoundEt, config.bomb.roundSeconds),
            plantSeconds = int(d.sndrPlantEt, config.bomb.plantSeconds),
            defuseSeconds = int(d.sndrDefuseEt, config.bomb.defuseSeconds),
            fuseSeconds = int(d.sndrFuseEt, config.bomb.fuseSeconds),
            sites = int(d.sndrSitesEt, config.bomb.sites),
            swapSides = d.sndrSwapSwitch.isChecked,
            siteRadiusMeters = d.sndrRadiusEt.text?.toString()?.toDoubleOrNull()?.let { units.toMeters(it) } ?: config.bomb.siteRadiusMeters,
            betweenRoundsSeconds = int(d.sndrBetweenEt, config.bomb.betweenRoundsSeconds),
        ).clamped()
        fun fillInfection(r: InfectionRules) {
            put(d.infrStartingEt, r.startingInfected)
            put(d.infrRevealEt, r.revealEverySeconds)
            put(d.infrMinutesEt, r.roundMinutes)
        }
        fun readInfection(): InfectionRules = InfectionRules(
            startingInfected = int(d.infrStartingEt, config.infection.startingInfected),
            revealEverySeconds = int(d.infrRevealEt, config.infection.revealEverySeconds),
            roundMinutes = int(d.infrMinutesEt, config.infection.roundMinutes),
        ).clamped()
        d.sndrRadiusLabel.text = context.getString(R.string.sndr_radius, unit)
        d.ctfRadiusLabel.text = context.getString(R.string.ctf_radius, unit)
        d.exRadiusLabel.text = context.getString(R.string.ex_radius, unit)

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

        // The blurb and picture: the how-to-play slides' first game slide, for the settings as they are in the dialog now
        fun showAbout() {
            val now = config.copy(mode = mode, objectiveRules = if (mode.hasObjectives) read() else config.objectiveRules,
                battleRoyale = readBattleRoyale(), marksman = readMarksman(), flag = readFlag(), extraction = readExtraction(), bomb = readBomb(), infection = readInfection())
            val slide = Briefing.goalSlide(now)
            d.modeAboutTv.text = Spelling.local(context, slide.body)
            d.modeAboutArt.art = slide.art
            d.modeAboutArt.feet = feet
            d.modeAboutArt.fuseSeconds = now.bomb.fuseSeconds
        }

        // Only what applies: no settings for Classic, the move timer only for Hardpoint
        fun showMode() {
            showAbout()
            val domination = mode == GameMode.DOMINATION
            val hardpoint = mode == GameMode.HARDPOINT
            val checkedId = d.modeGroup.checkedRadioButtonId
            val checkedView = d.modeGroup.findViewById<android.view.View>(checkedId)
            if (checkedView != null) {
                d.modeGroup.removeView(d.modeAboutCard)
                val index = d.modeGroup.indexOfChild(checkedView)
                if (index >= 0) {
                    d.modeGroup.addView(d.modeAboutCard, index + 1)
                }
            }
            d.modeSettings.isVisible = mode.hasObjectives
            d.brSettings.isVisible = mode == GameMode.BATTLE_ROYALE
            d.marksmanSettings.isVisible = mode == GameMode.MARKSMAN
            d.ctfSettings.isVisible = mode == GameMode.CAPTURE_THE_FLAG
            d.extractionSettings.isVisible = mode == GameMode.EXTRACTION
            d.sndSettings.isVisible = mode == GameMode.SEARCH_AND_DESTROY
            d.infectionSettings.isVisible = mode == GameMode.INFECTION
            d.modeResetButton.isVisible = mode != GameMode.CLASSIC
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
            GameMode.BATTLE_ROYALE -> R.id.mode_battle_royale
            GameMode.MARKSMAN -> R.id.mode_marksman
            GameMode.CAPTURE_THE_FLAG -> R.id.mode_ctf
            GameMode.EXTRACTION -> R.id.mode_extraction
            GameMode.SEARCH_AND_DESTROY -> R.id.mode_snd
            GameMode.INFECTION -> R.id.mode_infection
        })
        fill(start)
        fillBattleRoyale(brStart)
        fillMarksman(config.marksman)
        fillFlag(config.flag)
        fillExtraction(config.extraction)
        fillBomb(config.bomb)
        fillInfection(config.infection)
        showMode()
        markChanged()
        d.modeGroup.setOnCheckedChangeListener { _, id ->
            // Picking a mode loads its settings as last saved, or its defaults
            if (mode.hasObjectives) remembered[mode] = read()
            mode = when (id) {
                R.id.mode_domination -> GameMode.DOMINATION
                R.id.mode_hardpoint -> GameMode.HARDPOINT
                R.id.mode_battle_royale -> GameMode.BATTLE_ROYALE
                R.id.mode_marksman -> GameMode.MARKSMAN
                R.id.mode_ctf -> GameMode.CAPTURE_THE_FLAG
                R.id.mode_extraction -> GameMode.EXTRACTION
                R.id.mode_snd -> GameMode.SEARCH_AND_DESTROY
                R.id.mode_infection -> GameMode.INFECTION
                else -> GameMode.CLASSIC
            }
            if (mode.hasObjectives) fill(remembered[mode] ?: ObjectiveRules.defaultsFor(mode))
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

        val dialog = AlertDialog.Builder(context, R.style.AppDialogTheme)
            .setView(d.root)
            .setPositiveButton(R.string.field_dialog_save) { _, _ ->
                if (mode.needsBorder && config.border == null) {
                    Toast.makeText(context.applicationContext, R.string.mode_needs_border, Toast.LENGTH_LONG).show()
                }
                val rules = if (mode.hasObjectives) read() else config.objectiveRules
                if (mode.hasObjectives) remembered[mode] = rules
                onSave(config.copy(mode = mode, objectiveRules = rules, modeRules = remembered.toMap(),
                    battleRoyale = readBattleRoyale(), marksman = readMarksman(), flag = readFlag(), extraction = readExtraction(), bomb = readBomb(), infection = readInfection()))
            }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .create()
        d.ctfOneFlagSwitch.setOnCheckedChangeListener { _, on -> d.ctfOwnHomeSwitch.isVisible = !on }
        // Every setting typed or switched updates the blurb and picture, like the slides would show it
        val aboutWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = showAbout()
        }
        fun watch(view: View) {
            when (view) {
                is EditText -> view.addTextChangedListener(aboutWatcher)
                is RadioButton -> {}
                is CompoundButton -> view.setOnClickListener { showAbout() }
                is ViewGroup -> for (i in 0 until view.childCount) watch(view.getChildAt(i))
            }
        }
        watch(d.root)
        d.modeResetButton.setOnClickListener {
            when (mode) {
                GameMode.BATTLE_ROYALE -> fillBattleRoyale(BattleRoyaleRules())
                GameMode.MARKSMAN -> fillMarksman(MarksmanRules())
                GameMode.CAPTURE_THE_FLAG -> fillFlag(FlagRules())
                GameMode.EXTRACTION -> fillExtraction(ExtractionRules())
                GameMode.SEARCH_AND_DESTROY -> fillBomb(BombRules())
                GameMode.INFECTION -> fillInfection(InfectionRules())
                else -> fill(ObjectiveRules.defaultsFor(mode))
            }
            markChanged()
        }
        dialog.show()
    }

    private const val SQUARE_FEET_PER_SQUARE_METER = 10.7639
}
