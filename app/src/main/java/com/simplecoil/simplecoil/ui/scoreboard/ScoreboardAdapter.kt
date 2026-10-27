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

package com.simplecoil.simplecoil.ui.scoreboard

import android.content.res.ColorStateList
import android.graphics.Color
import android.content.ClipData
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import android.view.ContextThemeWrapper
import android.widget.PopupMenu
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.domain.team.TeamColors
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler

/**
 * The scoreboard of the dedicated server and of the play screen's scoreboard dialog. Updates are
 * diffed, so the list keeps its scroll position (S10).
 */
class ScoreboardAdapter(
    private val isClient: Boolean,
    private val listener: Listener,
) : ListAdapter<ScoreboardRow, RecyclerView.ViewHolder>(Diff) {

    interface Listener {
        /** An editable team header was tapped. */
        fun onTeamHeaderClicked()

        /** A player row was tapped (the server opens its settings). */
        fun onPlayerClicked(playerId: Byte) {}

        fun onKickClicked(playerId: Byte)

        /** Voice chat: mute or unmute [playerId] on this phone. */
        fun onMuteClicked(playerId: Byte) {}

        /** Voice chat: mute or unmute your [team] on this phone. */
        fun onMuteTeamClicked(team: Int) {}

        /** The rank-by dropdown picked [rank]. */
        fun onRankChanged(rank: ScoreboardRank) {}

        /** Squad mode: Create squad. */
        fun onCreateSquad() {}

        /** Squad mode: break up [squad], its players back to playing alone. */
        fun onDeleteSquad(squad: Int) {}

        /** Squad mode: done editing the squad. */
        fun onSquadDone() {}

        /** Squad mode: a squad's header, to edit that squad (or stop, if it's the one). */
        fun onSquadHeaderClicked(squad: Int) {}

        /** Squad mode, editing a squad: [playerId] in ([add]) or out. */
        fun onSquadToggle(playerId: Byte, add: Boolean) {}

        /** [playerId] was dragged onto [team]: a team, a squad's team, or 0 to play alone (squad mode). */
        fun onPlayerDropped(playerId: Byte, team: Int) {}
    }

    // Dragging a player onto another team: long-press a player row, drop it anywhere under a team's
    // header (that header lights up)

    /** The header a dragged player would join, lit up; null when nothing is being dragged. */
    private var dropHeader: Int? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.setOnDragListener { _, event ->
            val playerId = event.localState as? Byte ?: return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_LOCATION -> lightUp(dropHeaderAt(recyclerView, event.x, event.y))
                DragEvent.ACTION_DROP -> {
                    val header = dropHeaderAt(recyclerView, event.x, event.y)
                    lightUp(null)
                    val team = header?.let { (getItem(it) as ScoreboardRow.TeamHeader).dropTeam }
                    if (team != null) listener.onPlayerDropped(playerId, team)
                }
                DragEvent.ACTION_DRAG_ENDED -> lightUp(null)
            }
            true
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.setOnDragListener(null)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /** The position of the header a player dropped at ([x], [y]) would join: the nearest one above, else the first below. */
    private fun dropHeaderAt(recyclerView: RecyclerView, x: Float, y: Float): Int? {
        val child = recyclerView.findChildViewUnder(x, y)
        val at = child?.let { recyclerView.getChildAdapterPosition(it) }?.takeIf { it != RecyclerView.NO_POSITION }
            ?: if (y < 0f) 0 else itemCount - 1
        fun target(i: Int) = (getItem(i) as? ScoreboardRow.TeamHeader)?.dropTeam != null
        return (at downTo 0).firstOrNull(::target) ?: (at until itemCount).firstOrNull(::target)
    }

    private fun lightUp(header: Int?) {
        if (header == dropHeader) return
        val old = dropHeader
        dropHeader = header
        old?.let { notifyItemChanged(it, PAYLOAD_DROP) }
        header?.let { notifyItemChanged(it, PAYLOAD_DROP) }
    }

    private fun startDrag(view: View, playerId: Byte): Boolean {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return ViewCompat.startDragAndDrop(view, ClipData.newPlainText("player", playerId.toString()),
            View.DragShadowBuilder(view), playerId, 0)
    }

    /** The mute buttons: heard, or muted here. */
    private fun showMute(view: ImageView, muted: Boolean?, mutedText: Int, heardText: Int, onClick: () -> Unit) {
        if (muted == null) {
            view.visibility = View.GONE
            view.setOnClickListener(null)
            return
        }
        view.setImageResource(if (muted) R.drawable.ic_voice_muted else R.drawable.ic_voice_on)
        view.contentDescription = view.context.getString(if (muted) mutedText else heardText)
        view.visibility = View.VISIBLE
        view.setOnClickListener { onClick() }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ScoreboardRow.TeamHeader -> TYPE_TEAM_HEADER
        is ScoreboardRow.SquadBar -> TYPE_SQUAD_BAR
        is ScoreboardRow.RankBar -> TYPE_RANK_BAR
        else -> TYPE_PLAYER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_TEAM_HEADER) {
            TeamHeaderHolder(inflater.inflate(R.layout.player_display_team_header, parent, false))
        } else if (viewType == TYPE_SQUAD_BAR) {
            SquadBarHolder(inflater.inflate(R.layout.scoreboard_squad_bar, parent, false))
        } else if (viewType == TYPE_RANK_BAR) {
            RankBarHolder(inflater.inflate(R.layout.scoreboard_rank_bar, parent, false))
        } else {
            val layout = if (isClient) R.layout.player_display_data_client else R.layout.player_display_data
            PlayerHolder(inflater.inflate(layout, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ScoreboardRow.TeamHeader -> (holder as TeamHeaderHolder).bind(row)
            is ScoreboardRow.ColumnHeader -> (holder as PlayerHolder).bindColumnHeader(row)
            is ScoreboardRow.Player -> (holder as PlayerHolder).bind(row)
            is ScoreboardRow.SquadBar -> (holder as SquadBarHolder).bind(row)
            is ScoreboardRow.RankBar -> (holder as RankBarHolder).bind(row)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        // Only the drop highlight changed: no need to bind it all again
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_DROP } && holder is TeamHeaderHolder) holder.showDropHighlight(position == dropHeader)
        else onBindViewHolder(holder, position)
    }

    private inner class RankBarHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val choice: TextView = view.findViewById(R.id.rank_by_choice_tv)

        fun bind(row: ScoreboardRow.RankBar) {
            val context = itemView.context
            val teams = if (row.squads) R.string.rank_by_squad else R.string.rank_by_team
            choice.setText(if (row.rank == ScoreboardRank.TEAMS) teams else R.string.rank_by_player)
            choice.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_dropdown, 0)
            choice.setOnClickListener { anchor ->
                val menu = PopupMenu(ContextThemeWrapper(context, R.style.AppTheme_PopupOverlay), anchor)
                menu.menu.add(0, 0, 0, teams)
                menu.menu.add(0, 1, 1, R.string.rank_by_player)
                menu.setOnMenuItemClickListener { item ->
                    listener.onRankChanged(if (item.itemId == 0) ScoreboardRank.TEAMS else ScoreboardRank.PLAYERS)
                    true
                }
                menu.show()
            }
        }
    }

    private inner class SquadBarHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val hint: TextView = view.findViewById(R.id.squad_bar_hint_tv)
        private val button: MaterialButton = view.findViewById(R.id.squad_bar_button)
        private val delete: MaterialButton = view.findViewById(R.id.squad_bar_delete_button)

        fun bind(row: ScoreboardRow.SquadBar) {
            val context = itemView.context
            if (row.editing != null) {
                hint.text = context.getString(R.string.squad_editing_hint, row.editing)
                button.setText(R.string.squad_done)
                button.icon = null
                button.isEnabled = true
                button.setOnClickListener { listener.onSquadDone() }
                delete.visibility = View.VISIBLE
                delete.setOnClickListener { listener.onDeleteSquad(row.editing) }
            } else {
                hint.setText(if (row.full) R.string.squad_full_hint else R.string.squad_create_hint)
                button.setText(R.string.squad_create)
                button.setIconResource(R.drawable.ic_squad_add)
                button.isEnabled = !row.full
                button.setOnClickListener { listener.onCreateSquad() }
                delete.visibility = View.GONE
            }
        }
    }

    private inner class TeamHeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.team_header_title_tv)
        private val action: TextView = view.findViewById(R.id.team_header_action_tv)
        private val colorSquare: View? = view.findViewById(R.id.team_header_color_sq)
        private val mute: ImageView? = view.findViewById(R.id.team_mute_iv)

        fun showDropHighlight(on: Boolean) {
            itemView.foreground = if (on) ColorDrawable(DROP_HIGHLIGHT) else null
        }

        fun bind(row: ScoreboardRow.TeamHeader) {
            val context = itemView.context
            showDropHighlight(bindingAdapterPosition == dropHeader)
            if (row.team == 0) {
                title.setText(R.string.scoreboard_players_header)
                action.visibility = View.GONE
                colorSquare?.visibility = View.GONE
            } else if (row.solo) {
                // One player: their score on the bar; several: each on their own row
                title.text = if (row.playerCount == 1) context.getString(R.string.squad_solo_header, row.score) else context.getString(R.string.squad_solo_group)
                showColor(Color.TRANSPARENT)
                action.visibility = View.GONE
                mute?.visibility = View.GONE
                itemView.setOnClickListener(null)
                itemView.isClickable = false
                return
            } else if (row.squad != null) {
                title.text = context.getString(R.string.squad_header, row.squad,
                    context.resources.getQuantityString(R.plurals.network_player_count, row.playerCount, row.playerCount), row.score)
                showColor(row.color)
                // Tap a squad's bar to add or take out players; the one being edited says so
                action.visibility = if (row.editable) View.VISIBLE else View.GONE
                action.setText(if (row.editing) R.string.squad_editing else R.string.squad_tap_to_edit)
                mute?.let { showMute(it, row.allMuted, R.string.cd_unmute_team, R.string.cd_mute_team) { listener.onMuteTeamClicked(row.team) } }
                if (row.editable) itemView.setOnClickListener { listener.onSquadHeaderClicked(row.squad) } else itemView.setOnClickListener(null)
                itemView.isClickable = row.editable
                return
            } else {
                title.text = context.getString(R.string.player_list_team_header, row.team, row.playerCount, row.capacity, row.score)
                colorSquare?.let {
                    val color = row.color
                    if (color != Color.TRANSPARENT) {
                        it.setBackgroundColor(color)
                        it.visibility = View.VISIBLE
                    } else {
                        it.visibility = View.GONE
                    }
                }
                action.visibility = if (row.editable) View.VISIBLE else View.GONE
                action.setText(R.string.scoreboard_edit_ratio)
            }
            mute?.let { showMute(it, row.allMuted, R.string.cd_unmute_team, R.string.cd_mute_team) { listener.onMuteTeamClicked(row.team) } }
            if (row.editable) itemView.setOnClickListener { listener.onTeamHeaderClicked() } else itemView.setOnClickListener(null)
            itemView.isClickable = row.editable
        }

        private fun showColor(color: Int) {
            colorSquare?.let {
                it.setBackgroundColor(color)
                it.visibility = if (color != Color.TRANSPARENT) View.VISIBLE else View.GONE
            }
        }
    }

    private inner class PlayerHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.player_name_tv)
        private val points: TextView = view.findViewById(R.id.player_points_tv)
        private val stat: TextView = view.findViewById(R.id.player_eliminated_tv)
        private val blaster: ImageView = view.findViewById(R.id.blaster_type_iv)
        private val grenade: ImageView = view.findViewById(R.id.grenade_iv)
        private val networkStatus: ImageView? = view.findViewById(R.id.network_status_iv)
        private val kick: ImageView? = view.findViewById(R.id.kick_player_iv)
        private val mute: ImageView? = view.findViewById(R.id.mute_player_iv)
        private val squadToggle: ImageView? = view.findViewById(R.id.squad_toggle_iv)

        fun bindColumnHeader(row: ScoreboardRow.ColumnHeader) {
            name.setText(R.string.player_list_name_label)
            TextViewCompat.setTextAppearance(name, R.style.TextAppearance_SimpleCoil_Label)
            points.setText(R.string.player_list_points_label)
            stat.setText(if (row.livesColumn) R.string.scoreboard_lives else R.string.player_list_eliminated_label)
            // Invisible, not gone: the icons keep their space so the columns line up
            blaster.visibility = View.INVISIBLE
            grenade.visibility = View.INVISIBLE
            networkStatus?.visibility = View.GONE
            kick?.visibility = View.GONE
            kick?.setOnClickListener(null)
            mute?.visibility = View.GONE
            mute?.setOnClickListener(null)
            itemView.setOnClickListener(null)
            squadToggle?.visibility = View.GONE
            itemView.isClickable = false
            itemView.setOnLongClickListener(null)
            itemView.isLongClickable = false
        }

        fun bind(row: ScoreboardRow.Player) {
            val context = itemView.context
            TextViewCompat.setTextAppearance(name, R.style.TextAppearance_SimpleCoil_Body)
            name.setTextColor(if (row.isSelf) ScoreboardColors.SELF_NAME else ScoreboardColors.NAME)
            name.text = row.name
            // Ranked by player: their team's or squad's colour beside the name
            name.setCompoundDrawablesRelative(if (row.color == Color.TRANSPARENT) null else GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(row.color)
                val size = (10 * context.resources.displayMetrics.density).toInt()
                setBounds(0, 0, size, size)
            }, null, null, null)
            name.compoundDrawablePadding = (8 * context.resources.displayMetrics.density).toInt()
            points.text = context.getString(R.string.number, row.points)
            stat.text = context.getString(R.string.number, row.livesOrEliminations)
            when (row.blasterType) {
                GattProtocolHandler.BLASTER_TYPE_RIFLE -> showIcon(blaster, R.drawable.ic_blaster_rifle, R.string.cd_blaster_rifle)
                GattProtocolHandler.BLASTER_TYPE_PISTOL -> showIcon(blaster, R.drawable.ic_blaster_pistol, R.string.cd_blaster_pistol)
                else -> blaster.visibility = View.INVISIBLE
            }
            if (row.hasGrenade) showIcon(grenade, R.drawable.ic_grenade, R.string.cd_grenade_paired) else grenade.visibility = View.INVISIBLE
            networkStatus?.let {
                it.visibility = View.VISIBLE
                it.setImageResource(if (row.connected) R.drawable.ic_network_connected_24dp else R.drawable.ic_network_disconnected_24dp)
                it.contentDescription = context.getString(if (row.connected) R.string.scoreboard_connected else R.string.scoreboard_disconnected)
            }
            kick?.let {
                if (row.kickable) {
                    it.visibility = View.VISIBLE
                    it.setOnClickListener { listener.onKickClicked(row.playerId) }
                } else {
                    it.visibility = View.GONE
                    it.setOnClickListener(null)
                }
            }
            mute?.let { showMute(it, row.muted, R.string.cd_unmute_player, R.string.cd_mute_player) { listener.onMuteClicked(row.playerId) } }
            squadToggle?.let { toggle ->
                val add = row.squadAdd
                if (add == null) {
                    toggle.visibility = View.GONE
                    toggle.setOnClickListener(null)
                } else {
                    toggle.setImageResource(if (add) R.drawable.ic_squad_add else R.drawable.ic_squad_remove)
                    toggle.contentDescription = context.getString(if (add) R.string.cd_squad_add else R.string.cd_squad_remove)
                    toggle.visibility = View.VISIBLE
                    toggle.setOnClickListener { listener.onSquadToggle(row.playerId, add) }
                }
            }
            if (isClient) {
                itemView.setOnClickListener(null)
                itemView.isClickable = false
            } else {
                itemView.setOnClickListener { listener.onPlayerClicked(row.playerId) }
            }
            if (row.draggable) itemView.setOnLongClickListener { startDrag(it, row.playerId) } else {
                itemView.setOnLongClickListener(null)
                itemView.isLongClickable = false
            }
        }
    }

    private fun showIcon(view: ImageView, icon: Int, description: Int) {
        view.setImageResource(icon)
        view.contentDescription = view.context.getString(description)
        view.visibility = View.VISIBLE
    }

    private object Diff : DiffUtil.ItemCallback<ScoreboardRow>() {
        override fun areItemsTheSame(oldItem: ScoreboardRow, newItem: ScoreboardRow) = when (oldItem) {
            is ScoreboardRow.ColumnHeader -> newItem is ScoreboardRow.ColumnHeader
            is ScoreboardRow.TeamHeader -> newItem is ScoreboardRow.TeamHeader && newItem.team == oldItem.team
            is ScoreboardRow.SquadBar -> newItem is ScoreboardRow.SquadBar
            is ScoreboardRow.RankBar -> newItem is ScoreboardRow.RankBar
            is ScoreboardRow.Player -> newItem is ScoreboardRow.Player && newItem.playerId == oldItem.playerId
        }

        override fun areContentsTheSame(oldItem: ScoreboardRow, newItem: ScoreboardRow) = oldItem == newItem
    }

    private companion object {
        const val TYPE_TEAM_HEADER = 0
        const val TYPE_PLAYER = 1
        const val TYPE_SQUAD_BAR = 2
        const val TYPE_RANK_BAR = 3
        const val PAYLOAD_DROP = "drop"

        /** The header a dragged player would join: lit up with a white wash. */
        const val DROP_HIGHLIGHT = 0x40FFFFFF
    }
}
