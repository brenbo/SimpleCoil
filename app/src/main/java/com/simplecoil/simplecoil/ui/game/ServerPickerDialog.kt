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
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.databinding.ServerPickerDialogBinding
import com.simplecoil.simplecoil.databinding.ServerPickerRowBinding
import com.simplecoil.simplecoil.network.discovery.DiscoveredServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Connect to server: shows available servers on the network with ping bars. Tapping one connects
 * to it. If only one server is found, auto-connects to it after 5 seconds.
 */
class ServerPickerDialog : GameDialogFragment() {

    private var binding: ServerPickerDialogBinding? = null
    private var ticker: Job? = null
    private var trackedSingleServerIp: String? = null
    private var countdownMs = AUTO_CONNECT_COUNTDOWN_MS
    private lateinit var adapter: ServerAdapter

    private val udp get() = (activity?.application as? SimpleCoilApp)?.container?.networkGateway?.udp

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val b = ServerPickerDialogBinding.inflate(appInflater)
        binding = b
        adapter = ServerAdapter { server ->
            udp?.joinServer(server.ip)
            dismissAllowingStateLoss()
        }
        b.serverPickerRecycler.adapter = adapter
        b.serverPickerRecycler.itemAnimator = SlideOutItemAnimator()

        return alertBuilder()
            .setTitle(R.string.server_picker_title)
            .setView(b.root)
            .setNegativeButton(R.string.cancel) { _, _ ->
                vm.onServerSearchCancelled()
            }
            .create()
    }

    override fun onStart() {
        super.onStart()
        val u = udp ?: return
        u.startDiscovery()
        ticker = lifecycleScope.launch {
            var lastTime = SystemClock.elapsedRealtime()
            var lastShownSeconds = -1
            while (true) {
                delay(TICK_MILLIS)
                val now = SystemClock.elapsedRealtime()
                val delta = now - lastTime
                lastTime = now

                val servers = u.discoveredServers.value

                if (servers.size == 1 && servers.first().canJoin) {
                    val singleServer = servers.first()
                    if (trackedSingleServerIp != singleServer.ip) {
                        trackedSingleServerIp = singleServer.ip
                        countdownMs = AUTO_CONNECT_COUNTDOWN_MS
                        lastShownSeconds = -1
                    } else {
                        countdownMs -= delta
                    }

                    if (countdownMs <= 0L) {
                        u.joinServer(singleServer.ip)
                        dismissAllowingStateLoss()
                        break
                    }

                    val secondsLeft = ceil(countdownMs / 1000.0).toInt().coerceIn(1, 5)
                    if (secondsLeft != lastShownSeconds) {
                        lastShownSeconds = secondsLeft
                        render(servers)
                        if (adapter.itemCount > 0) {
                            adapter.notifyItemChanged(0, "countdown")
                        }
                    }
                } else {
                    trackedSingleServerIp = null
                    countdownMs = AUTO_CONNECT_COUNTDOWN_MS
                    lastShownSeconds = -1
                    render(servers)
                }
            }
        }
    }

    override fun onStop() {
        ticker?.cancel()
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render(servers: List<DiscoveredServer>) {
        val b = binding ?: return
        b.serverPickerSearching.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(servers)
    }

    private inner class ServerAdapter(
        private val onClick: (DiscoveredServer) -> Unit,
    ) : ListAdapter<DiscoveredServer, ServerAdapter.ViewHolder>(DiffCallback) {

        inner class ViewHolder(val binding: ServerPickerRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ServerPickerRowBinding.inflate(inflater, parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val server = getItem(position)
            val row = holder.binding
            val bars = signalBarsOfPing(server.pingMs)
            row.serverSignalIv.setImageResource(when (bars) {
                4 -> R.drawable.ic_signal_4
                3 -> R.drawable.ic_signal_3
                2 -> R.drawable.ic_signal_2
                else -> R.drawable.ic_signal_1
            })
            row.serverNameTv.text = server.name
            row.serverAddressTv.text = server.ip

            val alpha = if (server.canJoin) 1.0f else 0.4f
            row.root.alpha = alpha
            row.serverSignalIv.alpha = alpha
            row.serverNameTv.alpha = alpha
            row.serverAddressTv.alpha = alpha
            row.root.isEnabled = server.canJoin

            if (!server.canJoin) {
                row.root.setOnClickListener(null)
                row.serverStatusTv.visibility = View.VISIBLE
                row.serverStatusTv.text = getString(R.string.server_picker_in_progress)
            } else {
                row.root.setOnClickListener { onClick(server) }
                val isSingle = (itemCount == 1)
                val secondsLeft = ceil(countdownMs / 1000.0).toInt().coerceIn(1, 5)
                if (isSingle && countdownMs > 0) {
                    row.serverStatusTv.visibility = View.VISIBLE
                    row.serverStatusTv.text = getString(R.string.server_picker_autoconnect, secondsLeft)
                } else {
                    row.serverStatusTv.visibility = View.GONE
                }
            }
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            onBindViewHolder(holder, position)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<DiscoveredServer>() {
        override fun areItemsTheSame(oldItem: DiscoveredServer, newItem: DiscoveredServer): Boolean =
            oldItem.ip == newItem.ip

        override fun areContentsTheSame(oldItem: DiscoveredServer, newItem: DiscoveredServer): Boolean =
            oldItem.name == newItem.name &&
                oldItem.ip == newItem.ip &&
                oldItem.canJoin == newItem.canJoin &&
                signalBarsOfPing(oldItem.pingMs) == signalBarsOfPing(newItem.pingMs)

        override fun getChangePayload(oldItem: DiscoveredServer, newItem: DiscoveredServer): Any =
            "update_payload"
    }

    private class SlideOutItemAnimator : DefaultItemAnimator() {
        init {
            supportsChangeAnimations = false
        }

        override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
            val view = holder.itemView
            view.animate()
                .translationX(view.width.toFloat())
                .alpha(0f)
                .setDuration(ANIM_MILLIS)
                .withEndAction {
                    view.translationX = 0f
                    view.alpha = 1f
                    dispatchRemoveFinished(holder)
                }
                .start()
            return true
        }

        private companion object {
            const val ANIM_MILLIS = 300L
        }
    }

    companion object {
        private const val AUTO_CONNECT_COUNTDOWN_MS = 5_000L
        private const val TICK_MILLIS = 100L

        private fun signalBarsOfPing(pingMs: Long): Int = when {
            pingMs <= 0 -> 4
            pingMs <= 300 -> 4 // <= 300 ms: 4 full green bars
            pingMs <= 600 -> 3 // <= 600 ms: 3 green bars
            pingMs <= 1000 -> 2 // <= 1000 ms: 2 yellow bars
            else -> 1           // > 1000 ms: 1 red bar
        }

        fun newInstance() = ServerPickerDialog()
    }
}
