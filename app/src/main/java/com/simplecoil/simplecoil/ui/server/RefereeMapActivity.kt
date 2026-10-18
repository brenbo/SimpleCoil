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

package com.simplecoil.simplecoil.ui.server

import android.os.Bundle
import android.view.Choreographer
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.simplecoil.simplecoil.AppContainer
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.databinding.ActivityRefereeMapBinding
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.mode.GameMode
import com.simplecoil.simplecoil.domain.mode.InfectionEngine
import com.simplecoil.simplecoil.domain.mode.ObjectiveState
import com.simplecoil.simplecoil.network.server.LiveGame
import com.simplecoil.simplecoil.network.server.SessionPhase
import com.simplecoil.simplecoil.ui.map.CameraLimits
import com.simplecoil.simplecoil.ui.map.DropOverlay
import com.simplecoil.simplecoil.ui.map.GlyphOverlay
import com.simplecoil.simplecoil.ui.map.MapIcons
import com.simplecoil.simplecoil.ui.map.MapStyles
import com.simplecoil.simplecoil.ui.map.ObjectiveOverlay
import com.simplecoil.simplecoil.ui.map.OutsideShade
import com.simplecoil.simplecoil.ui.map.SpawnLayers
import com.simplecoil.simplecoil.ui.map.ZoneLayers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale

/**
 * The dedicated server's live referee map (map design: Dedicated server settings: "during a game
 * the home screen becomes the live referee map plus scoreboard, with controls to drop a supply
 * crate now, move the zone or end the game"). It opens when a game starts; Back returns to the
 * server's home. Updated once a second from the server's own game.
 */
class RefereeMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRefereeMapBinding
    private lateinit var container: AppContainer
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var points: ObjectiveOverlay? = null
    private var drops: DropOverlay? = null
    private var flags: GlyphOverlay? = null
    private var dots: GlyphOverlay? = null
    private var names: GlyphOverlay? = null
    private var placed = false
    private var framesRunning = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!framesRunning) return
            map?.projection?.let { p ->
                points?.place(p)
                drops?.place(p)
                flags?.place(p)
                dots?.place(p)
                names?.place(p)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as SimpleCoilApp).container
        MapLibre.getInstance(applicationContext)
        binding = ActivityRefereeMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.refMapView.onCreate(savedInstanceState)
        binding.refMapView.getMapAsync { onMapReady(it) }
        points = ObjectiveOverlay(this, binding.refObjectivesLayer) { colorOf(it) }
        drops = DropOverlay(this, binding.refDropsLayer)
        flags = GlyphOverlay(this, binding.refFlagsLayer)
        dots = GlyphOverlay(this, binding.refPlayersLayer, textSp = DOT_SP)
        names = GlyphOverlay(this, binding.refNamesLayer, textSp = NAME_SP, below = true)

        binding.refDropButton.setOnClickListener {
            val dropped = container.liveGame?.dropNow() == true
            Toast.makeText(applicationContext, if (dropped) R.string.ref_dropped else R.string.ref_no_drop, Toast.LENGTH_SHORT).show()
        }
        binding.refZoneButton.setOnClickListener {
            val closed = container.liveGame?.closeZoneNow() == true
            Toast.makeText(applicationContext, if (closed) R.string.ref_zone_closing else R.string.ref_zone_cant, Toast.LENGTH_SHORT).show()
        }
        binding.refEndButton.setOnClickListener {
            AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setMessage(R.string.ref_end_confirm)
                .setPositiveButton(R.string.ref_end_game) { _, _ -> container.serverController.endGame() }
                .setNegativeButton(R.string.field_dialog_cancel, null)
                .show()
        }

        // The game ended (or was reset, or the server stopped): back to the server's home
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.serverController.snapshot.collect { if (it?.phase != SessionPhase.RUNNING) finish() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val view = withContext(Dispatchers.Default) { container.liveGame?.referee() }
                    show(view)
                    delay(REFRESH_MILLIS)
                }
            }
        }
    }

    private fun onMapReady(map: MapLibreMap) {
        this.map = map
        map.setMinZoomPreference(MIN_ZOOM)
        map.setMaxZoomPreference(MAX_ZOOM)
        map.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = false
            isTiltGesturesEnabled = false
            setCompassMargins(0, (64 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), 0)
        }
        map.setStyle(MapStyles.playStyle()) { loaded ->
            style = loaded
            OutsideShade.install(loaded)
            ZoneLayers.install(loaded)
            SpawnLayers.install(loaded, this)
            loaded.addSource(GeoJsonSource(BORDER_SOURCE))
            loaded.addLayer(LineLayer(BORDER_SOURCE, BORDER_SOURCE).withProperties(
                PropertyFactory.lineColor(BORDER_COLOR.toColorInt()),
                PropertyFactory.lineWidth(3f),
            ))
            // The field's photo, from the server's own store
            lifecycleScope.launch {
                val photo = withContext(Dispatchers.IO) {
                    container.serverFieldStore.load()?.let { pack -> MapStyles.decodeFieldPhoto(pack)?.let { pack to it } }
                }
                photo?.let { (pack, bitmap) -> style?.let { MapStyles.showFieldPhoto(it, pack, bitmap) } }
            }
        }
    }

    private fun show(view: LiveGame.RefereeView?) {
        val style = style
        if (view == null) {
            binding.refInfoTv.setText(R.string.ref_no_game)
            return
        }
        val field = view.field
        val ring = field?.border?.ring()
        if (style != null) {
            OutsideShade.update(style, ring)
            style.getSourceAs<GeoJsonSource>(BORDER_SOURCE)?.setGeoJson(Feature.fromGeometry(
                LineString.fromLngLats(ring.orEmpty().map { Point.fromLngLat(it.longitude, it.latitude) })))
            val spawns = field?.spawns.orEmpty()
            SpawnLayers.update(style, spawns, radiusMeters = field?.spawnRules?.radiusMeters ?: 10.0,
                latitude = spawns.firstOrNull()?.point?.latitude ?: 0.0)
            ZoneLayers.update(style, view.mode?.zone)
        }
        if (!placed && ring != null) fit(ring)
        val mode = view.mode
        // The mode's things: points, sites, bases; flags; crates
        points?.update(when (mode?.mode) {
            GameMode.DOMINATION, GameMode.HARDPOINT, GameMode.SEARCH_AND_DESTROY -> mode.objectives
            GameMode.CAPTURE_THE_FLAG -> mode.flags.map { ObjectiveState("", it.base, it.team, 0, 0.0, false, active = true, next = false) }
            GameMode.EXTRACTION -> mode.extraction?.let { e ->
                e.zones.map { ObjectiveState("EX", it, 0, 0, 0.0, false, active = true, next = false) } +
                    e.loot.map { ObjectiveState(it.value.toString(), it.point, 0, 0, 0.0, false, active = true, next = false) }
            }.orEmpty()
            else -> emptyList()
        }, mode?.radiusMeters ?: 10.0, letters = mode?.mode != GameMode.HARDPOINT)
        flags?.update(mode?.flags.orEmpty().map { GlyphOverlay.Glyph("flag${it.team}", it.point, FLAG, colorOf(it.team)) })
        drops?.update(field?.drops.orEmpty(), System.currentTimeMillis())
        // Everyone, by side: out or with no position shown faded, or left off
        val located = view.players.filter { it.point != null }
        dots?.update(located.map { p ->
            val side = mode?.let { if (it.mode == GameMode.INFECTION) (if (p.id in it.infected) InfectionEngine.INFECTED else InfectionEngine.SURVIVORS) else null } ?: p.team
            val color = colorOf(side)
            GlyphOverlay.Glyph("p${p.id}", p.point!!, DOT, if (p.alive) color else (color and 0x00FFFFFF) or 0x66000000)
        })
        names?.update(located.map { GlyphOverlay.Glyph("n${it.id}", it.point!!, it.name.ifBlank { "#${it.id}" }, 0xFFFFFFFF.toInt()) })
        binding.refZoneButton.isVisible = mode?.mode == GameMode.BATTLE_ROYALE
        binding.refInfoTv.text = info(view, located.size)
    }

    private fun info(view: LiveGame.RefereeView, located: Int): String {
        val mode = view.mode
        val scores = mode?.scores?.entries?.sortedByDescending { it.value }?.joinToString("   ") { (team, score) ->
            "${sideName(team)} ${String.format(Locale.ROOT, "%d", score)}"
        }
        val base = getString(R.string.ref_info, located, view.players.size)
        return if (scores.isNullOrEmpty()) base else "$base\n$scores"
    }

    private fun sideName(team: Int): String = when (team) {
        InfectionEngine.SURVIVORS -> getString(R.string.ref_survivors)
        InfectionEngine.INFECTED -> getString(R.string.ref_infected)
        in 1..4 -> getString(R.string.ref_team, team)
        else -> getString(R.string.ref_player, team)
    }

    private fun colorOf(team: Int): Int = when (team) {
        InfectionEngine.INFECTED -> 0xFF76FF03.toInt()
        InfectionEngine.SURVIVORS -> 0xFF40C4FF.toInt()
        else -> MapIcons.teamColor(team, PALETTE[Math.floorMod(team, PALETTE.size)])
    }

    private fun fit(ring: List<LatLon>) {
        val map = map ?: return
        placed = true
        CameraLimits.keepNear(map, ring)
        val bounds = LatLngBounds.Builder().apply { ring.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, (48 * resources.displayMetrics.density).toInt()))
    }

    override fun onStart() {
        super.onStart()
        binding.refMapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.refMapView.onResume()
        framesRunning = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        framesRunning = false
        Choreographer.getInstance().removeFrameCallback(frame)
        binding.refMapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.refMapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.refMapView.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        @Suppress("DEPRECATION")
        super.onLowMemory()
        binding.refMapView.onLowMemory()
    }

    override fun onDestroy() {
        binding.refMapView.onDestroy()
        super.onDestroy()
    }

    private companion object {
        const val REFRESH_MILLIS = 1_000L
        const val MIN_ZOOM = 13.0
        const val MAX_ZOOM = 20.0
        const val BORDER_SOURCE = "ref-border"
        const val BORDER_COLOR = "#FF5252"
        const val DOT = "●"
        const val FLAG = "⚑"
        const val DOT_SP = 22f
        const val NAME_SP = 11f
        val PALETTE = intArrayOf(0xFF2979FF.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFFB300.toInt(), 0xFF8E24AA.toInt(),
            0xFF00ACC1.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(), 0xFFEC407A.toInt(), 0xFFC0CA33.toInt())
    }
}
