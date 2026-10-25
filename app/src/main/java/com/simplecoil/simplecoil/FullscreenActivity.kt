/*
 * Copyright (C) 2018
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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.simplecoil.simplecoil.ui.ScreenOrientation
import com.simplecoil.simplecoil.databinding.ActivityFullscreenBinding
import com.simplecoil.simplecoil.hardware.blaster.BlasterSupport
import com.simplecoil.simplecoil.ui.briefing.BriefingActivity
import com.simplecoil.simplecoil.ui.game.CompositeGameScreen
import com.simplecoil.simplecoil.ui.game.DialogCoordinator
import com.simplecoil.simplecoil.ui.game.GameNavigator
import com.simplecoil.simplecoil.ui.game.GameScreenRenderer
import com.simplecoil.simplecoil.ui.game.GameViewModel
import com.simplecoil.simplecoil.ui.game.GameViewModelFactory
import com.simplecoil.simplecoil.ui.game.AboutDialog
import com.simplecoil.simplecoil.ui.game.SavedGameScreen
import com.simplecoil.simplecoil.ui.game.WindowController
import com.simplecoil.simplecoil.ui.permissions.BlePermissionController
import com.simplecoil.simplecoil.ui.permissions.BlePermissions
import com.simplecoil.simplecoil.ui.permissions.PermissionAsk
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The play screen: wires the views to [GameViewModel], which makes every decision and tells the
 * screen what to show through its GameScreen. Views, window flags and session values are drawn by
 * [GameScreenRenderer]; dialogs, toasts and menus are shown by [DialogCoordinator]. The permission
 * and Bluetooth flow in front of a connect is run by [BlePermissionController].
 */
