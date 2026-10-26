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

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import android.animation.ObjectAnimator
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.domain.mode.GameResult
import com.simplecoil.simplecoil.domain.loadout.ShotRank
import com.simplecoil.simplecoil.domain.map.DistanceUnits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.BlasterDisconnectedDialogBinding
import com.simplecoil.simplecoil.databinding.ConnectStatusDialogBinding
import com.simplecoil.simplecoil.databinding.PlayerDataDialogBinding
import com.simplecoil.simplecoil.databinding.PlayerNameDialogBinding
import com.simplecoil.simplecoil.databinding.ServerIpDialogBinding
import com.simplecoil.simplecoil.ui.dialogs.AppDialogFragment
import com.simplecoil.simplecoil.ui.scoreboard.ScoreMatrixState
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardAdapter
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardRank
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardRows

/*
 * The play screen's dialogs as DialogFragments, so they survive recreation. Each one is built
 * exactly like the AlertDialog FullscreenActivity used to build inline (same layout, theme,
 * inflater context, buttons and cancelability) and reports back to GameViewModel.
 */

/** A play screen dialog; reaches the activity's GameViewModel (creating it if restored first). */
abstract class GameDialogFragment : AppDialogFragment() {
    protected val vm: GameViewModel by lazy {
        val activity = requireActivity()
        ViewModelProvider(activity, GameViewModelFactory(activity.application as SimpleCoilApp))[GameViewModel::class.java]
    }
}

/** Progress while connecting to a blaster; shows [GameViewModel.connectStatus], with Cancel. */
class ConnectStatusDialog : GameDialogFragment() {
    private var binding: ConnectStatusDialogBinding? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val b = ConnectStatusDialogBinding.inflate(appInflater)
        binding = b
        refresh()
        return alertBuilder()
            .setView(b.root)
            .setNegativeButton(R.string.cancel) { _, _ -> vm.onConnectCancelled() }
            .create()
    }

    /** Show the view model's current status. */
    fun refresh() {
        val status = vm.connectStatus ?: return
        binding?.connectStatusTv?.setText(when (status) {
            ConnectStatus.SCANNING -> R.string.connect_status_scanning
            ConnectStatus.CONNECTING -> R.string.connect_status_connecting
            ConnectStatus.COMMUNICATING -> R.string.connect_status_communicating
        })
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }
}

/** "Blaster disconnected" with Disconnect & Quit; shown until the link recovers. */
class BlasterDisconnectedDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        return alertBuilder()
            .setView(BlasterDisconnectedDialogBinding.inflate(appInflater).root)
            .setNegativeButton(R.string.blaster_disconnected_disconnect_quit) { dialog, _ ->
                vm.onDisconnectQuit()
                dialog.cancel()
            }
            .create()
    }
}

class EndGameConfirmDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        alertBuilder()
            .setTitle(R.string.end_game_dialog_title)
            .setMessage(R.string.end_game_dialog_message)
            .setPositiveButton(R.string.yes) { _, _ -> vm.onEndGameConfirmed() }
            .setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
            .create()
}

/** A grenade is pairing near the blaster: take it? On a server the first Yes gets it, and the prompt goes from the other phones. */
class GrenadeOfferDialog : GameDialogFragment() {
    private val grenadeId get() = requireArguments().getInt(ARG_GRENADE)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        alertBuilder()
            .setTitle(R.string.grenade_offer_title)
            .setMessage(R.string.grenade_offer_message)
            .setPositiveButton(R.string.yes) { _, _ -> vm.onGrenadeOfferAnswered(grenadeId, true) }
            .setNegativeButton(R.string.no) { _, _ -> vm.onGrenadeOfferAnswered(grenadeId, false) }
            .create()

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        vm.onGrenadeOfferAnswered(grenadeId, false)
    }

    companion object {
        private const val ARG_GRENADE = "grenade"

        fun newInstance(grenadeId: Int) = GrenadeOfferDialog().apply { arguments = bundleOf(ARG_GRENADE to grenadeId) }
    }
}

/** An admin asked to reset the server after a game. */
class ResetConfirmDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        alertBuilder()
            .setMessage(requireArguments().getInt(ARG_DISCONNECTED).let { resources.getQuantityString(R.plurals.dedicated_reset_confirm, it, it) })
            .setPositiveButton(R.string.reset_game_button) { _, _ -> vm.onResetConfirmed() }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .create()

    companion object {
        private const val ARG_DISCONNECTED = "disconnected"

        fun newInstance(disconnectedPlayers: Int) =
            ResetConfirmDialog().apply { arguments = bundleOf(ARG_DISCONNECTED to disconnectedPlayers) }
    }
}

class PlayerNameDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val binding = PlayerNameDialogBinding.inflate(appInflater)
        binding.playerNameEt.filters = arrayOf(PlayerNameInputFilter())
        binding.playerNameEt.setText(requireArguments().getString(ARG_TEXT))
        return alertBuilder()
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                vm.onPlayerNameEntered(binding.playerNameEt.text.toString().trim())
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }

    companion object {
        fun newInstance(current: String) = PlayerNameDialog().apply { arguments = bundleOf(ARG_TEXT to current) }
    }
}

class ServerIpDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val binding = ServerIpDialogBinding.inflate(appInflater)
        binding.serverIpEt.setText(requireArguments().getString(ARG_TEXT))
        return alertBuilder()
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                vm.onServerIpEntered(binding.serverIpEt.text.toString())
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }

    companion object {
        fun newInstance(suggestion: String) = ServerIpDialog().apply { arguments = bundleOf(ARG_TEXT to suggestion) }
    }
}

/**
 * The scoreboard; GameViewModel pushes new data into it while it is open. Two faces: the list, and
 * the score matrix (SCORE_MATRIX_DESIGN.md §5.4), switched with the neutral button. The face is
 * the view model's, so a recreated dialog comes back on the same one.
 */
class ScoreboardDialog : GameDialogFragment() {
    private var adapter: ScoreboardAdapter? = null
    private var binding: PlayerDataDialogBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated with the activity: ask for fresh data (and the matrix, if that face was
        // showing), which arrives through setData() and setMatrix().
        if (savedInstanceState != null) vm.openScoreboard()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val binding = PlayerDataDialogBinding.inflate(activity.layoutInflater)
        this.binding = binding
        binding.scoreMatrix.maxHeightPx = (resources.displayMetrics.heightPixels * MATRIX_MAX_HEIGHT_FRACTION).toInt()
        val players = vm.scoreboardPlayers ?: arrayOfNulls(GameSettingsRepository.MAX_PLAYER_ID + 2)
        val adapter = ScoreboardAdapter(isClient = true, listener = object : ScoreboardAdapter.Listener {
            override fun onTeamHeaderClicked() = vm.onTeamRatiosRequested()
            override fun onKickClicked(playerId: Byte) = vm.onKickPlayer(playerId)
            override fun onMuteClicked(playerId: Byte) = vm.onMuteClicked(playerId)
            override fun onMuteTeamClicked(team: Int) = vm.onMuteTeamClicked(team)
            override fun onRankChanged(rank: ScoreboardRank) {
                vm.scoreboardRank = rank
                lastPlayers?.let(::setData)
            }
        })
        this.adapter = adapter
        binding.playerList.layoutManager = LinearLayoutManager(activity)
        binding.playerList.adapter = adapter
        setData(players)
        showFace()
        // An objective game's result arrives a moment after the game ends
        lifecycleScope.launch { appContainer.modeController.state.collect { showResult() } }
        // After a game on a dedicated server, its map: kills, heat map and replay
        val gameOver = appContainer.gameState.gameState == GameStateRepository.GAME_STATE_NONE
        binding.postMapButton.visibility =
            if (gameOver && vm.networkMenuType == GameViewModel.NETWORK_TYPE_JOINED) View.VISIBLE else View.GONE
        binding.postMapButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), com.simplecoil.simplecoil.ui.map.PostGameMapActivity::class.java))
        }
        val builder = alertBuilder()
            .setNegativeButton(R.string.ok) { dialog, _ -> dialog.cancel() }
            .setView(binding.root)
        // Only a dedicated server has a matrix. The listener is set once shown: a button's
        // own listener would close the dialog.
        val matrixAvailable = vm.networkMenuType == GameViewModel.NETWORK_TYPE_JOINED
        if (matrixAvailable) builder.setNeutralButton(R.string.score_matrix, null)
        val dialog = builder.create()
        if (matrixAvailable) {
            dialog.setOnShowListener {
                val toggle = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                toggle.setText(nextFaceName())
                toggle.setOnClickListener {
                    vm.onScoreboardFaceNext()
                    showFace()
                    toggle.setText(nextFaceName())
                }
            }
        }
        return dialog
    }

    /** What the button switches to: list, matrix, longest shots, and round again. */
    private fun nextFaceName() = when {
        vm.longestShotsVisible -> R.string.scoreboard
        vm.scoreMatrixVisible -> R.string.longest_shots
        else -> R.string.score_matrix
    }

    fun setData(players: Array<PlayerDisplayData?>) {
        adapter?.submitList(ScoreboardRows.build(players, vm.scoreboardContext()))
        lastPlayers = players
        showResult()
    }

    /** The players last shown, for a classic team game's result. */
    private var lastPlayers: Array<PlayerDisplayData?>? = null

    private val appContainer get() = (requireActivity().application as SimpleCoilApp).container

    /** The result this dialog last animated, so it plays once. */
    private var resultShown: GameResult? = null

    /**
     * Victory, Defeat or Draw at the top once a game is over, in every mode but classic free for
     * all (map design: Longest shot and post-game stats): a win springs in over confetti, a loss
     * drops in and shakes, a draw settles in.
     */
    private fun showResult() {
        if (!isAdded) return
        val b = binding ?: return
        val container = appContainer
        val over = container.gameState.gameState == GameStateRepository.GAME_STATE_NONE
        val id = container.players.playerID.toInt()
        val layout = container.settings.teamLayout
        val freeForAll = layout.mode.isFreeForAll
        // Every game mode, classic team games too (by the teams' totals); not classic free for all
        val points = lastPlayers.orEmpty().filterNotNull().filter { it.playerID > 0 }.associate { it.playerID.toInt() to it.points }
        val result = if (over) GameResult.of(container.modeController.state.value, id, if (freeForAll) id else layout.teamOf(id),
            freeForAll, points) { layout.teamOf(it) } else null
        if (result == null) {
            b.gameResultLayout.visibility = View.GONE
            resultShown = null
            return
        }
        if (result == resultShown) return
        resultShown = result
        val won = result.outcome == GameResult.Outcome.VICTORY
        b.gameResultLayout.visibility = View.VISIBLE
        b.gameResultTv.setText(when (result.outcome) {
            GameResult.Outcome.VICTORY -> R.string.mode_victory_big
            GameResult.Outcome.DEFEAT -> R.string.mode_defeat_big
            GameResult.Outcome.DRAW -> R.string.mode_draw_big
        })
        b.gameResultTv.setTextColor(when (result.outcome) {
            GameResult.Outcome.VICTORY -> VICTORY_COLOR
            GameResult.Outcome.DEFEAT -> DEFEAT_COLOR
            GameResult.Outcome.DRAW -> DRAW_COLOR
        })
        b.gameResultScoreTv.text = getString(R.string.mode_result_score, result.mine, result.theirs)
        val tv = b.gameResultTv
        tv.animate().cancel()
        if (result.outcome == GameResult.Outcome.DRAW) {
            // Neither: it just settles in
            tv.alpha = 0f
            tv.animate().alpha(1f).setDuration(600).start()
        } else if (won) {
            tv.scaleX = 0.2f
            tv.scaleY = 0.2f
            tv.alpha = 0f
            tv.rotation = -12f
            tv.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(650)
                .setInterpolator(OvershootInterpolator(3f))
                .withEndAction { pulse(tv, 3) }
                .start()
            b.gameResultConfetti.post { b.gameResultConfetti.burst() }
        } else {
            tv.translationY = -80f * resources.displayMetrics.density
            tv.alpha = 0f
            tv.animate().translationY(0f).alpha(1f).setDuration(800)
                .setInterpolator(BounceInterpolator())
                .withEndAction {
                    val d = resources.displayMetrics.density
                    ObjectAnimator.ofFloat(tv, View.TRANSLATION_X, 0f, -10 * d, 10 * d, -7 * d, 7 * d, -3 * d, 3 * d, 0f)
                        .setDuration(450).start()
                }
                .start()
        }
    }

    /** A winner's glow: [times] gentle swells. */
    private fun pulse(view: View, times: Int) {
        if (times <= 0 || binding == null) return
        view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(260).setInterpolator(null).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(260).withEndAction { pulse(view, times - 1) }.start()
        }.start()
    }

    /** Shows the list or the matrix, as the view model has it. */
    private fun showFace() {
        val b = binding ?: return
        val matrix = vm.scoreMatrixVisible
        val shots = vm.longestShotsVisible
        val face = when {
            matrix -> b.scoreMatrixFace
            shots -> b.longestShotsFace
            else -> b.playerList
        }
        // Switching faces: the old one fades as the new one slides in from the side, and the box
        // grows or shrinks to it, rather than everything jumping
        if (!face.isVisible && b.root.isLaidOut) {
            (face.parent as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it, TransitionSet()
                .addTransition(Fade()).addTransition(ChangeBounds()).setDuration(FACE_MILLIS)) }
            face.translationX = FACE_SLIDE_DP * resources.displayMetrics.density
            face.animate().translationX(0f).setDuration(FACE_MILLIS).setInterpolator(DecelerateInterpolator()).start()
        }
        b.playerList.visibility = if (matrix || shots) View.GONE else View.VISIBLE
        b.scoreMatrixFace.visibility = if (matrix) View.VISIBLE else View.GONE
        b.longestShotsFace.visibility = if (shots) View.VISIBLE else View.GONE
        if (matrix) setMatrix(vm.scoreMatrixState ?: ScoreMatrixState.Loading)
        shotsJob?.cancel()
        if (shots) shotsJob = lifecycleScope.launch { pollLongestShots(b) }
    }

    private var shotsJob: Job? = null
    private var shotsShown = false

    /** Asks the server for the ranking every few seconds while that face shows (map design: Longest shot). */
    private suspend fun pollLongestShots(b: PlayerDataDialogBinding) {
        val container = (requireActivity().application as SimpleCoilApp).container
        if (!shotsShown) {
            b.longestShotsProgress.visibility = View.VISIBLE
            b.longestShotsMessage.visibility = View.GONE
        }
        while (true) {
            val server = container.gameState.serverIP
            val ranking = server?.let { withContext(Dispatchers.IO) { container.fieldImageryClient.shotRanking(it) } }
            val units = container.playerFieldConfig.config.value?.rules?.units ?: DistanceUnits.defaultFor(Locale.getDefault())
            setLongestShots(b, ranking, units)
            delay(SHOTS_REFRESH_MILLIS)
        }
    }

    private fun setLongestShots(b: PlayerDataDialogBinding, ranking: List<ShotRank>?, units: DistanceUnits) {
        shotsShown = true
        b.longestShotsProgress.visibility = View.GONE
        val message = when {
            ranking == null -> R.string.longest_shots_unsupported
            ranking.isEmpty() -> R.string.longest_shots_empty
            else -> 0
        }
        b.longestShotsMessage.visibility = if (message != 0) View.VISIBLE else View.GONE
        if (message != 0) b.longestShotsMessage.setText(message)
        val list = b.longestShotsList
        list.removeAllViews()
        val density = resources.displayMetrics.density
        ranking.orEmpty().forEachIndexed { index, rank ->
            val row = LinearLayout(list.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            }
            val color = if (index == 0) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt()
            row.addView(TextView(list.context).apply {
                text = getString(R.string.longest_shots_rank, index + 1)
                setTextColor(color)
                textSize = 18f
                minWidth = (36 * density).toInt()
            })
            row.addView(LinearLayout(list.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(list.context).apply {
                    text = rank.name
                    setTextColor(color)
                    textSize = 18f
                })
                addView(TextView(list.context).apply {
                    text = getString(R.string.longest_shots_victim, rank.victimName)
                    setTextColor(0xFFAAAAAA.toInt())
                    textSize = 13f
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(list.context).apply {
                text = units.format(rank.meters)
                setTextColor(color)
                textSize = 18f
            })
            list.addView(row)
        }
    }

    /** The matrix face's content: progress, a message, or the grid. */
    fun setMatrix(state: ScoreMatrixState) {
        val b = binding ?: return
        val ready = state as? ScoreMatrixState.Ready
        if (ready != null) b.scoreMatrix.setMatrix(ready.matrix)
        val message = when {
            state == ScoreMatrixState.Unsupported -> R.string.score_matrix_unsupported
            ready != null && ready.matrix.isEmpty -> R.string.score_matrix_empty
            else -> 0
        }
        b.scoreMatrixProgress.visibility = if (state == ScoreMatrixState.Loading) View.VISIBLE else View.GONE
        b.scoreMatrix.visibility = if (ready != null && message == 0) View.VISIBLE else View.GONE
        b.scoreMatrixMessage.visibility = if (message != 0) View.VISIBLE else View.GONE
        if (message != 0) b.scoreMatrixMessage.setText(message)
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Closed by the user, not torn down with the activity.
        if (!isStateSaved) vm.onScoreboardDismissed()
    }
}

private const val ARG_TEXT = "text"

/** How often an open longest shots ranking asks the server again. */
private const val SHOTS_REFRESH_MILLIS = 5_000L

private const val VICTORY_COLOR = 0xFFFFD54F.toInt()
private const val DEFEAT_COLOR = 0xFFEF5350.toInt()
private const val DRAW_COLOR = 0xFFB0BEC5.toInt()

/** The score matrix grows to at most this much of the window's height, then scrolls. */
private const val MATRIX_MAX_HEIGHT_FRACTION = 0.8f

/** How the scoreboard switches faces: how long, and how far the new one slides in from. */
private const val FACE_MILLIS = 260L
private const val FACE_SLIDE_DP = 32f
