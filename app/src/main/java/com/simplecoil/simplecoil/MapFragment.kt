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

package com.simplecoil.simplecoil

import android.graphics.Bitmap
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.FragmentMapBinding
import com.simplecoil.simplecoil.domain.loadout.AmmoMode
import com.simplecoil.simplecoil.domain.loadout.LoadoutController
import com.simplecoil.simplecoil.domain.loadout.LongestShot
import com.simplecoil.simplecoil.domain.map.AutoZoom
import com.simplecoil.simplecoil.domain.team.TeamLayout
import com.simplecoil.simplecoil.domain.map.BorderTracker
import com.simplecoil.simplecoil.domain.map.DistanceUnits
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoCircle
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.HeadingFilter
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.OutOfBoundsPenalty
import com.simplecoil.simplecoil.domain.map.SpawnController
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.domain.mode.GameMode
import com.simplecoil.simplecoil.domain.mode.ModeController
import com.simplecoil.simplecoil.domain.mode.ModeState
import com.simplecoil.simplecoil.hardware.location.CompassSensor
import com.simplecoil.simplecoil.hardware.location.LocationUpdateListener
import com.simplecoil.simplecoil.hardware.location.MapLocationManager
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.imagery.FieldPackInfo
import com.simplecoil.simplecoil.ui.map.DropOverlay
import com.simplecoil.simplecoil.ui.map.ZoomMenu
import com.simplecoil.simplecoil.ui.permissions.BlePermissions
import com.simplecoil.simplecoil.ui.permissions.PermissionAsk
import com.simplecoil.simplecoil.ui.permissions.PermissionKind
import com.simplecoil.simplecoil.ui.map.MapCredit
import com.simplecoil.simplecoil.ui.map.MapIcons
import com.simplecoil.simplecoil.ui.map.MapStyles
import com.simplecoil.simplecoil.ui.map.ObjectiveOverlay
import com.simplecoil.simplecoil.ui.map.GlyphOverlay
import com.simplecoil.simplecoil.domain.mode.ObjectiveState
import com.simplecoil.simplecoil.domain.mode.InfectionEngine
import com.simplecoil.simplecoil.domain.mode.RoundPhase
import com.simplecoil.simplecoil.domain.mode.sideOf
import com.simplecoil.simplecoil.domain.mode.FlagEngine
import com.simplecoil.simplecoil.domain.mode.flagBaseSpots
import com.simplecoil.simplecoil.ui.map.CameraLimits
import com.simplecoil.simplecoil.ui.map.OutsideShade
import com.simplecoil.simplecoil.ui.map.ZoneLayers
import com.simplecoil.simplecoil.ui.map.SpawnLayers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import java.util.Locale

/**
 * The in-game map (map design: Map engine, Border). The field photo and border the server sent,
 * the players, a heading-up compass that turns the map every frame, and a three-way auto zoom.
 * Location tracking lives in [MapLocationManager]; this fragment owns the map view, asks for
 * the location permission, tells the network about our position and feeds the border check.
 */
class MapFragment : Fragment() {

    private lateinit var container: AppContainer
    private lateinit var location: MapLocationManager
    private lateinit var settings: GameSettingsRepository
    private lateinit var preferences: UserPreferencesRepository

    private var binding: FragmentMapBinding? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private val heading = HeadingFilter()
    private var compass: CompassSensor? = null
    private var headingUp = true
    private var tracking = false

    private var photo: FieldPackInfo? = null
    private var field: FieldConfig? = null

    /** The auto zoom button: the whole field until the player picks something else. */
    private var autoZoom = AutoZoom.FIELD

    /** What auto zoom is fitting, refreshed when positions or the field change. */
    private var target: GeoCircle? = null

    /** The player has a finger on the map: leave the camera to them. */
    private var userMoving = false

    /** The camera has been put somewhere sensible once. */
    private var placed = false

    /** The other players on the map, their marker views, and the marker images by team. */
    private var shownPlayers: List<MapLocationManager.PlayerPosition> = emptyList()
    private val playerViews = HashMap<Int, ImageView>()
    private val playerBitmaps = HashMap<Int, Bitmap>()

    /** The drops over the map, and the last loadout notice with when it came. */
    private var dropOverlay: DropOverlay? = null
    private var lastNotice: LoadoutController.Notice? = null
    private var lastNoticeAt = 0L

    /** The capture points over the map, and the last news of them with when it came. */
    private var objectiveOverlay: ObjectiveOverlay? = null
    private var glyphOverlay: GlyphOverlay? = null
    private var lastModeNotice: ModeController.Notice? = null
    private var lastModeNoticeAt = 0L

    /** The team colour your arrow was last drawn in. */
    private var youTeam = -1

