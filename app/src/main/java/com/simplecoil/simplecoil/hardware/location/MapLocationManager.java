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

package com.simplecoil.simplecoil.hardware.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import com.simplecoil.simplecoil.data.model.GpsData;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.domain.team.TeamLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates GPS location updates, location provider comparison logic,
 * and which other players the map may show.
 */
public class MapLocationManager {
    private static final String TAG = "MapLocationManager";

    public static final int TWO_MINUTES = 1000 * 60 * 2;

    private final PlayerRepository playerRepo;
    private final GameSettingsRepository settingsRepo;

    private Location currentBestLocation;
    private LocationManager locationManager;
    private LocationListener locationListener;

    private double lastLongitude = 0;
    private double lastLatitude = 0;

    public MapLocationManager(PlayerRepository playerRepo, GameSettingsRepository settingsRepo) {
        this.playerRepo = playerRepo;
        this.settingsRepo = settingsRepo;
    }

    public synchronized void startLocationUpdates(Context context, LocationUpdateListener listener) {
        if (context == null) return;

        if (locationManager == null) {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        }

        if (locationManager == null) return;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) {
                listener.onPermissionRequired();
            }
            return;
        }

        if (locationListener == null) {
            lastLongitude = 0;
            lastLatitude = 0;

            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null) return;
                    if (isBetterLocation(location, currentBestLocation)) {
                        currentBestLocation = location;
                        lastLatitude = location.getLatitude();
                        lastLongitude = location.getLongitude();
                        if (listener != null) {
                            listener.onLocationUpdated(location, lastLatitude, lastLongitude);
                        }
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}

                @Override
                public void onProviderEnabled(String provider) {}

                @Override
                public void onProviderDisabled(String provider) {}
            };

            // No minimum distance: fixes keep coming while standing still (in a spawn, say)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
            currentBestLocation = getLastBestLocation(context);
            if (currentBestLocation != null && listener != null) {
                listener.onLocationUpdated(currentBestLocation, currentBestLocation.getLatitude(), currentBestLocation.getLongitude());
            }
        }
    }

    public synchronized void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }
    }

    public Location getCurrentBestLocation() {
        return currentBestLocation;
    }

    public Location getLastBestLocation(Context context) {
        if (locationManager == null) {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        }
        if (locationManager == null) return null;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        Location locationGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location locationNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        long gpsTime = (locationGPS != null) ? locationGPS.getTime() : 0;
        long netTime = (locationNet != null) ? locationNet.getTime() : 0;

        return (gpsTime - netTime > 0) ? locationGPS : locationNet;
    }

    public boolean isBetterLocation(Location location, Location currentBest) {
        if (location == null) return false;
        if (currentBest == null) return true;

        return isBetterLocation(
                location.getTime(), location.getAccuracy(), location.getProvider(),
                currentBest.getTime(), currentBest.getAccuracy(), currentBest.getProvider()
        );
    }

    public boolean isBetterLocation(long time, float accuracy, String provider,
                                   long currentTime, float currentAccuracy, String currentProvider) {
        long timeDelta = time - currentTime;
        boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
        boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
        boolean isNewer = timeDelta > 0;

        if (isSignificantlyNewer) {
            return true;
        } else if (isSignificantlyOlder) {
            return false;
        }

        int accuracyDelta = (int) (accuracy - currentAccuracy);
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        boolean isFromSameProvider = isSameProvider(provider, currentProvider);

        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else return isNewer && !isSignificantlyLessAccurate && isFromSameProvider;
    }

    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }

    /** Whether another player's marker may be shown: teammates always, everyone else only in GPS_ALL. */
    public static boolean isVisible(TeamLayout layout, int gpsMode, byte myPlayerId, GpsData other) {
        return isTeammate(layout, myPlayerId, other) || gpsMode == GameSettingsRepository.GPS_ALL;
    }

    public static boolean isTeammate(TeamLayout layout, byte myPlayerId, GpsData other) {
        return !layout.getMode().isFreeForAll() && other.team == layout.teamOf(myPlayerId);
    }

    /** Another player's last reported position, as the map shows it. */
    public static final class PlayerPosition {
        public final int playerId;
        public final double latitude;
        public final double longitude;
        public final boolean teammate;
        /** The team the server says they are on (in free for all, their player id). */
        public final int team;

        public PlayerPosition(int playerId, double latitude, double longitude, boolean teammate, int team) {
            this.playerId = playerId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.teammate = teammate;
            this.team = team;
        }
    }

    /** Every other player the map may show, from PlayerRepository's GPS data. */
    public List<PlayerPosition> visiblePlayers() {
        TeamLayout layout = settingsRepo.getTeamLayout();
        int gpsMode = settingsRepo.getGpsMode();
        byte me = playerRepo.getPlayerID();
        Map<Byte, GpsData> gpsDataMap = playerRepo.getGpsData();
        List<PlayerPosition> result = new ArrayList<>();
        // The repository's lock keeps a GPS report from landing while we read
        synchronized (playerRepo) {
            for (Map.Entry<Byte, GpsData> entry : gpsDataMap.entrySet()) {
                Byte pid = entry.getKey();
                GpsData data = entry.getValue();
                if (pid == null || data == null || pid == me) continue;
                data.hasUpdate = false;
                if (data.latitude == 0 && data.longitude == 0) continue; // never reported
                if (!isVisible(layout, gpsMode, me, data)) continue;
                result.add(new PlayerPosition(pid, data.latitude, data.longitude, isTeammate(layout, me, data), data.team));
            }
        }
        return result;
    }
}
