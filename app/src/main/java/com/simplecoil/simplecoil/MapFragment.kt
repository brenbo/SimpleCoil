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

import android.Manifest
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.FragmentMapBinding
import com.simplecoil.simplecoil.domain.map.AutoZoom
import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.BorderTracker
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoCircle
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.HeadingFilter
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.OutOfBoundsPenalty
import com.simplecoil.simplecoil.domain.map.Spawn
import com.simplecoil.simplecoil.domain.map.SpawnController
import com.simplecoil.simplecoil.domain.team.TeamColors
import com.simplecoil.simplecoil.hardware.location.CompassSensor
import com.simplecoil.simplecoil.hardware.location.LocationUpdateListener
import com.simplecoil.simplecoil.hardware.location.MapLocationManager
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.imagery.FieldPackInfo
import com.simplecoil.simplecoil.ui.map.MapCredit
import com.simplecoil.simplecoil.ui.map.MapStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

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

    /** When the phone was last seen connected to its server. */
    private var lastConnectedAt = Long.MIN_VALUE

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateVisibility()
            renderSpawns()
            refreshTarget()
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
            moveCamera()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val requestLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableGps(wantsGps())
    }

    private val locationListener = object : LocationUpdateListener {
        override fun onLocationUpdated(location: Location, latitude: Double, longitude: Double) {
            sendLocation(location)
            compass?.setLocation(location)
            if (location.hasBearing() && location.hasSpeed()) {
                heading.onCourse(location.bearing.toDouble(), location.speed, container.clock())
            }
            container.borderController.onFix(LatLon(latitude, longitude), location.accuracy)
            container.spawnController.onFix(LatLon(latitude, longitude), location.accuracy)
            renderPlayers()
            refreshTarget()
        }

        override fun onPermissionRequired() {
            tracking = false
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        MapLibre.getInstance(requireContext().applicationContext)
        val b = FragmentMapBinding.inflate(inflater, parent, false)
        binding = b
        b.mapView.onCreate(savedInstanceState)
        b.mapView.getMapAsync { onMapReady(it) }
        b.mapCompassButton.setOnClickListener { toggleHeadingUp() }
        b.mapZoomButton.setOnClickListener {
            setAutoZoom(autoZoom.next())
            preferences.saveMapAutoZoom(autoZoom.name)
        }
        MapCredit.attach(b.mapCreditTv)
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
            addPlayerLayers(loaded)
            photo?.let { showPhoto(it) }
            showField(field)
            renderPlayers()
            refreshTarget()
        }
    }

    private fun addFieldLayers(style: Style) {
        // Spawns: a disc in the team's colour, fading as they move; the dashed way to yours
        style.addSource(GeoJsonSource(SPAWNS_SOURCE))
        style.addSource(GeoJsonSource(GUIDE_SOURCE))
        style.addLayer(FillLayer(SPAWNS_FILL_LAYER, SPAWNS_SOURCE).withProperties(
            PropertyFactory.fillColor(Expression.toColor(Expression.get(COLOR))),
            PropertyFactory.fillOpacity(Expression.product(Expression.get(OPACITY), Expression.literal(SPAWN_FILL_OPACITY))),
        ))
        style.addLayer(LineLayer(SPAWNS_LINE_LAYER, SPAWNS_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get(COLOR))),
            PropertyFactory.lineOpacity(Expression.get(OPACITY)),
            PropertyFactory.lineWidth(2f),
        ))
        style.addLayer(LineLayer(GUIDE_LAYER, GUIDE_SOURCE).withProperties(
            PropertyFactory.lineColor(GUIDE_COLOR.toColorInt()),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
        ))
        style.addSource(GeoJsonSource(BORDER_SOURCE))
        style.addLayer(LineLayer(BORDER_LAYER, BORDER_SOURCE).withProperties(
            PropertyFactory.lineColor(BORDER_COLOR.toColorInt()),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
    }

    private fun addPlayerLayers(style: Style) {
        val res = resources
        style.addImage(ICON_YOU, BitmapFactory.decodeResource(res, R.drawable.ic_gps_you))
        style.addImage(ICON_TEAMMATE, BitmapFactory.decodeResource(res, R.drawable.ic_gps_teammate))
        style.addImage(ICON_ENEMY, BitmapFactory.decodeResource(res, R.drawable.ic_gps_enemy))
        style.addSource(GeoJsonSource(PLAYERS_SOURCE))
        style.addSource(GeoJsonSource(YOU_SOURCE))
        style.addLayer(SymbolLayer(PLAYERS_LAYER, PLAYERS_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get(KIND)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ))
        // You above everyone else
        style.addLayer(SymbolLayer(YOU_LAYER, YOU_SOURCE).withProperties(
            PropertyFactory.iconImage(ICON_YOU),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ))
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
        settings.isUseGPS || field?.border != null || field?.spawns?.isNotEmpty() == true

    private fun sendLocation(fix: Location) {
        // Only shared when the server asks for positions
        if (!settings.isUseGPS) return
        if (fix.latitude == lastSentLatitude && fix.longitude == lastSentLongitude) return
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

    private fun others(): List<MapLocationManager.PlayerPosition> =
        if (settings.isUseGPS) location.visiblePlayers() else emptyList()

    private fun renderPlayers() {
        val style = style ?: return
        val me = you()
        updateBorderVisibility()
        style.getSourceAs<GeoJsonSource>(YOU_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(
            listOfNotNull(me?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })))
        style.getSourceAs<GeoJsonSource>(PLAYERS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(
            others().map { p ->
                Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).also {
                    it.addStringProperty(KIND, if (p.teammate) ICON_TEAMMATE else ICON_ENEMY)
                }
            }))
    }

    /** Shows the field's photo, loading it off the main thread; null takes it away. */
    private fun showPhoto(info: FieldPackInfo?) {
        photo = info
        binding?.mapCreditTv?.isVisible = info != null
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

    /** Draws the field's border (or takes it away). */
    private fun showField(config: FieldConfig?) {
        field = config
        if (isResumed) enableGps(wantsGps())
        renderSpawns()
        val style = style ?: return
        val ring = config?.border?.ring()
        style.getSourceAs<GeoJsonSource>(BORDER_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(
            ring?.let { r -> Feature.fromGeometry(LineString.fromLngLats(r.map { Point.fromLngLat(it.longitude, it.latitude) })) })))
        updateBorderVisibility()
        refreshTarget()
    }

    /** Draws the spawns, in their team's colour, fading as they move; other teams' a little fainter. */
    private fun renderSpawns() {
        val style = style ?: return
        val features = container.spawnController.shown().map { shown ->
            val ring = Border.Circle(shown.spawn.point, shown.radiusMeters).ring().map { Point.fromLngLat(it.longitude, it.latitude) }
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).also {
                it.addStringProperty(COLOR, teamColor(shown.spawn.team))
                it.addNumberProperty(OPACITY, shown.opacity * if (shown.mine) 1.0 else OTHER_TEAM_OPACITY)
            }
        }
        style.getSourceAs<GeoJsonSource>(SPAWNS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
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
        banner.isVisible = guide != null && units != null
        if (guide != null && units != null) banner.text = getString(R.string.map_go_to_spawn, units.format(guide.meters))
    }

    private fun teamColor(team: Int): String =
        if (team == Spawn.ANYONE) ANYONE_COLOR else String.format(Locale.ROOT, "#%06X", TeamColors.colorOf(team) and 0xFFFFFF)

    private fun showBorderStatus(status: BorderTracker.Status?) {
        updateBorderVisibility()
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
        val button = binding?.mapZoomButton ?: return
        val (icon, description) = when (autoZoom) {
            AutoZoom.OFF -> R.drawable.ic_map_zoom_off to R.string.map_zoom_off
            AutoZoom.PLAYERS -> R.drawable.ic_map_zoom_players to R.string.map_zoom_players
            AutoZoom.FIELD -> R.drawable.ic_map_zoom_field to R.string.map_zoom_field
        }
        button.setImageResource(icon)
        button.contentDescription = getString(description)
        // Orange while it is zooming by itself
        if (autoZoom == AutoZoom.OFF) button.clearColorFilter() else button.setColorFilter(ACTIVE_COLOR.toColorInt())
    }

    /** Works out what auto zoom fits now; the first time, also puts the camera somewhere useful. */
    private fun refreshTarget() {
        val border = field?.border
        val photoBounds = photo?.bounds
        val me = you()
        val others = others().map { LatLon(it.latitude, it.longitude) }
        target = AutoZoom.target(autoZoom, border, photoBounds, me, others)
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
        return if (sizeDp > 0) GeoMath.zoomToFit(circle, sizeDp.toDouble()) else null
    }

    /**
     * One frame of camera movement: turn to the heading (already smoothed, so it is applied as
     * is, with no animation lag) and glide toward what auto zoom fits. Moves only when something
     * would visibly change, so a still map costs nothing.
     */
    /**
     * The map shows only while playing on a server and connected to it: never in no-network
     * mode, and not after the connection has been gone for [DISCONNECTED_HIDE_MILLIS].
     */
    private fun updateVisibility() {
        val root = binding?.root ?: return
        val net = container.networkGateway
        val now = container.clock()
        if (net.isConnectedToServer) lastConnectedAt = now
        root.isVisible = net.useNetwork && lastConnectedAt != Long.MIN_VALUE && now - lastConnectedAt < DISCONNECTED_HIDE_MILLIS
    }

    private fun moveCamera() {
        val map = map ?: return
        if (userMoving || binding?.root?.isVisible != true) return
        val now = map.cameraPosition
        val currentBearing = now.bearing
        val bearing = when {
            headingUp -> heading.heading ?: currentBearing
            // Back to north-up: swing round rather than snap
            else -> currentBearing + shortestTurn(currentBearing, 0.0) * GLIDE
        }
        var center = now.target
        var zoom = now.zoom
        val goal = target
        val goalZoom = goal?.let { zoomFor(it) }
        if (goal != null && goalZoom != null && center != null) {
            center = LatLng(center.latitude + (goal.center.latitude - center.latitude) * GLIDE,
                center.longitude + (goal.center.longitude - center.longitude) * GLIDE)
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

        const val PLAYERS_SOURCE = "players"
        const val PLAYERS_LAYER = "players"
        const val YOU_SOURCE = "you"
        const val YOU_LAYER = "you"
        const val BORDER_SOURCE = "border"
        const val BORDER_LAYER = "border"
        const val SPAWNS_SOURCE = "spawns"
        const val SPAWNS_FILL_LAYER = "spawns-fill"
        const val SPAWNS_LINE_LAYER = "spawns-line"
        const val GUIDE_SOURCE = "spawn-guide"
        const val GUIDE_LAYER = "spawn-guide"
        const val COLOR = "color"
        const val OPACITY = "opacity"
        const val SPAWN_FILL_OPACITY = 0.3
        const val OTHER_TEAM_OPACITY = 0.5
        const val ANYONE_COLOR = "#FFFFFF"
        const val GUIDE_COLOR = "#69F0AE"
        const val KIND = "kind"
        const val ICON_YOU = "player-you"
        const val ICON_TEAMMATE = "player-teammate"
        const val ICON_ENEMY = "player-enemy"
        const val BORDER_COLOR = "#FF5252"
        const val ACTIVE_COLOR = "#FF9800"

        const val TICK_MILLIS = 1_000L

        /** A lost connection hides the map only after this long, so a Wi-Fi blip doesn't flash it. */
        const val DISCONNECTED_HIDE_MILLIS = 5_000L

        /** Share of the way to auto zoom's goal covered each frame. */
        const val GLIDE = 0.12
        const val MIN_TURN_DEGREES = 0.2
        const val MIN_ZOOM_STEP = 0.002
        const val MIN_PAN_METERS = 0.05
    }
}
