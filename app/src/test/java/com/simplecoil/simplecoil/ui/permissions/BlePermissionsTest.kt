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

import com.simplecoil.simplecoil.ui.permissions.BlePermissions.Flow
import com.simplecoil.simplecoil.ui.permissions.PermissionKind.BLUETOOTH
import com.simplecoil.simplecoil.ui.permissions.PermissionKind.CAMERA
import com.simplecoil.simplecoil.ui.permissions.PermissionKind.LOCAL_NETWORK
import com.simplecoil.simplecoil.ui.permissions.PermissionKind.LOCATION
import com.simplecoil.simplecoil.ui.permissions.PermissionKind.MICROPHONE
import com.simplecoil.simplecoil.ui.permissions.PermissionKind.NOTIFICATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each part of the app asks for per API level, what's required, and who gets asked again. */
class BlePermissionsTest {

    private val scan = "android.permission.BLUETOOTH_SCAN"
    private val connect = "android.permission.BLUETOOTH_CONNECT"
    private val fineLocation = "android.permission.ACCESS_FINE_LOCATION"
    private val coarseLocation = "android.permission.ACCESS_COARSE_LOCATION"

    @Test
    fun connect_perApiLevel() {
        assertEquals(emptyList<PermissionKind>(), BlePermissions.kinds(Flow.CONNECT, 22))
        assertEquals(listOf(LOCATION), BlePermissions.kinds(Flow.CONNECT, 30))
        assertEquals(listOf(BLUETOOTH, LOCATION), BlePermissions.kinds(Flow.CONNECT, 31))
        assertEquals(listOf(BLUETOOTH, LOCATION, NOTIFICATIONS), BlePermissions.kinds(Flow.CONNECT, 33))
    }

    @Test
    fun connect_needsLocationOnlyUpToAndroid11() {
        assertEquals(setOf(LOCATION), BlePermissions.required(Flow.CONNECT, 30))
        assertEquals(setOf(BLUETOOTH), BlePermissions.required(Flow.CONNECT, 31))
        assertEquals(setOf(BLUETOOTH), BlePermissions.required(Flow.CONNECT, 34))
        assertTrue(BlePermissions.bluetoothNeedsLocation(30))
        assertFalse(BlePermissions.bluetoothNeedsLocation(31))
    }

    @Test
    fun network_onlyLocalNetworkIsRequired() {
        assertEquals(listOf(NOTIFICATIONS), BlePermissions.kinds(Flow.HOST_SERVER, 36))
        assertEquals(emptySet<PermissionKind>(), BlePermissions.required(Flow.HOST_SERVER, 36))
        assertEquals(listOf(LOCAL_NETWORK, NOTIFICATIONS), BlePermissions.kinds(Flow.JOIN_NETWORK, 37))
        assertEquals(setOf(LOCAL_NETWORK), BlePermissions.required(Flow.JOIN_NETWORK, 37))
    }

    @Test
    fun cameraAndMicrophone_fromApi23() {
        assertEquals(emptyList<PermissionKind>(), BlePermissions.kinds(Flow.QR_SCAN, 22))
        assertEquals(listOf(CAMERA), BlePermissions.kinds(Flow.QR_SCAN, 23))
        assertEquals(listOf(MICROPHONE), BlePermissions.kinds(Flow.MICROPHONE, 34))
    }

    @Test
    fun location_asksForPreciseAndApproximateTogether() {
        assertEquals(listOf(fineLocation, coarseLocation), LOCATION.permissions(34))
        assertEquals(listOf(scan, connect), BLUETOOTH.permissions(31))
        assertEquals(emptyList<String>(), BLUETOOTH.permissions(30))
    }

    @Test
    fun location_approximateOnlyIsNotGranted() {
        assertFalse(LOCATION.isGranted(34) { it == coarseLocation })
        assertTrue(LOCATION.isGranted(34) { it == fineLocation })
        // Before Android 6 it's granted at install
        assertTrue(LOCATION.isGranted(22) { false })
    }

    @Test
    fun toAsk_optionalOnlyTheFirstTime() {
        val kinds = listOf(BLUETOOTH, LOCATION, NOTIFICATIONS)
        val required = setOf(BLUETOOTH)
        val none: (PermissionKind) -> Boolean = { false }
        assertEquals(kinds, BlePermissions.toAsk(kinds, required, none, none, again = false))
        // Asked before and said no: the required one is asked again, the optional ones aren't
        assertEquals(listOf(BLUETOOTH), BlePermissions.toAsk(kinds, required, none, { true }, again = false))
        // Unless the player tapped something that needs them
        assertEquals(kinds, BlePermissions.toAsk(kinds, required, none, { true }, again = true))
        // What's held is never asked for
        assertEquals(listOf(NOTIFICATIONS), BlePermissions.toAsk(kinds, required, { it != NOTIFICATIONS }, none, again = false))
    }
}