    /** When the phone was last seen connected to its server. */
    private var lastConnectedAt = Long.MIN_VALUE

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateVisibility()
            renderSpawns()
            renderDrops()
            showDropBanner()
            showModeBanner()
            if (!inGame()) objectiveOverlay?.clear()
            refreshTarget()
            location.currentBestLocation?.takeIf { tracking && (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 < FRESH_FIX_MILLIS }
                ?.let { sendLocation(it) }
            // While connected, check for a new field every few seconds, so the host's edits
            // show up soon after they close the field map (the request is tiny when nothing changed)
            if (container.networkGateway.isConnectedToServer) container.fieldImageryClient.syncSoon(container.gameState.serverIP)
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    private var framesRunning = false
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!framesRunning) return
            if (container.spawnController.isFading()) renderSpawns()
            easeYou(frameTimeNanos)
            moveCamera()
            placeYou()
            placePlayers()
            map?.projection?.let {
                objectiveOverlay?.place(it)
                glyphOverlay?.place(it)
                dropOverlay?.place(it)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // Asked once, with what it's for first; a player who said no isn't asked at every map
    private val locationAsk: PermissionAsk = PermissionAsk.of(this, "mapLocation") { _ ->
        if (locationAsk.isGranted(PermissionKind.LOCATION)) enableGps(wantsGps())
    }

    private val locationListener = object : LocationUpdateListener {
        override fun onLocationUpdated(location: Location, latitude: Double, longitude: Double) {
            sendLocation(location)
            compass?.setLocation(location)
            if (location.hasBearing() && location.hasSpeed()) {
                heading.onCourse(location.bearing.toDouble(), location.speed, container.clock())
            }
            // A cached position from before the app started says nothing about where we are now
            if ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000 < FRESH_FIX_MILLIS) {
                container.borderController.onFix(LatLon(latitude, longitude), location.accuracy)
                container.spawnController.onFix(LatLon(latitude, longitude), location.accuracy)
                container.loadoutController.onFix(LatLon(latitude, longitude))
                container.zoneController.onFix(LatLon(latitude, longitude))
            }
            renderPlayers()
            refreshTarget()
        }

        override fun onPermissionRequired() {
            tracking = false
            locationAsk.ask(BlePermissions.Flow.MAP_LOCATION)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (requireActivity().application as SimpleCoilApp).container
        location = container.mapLocation
        settings = container.settings
        preferences = container.userPreferences
        headingUp = preferences.mapHeadingUp()
        // The last mode the player picked with the button, kept across restarts
        autoZoom = (savedInstanceState?.getString(STATE_AUTO_ZOOM) ?: preferences.mapAutoZoom())
            ?.let { runCatching { AutoZoom.valueOf(it) }.getOrNull() } ?: AutoZoom.FIELD
        // While resumed, as the broadcast receiver it replaces was registered
        container.clientEvents.observe(this, Lifecycle.State.RESUMED) { onClientEvent(it) }
        container.loadoutController // starts it: it watches the game from here on
        container.modeController
        container.zoneController
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        MapLibre.getInstance(requireContext().applicationContext)
        val b = FragmentMapBinding.inflate(inflater, parent, false)
        binding = b
        b.mapView.onCreate(savedInstanceState)
        b.mapView.getMapAsync { onMapReady(it) }
        b.mapCompassButton.setOnClickListener { toggleHeadingUp() }
        dropOverlay = DropOverlay(requireContext(), b.mapDropsLayer)
        objectiveOverlay = ObjectiveOverlay(requireContext(), b.mapObjectivesLayer) { objectiveColor(it) }
        glyphOverlay = GlyphOverlay(requireContext(), b.mapGlyphsLayer)
        b.mapZoomButton.setOnClickListener { button ->
            ZoomMenu.show(button, AutoZoom.entries, autoZoom) { zoom ->
                setAutoZoom(zoom)
                preferences.saveMapAutoZoom(autoZoom.name)
            }
        }
        MapCredit.attach(b.mapCreditTv, mapTiler = false)
        compass = CompassSensor(requireContext()).also { if (!it.isAvailable) headingUp = false }
        b.mapCompassButton.isVisible = compass?.isAvailable == true
        updateCompassButton()
        updateZoomButton()
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // As the old map did: as tall as the layout makes it wide
        view.post {
            val params = view.layoutParams ?: return@post
            params.height = params.width
            view.layoutParams = params
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.IO) {
                    container.playerFieldStore.refresh()
                    container.playerFieldConfig.refresh()
                }
                launch { container.playerFieldStore.info.collect { showPhoto(it) } }
                launch { container.playerFieldConfig.config.collect { showField(it) } }
                launch { container.borderController.status.collect { showBorderStatus(it) } }
                launch { container.spawnController.guide.collect { showGuide(it) } }
                launch { container.spawnController.noFix.collect { showGuide(container.spawnController.guide.value) } }
                launch { container.spawnController.waiting.collect { showGuide(container.spawnController.guide.value) } }
                launch { container.loadoutController.claiming.collect { showDropBanner() } }
                launch { container.loadoutController.notices.collect { lastNotice = it; lastNoticeAt = container.clock(); showDropBanner() } }
                launch { container.loadoutController.longestShot.collect { showLongestShot(it) } }
                launch { container.loadoutController.uav.collect { renderPlayers() } }
                launch { container.modeController.state.collect { renderMode(it) } }
                // Extraction zones open on the clock, with nothing else changing: draw them again then
                launch {
                    while (true) {
                        delay(EXTRACTION_REDRAW_MILLIS)
                        container.modeController.state.value?.takeIf { it.mode == GameMode.EXTRACTION }?.let { renderMode(it) }
                    }
                }
                launch { container.zoneController.outside.collect { showModeBanner() } }
                launch { container.modeController.notices.collect { lastModeNotice = it; lastModeNoticeAt = container.clock(); showModeBanner() } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        binding?.mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding?.mapView?.onResume()
        enableGps(wantsGps())
        if (headingUp) startCompass()
        updateVisibility()
        handler.post(ticker)
        framesRunning = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        framesRunning = false
        Choreographer.getInstance().removeFrameCallback(frame)
        handler.removeCallbacks(ticker)
        compass?.stop()
        binding?.mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding?.mapView?.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_AUTO_ZOOM, autoZoom.name)
        binding?.mapView?.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        @Suppress("DEPRECATION")
        super.onLowMemory()
        binding?.mapView?.onLowMemory()
    }

    override fun onDestroyView() {
        binding?.mapView?.onDestroy()
        binding = null
        youTeam = -1
        dropOverlay = null
        objectiveOverlay = null
        glyphOverlay = null
        playerViews.clear()
        map = null
        style = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        // The manager is process-wide; don't leave it reporting to a dead fragment.
        if (tracking) {
            location.stopLocationUpdates()
            tracking = false
        }
        super.onDestroy()
    }

    private fun onMapReady(map: MapLibreMap) {
        this.map = map
        // From a few blocks across down to a few metres: no zooming out to the planet or into the blur
        map.setMinZoomPreference(MIN_ZOOM)
        map.setMaxZoomPreference(MAX_ZOOM)
        map.uiSettings.apply {
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = false
            isTiltGesturesEnabled = false
            isRotateGesturesEnabled = false
        }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                // Moving the map by hand turns auto zoom off
                userMoving = true
                setAutoZoom(AutoZoom.OFF)
            }
        }
        map.addOnCameraIdleListener { userMoving = false }
        map.addOnCameraMoveListener { updateCompassButton() }
        map.setStyle(MapStyles.playStyle()) { loaded ->
            style = loaded
            addFieldLayers(loaded)
            limitCamera()
            photo?.let { showPhoto(it) }
            showField(field)
            renderPlayers()
            refreshTarget()
        }
    }

    private fun addFieldLayers(style: Style) {
        // Outside the border greyed out, under everything on the field
        OutsideShade.install(style)
        ZoneLayers.install(style)
        // Spawns under everything else; then the dashed way to yours, and the border
        SpawnLayers.install(style, requireContext())
        style.addSource(GeoJsonSource(GUIDE_SOURCE))
        style.addLayer(LineLayer(GUIDE_LAYER, GUIDE_SOURCE).withProperties(
            PropertyFactory.lineColor(GUIDE_COLOR.toColorInt()),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
        ))
        // Out of bounds: the way back to the field
        style.addSource(GeoJsonSource(BACK_SOURCE))
        style.addLayer(LineLayer(BACK_SOURCE, BACK_SOURCE).withProperties(
            PropertyFactory.lineColor(BORDER_COLOR.toColorInt()),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
        ))
        style.addSource(GeoJsonSource(LONGEST_SOURCE))
        style.addLayer(LineLayer(LONGEST_LAYER, LONGEST_SOURCE).withProperties(
            PropertyFactory.lineColor(LONGEST_COLOR.toColorInt()),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1f)),
        ))
        style.addSource(GeoJsonSource(BORDER_SOURCE))
        style.addLayer(LineLayer(BORDER_LAYER, BORDER_SOURCE).withProperties(
            PropertyFactory.lineColor(BORDER_COLOR.toColorInt()),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
    }

    /** The marker for a player on [team] (0 = no team colour), made once and reused. */
    private fun playerBitmap(team: Int, enemy: Boolean): Bitmap =
        playerBitmaps.getOrPut(if (enemy) -team - 1 else team) { MapIcons.player(requireContext(), MapIcons.teamColor(team), enemy) }

    /** A team's marker colour index: 1-4, a squad's team, or 0 in free for all or alone (where "teams" are player ids). */
    private fun markerTeam(team: Int): Int {
        val mode = settings.teamLayout.mode
        return when {
            mode.isFreeForAll -> 0
            mode.isSquads -> if (team > TeamLayout.SQUAD_TEAM_BASE) team else 0
            team in 1..4 -> team
            else -> 0
        }
    }

    private fun onClientEvent(event: ClientEvent) {
        when (event) {
            is ClientEvent.GpsData -> {
                renderPlayers()
                refreshTarget()
            }
            is ClientEvent.ListPlayers -> {
                enableGps(wantsGps())
                // Hearing from the server: fetch its field if it has a new one
                container.fieldImageryClient.syncSoon(container.gameState.serverIP)
            }
            // The game starts with the server's latest border and rules
            ClientEvent.StartGame -> container.fieldImageryClient.syncSoon(container.gameState.serverIP, force = true)
            ClientEvent.GpsSettingChanged -> enableGps(wantsGps())
            ClientEvent.NetworkConnected, ClientEvent.NetworkDisconnected, ClientEvent.ServerCancel -> updateVisibility()
            else -> {}
        }
    }

    private fun enableGps(enabled: Boolean) {
        if (activity == null) return
        // No GPS in this game: no map behind the play screen either
        updateVisibility()
        if (enabled) {
            if (!tracking) {
                tracking = true
                lastSentLatitude = 0.0
                lastSentLongitude = 0.0
                location.startLocationUpdates(activity, locationListener)
                if (!tracking) return // waiting for the permission
            }
        } else if (tracking) {
            location.stopLocationUpdates()
            tracking = false
        }
        renderPlayers()
    }

    /**
     * Track our position when the server shares GPS, or when the field needs it (a border to
     * keep to, spawns to walk back to): those are checked on this phone, even with sharing off.
     */
    private fun wantsGps(): Boolean =
        settings.isUseGPS || field?.border != null || field?.spawns?.isNotEmpty() == true || field?.mode?.onServer == true ||
            field?.loadout?.let { it.dropsEnabled || it.trackShots || it.ammo == AmmoMode.SCAVENGER } == true

    private fun sendLocation(fix: Location) {
        // Sent whenever we track: the server uses it for drops and shot distance, and only passes
        // it on to other players while it shares GPS
        // An unchanged position is sent again every few seconds, so the server knows it is current
        // (a player standing still still has their shots measured)
        val now = SystemClock.elapsedRealtime()
        if (fix.latitude == lastSentLatitude && fix.longitude == lastSentLongitude && now - lastSentAt < HEARTBEAT_MILLIS) return
        lastSentAt = now
        lastSentLatitude = fix.latitude
        lastSentLongitude = fix.longitude
        if (activity == null) return
        container.networkGateway.sendLocation(fix.latitude, fix.longitude)
    }

    private fun you(): LatLon? {
        if (!tracking) return null
        val best = location.currentBestLocation ?: return null
        return LatLon(best.latitude, best.longitude)
    }

    /** The other players to show: not those sitting out (joined too late to play), who aren't in the game. */
    private fun others(): List<MapLocationManager.PlayerPosition> {
        if (!settings.isUseGPS) return emptyList()
        val sittingOut = container.modeController.state.value?.sittingOut.orEmpty()
        return location.visiblePlayers().filter { it.playerId !in sittingOut }
    }

    private fun renderPlayers() {
        val style = style ?: return
        updateBorderVisibility()
        val myTeam = markerTeam(settings.teamLayout.teamOf(container.players.playerID))
        if (myTeam != youTeam) {
            youTeam = myTeam
            binding?.mapYouIv?.setImageBitmap(MapIcons.you(requireContext(), if (myTeam == 0) YOU_COLOR else MapIcons.teamColor(myTeam)))
        }
        // Other players: plain views over the map, placed every frame (placePlayers)
        val uav = container.loadoutController.uav.value
        shownPlayers = others().let { seen -> seen + uav.filter { u -> seen.none { it.playerId == u.playerId } }.map { u ->
            MapLocationManager.PlayerPosition(u.playerId, u.point.latitude, u.point.longitude, false, u.team) } }
        val layer = binding?.mapPlayersLayer ?: return
        val ids = shownPlayers.map { it.playerId }.toSet()
        playerViews.keys.filter { it !in ids }.forEach { layer.removeView(playerViews.remove(it)) }
        for (p in shownPlayers) {
            val view = playerViews.getOrPut(p.playerId) { ImageView(requireContext()).also { layer.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) } }
            view.setImageBitmap(playerBitmap(markerTeam(p.team), enemy = !p.teammate))
        }
        placePlayers()
    }

    /** Shows the field's photo, loading it off the main thread; null takes it away. */
    private fun showPhoto(info: FieldPackInfo?) {
        photo = info
        limitCamera()
        binding?.mapCreditTv?.let {
            it.isVisible = info != null
            // The photo's own credit: the server may have downloaded it from MapTiler or USGS
            MapCredit.attach(it, info != null && MapCredit.isMapTiler(info.credit))
        }
        val style = style ?: return
        if (info == null) {
            MapStyles.showFieldPhoto(style, null, null)
            refreshTarget()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val pack = container.playerFieldStore.load() ?: return@withContext null
                MapStyles.decodeFieldPhoto(pack)?.let { pack to it }
            }
            val current = this@MapFragment.style ?: return@launch
            if (loaded == null || photo?.id != loaded.first.info.id) {
                Log.w(TAG, "Couldn't show the field map")
                return@launch
            }
            MapStyles.showFieldPhoto(current, loaded.first, loaded.second)
            refreshTarget()
        }
    }

    /** Keeps the camera near the field: its border and photo, and a margin round them. */
    private fun limitCamera() {
        val map = map ?: return
        CameraLimits.keepNear(map, field?.border?.ring().orEmpty() + photo?.bounds?.corners.orEmpty())
    }

    /** Draws the field's border (or takes it away). */
    private fun showField(config: FieldConfig?) {
        field = config
        limitCamera()
        if (isResumed) enableGps(wantsGps())
        renderSpawns()
        val style = style ?: return
        val ring = config?.border?.ring()
        OutsideShade.update(style, ring)
        style.getSourceAs<GeoJsonSource>(BORDER_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(
            ring?.let { r -> Feature.fromGeometry(LineString.fromLngLats(r.map { Point.fromLngLat(it.longitude, it.latitude) })) })))
        updateBorderVisibility()
        refreshTarget()
    }

    /** Draws the spawns, the moving ones fading (map design: Moving spawns). */
    private fun renderSpawns() {
        val style = style ?: return
        // Battle Royale once respawns are over: spawns are no use to anyone, so they go
        val layers = container.spawnController.layers()?.takeIf { spawnsMatter() }
        val latitude = layers?.let { (it.steady + it.incoming + it.outgoing).firstOrNull()?.point?.latitude } ?: 0.0
        SpawnLayers.update(style, layers?.steady ?: emptyList(), layers?.incoming ?: emptyList(), layers?.outgoing ?: emptyList(),
            layers?.progress ?: 1.0, layers?.radiusMeters ?: 10.0, latitude)
    }

    /** Spawns are worth showing: not in a Battle Royale whose respawns are over. */
    private fun spawnsMatter(): Boolean {
        val state = container.modeController.state.value ?: return true
        return !(state.mode == GameMode.BATTLE_ROYALE && inGame() && state.zone?.noRespawn == true)
    }

    /** After an elimination with walk-back respawn: a dashed line and the distance to your nearest spawn. */
    private fun showGuide(guide: SpawnController.Guide?) {
        val me = you()
        style?.getSourceAs<GeoJsonSource>(GUIDE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(
            if (guide != null && me != null) {
                Feature.fromGeometry(LineString.fromLngLats(listOf(
                    Point.fromLngLat(me.longitude, me.latitude), Point.fromLngLat(guide.target.longitude, guide.target.latitude))))
            } else null)))
        val banner = binding?.mapSpawnTv ?: return
        val units = field?.rules?.units
        val waiting = container.spawnController.waiting.value
        when {
            guide != null && units != null -> banner.text = getString(R.string.map_go_to_spawn, units.format(guide.meters))
            waiting != null -> banner.text = if (waiting.of > 0) getString(R.string.map_waiting_for_everyone, waiting.atSpawn, waiting.of)
                else getString(R.string.map_waiting_for_everyone_short)
            container.spawnController.noFix.value -> banner.setText(R.string.map_spawn_no_gps)
        }
        banner.isVisible = (guide != null && units != null) || waiting != null || container.spawnController.noFix.value
    }

    /** Out of bounds: a dashed line from you to the nearest point of the border (map design: Border). */
    private fun showWayBack(status: BorderTracker.Status?) {
        val source = style?.getSourceAs<GeoJsonSource>(BACK_SOURCE) ?: return
        val border = field?.border
        val me = you()
        val outside = status != null && status.phase != BorderTracker.Phase.INSIDE && border != null && me != null && !border.contains(me)
        source.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(if (outside) {
            val edge = border!!.nearestEdgePoint(me!!)
            Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(me.longitude, me.latitude), Point.fromLngLat(edge.longitude, edge.latitude))))
        } else null)))
    }

    private fun showBorderStatus(status: BorderTracker.Status?) {
        updateBorderVisibility()
        showWayBack(status)
        val banner = binding?.mapOutOfBoundsTv ?: return
        val rules = field?.rules
        if (status == null || rules == null || status.phase == BorderTracker.Phase.INSIDE) {
            banner.isVisible = false
            return
        }
        val distance = rules.units.format(status.metersOutside)
        banner.text = when {
            status.phase == BorderTracker.Phase.WARNING -> getString(R.string.map_out_of_bounds_countdown, status.secondsLeft, distance)
            rules.penalty == OutOfBoundsPenalty.DISABLE_BLASTER -> getString(R.string.map_out_of_bounds_disabled, distance)
            rules.penalty == OutOfBoundsPenalty.LOSE_HEALTH -> getString(R.string.map_out_of_bounds_health, distance)
            else -> getString(R.string.map_out_of_bounds_warning, distance)
        }
        banner.isVisible = true
    }

    /**
     * With "show only when near", the border appears when you are within the rules' near
     * distance of it, or outside it; measured from your own position, in a game or not. Until
     * your position is known it stays shown.
     */
    private fun updateBorderVisibility() {
        val layer = style?.getLayer(BORDER_LAYER) ?: return
        val config = field
        val border = config?.border
        val me = you()
        val visible = border == null || !config.rules.showOnlyWhenNear || me == null ||
            !border.contains(me) || border.distanceToEdgeMeters(me) <= config.rules.nearDistanceMeters
        layer.setProperties(PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE))
    }

    private fun startCompass() {
        compass?.start { azimuth -> heading.onCompass(azimuth, container.clock()) }
    }

    private fun toggleHeadingUp() {
        if (compass?.isAvailable != true) return
        headingUp = !headingUp
        preferences.saveMapHeadingUp(headingUp)
        if (headingUp) {
            heading.reset()
            startCompass()
        } else {
            compass?.stop()
        }
        updateCompassButton()
    }

    private fun setAutoZoom(mode: AutoZoom) {
        if (autoZoom == mode) return
        autoZoom = mode
        updateZoomButton()
        refreshTarget()
    }

    private fun updateCompassButton() {
        val b = binding ?: return
        b.mapCompassButton.rotation = -(map?.cameraPosition?.bearing ?: 0.0).toFloat()
        b.mapCompassButton.contentDescription =
            getString(if (headingUp) R.string.map_compass_heading_up else R.string.map_compass_north_up)
    }

    private fun updateZoomButton() {
        binding?.mapZoomButton?.let { ZoomMenu.style(it, autoZoom) }
    }

    /** Works out what auto zoom fits now; the first time, also puts the camera somewhere useful. */
    private fun refreshTarget() {
        val border = field?.border
        val photoBounds = photo?.bounds
        val me = you()
        val others = others().map { LatLon(it.latitude, it.longitude) }
        // Battle Royale: fitting the field means fitting the safe zone, closer as it shrinks
        val zone = container.modeController.state.value?.takeIf { it.mode == GameMode.BATTLE_ROYALE && inGame() }?.zone?.current
        target = if (zone != null && autoZoom == AutoZoom.FIELD) zone else AutoZoom.target(autoZoom, border, photoBounds, me, others)
        if (!placed) {
            val start = AutoZoom.target(AutoZoom.FIELD, border, photoBounds, me, emptyList()) ?: return
            val map = map ?: return
            val zoom = zoomFor(start) ?: return
            placed = true
            map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                .target(LatLng(start.center.latitude, start.center.longitude)).zoom(zoom).build()))
        }
    }

    private fun zoomFor(circle: GeoCircle): Double? {
        val view = binding?.mapView ?: return null
        val sizeDp = min(view.width, view.height) / resources.displayMetrics.density
        return if (sizeDp > 0) GeoMath.zoomToFit(circle, sizeDp.toDouble(), MIN_ZOOM, MAX_ZOOM) else null
    }

    /**
     * One frame of camera movement: turn to the heading (already smoothed, so it is applied as
     * is, with no animation lag) and glide toward what auto zoom fits. Moves only when something
     * would visibly change, so a still map costs nothing.
     */
    /**
     * The map shows only while playing on a server and connected to it, in a game that uses GPS:
     * never in no-network mode, not in a classic game with GPS off, and not after the connection
     * has been gone for [DISCONNECTED_HIDE_MILLIS].
     */
    private fun updateVisibility() {
        val root = binding?.root ?: return
        val net = container.networkGateway
        val now = container.clock()
        if (net.isConnectedToServer) lastConnectedAt = now
        root.isVisible = net.useNetwork && lastConnectedAt != Long.MIN_VALUE && now - lastConnectedAt < DISCONNECTED_HIDE_MILLIS && wantsGps()
    }

    /**
     * Puts your arrow where you are on screen, turned the way you face. It's a plain view over
     * the map, moved every frame, so it turns smoothly and shows on every phone.
     */
    private fun placeYou() {
        val view = binding?.mapYouIv ?: return
        val map = map
        val me = smoothYou
        if (map == null || me == null || style == null) {
            view.isVisible = false
            return
        }
        val spot = map.projection.toScreenLocation(LatLng(me.latitude, me.longitude))
        view.translationX = spot.x - view.width / 2f
        view.translationY = spot.y - view.height / 2f
        val mapBearing = map.cameraPosition.bearing
        // Without a compass the arrow just points up the map
        view.rotation = ((heading.heading ?: mapBearing) - mapBearing).toFloat()
        view.isVisible = true
    }

    /** The drops on the field, with their countdowns (map design: Drops). */
    /** A team's colour on the capture points; in free for all, yours and everyone else's. */
    private fun objectiveColor(team: Int): Int = when {
        team == LOOT_TEAM -> MapIcons.LOOT
        team == EXTRACTION_TEAM -> MapIcons.EXTRACTION_OPEN
        team == EXTRACTION_CLOSED_TEAM -> MapIcons.EXTRACTION_CLOSED
        team == InfectionEngine.INFECTED -> INFECTED_COLOR
        team == InfectionEngine.SURVIVORS -> SURVIVOR_COLOR
        settings.teamLayout.mode.isFreeForAll || settings.teamLayout.mode.isSquads && team <= TeamLayout.SQUAD_TEAM_BASE ->
            if (team == container.players.playerID.toInt()) YOU_COLOR else ENEMY_COLOR
        else -> MapIcons.teamColor(team, ENEMY_COLOR)
    }

    private fun renderMode(state: ModeState?) {
        renderZone(state)
        val points = objectiveOverlay
        val glyphs = glyphOverlay
        if (state == null || !inGame()) {
            points?.clear()
            glyphs?.clear()
            showModeBanner()
            return
        }
        when (state.mode) {
            GameMode.DOMINATION, GameMode.HARDPOINT -> {
                points?.update(state.objectives, state.radiusMeters, letters = state.mode != GameMode.HARDPOINT)
                glyphs?.clear()
            }
            GameMode.SEARCH_AND_DESTROY -> {
                points?.update(state.objectives, state.radiusMeters, letters = true)
                glyphs?.update(bombGlyphs(state))
            }
            GameMode.INFECTION -> {
                points?.clear()
                glyphs?.update(survivorGlyphs(state))
            }
            GameMode.CAPTURE_THE_FLAG -> {
                // Each base in its team's colour, and the flags where they are
                points?.update(state.flagBaseSpots().map { (team, base) -> ObjectiveState("", base, team, 0, 0.0, contested = false, active = true, next = false) },
                    state.radiusMeters, letters = false)
                glyphs?.update(flagGlyphs(state))
            }
            GameMode.EXTRACTION -> {
                val e = state.extraction
                val open = e != null && container.fieldImageryClient.serverNow() >= e.opensAt
                // Extraction zones (red and dashed until they open, then green) and the loot, each pile showing its worth
                val areas = e?.zones?.mapIndexed { i, z ->
                    ObjectiveState(getString(R.string.ex_zone_label) + if (e.zones.size > 1) (i + 1).toString() else "", z, if (open) EXTRACTION_TEAM else EXTRACTION_CLOSED_TEAM, 0, 0.0,
                        contested = false, active = open, next = !open)
                }.orEmpty() + e?.loot?.map { ObjectiveState(it.value.toString(), it.point, LOOT_TEAM, 0, 0.0, contested = false, active = true, next = false) }.orEmpty()
                points?.update(areas, state.radiusMeters, letters = true)
                glyphs?.clear()
            }
            else -> {
                points?.clear()
                glyphs?.clear()
            }
        }
        showModeBanner()
    }

    /**
     * The flags: at home or on the ground for everyone; with a carrier, always for the carrier's
     * team, and for the flag's own team only while the server reveals the carrier.
     */
    private fun flagGlyphs(state: ModeState): List<GlyphOverlay.Glyph> {
        val myTeam = myTeam()
        return state.flags.filter {
            when {
                it.carrier == 0 -> true
                // The one flag: always for the carrier's team, for the other only while revealed
                it.team == FlagEngine.NEUTRAL -> it.carrierTeam == myTeam || it.revealed
                else -> it.team != myTeam || it.revealed
            }
        }.map {
            GlyphOverlay.Glyph("flag${it.team}", it.point, FLAG, if (it.team == FlagEngine.NEUTRAL) NEUTRAL_FLAG_COLOR else objectiveColor(it.team))
        }
    }

    /** The bomb: lying where it fell for the attackers, and planted for everyone. */
    private fun bombGlyphs(state: ModeState): List<GlyphOverlay.Glyph> {
        val b = state.bomb ?: return emptyList()
        val at = b.bomb ?: return emptyList()
        val attacker = myTeam() == b.attackers
        return if (b.plantedAt != null || attacker) listOf(GlyphOverlay.Glyph("bomb", at, BOMB, 0xFFFFFFFF.toInt())) else emptyList()
    }

    /** Infection: survivors on the infected's maps while the server reveals them. */
    private fun survivorGlyphs(state: ModeState): List<GlyphOverlay.Glyph> {
        if (state.sideOf(container.players.playerID.toInt(), myTeam()) != InfectionEngine.INFECTED) return emptyList()
        return state.revealed.map { (id, point) -> GlyphOverlay.Glyph("survivor$id", point, SURVIVOR, SURVIVOR_COLOR) }
    }

    /** Search & Destroy: what you should be doing this round. */
    private fun bombStatus(state: ModeState, myTeam: Int): String? {
        val b = state.bomb ?: return null
        val me = container.players.playerID.toInt()
        val attacking = myTeam == b.attackers
        return when {
            b.phase == RoundPhase.BETWEEN -> getString(if (b.lastRoundWinner == myTeam) R.string.snd_round_won else R.string.snd_round_lost)
            b.phase == RoundPhase.STARTING -> getString(if (field?.spawnRules?.walkBack == true) R.string.snd_to_spawn else R.string.snd_starting)
            me in b.out -> getString(R.string.snd_out)
            state.actor == me -> getString(if (b.phase == RoundPhase.PLANTED) R.string.snd_defusing else R.string.snd_planting)
            b.carrier == me -> getString(R.string.snd_you_have_bomb)
            b.phase == RoundPhase.PLANTED -> getString(if (attacking) R.string.snd_defend_bomb else R.string.snd_defuse, b.plantedAt ?: "")
            else -> getString(if (attacking) R.string.snd_attack else R.string.snd_defend)
        }
    }

    private fun myTeam(): Int = container.players.playerID.toInt().let { id ->
        if (settings.teamLayout.mode.isFreeForAll) id else settings.teamLayout.teamOf(id)
    }

    /**
     * Domination and Hardpoint: each team's score and the score to win, then where you stand,
     * or what just happened to a point, or who won.
     */
    private fun showModeBanner() {
        val banner = binding?.mapModeTv ?: return
        val state = container.modeController.state.value
        if (state == null || !state.mode.onServer) {
            banner.isVisible = false
            return
        }
        val myTeam = state.sideOf(container.players.playerID.toInt(), myTeam())
        val units = field?.rules?.units ?: DistanceUnits.defaultFor(Locale.getDefault())
        val text = SpannableStringBuilder()
        // Your team first, then the others by score (Marksman: distance)
        val teams = (state.scores.keys + myTeam).distinct().sortedWith(compareBy({ it != myTeam }, { -(state.scores[it] ?: 0) }))
        // Battle Royale is everyone for themselves: just how many are left
        if (state.mode == GameMode.BATTLE_ROYALE) {
            val left = state.scores.values.sum()
            text.append(resources.getQuantityString(R.plurals.br_players_left, left, left))
        }
        for (team in teams.take(MAX_SCORES_SHOWN).takeIf { state.mode != GameMode.BATTLE_ROYALE }.orEmpty()) {
            if (text.isNotEmpty()) text.append("   ")
            val start = text.length
            text.append("●")
            text.setSpan(ForegroundColorSpan(objectiveColor(team)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val score = state.scores[team] ?: 0
            text.append(" ").append(when (state.mode) {
                GameMode.MARKSMAN -> units.format(score.toDouble())
                else -> String.format(Locale.ROOT, "%d", score)
            })
        }
        val now = container.fieldImageryClient.serverNow()
        fun clock(at: Long): String = ((at - now) / 1000).coerceAtLeast(0).let { String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) }
        when (state.mode) {
            GameMode.BATTLE_ROYALE -> state.zone?.takeIf { state.winner == 0 && inGame() }?.let { zone ->
                text.append(" · ").append(when {
                    zone.shrinking -> getString(R.string.br_zone_shrinking)
                    zone.changesAt != 0L -> getString(R.string.br_zone_shrinks_in, clock(zone.changesAt))
                    else -> getString(R.string.br_zone_final)
                })
            }
            GameMode.MARKSMAN -> if (state.scoreToWin > 0) text.append("   ").append(getString(R.string.mode_to_win_text, units.format(state.scoreToWin.toDouble())))
            GameMode.SEARCH_AND_DESTROY -> {
                text.append("   ").append(getString(R.string.mode_to_win, state.scoreToWin))
                state.bomb?.takeIf { state.winner == 0 && inGame() }?.let { b ->
                    text.append(" · ").append(if (b.phase == RoundPhase.STARTING) getString(R.string.snd_round_starting, b.round) else getString(R.string.snd_round, b.round, clock(b.endsAt)))
                }
            }
            GameMode.INFECTION -> if (state.winner == 0 && inGame() && state.movesAt != 0L) {
                text.append(" · ").append(getString(R.string.inf_time_left, clock(state.movesAt)))
            }
            GameMode.EXTRACTION -> {
                if (state.scoreToWin > 0) text.append("   ").append(getString(R.string.mode_to_win, state.scoreToWin))
                state.extraction?.takeIf { state.winner == 0 && inGame() }?.let { e ->
                    text.append(" · ").append(if (now >= e.opensAt) getString(R.string.ex_open) else getString(R.string.ex_opens_in, clock(e.opensAt)))
                }
            }
            else -> {
                text.append("   ").append(getString(R.string.mode_to_win, state.scoreToWin))
                if (state.movesAt != 0L && state.winner == 0) text.append(" · ").append(getString(R.string.mode_moves_in, clock(state.movesAt)))
            }
        }
        modeStatus(state, myTeam, units)?.let { text.append("\n").append(it) }
        banner.text = text
        banner.isVisible = true
    }

    /** Battle Royale's zone: the storm shaded outside it, its edge, and where it goes next, dashed. */
    private fun renderZone(state: ModeState?) {
        val style = style ?: return
        val zone = state?.zone?.takeIf { state.mode == GameMode.BATTLE_ROYALE && inGame() }
        ZoneLayers.update(style, zone)
    }

    /** One line about you and the points: who won, a point's news, or the point you stand on. */
    private fun modeStatus(state: ModeState, myTeam: Int, units: DistanceUnits): String? {
        // After the game, only the scores: who won is on the scoreboard
        if (!inGame()) return null
        when (state.mode) {
            // Hardpoint has one point at a time, so it goes unnamed
            GameMode.HARDPOINT -> return hardpointStatus(state, myTeam)
            GameMode.BATTLE_ROYALE -> {
                if (container.players.playerID.toInt() in state.out) return getString(R.string.br_you_are_out)
                return container.zoneController.outside.value?.let { getString(R.string.br_outside, units.format(it.metersToSafety)) }
            }
            GameMode.CAPTURE_THE_FLAG -> return flagStatus(state, myTeam)
            GameMode.SEARCH_AND_DESTROY -> return bombStatus(state, myTeam)
            GameMode.INFECTION -> return getString(if (myTeam == InfectionEngine.INFECTED) R.string.inf_you_are_infected else R.string.inf_survive)
            GameMode.EXTRACTION -> return extractionStatus(state)
            GameMode.MARKSMAN, GameMode.CLASSIC -> return null
            GameMode.DOMINATION -> {}
        }
        val notice = lastModeNotice?.takeIf { container.clock() - lastModeNoticeAt < NOTICE_MILLIS }
        if (notice != null) return when (notice) {
            is ModeController.Notice.Secured -> getString(R.string.mode_secured, notice.id)
            is ModeController.Notice.Lost -> getString(R.string.mode_lost, notice.id)
            is ModeController.Notice.UnderAttack -> getString(R.string.mode_enemy_capturing, notice.id)
            is ModeController.Notice.Moved -> getString(R.string.mode_hp_moved)
        }
        val here = you() ?: return null
        val on = state.objectives.firstOrNull { it.active && GeoMath.distanceMeters(here, it.point) <= state.radiusMeters } ?: return null
        return when {
            on.contested -> getString(R.string.mode_contested, on.id)
            on.capturing == myTeam -> getString(R.string.mode_capturing, on.id)
            on.owner == myTeam -> getString(R.string.mode_holding, on.id)
            else -> null
        }
    }

    private fun flagStatus(state: ModeState, myTeam: Int): String? {
        val me = container.players.playerID.toInt()
        if (state.flags.any { it.carrier == me }) return getString(R.string.ctf_you_have_flag)
        val one = state.flags.singleOrNull { it.team == FlagEngine.NEUTRAL }
        if (one != null) return when {
            one.carrier == 0 && one.dropped -> getString(R.string.ctf_one_flag_down)
            one.carrier == 0 -> null
            one.carrierTeam == myTeam -> getString(R.string.ctf_one_flag_ours)
            else -> getString(R.string.ctf_one_flag_theirs)
        }
        val mine = state.flags.firstOrNull { it.team == myTeam } ?: return null
        return when {
            mine.carrier != 0 -> getString(R.string.ctf_flag_taken)
            mine.dropped -> getString(R.string.ctf_flag_down)
            else -> null
        }
    }

    private fun extractionStatus(state: ModeState): String? {
        val e = state.extraction ?: return null
        val me = container.players.playerID.toInt()
        val carrying = e.carrying[me] ?: 0
        val open = container.fieldImageryClient.serverNow() >= e.opensAt
        val here = you()
        val inZone = here != null && e.zones.any { GeoMath.distanceMeters(here, it) <= state.radiusMeters }
        return when {
            e.progress[me] != null -> getString(if (open && carrying > 0 && inZone) R.string.ex_extracting else R.string.ex_collecting)
            carrying > 0 -> resources.getQuantityString(R.plurals.ex_carrying, carrying, carrying)
            else -> null
        }
    }

    private fun hardpointStatus(state: ModeState, myTeam: Int): String? {
        val notice = lastModeNotice?.takeIf { container.clock() - lastModeNoticeAt < NOTICE_MILLIS }
        when (notice) {
            is ModeController.Notice.Secured -> return getString(R.string.mode_hp_secured)
            is ModeController.Notice.Moved -> return getString(R.string.mode_hp_moved)
            else -> {}
        }
        val here = you() ?: return null
        val on = state.objectives.firstOrNull { it.active && GeoMath.distanceMeters(here, it.point) <= state.radiusMeters } ?: return null
        return when {
            on.contested -> getString(R.string.mode_hp_contested)
            on.owner == myTeam -> getString(R.string.mode_hp_holding)
            else -> null
        }
    }

    private fun inGame() = container.gameState.gameState != GameStateRepository.GAME_STATE_NONE

    private fun renderDrops() {
        dropOverlay?.update(container.loadoutController.visibleDrops(), container.fieldImageryClient.serverNow())
    }

    /** Claiming a drop beats a fresh notice. (The longest shot is on the scoreboard.) */
    private fun showDropBanner() {
        val banner = binding?.mapDropTv ?: return
        val context = context ?: return
        val claiming = container.loadoutController.claiming.value
        val notice = lastNotice?.takeIf { container.clock() - lastNoticeAt < NOTICE_MILLIS }
        val text = when {
            claiming != null -> getString(R.string.map_drop_claiming, DropOverlay.name(context, claiming.drop.kind), claiming.secondsLeft)
            notice is LoadoutController.Notice.DropIncoming -> getString(R.string.map_drop_incoming, DropOverlay.name(context, notice.kind))
            notice is LoadoutController.Notice.Claimed -> getString(R.string.map_drop_claimed, DropOverlay.name(context, notice.kind))
            notice is LoadoutController.Notice.Killstreak -> getString(R.string.map_killstreak, DropOverlay.name(context, notice.kind))
            else -> null
        }
        banner.text = text
        banner.isVisible = text != null
    }

    /** After a game: the longest shot as a line from shooter to target (its names and distance are on the scoreboard). */
    private fun showLongestShot(shot: LongestShot?) {
        style?.getSourceAs<GeoJsonSource>(LONGEST_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(
            shot?.let {
                Feature.fromGeometry(LineString.fromLngLats(listOf(
                    Point.fromLngLat(it.from.longitude, it.from.latitude), Point.fromLngLat(it.to.longitude, it.to.latitude))))
            })))
    }

    /** Puts each other player's marker where they are on screen. */
    private fun placePlayers() {
        val map = map ?: return
        for (p in shownPlayers) {
            val view = playerViews[p.playerId] ?: continue
            val spot = map.projection.toScreenLocation(LatLng(p.latitude, p.longitude))
            view.translationX = spot.x - view.width / 2f
            view.translationY = spot.y - view.height / 2f
        }
    }

    /** You on the map, eased toward each GPS fix, so your arrow (and a camera following you) glides instead of jumping. */
    private var smoothYou: LatLon? = null
    private var easedAtNanos = 0L

    /**
     * Moves [smoothYou] toward the latest fix by how much time has passed, not per frame, so it
     * glides the same at any frame rate. A fix far off (the first one, or GPS catching up) jumps.
     */
    private fun easeYou(frameTimeNanos: Long) {
        val fix = you()
        val was = smoothYou
        val seconds = if (easedAtNanos == 0L) 0.0 else (frameTimeNanos - easedAtNanos) / 1e9
        easedAtNanos = frameTimeNanos
        smoothYou = when {
            fix == null -> null
            was == null || GeoMath.distanceMeters(was, fix) > YOU_SNAP_METERS -> fix
            else -> {
                val step = 1 - exp(-seconds / YOU_EASE_SECONDS)
                LatLon(was.latitude + (fix.latitude - was.latitude) * step, was.longitude + (fix.longitude - was.longitude) * step)
            }
        }
    }

    private fun moveCamera() {
        val map = map ?: return
        if (binding?.root?.isVisible != true) return
        if (userMoving) return
        val now = map.cameraPosition
        val currentBearing = now.bearing
        val bearing = when {
            headingUp -> heading.heading ?: currentBearing
            // Back to north-up: swing round rather than snap
            else -> currentBearing + shortestTurn(currentBearing, 0.0) * GLIDE
        }
        var center = now.target
        var zoom = now.zoom
        // Following you: close in on where your arrow is, eased, so the map moves as smoothly as you
        val following = autoZoom == AutoZoom.YOU
        val goal = if (following) smoothYou?.let { GeoCircle(it, AutoZoom.FOLLOW_RADIUS_METERS) } else target
        val goalZoom = goal?.let { zoomFor(it) }
        if (goal != null && goalZoom != null && center != null) {
            val glide = if (following) FOLLOW_GLIDE else GLIDE
            center = LatLng(center.latitude + (goal.center.latitude - center.latitude) * glide,
                center.longitude + (goal.center.longitude - center.longitude) * glide)
            zoom += (goalZoom - zoom) * GLIDE
        }
        val turned = HeadingFilter.difference(currentBearing, bearing) >= MIN_TURN_DEGREES
        val zoomed = abs(zoom - now.zoom) >= MIN_ZOOM_STEP
        val panned = center != null && now.target != null &&
            GeoMath.distanceMeters(LatLon(center.latitude, center.longitude), LatLon(now.target!!.latitude, now.target!!.longitude)) >= MIN_PAN_METERS
        if (!turned && !zoomed && !panned) return
        val position = CameraPosition.Builder().bearing(bearing).zoom(zoom).tilt(0.0)
        center?.let { position.target(it) }
        map.moveCamera(CameraUpdateFactory.newCameraPosition(position.build()))
    }

    /** The signed turn from [from] to [to], -180..180. */
    private fun shortestTurn(from: Double, to: Double): Double {
        val d = HeadingFilter.normalize(to - from)
        return if (d > 180) d - 360 else d
    }

    private companion object {
        const val TAG = "map"
        const val STATE_AUTO_ZOOM = "autoZoom"

        private var lastSentLatitude = 0.0
        private var lastSentLongitude = 0.0
        private var lastSentAt = 0L

        /** How often an unchanged position goes to the server again. */
        const val HEARTBEAT_MILLIS = 4_000L

        const val BORDER_SOURCE = "border"
        const val BORDER_LAYER = "border"
        const val GUIDE_SOURCE = "spawn-guide"
        const val GUIDE_LAYER = "spawn-guide"
        const val GUIDE_COLOR = "#69F0AE"
        const val LONGEST_SOURCE = "longest-shot"
        const val BACK_SOURCE = "way-back"
        const val LONGEST_LAYER = "longest-shot"
        const val LONGEST_COLOR = "#FFD54F"

        /** How long a drop or killstreak notice stays up. */
        const val NOTICE_MILLIS = 4_000L

        /** A fix older than this (a cached last-known position) isn't used for the border or spawns. */
        const val FRESH_FIX_MILLIS = 2 * 60_000L

        /** You, in free for all (no team colour). */
        const val YOU_COLOR = 0xFF2979FF.toInt()

        /** Everyone else on the capture points in free for all, and a team with no colour. */
        const val ENEMY_COLOR = 0xFFE53935.toInt()

        /** Scores on the banner: yours and the leaders, four teams at most. */
        const val MAX_SCORES_SHOWN = 4

        /** Not teams: the colours of loot piles and extraction zones on the capture point circles. */
        const val LOOT_TEAM = -2
        const val EXTRACTION_TEAM = -3
        const val EXTRACTION_CLOSED_TEAM = -10

        /** How often an Extraction map is redrawn, so the zones turn green as they open. */
        const val EXTRACTION_REDRAW_MILLIS = 1_000L
        const val INFECTED_COLOR = 0xFF76FF03.toInt()
        const val SURVIVOR_COLOR = 0xFF40C4FF.toInt()
        const val BOMB = "💣"
        const val SURVIVOR = "◉"
        const val FLAG = "⚑"

        /** The one neutral flag: white, nobody's color. */
        const val NEUTRAL_FLAG_COLOR = 0xFFFFFFFF.toInt()

        /** About 5 km across on a phone, to a few metres. */
        const val MIN_ZOOM = 13.0
        const val MAX_ZOOM = 20.0
        const val BORDER_COLOR = "#FF5252"

        const val TICK_MILLIS = 1_000L

        /** A lost connection hides the map only after this long, so a Wi-Fi blip doesn't flash it. */
        const val DISCONNECTED_HIDE_MILLIS = 5_000L

        /** Share of the way to auto zoom's goal covered each frame. */
        const val GLIDE = 0.12

        /** Following you, the map keeps up a little faster, so your arrow stays near the middle. */
        const val FOLLOW_GLIDE = 0.3

        /** Your arrow eases toward each fix over about this long (GPS updates about once a second). */
        const val YOU_EASE_SECONDS = 0.6

        /** A fix farther than this from the arrow (GPS catching up) jumps instead of gliding. */
        const val YOU_SNAP_METERS = 50.0
        const val MIN_TURN_DEGREES = 0.2
        const val MIN_ZOOM_STEP = 0.002
        const val MIN_PAN_METERS = 0.05
    }
}
