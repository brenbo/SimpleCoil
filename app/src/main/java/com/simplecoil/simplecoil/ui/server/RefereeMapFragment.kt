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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.simplecoil.simplecoil.AppContainer
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.databinding.ActivityRefereeMapBinding
import com.simplecoil.simplecoil.domain.loadout.DropKind
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.mode.FlagEngine
import com.simplecoil.simplecoil.domain.mode.GameMode
import com.simplecoil.simplecoil.domain.mode.InfectionEngine
import com.simplecoil.simplecoil.domain.mode.ObjectiveState
import com.simplecoil.simplecoil.domain.mode.flagBaseSpots
import com.simplecoil.simplecoil.network.server.LiveGame
import com.simplecoil.simplecoil.network.server.SessionPhase
import com.simplecoil.simplecoil.ui.map.CameraLimits
import com.simplecoil.simplecoil.ui.map.DropOverlay
import com.simplecoil.simplecoil.ui.map.GlyphOverlay
import com.simplecoil.simplecoil.ui.map.MapCredit
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
 * crate now, move the zone or end the game"). Updated once a second from the server's own game.
 *
 * On a phone it is RefereeMapActivity, opened when a game starts and closed when it ends. On a
 * wide screen (a Chromebook, a tablet) it is [embedded] as the server screen's middle pane, there
 * all the time: the saved field and who is where before a game, the live game during one.
 */
class RefereeMapFragment : Fragment() {

    private var _binding: ActivityRefereeMapBinding? = null
    private val binding get() = _binding!!
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

    /** A pane of the server screen, rather than a screen of its own. */
    private val embedded: Boolean get() = arguments?.getBoolean(ARG_EMBEDDED) == true

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

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        container = (requireActivity().application as SimpleCoilApp).container
        MapLibre.getInstance(requireContext().applicationContext)
        val b = ActivityRefereeMapBinding.inflate(inflater, parent, false)
        _binding = b
        b.refMapView.onCreate(savedInstanceState)
        b.refMapView.getMapAsync { onMapReady(it) }
        val context = requireContext()
        points = ObjectiveOverlay(context, b.refObjectivesLayer) { colorOf(it) }
        drops = DropOverlay(context, b.refDropsLayer)
        flags = GlyphOverlay(context, b.refFlagsLayer)
        dots = GlyphOverlay(context, b.refPlayersLayer, textSp = DOT_SP)
        names = GlyphOverlay(context, b.refNamesLayer, textSp = NAME_SP, below = true)

        // Drop now: pick what, then tap the field where it lands (tap Drop now again to cancel)
        b.refDropButton.setOnClickListener {
            if (placing != null) {
                stopPlacing()
                return@setOnClickListener
            }
            val kinds = DropKind.TIMED
            AlertDialog.Builder(context, R.style.AppDialogTheme)
                .setTitle(R.string.ref_drop_what)
                .setItems(kinds.map { DropOverlay.name(context, it) }.toTypedArray()) { _, which ->
                    placing = kinds[which]
                    binding.refDropButton.setText(R.string.ref_drop_cancel)
                    Toast.makeText(context.applicationContext, getString(R.string.ref_drop_where, DropOverlay.name(context, kinds[which])), Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(R.string.field_dialog_cancel, null)
                .show()
        }
        b.refZoneButton.setOnClickListener {
            val closed = container.liveGame?.closeZoneNow() == true
            Toast.makeText(context.applicationContext, if (closed) R.string.ref_zone_closing else R.string.ref_zone_cant, Toast.LENGTH_SHORT).show()
        }
        b.refEndButton.setOnClickListener {
            AlertDialog.Builder(context, R.style.AppDialogTheme_Confirm)
                .setMessage(R.string.ref_end_confirm)
                .setPositiveButton(R.string.ref_end_game) { _, _ -> container.serverController.endGame() }
                .setNegativeButton(R.string.field_dialog_cancel, null)
                .show()
        }
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (embedded) {
            // The server screen's full-window button sits over the info line's end
            val info = binding.refInfoTv
            info.setPaddingRelative(info.paddingStart, info.paddingTop,
                info.paddingEnd + (EXPAND_BUTTON_ROOM_DP * resources.displayMetrics.density).toInt(), info.paddingBottom)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.serverController.snapshot.collect {
                    val running = it?.phase == SessionPhase.RUNNING
                    // A screen of its own goes back to the server's home when the game ends;
                    // a pane stays, its game controls only shown during a game
                    if (!running && !embedded) requireActivity().finish()
                    binding.refControls.isVisible = running
                    // The credit sits above the referee's controls while they show
                    binding.refControls.post { _binding?.let { b -> b.refCreditTv.translationY = if (running) -b.refControls.height.toFloat() else 0f } }
                    if (!running) stopPlacing()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val referee = withContext(Dispatchers.Default) { container.liveGame?.referee() }
                    show(referee)
                    delay(REFRESH_MILLIS)
                }
            }
        }
    }

