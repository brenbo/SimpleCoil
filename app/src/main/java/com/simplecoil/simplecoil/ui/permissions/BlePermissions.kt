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
import android.annotation.SuppressLint
import android.os.Build

/** One thing SimpleCoil asks the player for, as the explanation names it: one or more Android permissions. */
enum class PermissionKind {
    BLUETOOTH,
    LOCATION,
    NOTIFICATIONS,
    CAMERA,
    MICROPHONE,
    LOCAL_NETWORK;

    /**
     * The Android permissions behind it on [sdkInt], in request order; empty where Android grants
     * it at install. Location asks for precise and approximate together, as Android 12 and later
     * require: the player then picks which.
     */
    @SuppressLint("InlinedApi") // each permission is only listed on the versions that have it
    fun permissions(sdkInt: Int): List<String> = when (this) {
        BLUETOOTH -> if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        LOCATION -> if (sdkInt >= Build.VERSION_CODES.M) listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) else emptyList()
        NOTIFICATIONS -> if (sdkInt >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        CAMERA -> if (sdkInt >= Build.VERSION_CODES.M) listOf(Manifest.permission.CAMERA) else emptyList()
        MICROPHONE -> if (sdkInt >= Build.VERSION_CODES.M) listOf(Manifest.permission.RECORD_AUDIO) else emptyList()
        LOCAL_NETWORK -> if (sdkInt >= BlePermissions.SDK_LOCAL_NETWORK) listOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else emptyList()
    }

    /**
     * Whether the player has given it. Location counts only when precise: approximate is a few
     * kilometers out, no use for a field, so the map and the GPS modes treat it as off.
     */
    fun isGranted(sdkInt: Int, granted: (String) -> Boolean): Boolean = when (this) {
        LOCATION -> sdkInt < Build.VERSION_CODES.M || granted(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> permissions(sdkInt).all(granted)
    }
}

/** Which permissions each part of the app asks for, and which it can't go ahead without. Pure; no Android calls. */
object BlePermissions {

    /** The parts of the app that ask. */
    enum class Flow { CONNECT, QR_SCAN, HOST_SERVER, JOIN_NETWORK, MICROPHONE, MAP_LOCATION }

    /** Android 17, where local network access became a runtime permission. */
    const val SDK_LOCAL_NETWORK = 37

    /** Android 11 and older find Bluetooth devices only with location; 12 and later don't (scans are declared never for location). */
    fun bluetoothNeedsLocation(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.S

    /**
     * What [flow] asks for on [sdkInt], in request order, leaving out what Android grants at
     * install there:
     * - Connect: Bluetooth (12+), location (the map; before 12, scanning too) and notifications
     *   (13+, the connected notification).
     * - Hosting or joining a network game: local network access (17+; without it every LAN packet
     *   fails with EPERM) and notifications (the network notification).
     */
    fun kinds(flow: Flow, sdkInt: Int): List<PermissionKind> = when (flow) {
        Flow.CONNECT -> listOf(PermissionKind.BLUETOOTH, PermissionKind.LOCATION, PermissionKind.NOTIFICATIONS)
        Flow.QR_SCAN -> listOf(PermissionKind.CAMERA)
        Flow.HOST_SERVER, Flow.JOIN_NETWORK -> listOf(PermissionKind.LOCAL_NETWORK, PermissionKind.NOTIFICATIONS)
        Flow.MICROPHONE -> listOf(PermissionKind.MICROPHONE)
        Flow.MAP_LOCATION -> listOf(PermissionKind.LOCATION)
    }.filter { it.permissions(sdkInt).isNotEmpty() }

    /** The kinds [flow] can't go ahead without on [sdkInt]; the rest are optional. */
    fun required(flow: Flow, sdkInt: Int): Set<PermissionKind> = when (flow) {
        Flow.CONNECT -> setOfNotNull(PermissionKind.BLUETOOTH, PermissionKind.LOCATION.takeIf { bluetoothNeedsLocation(sdkInt) })
        Flow.QR_SCAN -> setOf(PermissionKind.CAMERA)
        Flow.HOST_SERVER, Flow.JOIN_NETWORK -> setOf(PermissionKind.LOCAL_NETWORK)
        Flow.MICROPHONE -> setOf(PermissionKind.MICROPHONE)
        Flow.MAP_LOCATION -> emptySet() // the map works without it: asked once, or when a map button needs it
    }.filterTo(LinkedHashSet()) { it in kinds(flow, sdkInt) }

    /**
     * Of [kinds], the ones to ask for now: those not given yet, but an optional one only the
     * first time, so a player who said no isn't asked at every connect. [again] asks for those
     * too (the player tapped a button that needs it).
     */
    fun toAsk(
        kinds: List<PermissionKind>,
        required: Set<PermissionKind>,
        isGranted: (PermissionKind) -> Boolean,
        wasAsked: (PermissionKind) -> Boolean,
        again: Boolean,
    ): List<PermissionKind> = kinds.filter { !isGranted(it) && (again || it in required || !wasAsked(it)) }
}
