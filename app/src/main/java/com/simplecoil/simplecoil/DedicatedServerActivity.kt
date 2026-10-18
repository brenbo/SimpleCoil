/*
 * Copyright (C) 2018 Ethan Yonker
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

package com.simplecoil.simplecoil

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.ActivityDedicatedServerBinding
import com.simplecoil.simplecoil.databinding.DialogFieldNameBinding
import com.simplecoil.simplecoil.domain.mode.GamePreset
import com.simplecoil.simplecoil.domain.rules.LimitKind
import androidx.appcompat.app.AlertDialog
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.ui.dialogs.ConfirmDialog
import com.simplecoil.simplecoil.ui.dialogs.GameLimitDialog
import com.simplecoil.simplecoil.ui.dialogs.StartingTimerDialog
import com.simplecoil.simplecoil.ui.dialogs.TeamRatioDialog
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsDialog
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardAdapter
import com.simplecoil.simplecoil.ui.server.DedicatedServerRenderer
import com.simplecoil.simplecoil.ui.server.DedicatedServerViewModel
import com.simplecoil.simplecoil.ui.server.ServerUiEffect
import com.simplecoil.simplecoil.ui.map.FieldMapActivity
import com.simplecoil.simplecoil.ui.map.FieldRulesDialog
import com.simplecoil.simplecoil.ui.map.GameModeDialog
import com.simplecoil.simplecoil.domain.loadout.AmmoMode
import com.simplecoil.simplecoil.domain.loadout.LoadoutRules
import com.simplecoil.simplecoil.domain.mode.BattleRoyaleRules
import com.simplecoil.simplecoil.domain.mode.ExtractionRules
import com.simplecoil.simplecoil.domain.mode.FlagRules
import com.simplecoil.simplecoil.domain.mode.GameMode
import com.simplecoil.simplecoil.domain.mode.ObjectiveRules
import androidx.core.view.isVisible
import com.simplecoil.simplecoil.domain.map.BorderRules
import com.simplecoil.simplecoil.domain.map.DistanceUnits
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.ui.sheet.FillingBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.io.IOException

/**
 * The dedicated server's screen (dedicated server design §6.2). The server runs in its own
 * service and outlives this screen; the screen shows its state and sends the operator's
 * commands through [DedicatedServerViewModel]. Back stops the server, after a confirmation.
 */
class DedicatedServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDedicatedServerBinding
    private lateinit var renderer: DedicatedServerRenderer
    private val vm: DedicatedServerViewModel by viewModels { DedicatedServerViewModel.Factory(application as SimpleCoilApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDedicatedServerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The device stays awake while this screen is in the foreground (Q3); the service holds Wi-Fi
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listenForDialogResults()

        val scoreboard = ScoreboardAdapter(false, object : ScoreboardAdapter.Listener {
            override fun onTeamHeaderClicked() = vm.onTeamHeaderClicked()
            override fun onPlayerClicked(playerId: Byte) = vm.onPlayerClicked(playerId)
            override fun onKickClicked(playerId: Byte) = vm.onKick(playerId)
        })
        binding.playerList.layoutManager = LinearLayoutManager(this)
        binding.playerList.adapter = scoreboard
        renderer = DedicatedServerRenderer(binding, scoreboard)
        wireControls()
        binding.versionTv.text = packageManager.getPackageInfo(packageName, 0).versionName

        onBackPressedDispatcher.addCallback(this) { vm.onBackPressed() }
        // After the Back above, so an expanded sheet collapses before Back asks to stop the server
        attachPlayerSheet()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect { state -> state?.let { renderer.render(it) } } }
                launch { vm.effects.collect { onEffect(it) } }
                launch { showFieldMapStatus() }
                launch { showGps() }
                launch { showCardSummaries() }
            }
        }
    }

    /** "Field map saved Oct 16 · sent to 3 phones", or none (map design: Field imagery packs). */
    private suspend fun showFieldMapStatus() {
        val container = (application as SimpleCoilApp).container
        withContext(Dispatchers.IO) {
            container.serverFieldStore.refresh()
            container.serverFieldConfig.refresh()
        }
        combine(container.serverFieldStore.info, container.serverFieldConfig.config, container.fieldImageryServer.deliveredCount) { info, config, sent ->
            Triple(info, config, sent)
        }.collect { (info, config, sent) ->
            binding.fieldMapStatusTv.text = when {
                info == null && config?.border == null -> getString(R.string.field_map_status_none)
                else -> {
                    // The field's name if it has one, else when its photo was downloaded
                    val label = config?.name?.takeIf { it.isNotBlank() }
                        ?: info?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.createdAtMillis)) } ?: ""
                    if (sent > 0) resources.getQuantityString(R.plurals.field_map_status_sent, sent, label, sent)
                    else getString(R.string.field_map_status_saved, label)
                }
            }
        }
    }

    /** "GPS: 3 of 4 reporting · no fix from Bob": whose positions the server hears (shot distance, drops, spawns). */
    private suspend fun showGps() {
        val container = (application as SimpleCoilApp).container
        container.serverGps.collect { players ->
            binding.serverGpsTv.visibility = if (players.isEmpty()) View.GONE else View.VISIBLE
            val missing = players.filter { !it.reporting }
            binding.serverGpsTv.text = if (missing.isEmpty()) {
                resources.getQuantityString(R.plurals.server_gps_all, players.size, players.size)
            } else {
                getString(R.string.server_gps_some, players.size - missing.size, players.size,
                    missing.joinToString(", ") { it.name.ifBlank { "#${it.playerId}" } })
            }
            binding.serverGpsTv.setTextColor(if (missing.isEmpty()) 0xFF81C784.toInt() else 0xFFFFB74D.toInt())
        }
    }

    /** The game rules for the field: out of bounds, respawn, ammo and drops (map design: Server UI). */
    private fun showRules(section: FieldRulesDialog.Section) = editField { config, save ->
        FieldRulesDialog.show(this, config, section, save)
    }

    private fun showMode() = editField { config, save -> GameModeDialog.show(this, config, save) }

    /** Opens a sheet on the saved field (or a new one) with [sheet]; what it saves goes to the phones. */
    private fun editField(sheet: (FieldConfig, (FieldConfig) -> Unit) -> Unit) {
        val container = (application as SimpleCoilApp).container
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { container.serverFieldConfig.refresh() }
                ?: FieldConfig(rules = BorderRules(units = DistanceUnits.defaultFor(Locale.getDefault())))
            sheet(config) { updated -> saveField(updated) }
        }
    }

    private fun saveField(updated: FieldConfig) {
        val container = (application as SimpleCoilApp).container
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    container.serverFieldConfig.save(updated)
                    true
                } catch (e: IOException) {
                    false
                }
            }
            container.fieldImageryServer.onFieldChanged()
            if (!saved) Toast.makeText(applicationContext, R.string.field_map_save_error, Toast.LENGTH_SHORT).show()
        }
    }

    // Game presets (map design: Presets): the whole setup under a name, "Saturday BR"

    private fun showPresets() {
        val container = (application as SimpleCoilApp).container
        lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) { container.gamePresets.names() }
            val items = (listOf(getString(R.string.game_presets_save)) + names).toTypedArray()
            AlertDialog.Builder(this@DedicatedServerActivity, R.style.AppDialogTheme)
                .setTitle(R.string.game_presets_title)
                .setItems(items) { _, which -> if (which == 0) askPresetName() else presetOptions(names[which - 1]) }
                .setNegativeButton(R.string.field_dialog_cancel, null)
                .show()
        }
    }

    private fun askPresetName() {
        val dialog = DialogFieldNameBinding.inflate(layoutInflater)
        dialog.fieldNameTitleTv.setText(R.string.game_presets_save)
        dialog.fieldNameLabelTv.setText(R.string.game_presets_name_hint)
        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setView(dialog.root)
            .setPositiveButton(R.string.field_dialog_save) { _, _ ->
                val name = dialog.fieldNameEt.text.toString().trim()
                if (name.isNotEmpty()) savePreset(name)
            }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .show()
    }

    private fun savePreset(name: String) {
        val container = (application as SimpleCoilApp).container
        val state = vm.state.value ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val field = container.serverFieldConfig.refresh() ?: FieldConfig(rules = BorderRules(units = DistanceUnits.defaultFor(Locale.getDefault())))
                val preset = GamePreset(
                    name = name, mode = field.mode, objectiveRules = field.objectiveRules, borderRules = field.rules,
                    spawnRules = field.spawnRules, loadout = field.loadout, battleRoyale = field.battleRoyale, marksman = field.marksman, flag = field.flag, extraction = field.extraction,
                    teamMode = state.teamMode,
                    gpsMode = if (state.useGps) state.gpsMode else GameSettingsRepository.GPS_DISABLED,
                    timeLimit = state.limits.shown(LimitKind.TIME), livesLimit = state.limits.shown(LimitKind.LIVES),
                    scoreLimit = state.limits.shown(LimitKind.SCORE), startTimeSeconds = state.startTimeSeconds,
                    allowChooseTeams = state.allowChooseTeams,
                )
                try {
                    container.gamePresets.save(preset)
                    true
                } catch (e: IOException) {
                    false
                }
            }
            Toast.makeText(applicationContext, if (ok) getString(R.string.game_presets_saved, name) else getString(R.string.field_presets_error),
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun presetOptions(name: String) {
        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(name)
            .setItems(arrayOf(getString(R.string.field_presets_load), getString(R.string.field_presets_delete))) { _, which ->
                if (which == 0) loadPreset(name) else deletePreset(name)
            }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .show()
    }

    /** The preset's server settings go through the same paths as the controls; its rules onto the saved field. */
    private fun loadPreset(name: String) {
        val container = (application as SimpleCoilApp).container
        lifecycleScope.launch {
            val preset = withContext(Dispatchers.IO) { container.gamePresets.load(name) } ?: return@launch
            vm.onTeamModeSelected(preset.teamMode)
            vm.onGpsModeSelected(preset.gpsMode)
            vm.onGameLimitsEntered(mapOf(
                LimitKind.TIME to preset.timeLimit.toString(),
                LimitKind.LIVES to preset.livesLimit.toString(),
                LimitKind.SCORE to preset.scoreLimit.toString(),
            ))
            vm.onStartTimeEntered(preset.startTimeSeconds.toString())
            vm.onAllowChooseTeamsChanged(preset.allowChooseTeams)
            editField { config, _ -> saveField(preset.applyTo(config)) }
            Toast.makeText(applicationContext, getString(R.string.game_presets_loaded, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePreset(name: String) {
        val container = (application as SimpleCoilApp).container
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { container.gamePresets.delete(name) }
            Toast.makeText(applicationContext, getString(R.string.field_presets_deleted, name), Toast.LENGTH_SHORT).show()
        }
    }

    /** The Game mode and Extras cards' one-line summaries, from the saved field. */
    private suspend fun showCardSummaries() {
        val container = (application as SimpleCoilApp).container
        container.serverFieldConfig.config.collect { config ->
            val rules = config?.objectiveRules ?: ObjectiveRules()
            binding.modeSummaryTv.text = when (config?.mode ?: GameMode.CLASSIC) {
                GameMode.CLASSIC -> getString(R.string.server_summary_classic)
                GameMode.DOMINATION -> getString(R.string.server_summary_domination,
                    if (rules.pointCount == 0) getString(R.string.server_summary_points_auto)
                    else resources.getQuantityString(R.plurals.server_summary_points, rules.pointCount, rules.pointCount),
                    rules.scoreToWin)
                GameMode.HARDPOINT -> getString(R.string.server_summary_hardpoint, rules.moveSeconds, rules.scoreToWin)
                GameMode.BATTLE_ROYALE -> (config?.battleRoyale ?: BattleRoyaleRules()).let { br ->
                    getString(R.string.server_summary_battle_royale, br.phases, br.firstWaitSeconds / 60)
                }
                GameMode.MARKSMAN -> getString(R.string.server_summary_marksman)
                GameMode.CAPTURE_THE_FLAG -> getString(R.string.server_summary_ctf, (config?.flag ?: FlagRules()).capturesToWin)
                GameMode.EXTRACTION -> getString(R.string.server_summary_extraction, (config?.extraction ?: ExtractionRules()).opensAfterMinutes)
            }
            val loadout = config?.loadout ?: LoadoutRules()
            val parts = listOfNotNull(
                getString(when (loadout.ammo) {
                    AmmoMode.UNLIMITED -> R.string.server_summary_ammo_unlimited
                    AmmoMode.LIMITED -> R.string.server_summary_ammo_limited
                    AmmoMode.SCAVENGER -> R.string.server_summary_ammo_scavenger
                }),
                getString(R.string.server_summary_drops, loadout.dropIntervalMinutes).takeIf { loadout.dropsEnabled },
                getString(R.string.server_summary_killstreaks).takeIf { loadout.killstreaks },
                getString(R.string.server_summary_longest).takeIf { loadout.trackShots && loadout.longestShotAward },
            )
            binding.extrasSummaryTv.text = parts.joinToString(" · ")
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onScreenVisible(true)
    }

    override fun onStop() {
        super.onStop()
        vm.onScreenVisible(false)
        renderer.pause()
    }

    private fun wireControls() = with(binding) {
        // Click, not checked-change: the renderer checks the segments from the server's state
        teamMode2teamsButton.setOnClickListener { onTeamModeClicked(TeamMode.TWO_TEAMS) }
        teamMode4teamsButton.setOnClickListener { onTeamModeClicked(TeamMode.FOUR_TEAMS) }
        teamModeFfaButton.setOnClickListener { onTeamModeClicked(TeamMode.FREE_FOR_ALL) }
        sortTeamsButton.setOnClickListener { vm.onSortTeams() }
        gpsModeDisabledButton.setOnClickListener { onGpsModeClicked(GameSettingsRepository.GPS_DISABLED) }
        gpsModeTeammateButton.setOnClickListener { onGpsModeClicked(GameSettingsRepository.GPS_TEAMMATE) }
        gpsModeAllButton.setOnClickListener { onGpsModeClicked(GameSettingsRepository.GPS_ALL) }
        gameLimitButton.setOnClickListener {
            val limits = vm.state.value?.limits ?: return@setOnClickListener
            showDialog(GameLimitDialog.newInstance(limits, true), GameLimitDialog.REQUEST_KEY)
        }
        startingTimerButton.setOnClickListener {
            val current = vm.state.value?.startTimeSeconds ?: GameSettingsRepository.START_TIME_SECONDS
            showDialog(StartingTimerDialog.newInstance(current, DedicatedServerViewModel.MAX_START_TIME_SECONDS), StartingTimerDialog.REQUEST_KEY)
        }
        fieldMapButton.setOnClickListener { startActivity(Intent(this@DedicatedServerActivity, FieldMapActivity::class.java)) }
        fieldRulesButton.setOnClickListener { showRules(FieldRulesDialog.Section.FIELD) }
        extrasCardHeader.setOnClickListener { showRules(FieldRulesDialog.Section.EXTRAS) }
        modeCardHeader.setOnClickListener { showMode() }
        presetsButton.setOnClickListener { showPresets() }
        // Teams and players opens in place
        teamsCardHeader.setOnClickListener {
            teamsBody.isVisible = !teamsBody.isVisible
            teamsChevronTv.setText(if (teamsBody.isVisible) R.string.server_card_close else R.string.server_card_open)
        }
        startGameButton.setOnClickListener { vm.onStartPressed() }
        endGameButton.setOnClickListener { vm.onEndPressed() }
        resetGameButton.setOnClickListener { vm.onResetPressed() }
        // Click, not checked-change: the renderer sets the switches from the server's state
        allowJoinSwitch.setOnClickListener { vm.onAllowJoinChanged(allowJoinSwitch.isChecked) }
        onlyServerSettingsSwitch.setOnClickListener { vm.onOnlyServerSettingsChanged(onlyServerSettingsSwitch.isChecked) }
        allowChooseTeamsSwitch.setOnClickListener { vm.onAllowChooseTeamsChanged(allowChooseTeamsSwitch.isChecked) }
    }

    /** The players in a bottom sheet under the controls, never over Start/End/Reset; the large-screen layout shows them beside the controls instead. */
    private fun attachPlayerSheet() {
        val b = binding
        val sheet = b.playerSheet ?: return
        FillingBottomSheet(this, sheet, b.playerSheetHeader!!, b.serverCoordinator!!, b.serverContent!!, b.serverScroll!!,
            resources.getDimensionPixelSize(R.dimen.player_sheet_min_peek), keepAbove = b.phaseControls) { b.playerList.scrollToPosition(0) }
    }

    /** Tapping the chosen segment again changes nothing, so it doesn't reset the game. */
    private fun onTeamModeClicked(mode: TeamMode) {
        if (vm.state.value?.teamMode != mode) vm.onTeamModeSelected(mode)
    }

    private fun onGpsModeClicked(mode: Int) {
        val state = vm.state.value ?: return
        val current = if (state.useGps) state.gpsMode else GameSettingsRepository.GPS_DISABLED
        if (current != mode) vm.onGpsModeSelected(mode)
    }

    private fun listenForDialogResults() {
        val fm = supportFragmentManager
        fm.setFragmentResultListener(GameLimitDialog.REQUEST_KEY, this) { _, result ->
            vm.onGameLimitsEntered(GameLimitDialog.texts(result))
        }
        fm.setFragmentResultListener(StartingTimerDialog.REQUEST_KEY, this) { _, result ->
            vm.onStartTimeEntered(StartingTimerDialog.text(result))
        }
        fm.setFragmentResultListener(TeamRatioDialog.REQUEST_KEY, this) { _, result -> vm.onTeamSizesEntered(TeamRatioDialog.sizes(result)) }
        fm.setFragmentResultListener(PlayerSettingsDialog.REQUEST_KEY, this) { _, result ->
            vm.onPlayerSettingsSaved(PlayerSettingsDialog.result(result))
        }
        fm.setFragmentResultListener(REQUEST_RESET, this) { _, _ -> vm.onResetConfirmed() }
        fm.setFragmentResultListener(REQUEST_STOP, this) { _, _ -> vm.onStopConfirmed() }
        fm.setFragmentResultListener(REQUEST_END, this) { _, _ -> vm.onEndConfirmed() }
    }

    private fun onEffect(effect: ServerUiEffect) {
        when (effect) {
            is ServerUiEffect.Toast ->
                Toast.makeText(applicationContext, getString(effect.text, *effect.args.toTypedArray()), Toast.LENGTH_SHORT).show()
            is ServerUiEffect.ConfirmReset -> showDialog(ConfirmDialog.newInstance(REQUEST_RESET,
                quantity(R.plurals.dedicated_reset_confirm, effect.disconnectedPlayers), R.string.reset_game_button), REQUEST_RESET)
            is ServerUiEffect.ConfirmStop -> showDialog(ConfirmDialog.newInstance(REQUEST_STOP,
                quantity(R.plurals.dedicated_stop_confirm, effect.players), R.string.dedicated_stop_button), REQUEST_STOP)
            ServerUiEffect.ConfirmEnd -> showDialog(ConfirmDialog.newInstance(REQUEST_END,
                getString(R.string.dedicated_end_confirm), R.string.dedicated_end_button), REQUEST_END)
            is ServerUiEffect.ShowTeamRatio ->
                showDialog(TeamRatioDialog.newInstance(effect.teamCount, effect.sizes), TeamRatioDialog.REQUEST_KEY)
            is ServerUiEffect.ShowPlayerSettings -> showDialog(PlayerSettingsDialog.server(effect.playerId, effect.playerName, effect.values,
                effect.teamCount, effect.team, effect.indexInTeam, effect.fullTeams), PlayerSettingsDialog.REQUEST_KEY)
            ServerUiEffect.Finish -> finish()
        }
    }

    /** Shows [dialog] unless one is already showing under [tag] or the state is saved. */
    private fun showDialog(dialog: DialogFragment, tag: String) {
        val fm = supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(tag) != null) return
        dialog.show(fm, tag)
    }

    private fun quantity(id: Int, count: Int): String = resources.getQuantityString(id, count, count)

    private companion object {
        const val REQUEST_RESET = "serverReset"
        const val REQUEST_STOP = "serverStop"
        const val REQUEST_END = "serverEnd"
    }
}
