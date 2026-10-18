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

package com.simplecoil.simplecoil.ui.map

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.simplecoil.simplecoil.AppContainer
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.databinding.ActivityPostGameMapBinding
import com.simplecoil.simplecoil.domain.map.DistanceUnits
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.postgame.GameRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale

/**
 * The post-game map (map design: Longest shot and post-game stats), opened from the scoreboard
 * after a game: every measured kill as a line from shooter to victim, a heatmap of where
 * players spent their time, and a replay of everyone's movement with a time slider. All of it
 * can be narrowed to one player. Every layer has fixed paint, one per colour, so old phones draw
 * it too.
 */
class PostGameMapActivity : AppCompatActivity() {

    private enum class View3 { KILLS, HEAT, REPLAY }

    private lateinit var binding: ActivityPostGameMapBinding
    private lateinit var container: AppContainer
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var record: GameRecord? = null
    private var showing = View3.KILLS
    /** The player to show; null for everyone. */
    private var player: Int? = null
    private var replaySeconds = 0
    private var playing = false
    private val handler = Handler(Looper.getMainLooper())

    /** The sources made so far, by name (one per layer kind and colour). */
    private val sources = HashMap<String, GeoJsonSource>()

    private val player3 = object : Runnable {
        override fun run() {
            val length = record?.lengthSeconds ?: return
            if (!playing) return
            replaySeconds = (replaySeconds + REPLAY_STEP_SECONDS).coerceAtMost(length)
            binding.postSeek.progress = replaySeconds
            if (replaySeconds >= length) setPlaying(false) else handler.postDelayed(this, REPLAY_FRAME_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as SimpleCoilApp).container
        MapLibre.getInstance(applicationContext)
        binding = ActivityPostGameMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.postMapView.onCreate(savedInstanceState)
        binding.postMapView.getMapAsync { onMapReady(it) }

        binding.postViewGroup.check(R.id.post_kills_button)
        binding.postViewGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            showing = when (id) {
                R.id.post_heat_button -> View3.HEAT
                R.id.post_replay_button -> View3.REPLAY
                else -> View3.KILLS
            }
            if (showing != View3.REPLAY) setPlaying(false)
            draw()
        }
        binding.postPlayButton.setOnClickListener {
            val length = record?.lengthSeconds ?: 0
            if (!playing && replaySeconds >= length) replaySeconds = 0
            setPlaying(!playing)
        }
        binding.postSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                replaySeconds = progress
                drawReplay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = setPlaying(false)
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val server = container.gameState.serverIP
            val loaded = withContext(Dispatchers.IO) { server?.let { container.fieldImageryClient.replay(it) } }
            binding.postProgress.isVisible = false
            if (loaded == null) {
                binding.postInfoTv.setText(R.string.post_none)
                return@launch
            }
            record = loaded
            binding.postSeek.max = loaded.lengthSeconds
            // The full map needs paths; without them only the kills show
            binding.postHeatButton.isEnabled = loaded.track.isNotEmpty()
            binding.postReplayButton.isEnabled = loaded.track.isNotEmpty()
            val ids = (loaded.names.keys + loaded.kills.flatMap { listOf(it.shooter, it.victim) }).distinct().sortedBy { name(it).lowercase() }
            val choices = listOf(getString(R.string.post_everyone)) + ids.map { name(it) }
            binding.postPlayerSpinner.adapter = ArrayAdapter(this@PostGameMapActivity, R.layout.post_spinner_item, choices)
            binding.postPlayerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    player = if (position == 0) null else ids[position - 1]
                    draw()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            fitCamera()
            draw()
        }
    }

    private fun name(id: Int): String = record?.names?.get(id)?.takeIf { it.isNotBlank() } ?: "#$id"

    private fun onMapReady(map: MapLibreMap) {
        this.map = map
        // As on the play map: about 5 km across down to a few metres
        map.setMinZoomPreference(MIN_ZOOM)
        map.setMaxZoomPreference(MAX_ZOOM)
        map.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = false
            isTiltGesturesEnabled = false
        }
        map.setStyle(MapStyles.playStyle()) { loaded ->
            style = loaded
            OutsideShade.install(loaded)
            val border = container.playerFieldConfig.config.value?.border?.ring()
            OutsideShade.update(loaded, border)
            border?.let { line("border", BORDER_COLOR, 3f).setGeoJson(Feature.fromGeometry(LineString.fromLngLats(it.map { p -> point(p) }))) }
            lifecycleScope.launch {
                val photo = withContext(Dispatchers.IO) {
                    container.playerFieldStore.load()?.let { pack -> MapStyles.decodeFieldPhoto(pack)?.let { pack to it } }
                }
                photo?.let { (pack, bitmap) -> style?.let { MapStyles.showFieldPhoto(it, pack, bitmap) } }
            }
            fitCamera()
            draw()
        }
    }

    /** The whole field, or everything in the record. */
    private fun fitCamera() {
        val map = map ?: return
        val points = container.playerFieldConfig.config.value?.border?.ring()
            ?: record?.let { r -> r.track.map { it.point } + r.kills.flatMap { listOf(it.from, it.to) } }.orEmpty()
        if (points.size < 2) return
        val bounds = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, (40 * resources.displayMetrics.density).toInt()))
    }

    private fun draw() {
        val record = record ?: return
        if (style == null) return
        binding.postReplayRow.isVisible = showing == View3.REPLAY
        // Each view clears what the others drew
        sources.values.forEach { it.setGeoJson(FeatureCollection.fromFeatures(emptyList())) }
        sources["border"]?.let { src -> container.playerFieldConfig.config.value?.border?.ring()?.let { src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(it.map { p -> point(p) }))) } }
        when (showing) {
            View3.KILLS -> drawKills(record)
            View3.HEAT -> drawHeat(record)
            View3.REPLAY -> drawReplay()
        }
    }

    private fun drawKills(record: GameRecord) {
        val kills = record.kills.filter { player == null || it.shooter == player || it.victim == player }
        kills.groupBy { colorOf(it.shooter) }.forEach { (color, mine) ->
            line("kill-$color", color, 2.5f).setGeoJson(FeatureCollection.fromFeatures(mine.map {
                Feature.fromGeometry(LineString.fromLngLats(listOf(point(it.from), point(it.to))))
            }))
            dots("killer-$color", color, 5f).setGeoJson(FeatureCollection.fromFeatures(mine.map { Feature.fromGeometry(point(it.from)) }))
        }
        dots("victim", VICTIM_COLOR, 4f).setGeoJson(FeatureCollection.fromFeatures(kills.map { Feature.fromGeometry(point(it.to)) }))
        val units = container.playerFieldConfig.config.value?.rules?.units ?: DistanceUnits.defaultFor(Locale.getDefault())
        val longest = kills.maxByOrNull { it.meters }
        binding.postInfoTv.text = if (longest == null) getString(R.string.post_no_kills)
        else resources.getQuantityString(R.plurals.post_kills_info, kills.size, kills.size, name(longest.shooter), units.format(longest.meters))
    }

    private fun drawHeat(record: GameRecord) {
        // Many faint dots: where they pile up, it glows
        val points = record.track.filter { player == null || it.player == player }
        heat().setGeoJson(FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(point(it.point)) }))
        binding.postInfoTv.text = getString(R.string.post_heat_info)
    }

    private fun drawReplay() {
        val record = record ?: return
        if (showing != View3.REPLAY || style == null) return
        val positions = record.positionsAt(replaySeconds).filterKeys { player == null || it == player }
        val byColor = positions.entries.groupBy({ colorOf(it.key) }, { it.value })
        // Clear colours nobody has now, then draw the rest
        sources.filterKeys { it.startsWith("replay-") }.values.forEach { it.setGeoJson(FeatureCollection.fromFeatures(emptyList())) }
        byColor.forEach { (color, points) ->
            dots("replay-$color", color, 7f).setGeoJson(FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(point(it)) }))
        }
        // The kills so far, as they happened, each in the shooter's colour, from shooter to a white victim dot
        val kills = record.kills.filter { it.atSeconds <= replaySeconds && (player == null || it.shooter == player || it.victim == player) }
        kills.groupBy { colorOf(it.shooter) }.forEach { (color, mine) ->
            line("replay-kill-$color", color, 2.5f).setGeoJson(FeatureCollection.fromFeatures(mine.map {
                Feature.fromGeometry(LineString.fromLngLats(listOf(point(it.from), point(it.to))))
            }))
        }
        dots("replay-victims", VICTIM_COLOR, 3f).setGeoJson(FeatureCollection.fromFeatures(kills.map { Feature.fromGeometry(point(it.to)) }))
        val clock = String.format(Locale.ROOT, "%d:%02d", replaySeconds / 60, replaySeconds % 60)
        binding.postTimeTv.text = clock
        binding.postInfoTv.text = resources.getQuantityString(R.plurals.post_replay_info, positions.size, positions.size, clock)
    }

    private fun setPlaying(on: Boolean) {
        playing = on
        binding.postPlayButton.setText(if (on) R.string.post_pause else R.string.post_play)
        handler.removeCallbacks(player3)
        if (on) handler.post(player3)
    }

    /** A team's colour; in free for all, a colour each. */
    private fun colorOf(playerId: Int): Int {
        val team = record?.teams?.get(playerId) ?: playerId
        return if (team in 1..4 && team != playerId) MapIcons.teamColor(team) else PALETTE[Math.floorMod(playerId, PALETTE.size)]
    }

    private fun point(p: LatLon): Point = Point.fromLngLat(p.longitude, p.latitude)

    private fun line(name: String, color: Int, width: Float): GeoJsonSource = sources.getOrPut(name) {
        GeoJsonSource(name).also { src ->
            style?.addSource(src)
            style?.addLayer(LineLayer(name, name).withProperties(PropertyFactory.lineColor(color), PropertyFactory.lineWidth(width)))
        }
    }

    private fun dots(name: String, color: Int, radius: Float): GeoJsonSource = sources.getOrPut(name) {
        GeoJsonSource(name).also { src ->
            style?.addSource(src)
            style?.addLayer(CircleLayer(name, name).withProperties(
                PropertyFactory.circleColor(color),
                PropertyFactory.circleRadius(radius),
                PropertyFactory.circleStrokeColor(0xFF000000.toInt()),
                PropertyFactory.circleStrokeWidth(1f),
            ))
        }
    }

    private fun heat(): GeoJsonSource = sources.getOrPut("heat") {
        GeoJsonSource("heat").also { src ->
            style?.addSource(src)
            style?.addLayer(CircleLayer("heat", "heat").withProperties(
                PropertyFactory.circleColor(HEAT_COLOR),
                PropertyFactory.circleRadius(14f),
                PropertyFactory.circleBlur(1f),
                PropertyFactory.circleOpacity(0.12f),
            ))
        }
    }

    override fun onStart() {
        super.onStart()
        binding.postMapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.postMapView.onResume()
    }

    override fun onPause() {
        setPlaying(false)
        binding.postMapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.postMapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.postMapView.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        @Suppress("DEPRECATION")
        super.onLowMemory()
        binding.postMapView.onLowMemory()
    }

    override fun onDestroy() {
        handler.removeCallbacks(player3)
        binding.postMapView.onDestroy()
        super.onDestroy()
    }

    private companion object {
        const val BORDER_COLOR = 0xFFFF5252.toInt()
        const val VICTIM_COLOR = 0xFFFFFFFF.toInt()
        const val HEAT_COLOR = 0xFFFF6D00.toInt()
        const val MIN_ZOOM = 13.0
        const val MAX_ZOOM = 20.0

        /** The replay moves this much game time per frame. */
        const val REPLAY_STEP_SECONDS = 3
        const val REPLAY_FRAME_MILLIS = 150L

        /** Free for all: a colour per player. */
        val PALETTE = intArrayOf(0xFF2979FF.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFFB300.toInt(), 0xFF8E24AA.toInt(),
            0xFF00ACC1.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(), 0xFFEC407A.toInt(), 0xFFC0CA33.toInt())
    }
}
