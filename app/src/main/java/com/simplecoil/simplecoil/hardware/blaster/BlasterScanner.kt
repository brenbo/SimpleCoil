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

package com.simplecoil.simplecoil.hardware.blaster

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A blaster in range: its address, name, last signal strength (dBm) and when it was last heard. */
data class FoundBlaster(val address: String, val name: String, val rssi: Int, val seenAt: Long)

/** How strong a blaster's signal is, in bars (1-4): roughly how close it is. Pure. */
object SignalBars {
    fun of(rssi: Int): Int = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        else -> 1
    }

    /**
     * [found] still in range at [now] (heard within [STALE_MILLIS]), strongest first. A blaster
     * that turns off drops out of the list.
     */
    fun inRange(found: Collection<FoundBlaster>, now: Long): List<FoundBlaster> =
        found.filter { now - it.seenAt <= STALE_MILLIS }.sortedByDescending { it.rssi }

    const val STALE_MILLIS = 6_000L
}

/**
 * Scans for blasters for the blaster picker: every `SRG1` device in range with its signal, kept
 * up to date while the picker is open. Needs the scan permissions (the connect flow has them).
 */
class BlasterScanner(private val adapter: BluetoothAdapter) {

    private val _found = MutableStateFlow<Map<String, FoundBlaster>>(emptyMap())

    /** The blasters heard so far, by address. */
    val found: StateFlow<Map<String, FoundBlaster>> = _found.asStateFlow()

    private var scanner: BluetoothLeScanner? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = heard(result)

        override fun onBatchScanResults(results: List<ScanResult>) = results.forEach(::heard)
    }

    @SuppressLint("MissingPermission") // the connect flow asked for them before the picker opens
    fun start() {
        try {
            scanner = adapter.bluetoothLeScanner
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to scan: " + e.message)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            scanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to stop the scan: " + e.message)
        }
        scanner = null
    }

    @SuppressLint("MissingPermission")
    private fun heard(result: ScanResult) {
        val device: BluetoothDevice = result.device
        val name = try {
            device.name ?: result.scanRecord?.deviceName
        } catch (e: SecurityException) {
            null
        }
        if (name == null || !name.startsWith(ScanMatcher.BLASTER_NAME_PREFIX)) return
        val blaster = FoundBlaster(device.address, name, result.rssi, SystemClock.elapsedRealtime())
        _found.value = _found.value + (blaster.address to blaster)
    }

    private companion object {
        const val TAG = "BlasterScanner"
    }
}
