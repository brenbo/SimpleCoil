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

package com.simplecoil.simplecoil.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.Before;
import org.junit.Test;

public class GameStateRepositoryTest {

    private GameStateRepository repository;
    private int stateChangedOldState = -1;
    private int stateChangedNewState = -1;
    private long updatedTimeRemaining = -1;
    private InetAddress updatedServerIP = null;

    @Before
    public void setUp() {
        repository = GameStateRepository.getInstance();
        repository.setGameState(GameStateRepository.GAME_STATE_NONE);
        repository.setServerGameTimeRemaining(0);
        stateChangedOldState = -1;
        stateChangedNewState = -1;
        updatedTimeRemaining = -1;
        updatedServerIP = null;
    }

    @Test
    public void testStateTransitionsAndListeners() throws UnknownHostException {
        GameStateRepository.GameStateChangeListener listener = new GameStateRepository.GameStateChangeListener() {
            @Override
            public void onGameStateChanged(int oldState, int newState) {
                stateChangedOldState = oldState;
                stateChangedNewState = newState;
            }

            @Override
            public void onTimeRemainingUpdated(long secondsRemaining) {
                updatedTimeRemaining = secondsRemaining;
            }

            @Override
            public void onServerIPChanged(InetAddress serverIP) {
                updatedServerIP = serverIP;
            }
        };

        repository.addListener(listener);

        assertFalse(repository.isGameRunning());
        assertFalse(repository.isGameStarted());

        repository.setGameState(GameStateRepository.GAME_STATE_RUNNING);
        assertEquals(GameStateRepository.GAME_STATE_NONE, stateChangedOldState);
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, stateChangedNewState);
        assertTrue(repository.isGameRunning());
        assertTrue(repository.isGameStarted());

        repository.setServerGameTimeRemaining(300);
        assertEquals(300, updatedTimeRemaining);

        InetAddress testIP = InetAddress.getByName("192.168.1.100");
        repository.setServerIP(testIP);
        assertEquals(testIP, updatedServerIP);

        repository.removeListener(listener);
    }
}
