/*
 * Copyright (C) 2018 Ethan Yonker
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

package com.simplecoil.simplecoil;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

public class Globals {
    private static Globals mInstance= null;

    /* Highest player ID allowed in the GUI, absolute max is 0x3F or 63. Player ID 0 can technically
    be used but would require code changes to the hit detection if you really need 64 players. */
    public static final byte MAX_PLAYER_ID = (byte) 0x20;

    public static final byte RELOAD_COUNT = (byte) 30; // Number of shots you get after a reload, max 255
    public volatile byte mFullReload = RELOAD_COUNT;
    public static final long RESPAWN_TIME_SECONDS = 10; // Time to wait for respawn after elimination and to start the game
    public volatile long mRespawnTime = RESPAWN_TIME_SECONDS;
    public static final long RELOAD_TIME_MILLISECONDS = 1500; // Reload downtime in ms. 1.5 seconds
    public volatile long mReloadTime = RELOAD_TIME_MILLISECONDS;
    public static final int MAX_HEALTH = 20; // Number of hits you can take before you are eliminated
    public volatile int mFullHealth = MAX_HEALTH;
    public static final int DAMAGE_PER_HIT = -1;
    public volatile int mDamage = DAMAGE_PER_HIT;
    public volatile boolean mOverrideLives = false;
    public volatile int mOverrideLivesVal = 0;
    public volatile boolean mAllowPlayerSettings = true;
    public volatile boolean mReloadOnEmpty = false; // Primarily intended for instagib

    public static final int INVALID_PLAYER_ID = -100;
    public static final int GRENADE_PLAYER_ID = 167;

    // 1 for FFA, 2 for 2 Teams, and 4 for 4 Teams
    public static final int GAME_MODE_FFA = 1;
    public static final int GAME_MODE_2TEAMS = 2;
    public static final int GAME_MODE_4TEAMS = 4;
    public volatile int mGameMode = GAME_MODE_2TEAMS;

    public static final int GAME_LIMIT_NONE = 0;
    public static final int GAME_LIMIT_TIME = 1;
    public static final int GAME_LIMIT_LIVES = 2;
    public static final int GAME_LIMIT_SCORE = 4;
    public volatile int mGameLimit = GAME_LIMIT_NONE;
    public volatile int mTimeLimit = 0;
    public volatile int mScoreLimit = 0;
    public volatile int mLivesLimit = 0;

    public static final int GPS_DISABLED = 0;
    public static final int GPS_TEAMMATE = 1;
    public static final int GPS_ALL = 2;
    public volatile int mGPSMode = GPS_ALL;

    public static final int GAME_STATE_NONE = 0; // Game not started
    public static final int GAME_STATE_RUNNING = 1; // Game running and player is in the game
    public static final int GAME_STATE_ELIMINATED = 2; // Game running but player is out right now
    public volatile int mGameState = GAME_STATE_NONE;

    public static final int SHOT_MODE_FULL_AUTO = 1;
    public static final int SHOT_MODE_SINGLE = 2;
    public static final int SHOT_MODE_BURST = 4;
    public volatile boolean mAllowSingleShotMode = true;
    public volatile boolean mAllowBurst3ShotMode = true;
    public volatile boolean mAllowAutoShotMode = true;

    public static final int FIRING_MODE_OUTDOOR_NO_CONE = 0;
    public static final int FIRING_MODE_OUTDOOR_WITH_CONE = 1;
    public static final int FIRING_MODE_INDOOR_NO_CONE = 2;
    public volatile int mCurrentFiringMode = FIRING_MODE_OUTDOOR_NO_CONE;

    public volatile byte mPlayerID = 0;
    public volatile String mPlayerName = "";
    public volatile Map<InetAddress, Byte> mIPTeamMap;
    public volatile Map<Byte, InetAddress> mTeamIPMap;
    public volatile Map<Byte, String> mTeamPlayerNameMap;
    public volatile Map<Byte, GPSData> mGPSData;
    public volatile Map<Byte, PlayerSettings> mPlayerSettings;

    public static final int MAX_GRENADE_IDS = 16;
    public volatile byte mPairedGrenadeID = 0;
    public volatile int[] mGrenadePairings;

    public Semaphore mIPTeamMapSemaphore;
    public Semaphore mTeamIPMapSemaphore;
    public Semaphore mTeamPlayerNameSemaphore;
    public Semaphore mGPSDataSemaphore;
    public Semaphore mPlayerSettingsSemaphore;
    public Semaphore mGrenadePairingsSemaphore;
    public volatile boolean mUseGPS = false;
    public volatile boolean mOnlyServerSettings = false;

    public volatile long mServerGameTimeRemaining = 0; // in seconds

    public volatile InetAddress mServerIP = null;

    protected Globals(){}

    public static synchronized Globals getInstance(){
        if(mInstance == null){
            mInstance = new Globals();
            mInstance.mIPTeamMap = new HashMap<InetAddress, Byte>();
            mInstance.mTeamIPMap = new HashMap<Byte, InetAddress>();
            mInstance.mTeamPlayerNameMap = new HashMap<Byte, String>();
            mInstance.mPlayerSettings = new HashMap<Byte, PlayerSettings>();

            mInstance.mIPTeamMapSemaphore = new Semaphore(1);
            mInstance.mTeamIPMapSemaphore = new Semaphore(1);
            mInstance.mTeamPlayerNameSemaphore = new Semaphore(1);
            mInstance.mGPSDataSemaphore = new Semaphore(1);
            mInstance.mPlayerSettingsSemaphore = new Semaphore(1);
            mInstance.mGrenadePairingsSemaphore = new Semaphore(1);
            mInstance.mGrenadePairings = new int[MAX_GRENADE_IDS];
        }
        return mInstance;
    }

    public int calcNetworkTeam(byte player_id) {
        int team = 1;
        if (player_id > MAX_PLAYER_ID)
            return INVALID_PLAYER_ID;
        if (mGameMode == GAME_MODE_2TEAMS) {
            final int x = ((MAX_PLAYER_ID + 1) / 2);
            if (player_id > x)
                team = 2;
        } else if (mGameMode == GAME_MODE_4TEAMS){
            final int x = ((MAX_PLAYER_ID + 1) / 4);
            if (player_id > 3 * x)
                team = 4;
            else if (player_id > 2 * x)
                team = 3;
            else if (player_id > x)
                team = 2;
        } else if (mGameMode == GAME_MODE_FFA)
            return player_id;
        return team;
    }

    public static byte findAutoSortPlayerID() {
        int gameMode = getInstance().mGameMode;
        int maxPlayers = MAX_PLAYER_ID;

        getmTeamIPMapSemaphore();
        Map<Byte, InetAddress> teamMap = getInstance().mTeamIPMap;

        if (gameMode == GAME_MODE_FFA) {
            for (byte i = 1; i <= maxPlayers; i++) {
                if (i != getInstance().mPlayerID && teamMap.get(i) == null) {
                    getInstance().mTeamIPMapSemaphore.release();
                    return i;
                }
            }
        } else {
            int numTeams = (gameMode == GAME_MODE_4TEAMS) ? 4 : 2;
            int playersPerTeam = maxPlayers / numTeams;
            int[] teamCounts = new int[numTeams + 1];

            for (byte i = 1; i <= maxPlayers; i++) {
                if (i == getInstance().mPlayerID || teamMap.get(i) != null) {
                    int team = getInstance().calcNetworkTeam(i);
                    if (team >= 1 && team <= numTeams) {
                        teamCounts[team]++;
                    }
                }
            }

            int minCount = Integer.MAX_VALUE;
            int bestTeam = 1;
            for (int t = 1; t <= numTeams; t++) {
                if (teamCounts[t] < minCount) {
                    minCount = teamCounts[t];
                    bestTeam = t;
                }
            }

            int startID = (bestTeam - 1) * playersPerTeam + 1;
            int endID = bestTeam * playersPerTeam;
            for (int i = startID; i <= endID; i++) {
                byte testID = (byte) i;
                if (testID != getInstance().mPlayerID && teamMap.get(testID) == null) {
                    getInstance().mTeamIPMapSemaphore.release();
                    return testID;
                }
            }

            for (byte i = 1; i <= maxPlayers; i++) {
                if (i != getInstance().mPlayerID && teamMap.get(i) == null) {
                    getInstance().mTeamIPMapSemaphore.release();
                    return i;
                }
            }
        }

        getInstance().mTeamIPMapSemaphore.release();
        return 0;
    }

    public static byte findOpenPlayerIDOnTeam(int targetTeam) {
        int gameMode = getInstance().mGameMode;
        int maxPlayers = MAX_PLAYER_ID;
        int numTeams = (gameMode == GAME_MODE_4TEAMS) ? 4 : 2;
        if (targetTeam < 1 || targetTeam > numTeams)
            return 0;

        int playersPerTeam = maxPlayers / numTeams;
        int startID = (targetTeam - 1) * playersPerTeam + 1;
        int endID = targetTeam * playersPerTeam;

        getmTeamIPMapSemaphore();
        Map<Byte, InetAddress> teamMap = getInstance().mTeamIPMap;

        for (int i = startID; i <= endID; i++) {
            byte testID = (byte) i;
            if (testID != getInstance().mPlayerID && teamMap.get(testID) == null) {
                getInstance().mTeamIPMapSemaphore.release();
                return testID;
            }
        }

        getInstance().mTeamIPMapSemaphore.release();
        return 0; // Target team is full
    }

    public String getPlayerName(Byte playerID) {
        getmTeamPlayerNameSemaphore();
        String ret = getInstance().mTeamPlayerNameMap.get(playerID);
        getInstance().mTeamPlayerNameSemaphore.release();
        if (ret == null)
            ret = "";
        return ret;
    }

    /**
     * Get IP address from first non-localhost interface
     * @return  address or empty string
     */
    public static String getIPAddressStr() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;

                        if (isIPv4) {
                            return sAddr;
                        } /*else {
                            // IPv6 stuff
                            int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                            return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                        }*/
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "";
    }
    public static InetAddress getIPAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;

                        if (isIPv4) {
                            return addr;
                        } /*else {
                            // IPv6 stuff
                            int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                            return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                        }*/
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static int getPlayerCount() {
        int ret = 1;
        getmTeamIPMapSemaphore();
        if (getInstance().mTeamIPMap != null)
            ret = getInstance().mTeamIPMap.size();
        getInstance().mTeamIPMapSemaphore.release();
        return ret + 1; // All other players plus ourself
    }

    public static class GPSData {
        double latitude = 0;
        double longitude = 0;
        int team = 0;
        boolean hasUpdate = false;
    }

    public static void getmIPTeamMapSemaphore() {
        try {
            getInstance().mIPTeamMapSemaphore.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void getmTeamIPMapSemaphore() {
        try {
            getInstance().mTeamIPMapSemaphore.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void getmTeamPlayerNameSemaphore() {
        try {
            getInstance().mTeamPlayerNameSemaphore.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void getmGPSDataSemaphore() {
        try {
            getInstance().mGPSDataSemaphore.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void getmPlayerSettingsSemaphore() {
        try {
            getInstance().mPlayerSettingsSemaphore.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void getmGrenadePairingsSemaphore() {
        try {
            getInstance().mGrenadePairingsSemaphore.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void ClearGrenadePairings(boolean getSemaphore) {
        if (getSemaphore)
            getmGrenadePairingsSemaphore();
        for (int index = 0; index < MAX_GRENADE_IDS; index++) {
            getInstance().mGrenadePairings[index] = Globals.INVALID_PLAYER_ID;
        }
        if (getSemaphore)
            getInstance().mGrenadePairingsSemaphore.release();
    }

    public static final int RECOIL_SETTING_DEFAULT = 0; // Let Player Choose
    public static final int RECOIL_SETTING_ENABLED = 1; // Force Enabled
    public static final int RECOIL_SETTING_DISABLED = 2; // Force Disabled
    public volatile int mServerRecoilSetting = RECOIL_SETTING_DEFAULT;
    public volatile boolean mIsAdmin = false;

    public static class PlayerSettings {
        int health = Globals.MAX_HEALTH;
        byte shots = Globals.RELOAD_COUNT;
        long reloadTime = Globals.RELOAD_TIME_MILLISECONDS;
        boolean reloadOnEmpty = false;
        long spawnTime = Globals.RESPAWN_TIME_SECONDS;
        int damage = Globals.DAMAGE_PER_HIT;
        boolean overrideLives = false;
        int lives = 0;
        boolean allowShotModeSingle = true;
        boolean allowShotModeBurst3 = true;
        boolean allowShotModeAuto = true;
        int firingMode = FIRING_MODE_OUTDOOR_NO_CONE;
        int recoilSetting = RECOIL_SETTING_DEFAULT;
        boolean isAdmin = false;
    }
}