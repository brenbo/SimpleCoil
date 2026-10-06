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

package com.simplecoil.simplecoil.ui.main;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.domain.engine.GameEngine;

import org.junit.Before;
import org.junit.Test;

public class MainViewModelTest {

    private GameSettingsRepository settingsRepo;
    private GameStateRepository gameStateRepo;
    private PlayerRepository playerRepo;
    private GameEngine gameEngine;

    @Before
    public void setUp() {
        settingsRepo = GameSettingsRepository.getInstance();
        gameStateRepo = GameStateRepository.getInstance();
        playerRepo = PlayerRepository.getInstance();

        settingsRepo.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);
        settingsRepo.setFullHealth(20);
        settingsRepo.setFullReload((byte) 30);
        gameStateRepo.setGameState(GameStateRepository.GAME_STATE_RUNNING);
        playerRepo.setPlayerID((byte) 1);
        playerRepo.setPlayerName("Player 1");

        gameEngine = GameEngine.createInstanceForTesting(settingsRepo, gameStateRepo, playerRepo);
        gameEngine.setHitCooldownMs(0);
    }

    @Test
    public void testPlayerRepositoryIntegration() {
        assertEquals((byte) 1, playerRepo.getPlayerID());
        assertEquals("Player 1", playerRepo.getPlayerName());

        playerRepo.setPlayerName("Testing Hero");
        assertEquals("Testing Hero", playerRepo.getPlayerName());
    }

    @Test
    public void testGameEngineTriggerPullInViewModelDomain() {
        assertEquals(30, gameEngine.getRemainingShots());

        boolean success = gameEngine.processTriggerPull();

        assertTrue(success);
        assertEquals(29, gameEngine.getRemainingShots());
    }

    @Test
    public void testGameStateRepositoryChanges() {
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, gameStateRepo.getGameState());

        gameStateRepo.setGameState(GameStateRepository.GAME_STATE_ELIMINATED);
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, gameStateRepo.getGameState());
    }
}
