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
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplecoil.simplecoil.ui.shortName
import com.simplecoil.simplecoil.ui.ScreenOrientation
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
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardRank
import com.simplecoil.simplecoil.ui.server.DedicatedServerRenderer
import com.simplecoil.simplecoil.ui.server.ServerPanes
import com.simplecoil.simplecoil.ui.server.RefereeMapActivity
import com.simplecoil.simplecoil.ui.server.RefereeMapFragment
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
import com.simplecoil.simplecoil.domain.mode.BombRules
import com.simplecoil.simplecoil.domain.mode.InfectionRules
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

    /** The phase last shown, so a game starting opens the referee map once. */
    private var lastPhase: com.simplecoil.simplecoil.network.server.SessionPhase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Upright on a phone; a tablet or Chromebook window may be wide
        ScreenOrientation.portraitOnPhones(this)
        binding = ActivityDedicatedServerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The device stays awake while this screen is in the foreground (Q3); the service holds Wi-Fi
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listenForDialogResults()

        val scoreboard = ScoreboardAdapter(false, object : ScoreboardAdapter.Listener {
            override fun onTeamHeaderClicked() = vm.onTeamHeaderClicked()
            override fun onPlayerClicked(playerId: Byte) = vm.onPlayerClicked(playerId)
            override fun onKickClicked(playerId: Byte) = vm.onKick(playerId)
            override fun onCreateSquad() = vm.onCreateSquad()
            override fun onSquadDone() = vm.onSquadDone()
            override fun onDeleteSquad(squad: Int) = vm.onDeleteSquad(squad)
            override fun onRankChanged(rank: ScoreboardRank) = vm.onRankChanged(rank)
            override fun onSquadHeaderClicked(squad: Int) = vm.onSquadHeaderClicked(squad)
            override fun onSquadToggle(playerId: Byte, add: Boolean) = vm.onSquadToggle(playerId, add)
            override fun onPlayerDropped(playerId: Byte, team: Int) = vm.onPlayerDropped(playerId, team)
        })
        binding.playerList.layoutManager = LinearLayoutManager(this)
        binding.playerList.adapter = scoreboard
        renderer = DedicatedServerRenderer(binding, scoreboard)
        wireControls()
        binding.versionTv.text = packageManager.getPackageInfo(packageName, 0).versionName

        onBackPressedDispatcher.addCallback(this) { vm.onBackPressed() }
        // After the Back above, so an expanded sheet collapses before Back asks to stop the server
        attachPlayerSheet()
        // Wide windows: the panes (and Back from a full-window one, ahead of the rest)
        setUpPanes()
        // The players in a pane of their own (a Chromebook, a tablet): room to rank them during a game
        vm.setWideScreen(binding.playersPane != null)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.state.collect { state ->
                        state ?: return@collect
                        renderer.render(state)
                        // A game starting: the home screen becomes the live referee map
                        val running = state.phase == com.simplecoil.simplecoil.network.server.SessionPhase.RUNNING
                        // (A wide window shows it as a pane already)
                        if (running && lastPhase != null && lastPhase != state.phase && !liveMapOff() && binding.mapPane == null) {
                            startActivity(Intent(this@DedicatedServerActivity, RefereeMapActivity::class.java))
                        }
                        lastPhase = state.phase
                    }
                }
                launch { vm.effects.collect { onEffect(it) } }
                launch { showFieldMapStatus() }
                launch { applyFieldRules() }
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

    /**
     * What the selected field rules out (the renderer greys those buttons out). The game mode's
     * team modes: free for all for Infection and Battle Royale, two teams for Capture the Flag and Search &
     * Destroy; a team mode it can't use changes to one it can. A game mode played on positions
     * (any but classic, on a field map) needs GPS: if it was off, it turns on for everyone.
     */
    private suspend fun applyFieldRules() {
        val container = (application as SimpleCoilApp).container
        combine(container.serverFieldConfig.config, vm.state) { config, state -> config to state }
            .collect { (config, state) ->
                state ?: return@collect
                val gameMode = config?.mode ?: GameMode.CLASSIC
                val gpsRequired = config?.border != null && gameMode != GameMode.CLASSIC
                val liveMapOff = liveMapOff()
                if (renderer.gameMode != gameMode || renderer.gpsRequired != gpsRequired || renderer.liveMapOff != liveMapOff) {
                    renderer.gameMode = gameMode
                    renderer.gpsRequired = gpsRequired
                    renderer.liveMapOff = liveMapOff
                    renderer.render(state)
                }
                if (state.teamMode !in gameMode.teamModes) teamModeFor(gameMode)
                if (gpsRequired) vm.onFieldNeedsGps()
            }
    }

    /** A classic game with GPS off has no positions to show: no live referee map. */
    private fun liveMapOff(): Boolean {
        val state = vm.state.value ?: return false
        val gameMode = (application as SimpleCoilApp).container.serverFieldConfig.config.value?.mode ?: GameMode.CLASSIC
        return !state.useGps && gameMode == GameMode.CLASSIC
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
    private fun showRules() = editField { config, save ->
        FieldRulesDialog.show(this, config, save)
    }

    private fun showMode() = editField { config, save ->
        GameModeDialog.show(this, config) { updated ->
            save(updated)
            if (updated.mode != config.mode) teamModeFor(updated.mode)
        }
    }

    /**
     * A new game mode: the team mode it needs (free for all for Infection and Battle Royale, two teams for Capture
     * the Flag and Search & Destroy), or the one it was last played with.
     */
    private fun teamModeFor(mode: GameMode) {
        val prefs = (application as SimpleCoilApp).container.userPreferences
        val current = vm.state.value?.teamMode ?: return
        val target = mode.teamModeFor(prefs.teamModeFor(mode.wireValue), current)
        if (target == current) return
        vm.onTeamModeSelected(target)
        Toast.makeText(applicationContext, getString(R.string.team_mode_set_for_mode, teamModeName(target), modeName(mode)),
            Toast.LENGTH_SHORT).show()
    }

    private fun teamModeName(mode: TeamMode): String = getString(mode.shortName)

    private fun modeName(mode: GameMode): String = getString(when (mode) {
        GameMode.CLASSIC -> R.string.mode_classic
        GameMode.DOMINATION -> R.string.mode_domination
        GameMode.HARDPOINT -> R.string.mode_hardpoint
        GameMode.BATTLE_ROYALE -> R.string.mode_battle_royale
        GameMode.MARKSMAN -> R.string.mode_marksman
        GameMode.CAPTURE_THE_FLAG -> R.string.mode_ctf
        GameMode.EXTRACTION -> R.string.mode_extraction
        GameMode.SEARCH_AND_DESTROY -> R.string.mode_snd
        GameMode.INFECTION -> R.string.mode_infection
    })

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
        val activeName = container.userPreferences.activePresetName()
        lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) { container.gamePresets.names() }
            val view = com.simplecoil.simplecoil.databinding.DialogFieldPresetsBinding.inflate(layoutInflater)
            view.presetsSaveNewButton.setText(R.string.game_presets_save)
            view.presetsListTitleTv.setText(R.string.game_presets_title)
            view.presetsHintTv.setText(R.string.game_presets_hint)
            val dialog = AlertDialog.Builder(this@DedicatedServerActivity, R.style.AppDialogTheme)
                .setTitle(R.string.game_presets_title)
                .setView(view.root)
                .setNegativeButton(R.string.field_dialog_cancel, null)
                .create()
            view.presetsSaveNewButton.setOnClickListener {
                askPresetName(names) {
                    dialog.dismiss()
                    showPresets()
                }
            }
            view.presetsEmptyTv.isVisible = names.isEmpty()
            for (name in names) {
                val row = com.simplecoil.simplecoil.databinding.DialogFieldPresetRowBinding.inflate(layoutInflater, view.presetsRows, true)
                row.presetNameTv.text = name
                row.presetOnMapTv.isVisible = name == activeName
                row.presetLoadButton.setOnClickListener {
                    dialog.dismiss()
                    loadPreset(name)
                }
                row.presetOverwriteButton.setOnClickListener {
                    confirmPresetOverwrite(name, getString(R.string.field_presets_overwrite_title, name)) {
                        dialog.dismiss()
                        showPresets()
                    }
                }
                row.presetDeleteButton.setOnClickListener {
                    confirmPresetDelete(name) {
                        dialog.dismiss()
                        showPresets()
                    }
                }
            }
            dialog.show()
        }
    }

    private fun askPresetName(taken: List<String>, onSaved: (() -> Unit)? = null) {
        val dialog = DialogFieldNameBinding.inflate(layoutInflater)
        dialog.fieldNameTitleTv.setText(R.string.game_presets_save)
        dialog.fieldNameLabelTv.setText(R.string.game_presets_name_hint)
        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setView(dialog.root)
            .setPositiveButton(R.string.field_dialog_save) { _, _ ->
                val name = dialog.fieldNameEt.text.toString().trim()
                val existing = taken.firstOrNull { it.equals(name, ignoreCase = true) }
                when {
                    name.isEmpty() -> Unit
                    existing != null -> confirmPresetOverwrite(existing, getString(R.string.field_presets_exists_title, existing), onSaved)
                    else -> savePreset(name, onSaved)
                }
            }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .show()
    }

    private fun confirmPresetOverwrite(
        name: String,
        title: String = getString(R.string.field_presets_overwrite_title, name),
        onSaved: (() -> Unit)? = null,
    ) {
        AlertDialog.Builder(this, R.style.AppDialogTheme_Confirm)
            .setTitle(title)
            .setMessage(R.string.game_presets_overwrite_body)
            .setPositiveButton(R.string.field_presets_overwrite) { _, _ -> savePreset(name, onSaved) }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .show()
    }

    private fun confirmPresetDelete(name: String, onDeleted: (() -> Unit)? = null) {
        AlertDialog.Builder(this, R.style.AppDialogTheme_Confirm)
            .setTitle(getString(R.string.field_presets_delete_title, name))
            .setMessage(R.string.game_presets_delete_body)
            .setPositiveButton(R.string.field_presets_delete) { _, _ -> deletePreset(name, onDeleted) }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .show()
    }

    private fun savePreset(name: String, onSaved: (() -> Unit)? = null) {
        val container = (application as SimpleCoilApp).container
        val state = vm.state.value ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val field = container.serverFieldConfig.refresh() ?: FieldConfig(rules = BorderRules(units = DistanceUnits.defaultFor(Locale.getDefault())))
                val preset = GamePreset(
                    name = name, mode = field.mode, objectiveRules = field.objectiveRules, borderRules = field.rules,
                    spawnRules = field.spawnRules, loadout = field.loadout, battleRoyale = field.battleRoyale, marksman = field.marksman, flag = field.flag, extraction = field.extraction, bomb = field.bomb, infection = field.infection,
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
            if (ok) {
                container.userPreferences.saveActivePresetName(name)
                container.serverController.updateServerName(name)
                onSaved?.invoke()
            }
            Toast.makeText(applicationContext, if (ok) getString(R.string.game_presets_saved, name) else getString(R.string.field_presets_error),
                Toast.LENGTH_SHORT).show()
        }
    }

    /** The preset's server settings go through the same paths as the controls; its rules onto the saved field. */
    private fun loadPreset(name: String) {
        val container = (application as SimpleCoilApp).container
        lifecycleScope.launch {
            val preset = withContext(Dispatchers.IO) { container.gamePresets.load(name) } ?: return@launch
            container.userPreferences.saveActivePresetName(name)
            container.serverController.updateServerName(name)
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

    private fun deletePreset(name: String, onDeleted: (() -> Unit)? = null) {
        val container = (application as SimpleCoilApp).container
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { container.gamePresets.delete(name) }
            if (container.userPreferences.activePresetName() == name) {
                container.userPreferences.saveActivePresetName(null)
                container.serverController.updateServerName("SimpleCoil Dedicated Server")
            }
            onDeleted?.invoke()
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
                GameMode.SEARCH_AND_DESTROY -> getString(R.string.server_summary_snd, (config?.bomb ?: BombRules()).roundsToWin)
                GameMode.INFECTION -> getString(R.string.server_summary_infection, (config?.infection ?: InfectionRules()).roundMinutes)
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
        teamModePicker.onPick = { onTeamModeClicked(it) }
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
        fieldMapButton.setOnClickListener {
            val running = vm.state.value?.phase == com.simplecoil.simplecoil.network.server.SessionPhase.RUNNING
            when {
                // A wide window already shows the live map: fill the window with it
                running && mapPane != null -> panes?.showMapFull()
                else -> startActivity(Intent(this@DedicatedServerActivity, if (running) RefereeMapActivity::class.java else FieldMapActivity::class.java))
            }
        }
        fieldRulesButton.setOnClickListener { showRules() }
        modeChangeButton.setOnClickListener { showMode() }
        presetsButton.setOnClickListener { showPresets() }
        // The settings cards open in place (each on its own)
        // Each card clips to its rounded shape, so the header's press ripple keeps the corners
        listOf(modeCard, fieldCard, teamsCard).forEach { it.clipToOutline = true }
        dropDown(modeCardHeader, modeBody, modeChevronTv)
        dropDown(fieldCardHeader, fieldBody, fieldChevronTv)
        dropDown(teamsCardHeader, teamsBody, teamsChevronTv)
        startGameButton.setOnClickListener { vm.onStartPressed() }
        endGameButton.setOnClickListener { vm.onEndPressed() }
        resetGameButton.setOnClickListener { vm.onResetPressed() }
        // Click, not checked-change: the renderer sets the switches from the server's state
        allowJoinSwitch.setOnClickListener { vm.onAllowJoinChanged(allowJoinSwitch.isChecked) }
        onlyServerSettingsSwitch.setOnClickListener { vm.onOnlyServerSettingsChanged(onlyServerSettingsSwitch.isChecked) }
        allowChooseTeamsSwitch.setOnClickListener { vm.onAllowChooseTeamsChanged(allowChooseTeamsSwitch.isChecked) }
        briefingButton.setOnClickListener { startActivity(com.simplecoil.simplecoil.ui.briefing.BriefingActivity.hostIntent(this@DedicatedServerActivity)) }
        voiceChatSwitch.isChecked = vm.voiceChat
        voiceChatSwitch.setOnClickListener { vm.onVoiceChatChanged(voiceChatSwitch.isChecked) }
    }

    /** A settings card that slides open and shut in place when its header is tapped. */
    private fun dropDown(header: View, body: View, chevron: TextView) {
        header.setOnClickListener {
            // Opening unless it's open, or on its way open
            val open = (body.getTag(R.id.drop_down_open) as? Boolean ?: body.isVisible).not()
            body.setTag(R.id.drop_down_open, open)
            slide(body, open)
            chevron.animate().rotation(if (open) 90f else 0f).setDuration(DROP_DOWN_MILLIS).start()
        }
    }

    /**
     * Grows [body] from nothing to its full height, or shrinks it away, fading as it goes, so
     * the cards under it slide down and back up rather than jump. A tap partway turns it around.
     */
    private fun slide(body: View, open: Boolean) {
        (body.getTag(R.id.drop_down_animator) as? Animator)?.cancel()
        val from = if (body.isVisible) body.height else 0
        val to = if (open) {
            val parent = body.parent as View
            val lp = body.layoutParams as ViewGroup.MarginLayoutParams
            val width = parent.width - parent.paddingLeft - parent.paddingRight - lp.leftMargin - lp.rightMargin
            body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            body.measuredHeight
        } else 0
        body.layoutParams.height = from
        body.isVisible = true
        body.requestLayout()
        val animator = ValueAnimator.ofInt(from, to).apply {
            duration = DROP_DOWN_MILLIS
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f) // fast out, slow in
            addUpdateListener {
                body.layoutParams.height = it.animatedValue as Int
                body.alpha = (it.animatedValue as Int).toFloat() / maxOf(from, to, 1)
                body.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    // Done: as tall as it needs to be from now on (its contents change), or gone
                    body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    body.alpha = 1f
                    body.isVisible = open
                    body.setTag(R.id.drop_down_animator, null)
                }
            })
        }
        body.setTag(R.id.drop_down_animator, animator)
        animator.start()
    }

    /** The wide window's panes side by side; null on a phone, whose layout has none. */
    private var panes: ServerPanes? = null

    private val leaveFullPane = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            panes?.leaveFull()
        }
    }

    /**
     * A wide window (a Chromebook, a big tablet) has the settings, the live map and the players
     * side by side (or the settings and the players, if it's less wide), sized, closed and
     * filling the window as [ServerPanes] has it.
     */
    private fun setUpPanes() {
        val b = binding
        if (b.mapPane != null && supportFragmentManager.findFragmentById(R.id.referee_map_container) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.referee_map_container, RefereeMapFragment.newInstance(embedded = true))
                .commit()
        }
        // The map pane's corners are rounded like the others' (its map draws as a texture, so it's clipped)
        b.mapPane?.clipToOutline = true
        val settings = b.settingsPane ?: return
        val players = b.playersPane ?: return
        val divider = b.dividerLeft ?: return
        onBackPressedDispatcher.addCallback(this, leaveFullPane)
        panes = ServerPanes(settings.parent as LinearLayout, settings, b.mapPane, players, divider, b.dividerRight,
            b.mapExpandButton, b.playersExpandButton, getSharedPreferences(PANES_PREFS, MODE_PRIVATE)) { full ->
            leaveFullPane.isEnabled = full
        }
    }

    /** The players in a bottom sheet under the controls, never over Start/End/Reset; the large-screen layout shows them beside the controls instead. */
    private fun attachPlayerSheet() {
        val b = binding
        val sheet = b.playerSheet ?: return
        FillingBottomSheet(this, sheet, b.playerSheetHeader!!, b.serverCoordinator!!, b.serverContent!!, b.serverScroll!!,
            resources.getDimensionPixelSize(R.dimen.player_sheet_min_peek), keepAbove = b.phaseControls,
            onCollapsed = { b.playerList.scrollToPosition(0) },
            // Closed, the sheet has room only for a small Coil in its header; open, the big one
            onOpenChanged = { open -> renderer.setSheetOpen(open) })
        renderer.setSheetOpen(false)
    }

    /** Tapping the chosen segment again changes nothing, so it doesn't reset the game. */
    private fun onTeamModeClicked(mode: TeamMode) {
        val state = vm.state.value ?: return
        val container = (application as SimpleCoilApp).container
        val gameMode = container.serverFieldConfig.config.value?.mode ?: GameMode.CLASSIC
        if (mode !in gameMode.teamModes) {
            // The game mode can't be played that way: say so, and put the segment back
            Toast.makeText(applicationContext, getString(R.string.team_mode_not_for_mode, modeName(gameMode),
                teamModeName(gameMode.teamModes.first())), Toast.LENGTH_LONG).show()
            renderer.render(state)
            return
        }
        // Remembered for this game mode, so picking it again brings it back
        container.userPreferences.saveTeamModeFor(gameMode.wireValue, mode)
        if (state.teamMode != mode) vm.onTeamModeSelected(mode)
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
                effect.teamCount, effect.team, effect.indexInTeam, effect.fullTeams, effect.sound, effect.voice), PlayerSettingsDialog.REQUEST_KEY)
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
        const val DROP_DOWN_MILLIS = 250L
        const val PANES_PREFS = "serverPanes"
        const val REQUEST_RESET = "serverReset"
        const val REQUEST_STOP = "serverStop"
        const val REQUEST_END = "serverEnd"
    }
}
