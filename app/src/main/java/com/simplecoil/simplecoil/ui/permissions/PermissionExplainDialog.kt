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
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.simplecoil.simplecoil.R

/**
 * The two pop-ups around a permission request:
 * - before it, what each permission is for and whether it's required; Allow brings up
 *   Android's own prompt, Not now skips it (the answer goes back as the fragment result named by
 *   the asker's key);
 * - after location is denied, or allowed only approximately, what won't work without it, with
 *   the app settings to turn it on.
 */
class PermissionExplainDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val builder = AlertDialog.Builder(requireContext(), R.style.AppDialogTheme)
        return if (args.getBoolean(ARG_LOCATION_OFF)) {
            locationOff(builder, approximate = args.getBoolean(ARG_APPROXIMATE))
        } else {
            explain(builder, args)
        }
    }

    private fun explain(builder: AlertDialog.Builder, args: Bundle): Dialog {
        val kinds = args.getStringArrayList(ARG_KINDS)!!.map { PermissionKind.valueOf(it) }
        val required = args.getStringArrayList(ARG_REQUIRED)!!.map { PermissionKind.valueOf(it) }.toSet()
        val message = SpannableStringBuilder(getString(R.string.permission_explain_intro))
        for (kind in kinds) {
            message.append("\n\n")
            val start = message.length
            message.append(getString(nameOf(kind)))
            message.setSpan(StyleSpan(Typeface.BOLD), start, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Where Bluetooth needs location, the app needs it, whichever screen asks
            val needed = kind in required || kind == PermissionKind.LOCATION && BlePermissions.bluetoothNeedsLocation(Build.VERSION.SDK_INT)
            message.append(" · ").append(getString(if (needed) R.string.permission_required else R.string.permission_optional))
            message.append('\n').append(getString(whyOf(kind)))
            if (kind == PermissionKind.LOCATION && BlePermissions.bluetoothNeedsLocation(Build.VERSION.SDK_INT)) {
                message.append(' ').append(getString(R.string.permission_why_location_bluetooth))
            }
        }
        return builder.setTitle(R.string.permission_explain_title)
            .setMessage(message)
            .setPositiveButton(R.string.permission_allow) { _, _ -> answer(true) }
            .setNegativeButton(R.string.permission_not_now) { _, _ -> answer(false) }
            .create()
    }

    /** Back, or a tap outside, is Not now. */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (!requireArguments().getBoolean(ARG_LOCATION_OFF)) answer(false)
    }

    private fun answer(allowed: Boolean) {
        val args = requireArguments()
        parentFragmentManager.setFragmentResult(args.getString(ARG_KEY)!!, bundleOf(
            ARG_FLOW to args.getString(ARG_FLOW),
            ARG_KINDS to args.getStringArrayList(ARG_KINDS),
            ARG_ALLOWED to allowed,
        ))
    }

    private fun locationOff(builder: AlertDialog.Builder, approximate: Boolean): Dialog {
        val message = if (approximate) {
            getString(R.string.location_approximate_body)
        } else {
            val bluetooth = if (BlePermissions.bluetoothNeedsLocation(Build.VERSION.SDK_INT)) R.string.location_off_bluetooth_old else R.string.location_off_bluetooth_new
            getString(R.string.location_off_body) + "\n\n" + getString(bluetooth) + "\n\n" + getString(R.string.location_off_settings)
        }
        return builder.setTitle(if (approximate) R.string.location_approximate_title else R.string.location_off_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.permission_open_settings) { _, _ ->
                val context = requireContext()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
            .create()
    }

    companion object {
        const val TAG = "permissionExplain"
        const val ARG_FLOW = "flow"
        const val ARG_KINDS = "kinds"
        const val ARG_ALLOWED = "allowed"
        private const val ARG_KEY = "key"
        private const val ARG_REQUIRED = "required"
        private const val ARG_LOCATION_OFF = "locationOff"
        private const val ARG_APPROXIMATE = "approximate"

        /** What [kinds] are for, before Android asks; the answer goes to the fragment result [key]. */
        fun explain(key: String, flow: BlePermissions.Flow, kinds: List<PermissionKind>, required: Set<PermissionKind>) =
            PermissionExplainDialog().apply {
                arguments = bundleOf(
                    ARG_KEY to key,
                    ARG_FLOW to flow.name,
                    ARG_KINDS to ArrayList(kinds.map { it.name }),
                    ARG_REQUIRED to ArrayList(required.map { it.name }),
                )
            }

        /** Location was denied ([approximate]: allowed only approximately): what won't work. */
        fun locationOff(approximate: Boolean) = PermissionExplainDialog().apply {
            arguments = bundleOf(ARG_LOCATION_OFF to true, ARG_APPROXIMATE to approximate)
        }

        private fun nameOf(kind: PermissionKind) = when (kind) {
            PermissionKind.BLUETOOTH -> R.string.permission_name_bluetooth
            PermissionKind.LOCATION -> R.string.permission_name_location
            PermissionKind.NOTIFICATIONS -> R.string.permission_name_notifications
            PermissionKind.CAMERA -> R.string.permission_name_camera
            PermissionKind.MICROPHONE -> R.string.permission_name_microphone
            PermissionKind.LOCAL_NETWORK -> R.string.permission_name_local_network
        }

        private fun whyOf(kind: PermissionKind) = when (kind) {
            PermissionKind.BLUETOOTH -> R.string.permission_why_bluetooth
            PermissionKind.LOCATION -> R.string.permission_why_location
            PermissionKind.NOTIFICATIONS -> R.string.permission_why_notifications
            PermissionKind.CAMERA -> R.string.permission_why_camera
            PermissionKind.MICROPHONE -> R.string.permission_why_microphone
            PermissionKind.LOCAL_NETWORK -> R.string.permission_why_local_network
        }
    }
}
