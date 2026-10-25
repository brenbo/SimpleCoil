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

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.ui.permissions.BlePermissions.Flow

/** After a required permission is denied for good: what's off, and the app settings to turn it on. */
class PermissionDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val flow = Flow.valueOf(requireArguments().getString(ARG_FLOW)!!)
        return AlertDialog.Builder(requireContext(), R.style.AppDialogTheme)
            .setTitle(R.string.permission_settings_title)
            .setMessage(
                when (flow) {
                    Flow.QR_SCAN -> R.string.permission_settings_camera
                    Flow.HOST_SERVER, Flow.JOIN_NETWORK -> R.string.permission_settings_network
                    else -> R.string.permission_settings_ble
                }
            )
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                val context = requireContext()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
            .create()
    }

    companion object {
        const val TAG = "permissionDialog"
        private const val ARG_FLOW = "flow"

        fun openSettings(flow: Flow) = PermissionDialog().apply { arguments = bundleOf(ARG_FLOW to flow.name) }
    }
}