class FullscreenActivity : AppCompatActivity(), GameNavigator {

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            vm.onQrScanned(result.contents)
        } else {
            Log.e(TAG, "QR scan cancelled or no result")
        }
    }

    // The microphone, once the server turns voice chat on: what it's for, then Android's prompt (asked once)
    private val micAsk = PermissionAsk.of(this, "microphone") {
        (application as SimpleCoilApp).container.voiceChat.onMicPermission()
    }

    /**
     * Voice chat on this screen: asks for the microphone once the server turns it on, and shows
     * "Talking to your team" (or everyone) while you hold the button, else who you hear.
     */
    /** The host started a briefing: it takes over this phone's screen until the host ends it. */
    private fun followBriefings() {
        val container = (application as SimpleCoilApp).container
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.briefing.collect { now ->
                    if (now != null && !BriefingActivity.showing) startActivity(BriefingActivity.playerIntent(this@FullscreenActivity))
                }
            }
        }
    }

    private fun showVoiceChat() {
        val container = (application as SimpleCoilApp).container
        val voice = container.voiceChat
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { voice.needsMic.collect { if (it) micAsk.ask(BlePermissions.Flow.MICROPHONE) } }
                launch {
                    combine(voice.enabled, voice.talking, voice.speakers, voice.needsMic) { on, talking, speakers, needsMic ->
                        when {
                            !on -> null
                            talking -> getString(if (container.players.isAdmin && container.voiceToEveryone) R.string.voice_talking_everyone
                                else R.string.voice_talking_team)
                            speakers.isNotEmpty() -> getString(R.string.voice_hearing, speakers.joinToString(", ") { id ->
                                container.players.getPlayerName(id.toByte())?.ifBlank { null } ?: getString(R.string.voice_player, id)
                            })
                            needsMic -> getString(R.string.voice_needs_mic)
                            else -> null
                        }
                    }.collect { text ->
                        binding.voiceTv.text = text
                        binding.voiceTv.visibility = if (text == null) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    private lateinit var vm: GameViewModel
    private lateinit var binding: ActivityFullscreenBinding
    private lateinit var renderer: GameScreenRenderer
    private lateinit var dialogs: DialogCoordinator
    private lateinit var permissions: BlePermissionController
    private var serverOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Upright on a phone; a tablet or Chromebook window may be wide
        ScreenOrientation.portraitOnPhones(this)
        binding = ActivityFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitHomeToWindow()
        // Back keeps the session running: send the task to the background instead of finishing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
        serverOnly = BlasterSupport.isServerOnly(packageManager)
        renderer = GameScreenRenderer(binding, WindowController(window), lifecycleScope, serverOnly,
            onTeamTapped = { vm.onTeamLabelPressed() }, onPlayerNameTapped = { vm.onPlayerNamePressed() },
            onShotModeTapped = { vm.onShotModePressed() }, onRecoilTapped = { vm.onRecoilPressed() },
            onGrenadeTapped = { vm.onGrenadeIconClicked() }, onScoresTapped = { vm.openScoreboard() })
        vm = ViewModelProvider(this, GameViewModelFactory(application as SimpleCoilApp))[GameViewModel::class.java]
        dialogs = DialogCoordinator(this, vm, binding.useNetworkButton, binding.gameLimitButton, binding.teamLabelTv)
        vm.screen = CompositeGameScreen(renderer, dialogs, this)
        permissions = BlePermissionController(this, vm, ::startQRScan)
        val saved = savedInstanceState?.let {
            SavedGameScreen(
                it.getByte("mSelectedPlayerID", 0),
                it.getString("mDeviceAddress", ""),
                it.getBoolean("mUseNetwork", false),
                it.getInt("mNetworkMenuType", GameViewModel.NETWORK_TYPE_ENABLED),
            )
        }
        vm.onCreate(saved)

        // The session runs app-scoped (D4); render it whenever this screen exists.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                vm.session.collect(renderer::render)
            }
        }
        showVoiceChat()
        // The host started a briefing: it takes over this phone until the host ends it
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as SimpleCoilApp).container.briefing.collect { now ->
                    if (now != null && !BriefingActivity.showing) startActivity(BriefingActivity.playerIntent(this@FullscreenActivity))
                }
            }
        }
        followBriefings()

        binding.reconnectWeaponButton.setOnClickListener { vm.onReconnectPressed() }
        binding.connectWeaponButton.setOnClickListener { vm.onConnectPressed() }
        binding.connectQrWeaponButton.setOnClickListener { permissions.scanQr() }
        binding.qrLearnMoreTv.setOnClickListener { dialogs.showQrInfo() }
        binding.dedicatedServerButton.setOnClickListener { permissions.hostServer() }
        binding.teamMinusButton.visibility = View.VISIBLE
        binding.teamMinusButton.setOnClickListener { vm.onTeamMinusPressed() }
        binding.teamPlusButton.visibility = View.VISIBLE
        binding.teamPlusButton.setOnClickListener { vm.onTeamPlusPressed() }
        binding.startGameButton.setOnClickListener { vm.onStartGamePressed() }
        binding.playerSettingsButton.setOnClickListener { vm.onPlayerSettingsPressed() }
        // In a network game an admin's button is the server controls (End Game, talk to everyone)
        binding.endGameButton.setOnClickListener { if (vm.adminMenuInGame()) dialogs.showAdminMenu(it) else vm.onEndGamePressed() }
        binding.startRespawnButton.setOnClickListener { vm.onStartRespawnPressed() }
        binding.generateQrButton.setOnClickListener { vm.onGenerateQrPressed() }

        try {
            // Display app version
            title = getString(R.string.app_name_with_version, packageManager.getPackageInfo(packageName, 0).versionName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Unable to get app information from PackageManager: " + e.message)
        }

        binding.useNetworkButton.setOnClickListener { permissions.joinNetwork() }
        // Offline before a game: the game limits and the team mode, from one button
        binding.gameLimitButton.setOnClickListener { anchor ->
            PopupMenu(ContextThemeWrapper(this, R.style.AppTheme_PopupOverlay), anchor).apply {
                menu.add(0, 0, 0, R.string.game_limit_button)
                menu.add(0, 1, 1, R.string.team_mode_label)
                setOnMenuItemClickListener { item ->
                    if (item.itemId == 0) vm.onGameLimitPressed() else vm.onTeamModePressed()
                    true
                }
            }.show()
        }
        binding.adminControlsButton.setOnClickListener(dialogs::showAdminMenu)
        binding.useNetworkButton.visibility = View.VISIBLE
        vm.onViewsCreated()
    }

    /** The home screen's top bar: About (the game screen hides the bar). */
    /**
     * On a tablet or Chromebook the home screen's cards are three quarters of the window wide,
     * centred, rather than stretched edge to edge; a phone's are the full width.
     */
    private fun fitHomeToWindow() {
        val config = resources.configuration
        val content = binding.connectContent
        val lp = content.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        if (config.smallestScreenWidthDp >= ScreenOrientation.LARGE_SCREEN_DP) {
            lp.width = (config.screenWidthDp * HOME_WIDTH_SHARE * resources.displayMetrics.density).toInt()
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
        } else {
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        }
        content.layoutParams = lp
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // This screen keeps itself across size changes (the manifest): refit the home screen
        fitHomeToWindow()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_about) {
            AboutDialog.show(this)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val saved = vm.saveState()
        outState.putByte("mSelectedPlayerID", saved.selectedPlayerId)
        outState.putString("mDeviceAddress", saved.deviceAddress)
        outState.putBoolean("mUseNetwork", saved.useNetwork)
        outState.putInt("mNetworkMenuType", saved.networkMenuType)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onPause() {
        super.onPause()
        vm.onPause()
    }

    override fun onDestroy() {
        vm.onScreenDestroyed()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // QR pairing
    // ---------------------------------------------------------------------------------------

    private fun startQRScan() {
        try {
            val options = ScanOptions()
            options.setPrompt(getString(R.string.scan_qr_prompt))
            options.setOrientationLocked(false)
            options.setBeepEnabled(true)
            options.captureActivity = CaptureActivity::class.java
            barcodeLauncher.launch(options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch built-in QR scanner: " + e.message)
            Toast.makeText(this, "Could not launch QR scanner", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------------------------------
    // GameNavigator
    // ---------------------------------------------------------------------------------------

    override fun requestConnect() {
        // No blaster here, so no Bluetooth prompts (e.g. from the adapter turning on).
        if (serverOnly) return
        if (GameViewModel.TEST_NETWORK) {
            renderer.showPlayScreen()
            return
        }
        permissions.connect()
    }

    override fun finishScreen() {
        finish()
    }

    override fun openDedicatedServer() {
        startActivity(Intent(this, DedicatedServerActivity::class.java))
    }

    companion object {
        /** How much of a tablet or Chromebook window the home screen's cards take. */
        private const val HOME_WIDTH_SHARE = 0.75f
        private const val TAG = "scmain"

        /**
         * Brings the running task back as it was, like tapping the launcher icon. A plain intent
         * for this class would stack a second, fresh copy of the screen (back on the connect
         * layout) on top of the one holding the session.
         */
        fun resumeIntent(context: Context): Intent =
            asResumeIntent(Intent(context, FullscreenActivity::class.java))

        /** The launcher's action and category are what let Android match the existing task. */
        internal fun asResumeIntent(intent: Intent): Intent =
            intent.setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}
