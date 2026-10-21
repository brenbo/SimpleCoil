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

package com.simplecoil.simplecoil.ui.briefing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.simplecoil.simplecoil.AppContainer
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.ActivityBriefingBinding
import com.simplecoil.simplecoil.domain.briefing.Briefing
import com.simplecoil.simplecoil.domain.briefing.BriefingInputs
import com.simplecoil.simplecoil.domain.briefing.BriefingNow
import com.simplecoil.simplecoil.domain.briefing.Section
import com.simplecoil.simplecoil.domain.briefing.Slide
import com.simplecoil.simplecoil.domain.mode.GameMode
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
import com.simplecoil.simplecoil.ui.Spelling
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsValues
import kotlinx.coroutines.launch

/**
 * The host's briefing (the "how to play" slideshow): the blaster's buttons, the drops, and how
 * this game plays with its settings. On the dedicated server ([EXTRA_HOST]) it runs the show:
 * Next (the last slide's ends it), Skip to the game mode, Close. On a player's phone it can't be
 * closed: it shows whatever slide the host is on, and goes when the host ends it.
 */
class BriefingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBriefingBinding
    private lateinit var container: AppContainer
    private val host get() = intent.getBooleanExtra(EXTRA_HOST, false)
    private var slides: List<Slide> = emptyList()
    private var shown: String? = null
    private var shownSlide: Slide? = null
    private var shownIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as SimpleCoilApp).container
        binding = ActivityBriefingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (host) startAsHost(savedInstanceState) else followTheHost()
    }

    // ---- The host --------------------------------------------------------------------------

    private fun startAsHost(savedInstanceState: Bundle?) {
        slides = Briefing.slides(hostInputs(container))
        binding.briefingControls.visibility = android.view.View.VISIBLE
        val imagery = container.fieldImageryServer
        val startIdx = intent.getIntExtra(EXTRA_START_INDEX, 0)
        // A new showing from the requested slide index (or first slide), or the one it was on (the screen turned)
        val id = imagery.briefing?.takeIf { savedInstanceState != null }?.id ?: System.currentTimeMillis()
        val key = imagery.briefing?.takeIf { savedInstanceState != null }?.slide
            ?: slides.getOrNull(startIdx)?.key
            ?: slides.first().key
        imagery.briefing = BriefingNow(id, key)
        goTo(key)
        binding.briefingNextButton.setOnClickListener {
            val at = slides.indexOfFirst { it.key == shown }
            if (at < 0 || at >= slides.size - 1) end() else goTo(slides[at + 1].key)
        }
        binding.briefingSkipButton.setOnClickListener { Briefing.firstModeSlide(slides)?.let { goTo(it.key) } }
        binding.briefingCloseButton.setOnClickListener { end() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = end()
        })
    }

    /** Shows [key] here and sends the whole slide to the phones, so they say just what this says. */
    private fun goTo(key: String) {
        val imagery = container.fieldImageryServer
        val index = slides.indexOfFirst { it.key == key }
        val now = BriefingNow(imagery.briefing?.id ?: System.currentTimeMillis(), key, slides.getOrNull(index), index, slides.size)
        imagery.briefing = now
        show(now)
    }

    /** Over for everyone: the phones close theirs on their next look. */
    private fun end() {
        container.fieldImageryServer.briefing = null
        finish()
    }

    // ---- A player ---------------------------------------------------------------------------

    private fun followTheHost() {
        showing = true
        // Only the host closes it
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        slides = Briefing.slides(playerInputs(container))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.briefing.collect { now ->
                    if (now == null) {
                        finish()
                        return@collect
                    }
                    // The host's own slide when it sent one; an older server sends only the key
                    if (now.shown == null) slides = Briefing.slides(playerInputs(container))
                    show(now)
                }
            }
        }
    }

    override fun onDestroy() {
        if (!host) showing = false
        super.onDestroy()
    }

    // ---- Showing a slide -------------------------------------------------------------------

    private fun show(now: BriefingNow) {
        val key = now.slide
        val index = if (now.shown != null) now.index else slides.indexOfFirst { it.key == key }
        val total = if (now.shown != null) now.total else slides.size
        val slide = now.shown ?: slides.getOrNull(index)
        if (key == shown && slide == shownSlide) return
        val forward = index >= shownIndex
        shown = key
        shownSlide = slide
        shownIndex = index
        val b = binding
        b.briefingSectionTv.setText(when (slide?.section) {
            Section.BUTTONS -> R.string.briefing_section_buttons
            Section.DROPS -> R.string.briefing_section_drops
            else -> R.string.briefing_section_mode
        })
        b.briefingTitleTv.text = Spelling.local(this, slide?.title ?: "")
        b.briefingBodyTv.text = slide?.body?.let { Spelling.local(this, it) } ?: getString(R.string.briefing_waiting)
        slide?.let {
            b.briefingArt.art = it.art
            b.briefingArt.drop = it.drop
        }
        b.briefingArt.pistol = !host && container.sessionEngine.state.value.blasterType == GattProtocolHandler.BLASTER_TYPE_PISTOL
        b.briefingProgressTv.text = if (index >= 0) getString(R.string.briefing_progress, index + 1, total) else ""
        if (host) {
            b.briefingNextButton.setText(if (index >= total - 1) R.string.briefing_done else R.string.briefing_next)
            // Already on the game slides: nothing to skip to
            b.briefingSkipButton.visibility = if (slide?.section == Section.MODE) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
        // The new slide slides in, from the side it comes from
        val from = (if (forward) 1 else -1) * resources.displayMetrics.widthPixels * 0.35f
        b.briefingSlide.animate().cancel()
        b.briefingSlide.translationX = from
        b.briefingSlide.alpha = 0f
        b.briefingSlide.animate().translationX(0f).alpha(1f).setDuration(SLIDE_MILLIS).start()
        b.briefingTitleTv.scaleX = 0.85f
        b.briefingTitleTv.scaleY = 0.85f
        b.briefingTitleTv.animate().scaleX(1f).scaleY(1f).setStartDelay(SLIDE_MILLIS / 2).setDuration(SLIDE_MILLIS).start()
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_START_INDEX = "start_index"
        private const val SLIDE_MILLIS = 350L

        /** A player's briefing is on screen (so the play screen doesn't open a second). */
        @Volatile
        var showing = false
            private set

        fun hostIntent(context: Context, startIndex: Int = 0) =
            Intent(context, BriefingActivity::class.java)
                .putExtra(EXTRA_HOST, true)
                .putExtra(EXTRA_START_INDEX, startIndex)
        fun playerIntent(context: Context) = Intent(context, BriefingActivity::class.java)

        /** The game as the server will run it. */
        fun hostInputs(container: AppContainer): BriefingInputs {
            val snapshot = container.serverController.snapshot.value
            val settings = snapshot?.settings
            val field = container.serverFieldConfig.config.value
            // Respawning is set per player; the briefing uses the first player's (they're usually all the same)
            val player = snapshot?.players?.firstOrNull()?.let { container.serverController.playerSettings(it.playerId) } ?: PlayerSettingsValues()
            val limit = settings?.gameLimit ?: 0
            val gpsOn = settings?.useGps ?: true
            return BriefingInputs(
                field = field?.takeUnless { it.mode == GameMode.CLASSIC && !gpsOn },
                freeForAll = settings?.teamLayout?.mode?.isFreeForAll ?: true,
                teamCount = settings?.teamLayout?.mode?.teamCount ?: 0,
                respawnSeconds = player.spawnTimeSeconds,
                manualRespawn = player.manualRespawn,
                livesLimit = if (player.overrideLives) player.lives
                    else if (limit and GameSettingsRepository.GAME_LIMIT_LIVES != 0) settings?.livesLimit ?: 0 else 0,
                timeLimitMinutes = if (limit and GameSettingsRepository.GAME_LIMIT_TIME != 0) settings?.timeLimit ?: 0 else 0,
                scoreLimit = if (limit and GameSettingsRepository.GAME_LIMIT_SCORE != 0) settings?.scoreLimit ?: 0 else 0,
                voiceChat = container.fieldImageryServer.voice.enabled,
            )
        }

        /** The game as this phone knows it from the server. */
        fun playerInputs(container: AppContainer): BriefingInputs {
            val settings = container.settings
            val field = container.playerFieldConfig.config.value
            val limit = settings.gameLimit
            return BriefingInputs(
                field = field?.takeUnless { it.mode == GameMode.CLASSIC && !settings.isUseGPS },
                freeForAll = settings.teamLayout.mode.isFreeForAll,
                teamCount = settings.teamLayout.mode.teamCount,
                respawnSeconds = settings.respawnTime,
                manualRespawn = settings.isManualRespawn,
                livesLimit = if (settings.isOverrideLives) settings.overrideLivesVal
                    else if (limit and GameSettingsRepository.GAME_LIMIT_LIVES != 0) settings.livesLimit else 0,
                timeLimitMinutes = if (limit and GameSettingsRepository.GAME_LIMIT_TIME != 0) settings.timeLimit else 0,
                scoreLimit = if (limit and GameSettingsRepository.GAME_LIMIT_SCORE != 0) settings.scoreLimit else 0,
                voiceChat = container.voiceChat.enabled.value,
            )
        }
    }
}
