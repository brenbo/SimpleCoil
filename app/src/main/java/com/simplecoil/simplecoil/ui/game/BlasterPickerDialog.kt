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
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.databinding.BlasterPickerDialogBinding
import com.simplecoil.simplecoil.databinding.BlasterPickerRowBinding
import com.simplecoil.simplecoil.hardware.blaster.BlasterScanner
import com.simplecoil.simplecoil.hardware.blaster.FoundBlaster
import com.simplecoil.simplecoil.hardware.blaster.SignalBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Connect blaster: the blasters in range, strongest signal first with signal bars, the ones this
 * phone has connected to before labeled. Tapping one connects to it. The list keeps scanning
 * while it's open, and a blaster that turns off drops out.
 */
class BlasterPickerDialog : GameDialogFragment() {
    private var binding: BlasterPickerDialogBinding? = null
    private var scanner: BlasterScanner? = null
    private var ticker: Job? = null
    private val known get() = requireArguments().getStringArray(ARG_KNOWN)?.toSet() ?: emptySet()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val b = BlasterPickerDialogBinding.inflate(appInflater)
        binding = b
        val adapter = (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
        scanner = adapter?.let { BlasterScanner(it) }
        return alertBuilder()
            .setTitle(R.string.blaster_picker_title)
            .setView(b.root)
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        scanner?.start()
        // Redrawn every second, so the bars follow the signal and lost blasters drop out
        ticker = lifecycleScope.launch {
            while (true) {
                render()
                delay(REFRESH_MILLIS)
            }
        }
    }

    override fun onStop() {
        ticker?.cancel()
        scanner?.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render() {
        val b = binding ?: return
        val blasters = SignalBars.inRange(scanner?.found?.value?.values ?: emptyList(), SystemClock.elapsedRealtime())
        b.blasterPickerSearching.visibility = if (blasters.isEmpty()) View.VISIBLE else View.GONE
        b.blasterPickerRows.removeAllViews()
        for (blaster in blasters) b.blasterPickerRows.addView(row(blaster))
    }

    private fun row(blaster: FoundBlaster): View {
        val ctx = context ?: return View(requireContext())
        val row = BlasterPickerRowBinding.inflate(LayoutInflater.from(ctx), binding!!.blasterPickerRows, false)
        val bars = SignalBars.of(blaster.rssi)
        row.blasterSignalIv.setImageResource(when (bars) {
            4 -> R.drawable.ic_signal_4
            3 -> R.drawable.ic_signal_3
            2 -> R.drawable.ic_signal_2
            else -> R.drawable.ic_signal_1
        })
        row.blasterNameTv.text = blaster.name
        row.blasterAddressTv.text = blaster.address
        val previously = blaster.address in known
        row.blasterKnownTv.visibility = if (previously) View.VISIBLE else View.GONE
        row.root.contentDescription = listOfNotNull(blaster.name, getString(R.string.blaster_picker_signal, bars),
            if (previously) getString(R.string.blaster_picker_known) else null).joinToString(", ")
        row.root.setOnClickListener {
            scanner?.stop()
            vm.onBlasterPicked(blaster.address)
            dismiss()
        }
        return row.root
    }

    companion object {
        private const val ARG_KNOWN = "known"
        private const val REFRESH_MILLIS = 1_000L

        fun newInstance(known: Set<String>) = BlasterPickerDialog().apply { arguments = bundleOf(ARG_KNOWN to known.toTypedArray()) }
    }
}
