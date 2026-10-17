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
import android.view.LayoutInflater
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.appcompat.app.AlertDialog
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.databinding.DialogBorderRulesBinding
import com.simplecoil.simplecoil.domain.loadout.AmmoMode
import com.simplecoil.simplecoil.domain.loadout.DropKind
import com.simplecoil.simplecoil.domain.loadout.LoadoutRules
import com.simplecoil.simplecoil.domain.map.BorderRules
import com.simplecoil.simplecoil.domain.map.DistanceUnits
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.OutOfBoundsPenalty
import com.simplecoil.simplecoil.domain.map.SpawnRules
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The game rules a dedicated server sets for its field: out of bounds, respawn, ammo and drops,
 * shot distance (map design: Server UI). Opened from the server's main screen.
 */
object FieldRulesDialog {

    /** Which part to show: the field's rules (out of bounds, respawn), or the extras (ammo, drops, shot distance). */
    enum class Section { FIELD, EXTRAS }

    /** Shows [config]'s rules; Save hands [onSave] the config with the new ones. */
    fun show(context: Context, config: FieldConfig, section: Section, onSave: (FieldConfig) -> Unit) {
        val dialog = DialogBorderRulesBinding.inflate(LayoutInflater.from(context))
        // GPS (and its units) shows with both: the extras have distances too
        val field = section == Section.FIELD
        dialog.rulesFieldGroup.isVisible = field
        dialog.rulesMapGroup.isVisible = field
        dialog.rulesExtrasGroup.isVisible = !field
        dialog.rulesTitleTv.setText(if (field) R.string.rules_title else R.string.rules_title_extras)
        val rules = config.rules
        val spawnRules = config.spawnRules
        var units = rules.units
        val fields = listOf(dialog.rulesMarginEt, dialog.rulesAccuracyEt, dialog.rulesBufferEt, dialog.rulesNearEt,
            dialog.rulesSpawnRadiusEt, dialog.rulesEnemyClearEt, dialog.rulesClaimRadiusEt, dialog.rulesMaxShotEt)
        val loadout = config.loadout
        fun showDistances(meters: List<Double>) {
            val unit = if (units == DistanceUnits.FEET) "ft" else "m"
            dialog.rulesMarginLabel.text = context.getString(R.string.rules_leave_margin, unit)
            dialog.rulesAccuracyLabel.text = context.getString(R.string.rules_accuracy_gate, unit)
            dialog.rulesBufferLabel.text = context.getString(R.string.rules_edge_buffer, unit)
            dialog.rulesNearLabel.text = context.getString(R.string.rules_near_distance, unit)
            dialog.rulesSpawnRadiusLabel.text = context.getString(R.string.rules_spawn_radius, unit)
            dialog.rulesEnemyClearLabel.text = context.getString(R.string.rules_enemy_clear, unit)
            dialog.rulesClaimRadiusLabel.text = context.getString(R.string.rules_claim_radius, unit)
            dialog.rulesMaxShotLabel.text = context.getString(R.string.rules_max_shot, unit)
            fields.zip(meters).forEach { (field, m) -> field.setText(number(units.fromMeters(m).roundToInt())) }
        }
        fun readMeters(field: EditText, fallback: Double) = field.text?.toString()?.toDoubleOrNull()?.let { units.toMeters(it) } ?: fallback
        fun readInt(field: EditText, fallback: Int) = field.text?.toString()?.toIntOrNull() ?: fallback
        val defaults = listOf(rules.leaveMarginMeters, rules.accuracyGateMeters, rules.edgeBufferMeters, rules.nearDistanceMeters,
            spawnRules.radiusMeters, spawnRules.enemyClearMeters, loadout.claimRadiusMeters, loadout.maxShotMeters)
        val dropBoxes = mapOf(
            DropKind.SUPPLY to dialog.rulesDropSupply, DropKind.BURST to dialog.rulesDropBurst, DropKind.AMMO to dialog.rulesDropAmmo,
            DropKind.MEDKIT to dialog.rulesDropMedkit, DropKind.EXTRA_LIFE to dialog.rulesDropLife,
            DropKind.OVERSHIELD to dialog.rulesDropShield, DropKind.UAV to dialog.rulesDropUav,
        )
        fun readAll() = fields.zip(defaults).map { (field, fallback) -> readMeters(field, fallback) }

        dialog.rulesPenaltyGroup.check(when (rules.penalty) {
            OutOfBoundsPenalty.DISABLE_BLASTER -> R.id.rules_penalty_disable
            OutOfBoundsPenalty.LOSE_HEALTH -> R.id.rules_penalty_health
            OutOfBoundsPenalty.ELIMINATED -> R.id.rules_penalty_eliminate
            OutOfBoundsPenalty.WARNING_ONLY -> R.id.rules_penalty_warning
        })
        dialog.rulesCountdownEt.setText(number(rules.countdownSeconds))
        dialog.rulesHealthEt.setText(number(rules.healthPerTick))
        dialog.rulesTickEt.setText(number(rules.tickSeconds))
        dialog.rulesGraceEt.setText(number(rules.graceSeconds))
        dialog.rulesOnlyNearSwitch.isChecked = rules.showOnlyWhenNear
        dialog.rulesWalkBackSwitch.isChecked = spawnRules.walkBack
        dialog.rulesStartAtSpawnSwitch.isChecked = spawnRules.startAtSpawn
        dialog.rulesShieldInSpawnSwitch.isChecked = spawnRules.shieldOnlyInSpawn
        dialog.rulesSecondsInSpawnEt.setText(number(spawnRules.secondsInSpawn))
        dialog.rulesPerTeamEt.setText(number(spawnRules.perTeam))
        dialog.rulesEnemyShareEt.setText(number((spawnRules.enemySpawnShare * 100).roundToInt()))
        dialog.rulesMoveMinutesEt.setText(number(spawnRules.moveMinutes))
        dialog.rulesCrossfadeEt.setText(number(spawnRules.crossfadeSeconds))
        dialog.rulesOldGraceEt.setText(number(spawnRules.oldSpawnGraceSeconds))
        dialog.rulesAmmoGroup.check(when (loadout.ammo) {
            AmmoMode.UNLIMITED -> R.id.rules_ammo_unlimited
            AmmoMode.LIMITED -> R.id.rules_ammo_limited
            AmmoMode.SCAVENGER -> R.id.rules_ammo_scavenger
        })
        dialog.rulesReserveEt.setText(number(loadout.startingReserve))
        dialog.rulesRefillSwitch.isChecked = loadout.refillAtSpawn
        dialog.rulesScavengeSecondsEt.setText(number(loadout.scavengeSeconds))
        dialog.rulesScavengeMagsEt.setText(number(loadout.scavengeMagazines))
        dialog.rulesDropsSwitch.isChecked = loadout.dropsEnabled
        dropBoxes.forEach { (kind, box) -> box.isChecked = kind in loadout.dropKinds }
        dialog.rulesDropIntervalEt.setText(number(loadout.dropIntervalMinutes))
        dialog.rulesDropWarningEt.setText(number(loadout.dropWarningSeconds))
        dialog.rulesClaimSecondsEt.setText(number(loadout.claimSeconds))
        dialog.rulesDropLastsEt.setText(number(loadout.dropLastsMinutes))
        dialog.rulesNearestTeamSwitch.isChecked = loadout.dropsNearestTeamOnly
        dialog.rulesShieldHitsEt.setText(number(loadout.overshieldHits))
        dialog.rulesUavSecondsEt.setText(number(loadout.uavSeconds))
        dialog.rulesKillstreaksSwitch.isChecked = loadout.killstreaks
        dialog.rulesUavKillsEt.setText(number(loadout.uavKills))
        dialog.rulesAutoKillsEt.setText(number(loadout.fullAutoKills))
        dialog.rulesTrackShotsSwitch.isChecked = loadout.trackShots
        dialog.rulesLongestShotSwitch.isChecked = loadout.longestShotAward
        dialog.rulesUnitsGroup.check(if (units == DistanceUnits.FEET) R.id.rules_units_feet else R.id.rules_units_meters)
        showDistances(defaults)
        dialog.rulesUnitsGroup.setOnCheckedChangeListener { _, id ->
            // Keep the distances, shown in the new units
            val meters = readAll()
            units = if (id == R.id.rules_units_feet) DistanceUnits.FEET else DistanceUnits.METERS
            showDistances(meters)
        }

        AlertDialog.Builder(context, R.style.AppDialogTheme)
            .setView(dialog.root)
            .setPositiveButton(R.string.field_dialog_save) { _, _ ->
                val distances = readAll()
                val updated = BorderRules(
                    countdownSeconds = readInt(dialog.rulesCountdownEt, rules.countdownSeconds),
                    penalty = when (dialog.rulesPenaltyGroup.checkedRadioButtonId) {
                        R.id.rules_penalty_health -> OutOfBoundsPenalty.LOSE_HEALTH
                        R.id.rules_penalty_eliminate -> OutOfBoundsPenalty.ELIMINATED
                        R.id.rules_penalty_warning -> OutOfBoundsPenalty.WARNING_ONLY
                        else -> OutOfBoundsPenalty.DISABLE_BLASTER
                    },
                    healthPerTick = readInt(dialog.rulesHealthEt, rules.healthPerTick),
                    tickSeconds = readInt(dialog.rulesTickEt, rules.tickSeconds),
                    showOnlyWhenNear = dialog.rulesOnlyNearSwitch.isChecked,
                    nearDistanceMeters = distances[3],
                    graceSeconds = readInt(dialog.rulesGraceEt, rules.graceSeconds),
                    leaveMarginMeters = distances[0],
                    accuracyGateMeters = distances[1],
                    edgeBufferMeters = distances[2],
                    units = units,
                ).clamped()
                val updatedSpawns = SpawnRules(
                    walkBack = dialog.rulesWalkBackSwitch.isChecked,
                    startAtSpawn = dialog.rulesStartAtSpawnSwitch.isChecked,
                    secondsInSpawn = readInt(dialog.rulesSecondsInSpawnEt, spawnRules.secondsInSpawn),
                    radiusMeters = distances[4],
                    perTeam = readInt(dialog.rulesPerTeamEt, spawnRules.perTeam),
                    enemySpawnShare = dialog.rulesEnemyShareEt.text?.toString()?.toIntOrNull()?.let { it / 100.0 } ?: spawnRules.enemySpawnShare,
                    enemyClearMeters = distances[5],
                    moveMinutes = readInt(dialog.rulesMoveMinutesEt, spawnRules.moveMinutes),
                    crossfadeSeconds = readInt(dialog.rulesCrossfadeEt, spawnRules.crossfadeSeconds),
                    oldSpawnGraceSeconds = readInt(dialog.rulesOldGraceEt, spawnRules.oldSpawnGraceSeconds),
                    shieldOnlyInSpawn = dialog.rulesShieldInSpawnSwitch.isChecked,
                ).clamped()
                val updatedLoadout = LoadoutRules(
                    ammo = when (dialog.rulesAmmoGroup.checkedRadioButtonId) {
                        R.id.rules_ammo_limited -> AmmoMode.LIMITED
                        R.id.rules_ammo_scavenger -> AmmoMode.SCAVENGER
                        else -> AmmoMode.UNLIMITED
                    },
                    startingReserve = readInt(dialog.rulesReserveEt, loadout.startingReserve),
                    refillAtSpawn = dialog.rulesRefillSwitch.isChecked,
                    scavengeSeconds = readInt(dialog.rulesScavengeSecondsEt, loadout.scavengeSeconds),
                    scavengeMagazines = readInt(dialog.rulesScavengeMagsEt, loadout.scavengeMagazines),
                    dropsEnabled = dialog.rulesDropsSwitch.isChecked,
                    dropKinds = dropBoxes.filterValues { it.isChecked }.keys,
                    dropIntervalMinutes = readInt(dialog.rulesDropIntervalEt, loadout.dropIntervalMinutes),
                    dropWarningSeconds = readInt(dialog.rulesDropWarningEt, loadout.dropWarningSeconds),
                    claimSeconds = readInt(dialog.rulesClaimSecondsEt, loadout.claimSeconds),
                    dropLastsMinutes = readInt(dialog.rulesDropLastsEt, loadout.dropLastsMinutes),
                    claimRadiusMeters = distances[6],
                    dropsNearestTeamOnly = dialog.rulesNearestTeamSwitch.isChecked,
                    overshieldHits = readInt(dialog.rulesShieldHitsEt, loadout.overshieldHits),
                    uavSeconds = readInt(dialog.rulesUavSecondsEt, loadout.uavSeconds),
                    killstreaks = dialog.rulesKillstreaksSwitch.isChecked,
                    uavKills = readInt(dialog.rulesUavKillsEt, loadout.uavKills),
                    fullAutoKills = readInt(dialog.rulesAutoKillsEt, loadout.fullAutoKills),
                    trackShots = dialog.rulesTrackShotsSwitch.isChecked,
                    maxShotMeters = distances[7],
                    longestShotAward = dialog.rulesLongestShotSwitch.isChecked,
                ).clamped()
                onSave(config.copy(rules = updated, spawnRules = updatedSpawns, loadout = updatedLoadout))
            }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .show()
    }

    private fun number(value: Int): String = String.format(Locale.ROOT, "%d", value)
}
