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

package com.simplecoil.simplecoil.ui.permissions

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.ui.game.GameViewModel
import com.simplecoil.simplecoil.ui.permissions.BlePermissions.Flow

/**
 * The permission and Bluetooth checks in front of a connect, the camera permission in front of a
 * QR scan, and the local network (Android 17+) and notification permissions in front of hosting
 * the dedicated server or joining a network game. Each is asked through [PermissionAsk]: what it's
 * for first, then Android's prompt; a required one denied for good offers the app settings.
 *
 * Construct it in the activity's onCreate (it registers its launchers).
 */
class BlePermissionController(
    private val activity: FragmentActivity,
    private val vm: GameViewModel,
    /** The camera may be used: start the QR scanner. */
    private val onQrScanAllowed: () -> Unit,
) {
    private val ask = PermissionAsk.of(activity, REQUEST_KEY) { result ->
        when {
            result.granted -> proceed(result.flow)
            result.deniedRequired.isEmpty() -> Unit // location's own pop-up has said what's off
            result.permanently -> showSettings(result.flow)
            else -> Toast.makeText(activity, deniedMessage(result.flow), Toast.LENGTH_SHORT).show()
        }
    }

    private val enableBluetooth = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { connect() }

    /** "Connect": permissions, Bluetooth on, then [GameViewModel.startScan] (scan + 30 s give-up). */
    fun connect() {
        if (vm.isScanning) return

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(activity, R.string.ble_not_supported, Toast.LENGTH_SHORT).show()
            activity.finish()
            return
        }

        // Permissions first; once they're answered, the connect comes back here
        if (ask.needsAsking(Flow.CONNECT)) {
            ask.ask(Flow.CONNECT)
            return
        }

        // Initializes a Bluetooth manager and adapter.
        val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        if (bluetoothManager == null) {
            Log.e(TAG, "Failed to get Bluetooth service")
            Toast.makeText(activity, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            activity.finish()
            return
        }
        // Checks if Bluetooth is supported on the device.
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            Toast.makeText(activity, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            activity.finish()
            return
        }
        if (!adapter.isEnabled) {
            Log.d(TAG, "Bluetooth is off")
            try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException enabling bluetooth: " + e.message)
            }
            return
        }

        // Connect blaster: the player picks one from the blasters in range first
        if (vm.choosesBlaster()) return
        if (!vm.startScan(adapter)) {
            Toast.makeText(activity, R.string.error_bluetooth_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    /** The QR button: camera permission, then [onQrScanAllowed]. */
    fun scanQr() = ask.ask(Flow.QR_SCAN, again = true)

    /** "Dedicated server": local network access and notifications, then open the server screen. */
    fun hostServer() = ask.ask(Flow.HOST_SERVER)

    /** "Use network": local network access and notifications, then the network menu. */
    fun joinNetwork() = ask.ask(Flow.JOIN_NETWORK)

    private fun proceed(flow: Flow) = when (flow) {
        Flow.CONNECT -> vm.onLocationPermissionsGranted()
        Flow.QR_SCAN -> onQrScanAllowed()
        Flow.HOST_SERVER -> vm.onDedicatedServerPressed()
        Flow.JOIN_NETWORK -> vm.onNetworkButtonPressed()
        Flow.MICROPHONE, Flow.MAP_LOCATION -> Unit // asked elsewhere
    }

    private fun deniedMessage(flow: Flow) = when (flow) {
        Flow.QR_SCAN -> R.string.error_camera_permission_required
        Flow.HOST_SERVER, Flow.JOIN_NETWORK -> R.string.error_local_network_permission_required
        else -> R.string.error_bluetooth_permission_required
    }

    private fun showSettings(flow: Flow) {
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(PermissionDialog.TAG) != null) return
        PermissionDialog.openSettings(flow).show(fm, PermissionDialog.TAG)
    }

    private companion object {
        const val TAG = "scmain"
        const val REQUEST_KEY = "blePermissions"
    }
}
