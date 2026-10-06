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

package com.simplecoil.simplecoil.hardware.blaster;

/**
 * Event listener interface for Bluetooth LE blaster hardware events,
 * telemetry updates, button triggers, and IR hits.
 */
public interface BlasterEventListener {
    default void onBlasterConnected(String deviceAddress) {}
    default void onBlasterDisconnected() {}
    default void onServicesDiscovered() {}
    default void onBlasterTypeDiscovered(byte blasterType) {}
    default void onTelemetryReceived(GattProtocolHandler.TelemetryData telemetryData) {}
    default void onTriggerPulled(byte shotsRemaining) {}
    default void onReloadRequested() {}
    default void onShotModeChanged(int newShotMode) {}
    default void onRecoilToggled(boolean enabled) {}
    default void onBatteryLevelUpdated(long averageBatteryLevel, byte blasterType) {}
    default void onHitReceived(int shooterPlayerId, byte shotId, boolean isSecondaryHit) {}
    default void onGrenadeEvent(int grenadeId, byte eventType) {}
    default void onPlayerIdChanged(byte newPlayerId) {}
    default void onCharacteristicWriteFinished() {}
}
