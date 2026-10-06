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

package com.simplecoil.simplecoil.hardware;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class GattProtocolHandlerTest {

    @Test
    public void testParseTelemetryNullOrShort() {
        assertNull(GattProtocolHandler.parseTelemetry(null));
        assertNull(GattProtocolHandler.parseTelemetry(new byte[10]));
    }

    @Test
    public void testParseTelemetryValid() {
        byte[] data = new byte[20];
        data[GattProtocolHandler.RECOIL_OFFSET_TEAM] = 3;
        data[GattProtocolHandler.RECOIL_OFFSET_SHOTS_REMAINING] = 25;
        data[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = (byte) 0x24; // 2 reload, 4 trigger
        data[GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL] = 12;
        data[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1] = 5;
        data[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID] = 3;

        GattProtocolHandler.TelemetryData telemetry = GattProtocolHandler.parseTelemetry(data);
        assertNotNull(telemetry);
        assertEquals(3, telemetry.playerId);
        assertEquals(25, telemetry.shotsRemaining);
        assertEquals(4, telemetry.triggerCounter);
        assertEquals((byte) 0x20, telemetry.reloadCounter);
        assertEquals(12, telemetry.batteryLevel);
        assertEquals(5, telemetry.hitByPlayer1);
        assertEquals(3, telemetry.shotId1);
    }

    @Test
    public void testBuildSetTeamCommand() {
        byte[] cmd = GattProtocolHandler.buildSetTeamCommand((byte) 0x10, (byte) 2);
        assertEquals(20, cmd.length);
        assertEquals((byte) 0x10, cmd[0]);
        assertEquals((byte) 0x80, cmd[2]);
        assertEquals((byte) 2, cmd[4]);
    }

    @Test
    public void testBuildStartReloadCommand() {
        byte[] cmd = GattProtocolHandler.buildStartReloadCommand((byte) 0x20, (byte) 4);
        assertEquals(20, cmd.length);
        assertEquals((byte) 0x20, cmd[0]);
        assertEquals((byte) 0x02, cmd[2]);
        assertEquals((byte) 4, cmd[4]);
    }

    @Test
    public void testBuildFinishReloadCommand() {
        byte[] cmd = GattProtocolHandler.buildFinishReloadCommand((byte) 0x30, (byte) 4, (byte) 1, (byte) 30);
        assertEquals(20, cmd.length);
        assertEquals((byte) 0x30, cmd[0]);
        assertEquals((byte) 0x04, cmd[2]);
        assertEquals((byte) 4, cmd[4]);
        assertEquals((byte) 1, cmd[5]);
        assertEquals((byte) 30, cmd[6]);
    }

    @Test
    public void testBuildShotModeConfig() {
        byte[] configSingle = GattProtocolHandler.buildShotModeConfig(
                (byte) 1,
                GameSettingsRepository.SHOT_MODE_SINGLE,
                GattProtocolHandler.BLASTER_TYPE_PISTOL,
                GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE
        );
        assertEquals(20, configSingle.length);
        assertEquals((byte) 0xFE, configSingle[3]);
        assertEquals((byte) 0x00, configSingle[4]);
        assertEquals((byte) 0xFF, configSingle[5]);
        assertEquals((byte) 0x00, configSingle[6]);

        byte[] configAuto = GattProtocolHandler.buildShotModeConfig(
                (byte) 1,
                GameSettingsRepository.SHOT_MODE_FULL_AUTO,
                GattProtocolHandler.BLASTER_TYPE_PISTOL,
                GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE
        );
        assertEquals((byte) 0xFE, configAuto[3]);
        assertEquals((byte) 0x01, configAuto[4]);
        assertEquals((byte) 0x19, configAuto[5]);
        assertEquals((byte) 0x00, configAuto[6]);
    }

    @Test
    public void testBuildRecoilConfig() {
        byte[] enabled = GattProtocolHandler.buildRecoilConfig(true);
        assertEquals((byte) 0x03, enabled[3]);

        byte[] disabled = GattProtocolHandler.buildRecoilConfig(false);
        assertEquals((byte) 0x02, disabled[3]);
    }
}
