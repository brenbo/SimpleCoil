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

import com.simplecoil.simplecoil.GattAttributes;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;

/**
 * Encapsulates GATT attribute constants, telemetry byte offset parsing,
 * and command/config packet construction for Bluetooth LE blasters.
 */
public class GattProtocolHandler {

    // Telemetry packet byte offsets (20-byte payload)
    public static final int RECOIL_OFFSET_TEAM = 1;
    public static final int RECOIL_OFFSET_BUTTONS = 2;
    public static final int RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER = 3;
    public static final int RECOIL_OFFSET_THUMB_COUNTER = 4;
    public static final int RECOIL_OFFSET_POWER_COUNTER = 5;
    public static final int RECOIL_OFFSET_BATTERY_LEVEL = 7;
    public static final int RECOIL_OFFSET_HIT_BY1_SHOTID = 8;
    public static final int RECOIL_OFFSET_HIT_BY1 = 9;
    public static final int RECOIL_OFFSET_HIT_BY2_SHOTID = 11;
    public static final int RECOIL_OFFSET_HIT_BY2 = 12;
    public static final int RECOIL_OFFSET_SHOTS_REMAINING = 14;
    public static final int RECOIL_OFFSET_STATUS = 15;

    // Blaster types
    public static final byte BLASTER_TYPE_RIFLE = 0x01;
    public static final byte BLASTER_TYPE_PISTOL = 0x02;

    // Command IDs
    public static final byte COMMAND_ID_INCREMENT = 0x10;

    /**
     * Value object representing a parsed 20-byte telemetry packet.
     */
    public static class TelemetryData {
        public final byte playerId;
        public final byte shotsRemaining;
        public final byte triggerCounter;
        public final byte reloadCounter;
        public final byte thumbCounter;
        public final byte powerCounter;
        public final byte batteryLevel;
        public final int hitByPlayer1;
        public final byte shotId1;
        public final int hitByPlayer2;
        public final byte shotId2;
        public final byte status;

        public TelemetryData(byte playerId, byte shotsRemaining, byte triggerCounter,
                             byte reloadCounter, byte thumbCounter, byte powerCounter,
                             byte batteryLevel, int hitByPlayer1, byte shotId1,
                             int hitByPlayer2, byte shotId2, byte status) {
            this.playerId = playerId;
            this.shotsRemaining = shotsRemaining;
            this.triggerCounter = triggerCounter;
            this.reloadCounter = reloadCounter;
            this.thumbCounter = thumbCounter;
            this.powerCounter = powerCounter;
            this.batteryLevel = batteryLevel;
            this.hitByPlayer1 = hitByPlayer1;
            this.shotId1 = shotId1;
            this.hitByPlayer2 = hitByPlayer2;
            this.shotId2 = shotId2;
            this.status = status;
        }
    }

    /**
     * Parses a 20-byte raw telemetry byte array from the blaster.
     *
     * @param data Raw 20-byte telemetry buffer
     * @return TelemetryData instance or null if data is invalid
     */
    public static TelemetryData parseTelemetry(byte[] data) {
        if (data == null || data.length < 16) {
            return null;
        }

        byte playerId = data[RECOIL_OFFSET_TEAM];
        byte shotsRemaining = data[RECOIL_OFFSET_SHOTS_REMAINING];
        byte status = data[RECOIL_OFFSET_STATUS];

        int hitByPlayer1 = (data[RECOIL_OFFSET_HIT_BY1] & 0xFF);
        int hitByPlayer2 = (data[RECOIL_OFFSET_HIT_BY2] & 0xFF);

        byte triggerCounter = (byte) (data[RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] & 0x0F);
        byte reloadCounter = (byte) (data[RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] & 0xF0);
        byte thumbCounter = data[RECOIL_OFFSET_THUMB_COUNTER];
        byte powerCounter = data[RECOIL_OFFSET_POWER_COUNTER];

        byte batteryLevel = data.length > RECOIL_OFFSET_BATTERY_LEVEL ? data[RECOIL_OFFSET_BATTERY_LEVEL] : 0;
        byte shotId1 = data.length > RECOIL_OFFSET_HIT_BY1_SHOTID ? (byte) (data[RECOIL_OFFSET_HIT_BY1_SHOTID] & 0x07) : 0;
        byte shotId2 = data.length > RECOIL_OFFSET_HIT_BY2_SHOTID ? (byte) (data[RECOIL_OFFSET_HIT_BY2_SHOTID] & 0x07) : 0;

        return new TelemetryData(
                playerId,
                shotsRemaining,
                triggerCounter,
                reloadCounter,
                thumbCounter,
                powerCounter,
                batteryLevel,
                hitByPlayer1,
                shotId1,
                hitByPlayer2,
                shotId2,
                status
        );
    }