    /** The crate the referee picked, waiting for a tap on the field; null when not placing one. */
    private var placing: DropKind? = null

    private fun stopPlacing() {
        placing = null
        _binding?.refDropButton?.setText(R.string.ref_drop_now)
    }

    /** A tap on the map: with a crate picked, it lands there, if that's on the field. */
    private fun onMapTapped(point: LatLon): Boolean {
        val kind = placing ?: return false
        val text = when (container.liveGame?.dropAt(kind, point) ?: LiveGame.Placed.NO_GAME) {
            LiveGame.Placed.DROPPED -> {
                stopPlacing()
                R.string.ref_dropped
            }
            // Keep placing: tap again, inside the border
            LiveGame.Placed.OUTSIDE -> R.string.ref_drop_outside
            LiveGame.Placed.NO_GAME -> {
                stopPlacing()
                R.string.ref_no_drop
            }
        }
        Toast.makeText(requireContext().applicationContext, text, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun onMapReady(map: MapLibreMap) {
        this.map = map
        map.addOnMapClickListener { onMapTapped(LatLon(it.latitude, it.longitude)) }
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
            SpawnLayers.install(loaded, requireContext())
            loaded.addSource(GeoJsonSource(BORDER_SOURCE))
            loaded.addLayer(LineLayer(BORDER_SOURCE, BORDER_SOURCE).withProperties(
                PropertyFactory.lineColor(BORDER_COLOR.toColorInt()),
                PropertyFactory.lineWidth(3f),
            ))
            // The field's photo, from the server's own store, again whenever the host saves another
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    container.serverFieldStore.info.collect { info ->
                        val photo = if (info == null) null else withContext(Dispatchers.IO) {
                            container.serverFieldStore.load()?.let { pack -> MapStyles.decodeFieldPhoto(pack)?.let { pack to it } }
                        }
                        style?.let { MapStyles.showFieldPhoto(it, photo?.first, photo?.second) }
                        // Its credit, as every map shows (MapTiler's or USGS's, as the photo says)
                        _binding?.refCreditTv?.let { credit ->
                            credit.isVisible = photo != null
                            photo?.let { MapCredit.attach(credit, MapCredit.isMapTiler(it.first.info.credit)) }
                        }
                        // A new field: fit it again
                        placed = false
                    }
                }
            }
        }
    }

    private fun show(view: LiveGame.RefereeView?) {
        val b = _binding ?: return
        val style = style
        if (view == null) {
            b.refInfoTv.setText(R.string.ref_no_game)
            return
        }
        // Between games the live field is off: the saved one, so the pane still shows the field
        val field = view.field ?: container.serverFieldConfig.config.value
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
            GameMode.CAPTURE_THE_FLAG -> mode.flagBaseSpots().map { (team, base) -> ObjectiveState("", base, team, 0, 0.0, false, active = true, next = false) }
            GameMode.EXTRACTION -> mode.extraction?.let { e ->
                // Red until the zones open, then green
                val open = System.currentTimeMillis() >= e.opensAt
                e.zones.map { ObjectiveState("EX", it, if (open) EXTRACTION_OPEN else EXTRACTION_CLOSED, 0, 0.0, false, active = open, next = !open) } +
                    e.loot.map { ObjectiveState(it.value.toString(), it.point, LOOT, 0, 0.0, false, active = true, next = false) }
            }.orEmpty()
            else -> emptyList()
        }, mode?.radiusMeters ?: 10.0, letters = mode?.mode != GameMode.HARDPOINT)
        flags?.update(mode?.flags.orEmpty().map { GlyphOverlay.Glyph("flag${it.team}", it.point, FLAG, if (it.team == FlagEngine.NEUTRAL) 0xFFFFFFFF.toInt() else colorOf(it.team)) })
        drops?.update(field?.drops.orEmpty(), System.currentTimeMillis())
        // Everyone, by side: out or with no position shown faded, or left off; not those sitting out (joined too late)
        val sittingOut = mode?.sittingOut.orEmpty()
        val located = view.players.filter { it.point != null && it.id !in sittingOut }
        dots?.update(located.map { p ->
            val side = mode?.let { if (it.mode == GameMode.INFECTION) (if (p.id in it.infected) InfectionEngine.INFECTED else InfectionEngine.SURVIVORS) else null } ?: p.team
            val color = colorOf(side)
            GlyphOverlay.Glyph("p${p.id}", p.point!!, DOT, if (p.alive) color else (color and 0x00FFFFFF) or 0x66000000)
        })
        names?.update(located.map { GlyphOverlay.Glyph("n${it.id}", it.point!!, it.name.ifBlank { "#${it.id}" }, 0xFFFFFFFF.toInt()) })
        b.refZoneButton.isVisible = mode?.mode == GameMode.BATTLE_ROYALE
        b.refInfoTv.text = info(view, located.size)
    }

    private fun info(view: LiveGame.RefereeView, located: Int): String {
        val mode = view.mode
        // Battle Royale is everyone for themselves: just how many are left
        val scores = if (mode?.mode == GameMode.BATTLE_ROYALE) {
            mode.scores.values.sum().let { resources.getQuantityString(R.plurals.br_players_left, it, it) }
        } else mode?.scores?.entries?.sortedByDescending { it.value }?.joinToString("   ") { (team, score) ->
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
        EXTRACTION_OPEN -> MapIcons.EXTRACTION_OPEN
        EXTRACTION_CLOSED -> MapIcons.EXTRACTION_CLOSED
        LOOT -> MapIcons.LOOT
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
        _binding?.refMapView?.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        @Suppress("DEPRECATION")
        super.onLowMemory()
        _binding?.refMapView?.onLowMemory()
    }

    override fun onDestroyView() {
        binding.refMapView.onDestroy()
        map = null
        style = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_EMBEDDED = "embedded"

        /** The map as a pane of the server screen ([embedded]) or a screen of its own. */
        fun newInstance(embedded: Boolean) = RefereeMapFragment().apply { arguments = bundleOf(ARG_EMBEDDED to embedded) }

        private const val REFRESH_MILLIS = 1_000L
        private const val EXPAND_BUTTON_ROOM_DP = 52
        private const val MIN_ZOOM = 13.0
        private const val MAX_ZOOM = 20.0
        private const val BORDER_SOURCE = "ref-border"
        private const val BORDER_COLOR = "#FF5252"
        private const val DOT = "●"
        private const val FLAG = "⚑"

        /** Stand-in teams for the Extraction zones (closed, open) and the loot, for their colours. */
        private const val EXTRACTION_CLOSED = -10
        private const val EXTRACTION_OPEN = -11
        private const val LOOT = -12

        private const val DOT_SP = 22f
        private const val NAME_SP = 11f
        private val PALETTE = intArrayOf(0xFF2979FF.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFFB300.toInt(), 0xFF8E24AA.toInt(),
            0xFF00ACC1.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(), 0xFFEC407A.toInt(), 0xFFC0CA33.toInt())
    }
}
