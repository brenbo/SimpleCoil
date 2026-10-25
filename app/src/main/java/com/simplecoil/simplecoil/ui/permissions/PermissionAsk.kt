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

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.simplecoil.simplecoil.ui.permissions.BlePermissions.Flow

/**
 * Asks for a part of the app's permissions the same way everywhere: first a pop-up saying what
 * each is for and whether it's required, then (on Allow) Android's own prompt, then, if location
 * ended up off or only approximate, a pop-up saying what won't work without it. [onResult] hears
 * how it went.
 *
 * Make one while its activity or fragment is being created (it registers a launcher), with a
 * [key] of its own for the pop-up's answer.
 */
class PermissionAsk private constructor(
    caller: ActivityResultCaller,
    owner: LifecycleOwner,
    private val activity: () -> Activity,
    private val fragments: () -> FragmentManager,
    private val key: String,
    private val onResult: (Result) -> Unit,
) {
    /**
     * How a request went: [granted] when [flow] has every permission it requires;
     * [deniedRequired] those it doesn't, beyond location, whose own pop-up has already said what's
     * off; [permanently] when Android won't ask for them again (only the app settings can).
     */
    class Result(val flow: Flow, val granted: Boolean, val deniedRequired: Set<PermissionKind>, val permanently: Boolean)

    private val sdk = Build.VERSION.SDK_INT

    /** The flow and kinds in front of Android's prompt. */
    private var pending: Pair<Flow, List<PermissionKind>>? = null

    private val launcher = caller.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val (flow, kinds) = pending ?: return@registerForActivityResult
        pending = null
        finish(flow, kinds, results)
    }

    init {
        owner.lifecycle.addObserver(LifecycleEventObserver { source, event ->
            if (event != Lifecycle.Event.ON_CREATE) return@LifecycleEventObserver
            fragments().setFragmentResultListener(key, source) { _, bundle ->
                val flow = Flow.valueOf(bundle.getString(PermissionExplainDialog.ARG_FLOW)!!)
                val kinds = bundle.getStringArrayList(PermissionExplainDialog.ARG_KINDS)!!.map { PermissionKind.valueOf(it) }
                if (bundle.getBoolean(PermissionExplainDialog.ARG_ALLOWED)) {
                    pending = flow to kinds
                    launcher.launch(kinds.flatMap { it.permissions(sdk) }.toTypedArray())
                } else {
                    finish(flow, kinds, emptyMap())
                }
            }
        })
    }

    /** Whether [kind] is given (location only when precise). */
    fun isGranted(kind: PermissionKind): Boolean = kind.isGranted(sdk) { granted(it) }

    /**
     * Asks for what [flow] needs and hasn't got: an optional permission only the first time,
     * unless [again] (the player tapped something that needs it). With nothing to ask, [onResult]
     * hears straight away.
     */
    fun ask(flow: Flow, again: Boolean = false) {
        val required = BlePermissions.required(flow, sdk)
        val asked = asked()
        val toAsk = toAsk(flow, again)
        if (toAsk.isEmpty()) {
            deliver(flow, systemAsked = false)
            return
        }
        prefs().edit { putStringSet(ASKED, asked + toAsk.map { it.name }) }
        val fm = fragments()
        if (fm.isStateSaved || fm.findFragmentByTag(PermissionExplainDialog.TAG) != null) return
        PermissionExplainDialog.explain(key, flow, toAsk, required.filterTo(LinkedHashSet()) { it in toAsk })
            .show(fm, PermissionExplainDialog.TAG)
    }

    /** Whether [ask] would ask anything for [flow] (without [again]). */
    fun needsAsking(flow: Flow): Boolean = toAsk(flow, again = false).isNotEmpty()

    private fun toAsk(flow: Flow, again: Boolean): List<PermissionKind> {
        val asked = asked()
        return BlePermissions.toAsk(BlePermissions.kinds(flow, sdk), BlePermissions.required(flow, sdk), ::isGranted, { it.name in asked }, again)
    }

    private fun asked(): Set<String> = prefs().getStringSet(ASKED, emptySet()).orEmpty()

    /** After Not now ([results] empty) or Android's prompt: location's pop-up if it's off, then [onResult]. */
    private fun finish(flow: Flow, kinds: List<PermissionKind>, results: Map<String, Boolean>) {
        if (PermissionKind.LOCATION in kinds && !isGranted(PermissionKind.LOCATION)) {
            val fm = fragments()
            if (!fm.isStateSaved) {
                PermissionExplainDialog.locationOff(approximate = granted(Manifest.permission.ACCESS_COARSE_LOCATION))
                    .show(fm, PermissionExplainDialog.TAG + "Location")
            }
        }
        deliver(flow, systemAsked = results.isNotEmpty())
    }

    private fun deliver(flow: Flow, systemAsked: Boolean) {
        val denied = BlePermissions.required(flow, sdk).filterNot(::isGranted)
        // Location has its own pop-up, with the app settings in it
        val others = denied.filterTo(LinkedHashSet()) { it != PermissionKind.LOCATION }
        val permanently = systemAsked && others.any { kind ->
            kind.permissions(sdk).any { !granted(it) && !ActivityCompat.shouldShowRequestPermissionRationale(activity(), it) }
        }
        onResult(Result(flow, denied.isEmpty(), others, permanently))
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity(), permission) == PackageManager.PERMISSION_GRANTED

    private fun prefs() = activity().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "permissions"

        /** The kinds the explanation has offered before. */
        private const val ASKED = "asked"

        fun of(activity: FragmentActivity, key: String, onResult: (Result) -> Unit) =
            PermissionAsk(activity, activity, { activity }, { activity.supportFragmentManager }, key, onResult)

        fun of(fragment: Fragment, key: String, onResult: (Result) -> Unit) =
            PermissionAsk(fragment, fragment, { fragment.requireActivity() }, { fragment.childFragmentManager }, key, onResult)
    }
}