    /**
     * Constructs a 20-byte GATT command packet to set the player ID.
     */
    public static byte[] buildSetTeamCommand(byte commandId, byte playerId) {
        byte[] command = new byte[20];
        command[0] = commandId;
        command[2] = (byte) 0x80;
        command[4] = playerId;
        return command;
    }

    /**
     * Constructs a 20-byte GATT command packet to start a reload operation.
     */
    public static byte[] buildStartReloadCommand(byte commandId, byte playerId) {
        byte[] command = new byte[20];
        command[0] = commandId;
        command[2] = (byte) 0x02;
        command[4] = playerId;
        return command;
    }

    /**
     * Constructs a 20-byte GATT command packet to finish a reload operation.
     */
    public static byte[] buildFinishReloadCommand(byte commandId, byte playerId, byte weaponProfile, byte shotCount) {
        byte[] command = new byte[20];
        command[0] = commandId;
        command[2] = (byte) 0x04;
        command[4] = playerId;
        command[5] = weaponProfile;
        command[6] = shotCount;
        return command;
    }

    /**
     * Constructs a 20-byte GATT config packet to set shot mode and firing cone settings.
     */
    public static byte[] buildShotModeConfig(byte weaponProfile, int shotMode, byte blasterType, int firingMode) {
        byte[] config = new byte[20];
        config[0] = weaponProfile;
        config[2] = (byte) 0x09;
        config[7] = (byte) 0xFF;
        config[8] = (byte) 0xFF;
        config[9] = (byte) 0x80; // Recoil strength default
        config[10] = (byte) 0x02;
        config[11] = (byte) 0x34;

        if (shotMode == GameSettingsRepository.SHOT_MODE_SINGLE) {
            config[3] = (byte) 0xFE;
            config[4] = (byte) 0x00;
        } else if (shotMode == GameSettingsRepository.SHOT_MODE_BURST) {
            config[3] = (byte) 0x03;
            config[4] = (byte) 0x03;
            if (blasterType == BLASTER_TYPE_RIFLE) {
                config[9] = (byte) 0x78; // Reduced recoil for rifle burst
            }
        } else if (shotMode == GameSettingsRepository.SHOT_MODE_FULL_AUTO) {
            config[3] = (byte) 0xFE;
            config[4] = (byte) 0x01;
        }

        switch (firingMode) {
            case GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE:
                config[5] = (byte) 0xFF;
                config[6] = (byte) 0x00;
                break;
            case GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE:
                config[5] = (byte) 0xFF;
                config[6] = (byte) 0xC8;
                break;
            case GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE:
                config[5] = (byte) 0x19;
                config[6] = (byte) 0x00;
                break;
        }

        return config;
    }

    /**
     * Constructs a 20-byte GATT config packet to enable or disable recoil.
     */
    public static byte[] buildRecoilConfig(boolean enabled) {
        byte[] config = new byte[20];
        config[0] = (byte) 0x10;
        config[2] = (byte) 0x02;
        config[4] = (byte) 0xFF;
        config[3] = enabled ? (byte) 0x03 : (byte) 0x02;
        return config;
    }
}
