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

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.location.LocationManagerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.simplecoil.simplecoil.AppContainer
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.databinding.ActivityFieldMapBinding
import com.simplecoil.simplecoil.databinding.DialogBorderRulesBinding
import com.simplecoil.simplecoil.databinding.DialogFieldNameBinding
import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.BorderRules
import com.simplecoil.simplecoil.domain.map.DistanceUnits
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoBounds
import com.simplecoil.simplecoil.domain.map.GeoCircle
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.OutOfBoundsPenalty
import com.simplecoil.simplecoil.domain.map.PathSimplifier
import com.simplecoil.simplecoil.domain.map.Spawn
import com.simplecoil.simplecoil.domain.map.SpawnPlanner
import com.simplecoil.simplecoil.domain.map.SpawnRules
import com.simplecoil.simplecoil.domain.map.UsgsImagery
import com.simplecoil.simplecoil.network.imagery.FieldPackInfo
import com.simplecoil.simplecoil.network.imagery.UsgsFieldDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
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
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The dedicated server's field map (map design: Field imagery packs, Border, Field presets).
 * The host finds the field, draws its border (a circle, tapped corners, or by walking it), sets
 * the out-of-bounds rules, downloads the field's photo, and saves the lot as a named field to
 * reuse. Only the download and the map underneath need internet; the server then sends the
 * field to every phone that joins.
 */
class FieldMapActivity : AppCompatActivity() {

    private enum class Tool { NONE, CIRCLE, POLYGON, WALK, SPAWNS }

    private lateinit var container: AppContainer
    private lateinit var binding: ActivityFieldMapBinding
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var photo: FieldPackInfo? = null
    private var config = FieldConfig(rules = BorderRules(units = DistanceUnits.defaultFor(Locale.getDefault())))
    private var downloading = false
    private val downloader = UsgsFieldDownloader()

    private var tool = Tool.NONE
    private var circleCenter: LatLon? = null
    private val corners = ArrayList<LatLon>()
    private val walked = ArrayList<LatLon>()
    private var walkListener: LocationListener? = null
    private var spawnTeam = 1

    private val requestLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (tool == Tool.WALK) startWalk() else goToMyLocation()
        } else if (tool == Tool.WALK) {
            binding.fieldToolGroup.clearChecked()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as SimpleCoilApp).container
        MapLibre.getInstance(applicationContext)
        binding = ActivityFieldMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.fieldMapView.onCreate(savedInstanceState)
        binding.fieldMapView.getMapAsync { onMapReady(it) }

        binding.fieldToolGroup.addOnButtonCheckedListener { _, id, checked ->
            val chosen = when (id) {
                binding.fieldToolCircleButton.id -> Tool.CIRCLE
                binding.fieldToolPolygonButton.id -> Tool.POLYGON
                binding.fieldToolSpawnsButton.id -> Tool.SPAWNS
                else -> Tool.WALK
            }
            if (checked) startTool(chosen) else if (tool == chosen) finishTool()
        }
        binding.fieldUndoButton.setOnClickListener { undo() }
        binding.fieldClearButton.setOnClickListener { if (tool == Tool.SPAWNS) clearSpawns() else clearBorder() }
        binding.fieldSpawnTeamButton.setOnClickListener { nextSpawnTeam() }
        binding.fieldAutoPlaceButton.setOnClickListener { autoPlace() }
        binding.fieldRulesButton.setOnClickListener { showRules() }
        binding.fieldPresetsButton.setOnClickListener { showPresets() }
        binding.fieldMyLocationButton.setOnClickListener { goToMyLocation() }
        binding.fieldDownloadButton.setOnClickListener { download() }
        binding.fieldDeleteButton.setOnClickListener { deletePhoto() }
        MapCredit.attach(binding.fieldCreditTv)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.IO) {
                    container.serverFieldStore.refresh()
                    container.serverFieldConfig.refresh()
                }
                launch { container.serverFieldStore.info.collect { showPhoto(it) } }
                launch {
                    container.serverFieldConfig.config.collect { saved ->
                        saved?.let { config = it }
                        drawBorder()
                        drawSpawns()
                        showStatus()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        binding.fieldMapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.fieldMapView.onResume()
    }

    override fun onPause() {
        // Reopen where the host left off, even after a restart
        map?.cameraPosition?.let { position ->
            position.target?.let { container.userPreferences.saveFieldMapCamera(it.latitude, it.longitude, position.zoom) }
        }
        binding.fieldMapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.fieldMapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.fieldMapView.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        @Suppress("DEPRECATION")
        super.onLowMemory()
        binding.fieldMapView.onLowMemory()
    }

    override fun onDestroy() {
        stopWalk()
        binding.fieldMapView.onDestroy()
        super.onDestroy()
    }

    private fun onMapReady(map: MapLibreMap) {
        this.map = map
        // Out to the whole country, to find the field from home; in to a few metres
        map.setMinZoomPreference(MIN_ZOOM)
        map.setMaxZoomPreference(MAX_ZOOM)
        map.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = false
            isTiltGesturesEnabled = false
            isRotateGesturesEnabled = false
        }
        map.addOnMapClickListener { point -> onMapTapped(LatLon(point.latitude, point.longitude)) }
        map.setStyle(MapStyles.browseStyle()) { loaded ->
            style = loaded
            loaded.addSource(GeoJsonSource(PHOTO_SOURCE))
            loaded.addSource(GeoJsonSource(BORDER_SOURCE))
            loaded.addSource(GeoJsonSource(DRAFT_SOURCE))
            loaded.addSource(GeoJsonSource(POINTS_SOURCE))
            SpawnLayers.install(loaded, this)
            loaded.addLayer(LineLayer(PHOTO_SOURCE, PHOTO_SOURCE).withProperties(
                PropertyFactory.lineColor(PHOTO_COLOR.toColorInt()),
                PropertyFactory.lineWidth(1.5f),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            ))
            loaded.addLayer(LineLayer(BORDER_SOURCE, BORDER_SOURCE).withProperties(
                PropertyFactory.lineColor(BORDER_COLOR.toColorInt()),
                PropertyFactory.lineWidth(3f),
            ))
            loaded.addLayer(LineLayer(DRAFT_SOURCE, DRAFT_SOURCE).withProperties(
                PropertyFactory.lineColor(DRAFT_COLOR.toColorInt()),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
            ))
            loaded.addLayer(CircleLayer(POINTS_SOURCE, POINTS_SOURCE).withProperties(
                PropertyFactory.circleColor(DRAFT_COLOR.toColorInt()),
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleStrokeColor("#FFFFFF".toColorInt()),
                PropertyFactory.circleStrokeWidth(1.5f),
            ))
            moveToStart()
            showPhoto(photo)
            drawBorder()
            drawSpawns()
        }
    }

    /** Where the host left the map, else the saved border, else the saved photo, else where this phone last was, else the whole US. */
    private fun moveToStart() {
        val saved = container.userPreferences.fieldMapCamera()
        if (saved != null) {
            map?.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(saved[0], saved[1])).zoom(saved[2]).build()))
            return
        }
        val area = config.border?.bounds ?: photo?.bounds
        val here = lastKnownLocation()
        when {
            area != null -> moveTo(area)
            here != null -> moveTo(GeoMath.squareAround(LatLon(here.latitude, here.longitude), START_VIEW_METERS))
            else -> map?.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(US_CENTER.latitude, US_CENTER.longitude)).zoom(US_ZOOM).build()))
        }
    }

    private fun moveTo(area: GeoBounds) {
        val circle = GeoMath.enclosingCircle(area.corners) ?: return
        val view = binding.fieldMapView
        val sizeDp = min(view.width, view.height) / resources.displayMetrics.density
        val zoom = if (sizeDp > 0) GeoMath.zoomToFit(GeoCircle(circle.center, circle.radiusMeters * 1.1), sizeDp.toDouble()) else 16.0
        map?.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(circle.center.latitude, circle.center.longitude)).zoom(zoom).bearing(0.0).build()), 500)
    }

    // Drawing the border

    private fun startTool(chosen: Tool) {
        if (tool == Tool.WALK) stopWalk()
        tool = chosen
        circleCenter = null
        corners.clear()
        walked.clear()
        when (chosen) {
            Tool.CIRCLE -> binding.fieldHintTv.setText(R.string.field_hint_circle)
            Tool.POLYGON -> {
                // Carry on from the saved corners, so a field can be touched up
                (config.border as? Border.Polygon)?.let { corners.addAll(it.corners) }
                binding.fieldHintTv.setText(R.string.field_hint_polygon)
            }
            Tool.WALK -> {
                binding.fieldHintTv.setText(R.string.field_hint_walk)
                startWalk()
            }
            Tool.SPAWNS -> {
                if (spawnTeam !in spawnTeams()) spawnTeam = spawnTeams().first()
                showSpawnTeam()
            }
            Tool.NONE -> binding.fieldHintTv.setText(R.string.field_map_hint)
        }
        binding.fieldSpawnRow.isVisible = chosen == Tool.SPAWNS
        drawDraft()
    }

    /** The tool was switched off: a walk becomes the border; the others already saved as they went. */
    private fun finishTool() {
        if (tool == Tool.WALK) {
            stopWalk()
            val outline = PathSimplifier.simplify(walked, WALK_TOLERANCE_METERS)
                .let { if (it.size > 3 && GeoMath.distanceMeters(it.first(), it.last()) < CLOSE_LOOP_METERS) it.dropLast(1) else it }
            if (outline.size >= 3) {
                saveBorder(Border.Polygon(outline))
            } else if (walked.isNotEmpty()) {
                Toast.makeText(applicationContext, R.string.field_walk_too_short, Toast.LENGTH_LONG).show()
            }
        }
        tool = Tool.NONE
        circleCenter = null
        corners.clear()
        walked.clear()
        binding.fieldSpawnRow.isVisible = false
        binding.fieldHintTv.setText(R.string.field_map_hint)
        drawDraft()
    }

    private fun onMapTapped(point: LatLon): Boolean {
        when (tool) {
            Tool.CIRCLE -> {
                val center = circleCenter
                if (center == null) {
                    circleCenter = point
                    binding.fieldHintTv.setText(R.string.field_hint_circle_edge)
                } else {
                    val radius = GeoMath.distanceMeters(center, point)
                    if (radius >= MIN_RADIUS_METERS) saveBorder(Border.Circle(center, radius))
                    circleCenter = null
                    binding.fieldHintTv.setText(R.string.field_hint_circle)
                }
            }
            Tool.POLYGON -> {
                corners += point
                if (corners.size >= 3) saveBorder(Border.Polygon(corners.toList()))
            }
            Tool.SPAWNS -> {
                // A tap on a spawn removes it; anywhere else adds one for the chosen team
                val radius = config.spawnRules.radiusMeters
                val hit = config.spawns.lastOrNull { GeoMath.distanceMeters(it.point, point) <= radius }
                saveConfig(config.copy(spawns = if (hit != null) config.spawns - hit else config.spawns + Spawn(spawnTeam, point)))
                drawSpawns()
            }
            Tool.WALK, Tool.NONE -> return false
        }
        drawDraft()
        return true
    }

    private fun undo() {
        when (tool) {
            Tool.CIRCLE -> circleCenter = null
            Tool.POLYGON -> if (corners.isNotEmpty()) {
                corners.removeAt(corners.size - 1)
                saveBorder(if (corners.size >= 3) Border.Polygon(corners.toList()) else null)
            }
            Tool.WALK -> if (walked.isNotEmpty()) walked.removeAt(walked.size - 1)
            Tool.SPAWNS -> if (config.spawns.isNotEmpty()) {
                saveConfig(config.copy(spawns = config.spawns.dropLast(1)))
                drawSpawns()
            }
            Tool.NONE -> {}
        }
        drawDraft()
    }

    // Spawns

    /** The teams spawns can be for in the server's team mode: 1-2, 1-4, or anyone (free for all). */
    private fun spawnTeams(): List<Int> = SpawnPlanner.teamsFor(container.userPreferences.teamMode().teamCount)

    private fun nextSpawnTeam() {
        val teams = spawnTeams()
        spawnTeam = teams[(teams.indexOf(spawnTeam) + 1) % teams.size]
        showSpawnTeam()
    }

    private fun teamName(team: Int): String = getString(when (team) {
        1 -> R.string.field_team_1
        2 -> R.string.field_team_2
        3 -> R.string.field_team_3
        4 -> R.string.field_team_4
        else -> R.string.field_team_anyone
    })

    private fun showSpawnTeam() {
        val name = teamName(spawnTeam)
        binding.fieldSpawnTeamButton.text = getString(R.string.field_spawn_team, name)
        binding.fieldHintTv.text = getString(R.string.field_hint_spawns, name)
    }

    /** Puts down every team's spawns by the rules, in place of the ones there. */
    private fun autoPlace() {
        val border = config.border
        if (border == null) {
            Toast.makeText(applicationContext, R.string.field_auto_place_need_border, Toast.LENGTH_LONG).show()
            return
        }
        val base = config
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                SpawnPlanner.place(border, spawnTeams(), base.spawnRules, base.rules.edgeBufferMeters)
            }
            if (result.spawns.isEmpty()) {
                Toast.makeText(applicationContext, R.string.field_auto_place_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            saveConfig(config.copy(spawns = result.spawns))
            drawSpawns()
            if (result.relaxed) Toast.makeText(applicationContext, R.string.field_auto_place_relaxed, Toast.LENGTH_LONG).show()
        }
    }

    private fun clearSpawns() {
        saveConfig(config.copy(spawns = emptyList()))
        drawSpawns()
        Toast.makeText(applicationContext, R.string.field_spawns_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun drawSpawns() {
        val style = style ?: return
        SpawnLayers.update(style, config.spawns, radiusMeters = config.spawnRules.radiusMeters,
            latitude = config.spawns.firstOrNull()?.point?.latitude ?: 0.0)
    }

    private fun clearBorder() {
        corners.clear()
        circleCenter = null
        walked.clear()
        saveBorder(null)
        drawDraft()
        Toast.makeText(applicationContext, R.string.field_border_cleared, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission") // checked first
    private fun startWalk() {
        if (!hasLocationPermission()) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val manager = getSystemService(LocationManager::class.java) ?: return
        val listener = LocationListener { fix -> onWalked(fix) }
        walkListener = listener
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, WALK_INTERVAL_MILLIS, WALK_MIN_STEP_METERS, listener, Looper.getMainLooper())
    }

    private fun stopWalk() {
        val listener = walkListener ?: return
        walkListener = null
        getSystemService(LocationManager::class.java)?.removeUpdates(listener)
    }

    private fun onWalked(fix: Location) {
        if (fix.hasAccuracy() && fix.accuracy > WALK_ACCURACY_METERS) return
        val point = LatLon(fix.latitude, fix.longitude)
        val last = walked.lastOrNull()
        if (last != null && GeoMath.distanceMeters(last, point) < WALK_MIN_STEP_METERS) return
        walked += point
        drawDraft()
        map?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(point.latitude, point.longitude)), 300)
    }

    private fun saveBorder(border: Border?) {
        saveConfig(config.copy(border = border), R.string.field_border_saved.takeIf { border != null && tool == Tool.WALK })
    }

    private fun saveConfig(updated: FieldConfig, message: Int? = null) {
        config = updated
        drawBorder()
        showStatus()
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    container.serverFieldConfig.save(updated)
                    true
                } catch (e: IOException) {
                    false
                }
            }
            container.fieldImageryServer.onFieldChanged()
            val text = if (!saved) R.string.field_map_save_error else message ?: return@launch
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun drawBorder() {
        val style = style ?: return
        style.getSourceAs<GeoJsonSource>(BORDER_SOURCE)?.setGeoJson(lines(config.border?.ring()))
    }

    /** What is being drawn: the tapped points, the walked path, a circle's center. */
    private fun drawDraft() {
        val style = style ?: return
        val path = when (tool) {
            Tool.WALK -> walked
            Tool.POLYGON -> if (corners.size >= 2) corners + corners.first() else corners
            else -> emptyList()
        }
        style.getSourceAs<GeoJsonSource>(DRAFT_SOURCE)?.setGeoJson(lines(path.takeIf { it.size >= 2 }))
        val points = when (tool) {
            Tool.CIRCLE -> listOfNotNull(circleCenter)
            Tool.POLYGON -> corners
            else -> emptyList()
        }
        style.getSourceAs<GeoJsonSource>(POINTS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(
            points.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }))
    }

    private fun lines(path: List<LatLon>?): FeatureCollection = FeatureCollection.fromFeatures(listOfNotNull(
        path?.let { p -> Feature.fromGeometry(LineString.fromLngLats(p.map { Point.fromLngLat(it.longitude, it.latitude) })) }))

    // The photo

    private fun showPhoto(info: FieldPackInfo?) {
        photo = info
        showStatus()
        val style = style ?: return
        style.getSourceAs<GeoJsonSource>(PHOTO_SOURCE)?.setGeoJson(lines(info?.bounds?.corners?.let { it + it.first() }))
        if (info == null) {
            MapStyles.showFieldPhoto(style, null, null)
            return
        }
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val pack = container.serverFieldStore.load() ?: return@withContext null
                MapStyles.decodeFieldPhoto(pack)?.let { pack to it }
            } ?: return@launch
            val current = this@FieldMapActivity.style ?: return@launch
            if (photo?.id == loaded.first.info.id) MapStyles.showFieldPhoto(current, loaded.first, loaded.second)
        }
    }

    /** Downloads a mile-wide photo around the border (or, before there is one, the middle of the map). */
    private fun download() {
        val center = config.border?.bounds?.center ?: map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) } ?: return
        if (downloading) return
        setDownloading(true)
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    container.serverFieldStore.save(downloader.download(center, UsgsImagery.FIELD_SIDE_METERS))
                    R.string.field_map_saved
                } catch (e: UsgsFieldDownloader.DownloadException) {
                    if (e.reason == UsgsFieldDownloader.Reason.NO_IMAGERY) R.string.field_map_no_imagery else R.string.field_map_network_error
                } catch (e: IOException) {
                    R.string.field_map_save_error
                }
            }
            if (message == R.string.field_map_saved) container.fieldImageryServer.onFieldChanged()
            setDownloading(false)
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun deletePhoto() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { container.serverFieldStore.clear() }
            container.fieldImageryServer.onFieldChanged()
            Toast.makeText(applicationContext, R.string.field_map_deleted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setDownloading(on: Boolean) {
        downloading = on
        binding.fieldProgress.isVisible = on
        binding.fieldDownloadButton.isEnabled = !on
        binding.fieldDeleteButton.isEnabled = !on
        if (on) binding.fieldStatusTv.setText(R.string.field_map_downloading) else showStatus()
    }

    /** "Johnson Park: Border with 5 corners · photo from Oct 16, 2026". */
    private fun showStatus() {
        if (downloading) return
        val border = when (val b = config.border) {
            null -> getString(R.string.field_status_border_none)
            is Border.Circle -> getString(R.string.field_status_border_circle, config.rules.units.format(b.radiusMeters * 2))
            is Border.Polygon -> resources.getQuantityString(R.plurals.field_status_border_polygon, b.corners.size, b.corners.size)
        }
        val photoText = photo?.let {
            getString(R.string.field_status_photo_saved, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.createdAtMillis)))
        } ?: getString(R.string.field_status_photo_none)
        val spawns = config.spawns.size.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.field_status_spawns, it, it) }
        val line = getString(R.string.field_status_line, if (spawns == null) border else "$border · $spawns", photoText)
        binding.fieldStatusTv.text = if (config.name.isBlank()) line else getString(R.string.field_status_named, config.name, line)
        binding.fieldDeleteButton.isEnabled = photo != null
    }

    // Rules

    private fun showRules() {
        val dialog = DialogBorderRulesBinding.inflate(layoutInflater)
        val rules = config.rules
        val spawnRules = config.spawnRules
        var units = rules.units
        val fields = listOf(dialog.rulesMarginEt, dialog.rulesAccuracyEt, dialog.rulesBufferEt, dialog.rulesNearEt,
            dialog.rulesSpawnRadiusEt, dialog.rulesEnemyClearEt)
        fun showDistances(meters: List<Double>) {
            val unit = if (units == DistanceUnits.FEET) "ft" else "m"
            dialog.rulesMarginLabel.text = getString(R.string.rules_leave_margin, unit)
            dialog.rulesAccuracyLabel.text = getString(R.string.rules_accuracy_gate, unit)
            dialog.rulesBufferLabel.text = getString(R.string.rules_edge_buffer, unit)
            dialog.rulesNearLabel.text = getString(R.string.rules_near_distance, unit)
            dialog.rulesSpawnRadiusLabel.text = getString(R.string.rules_spawn_radius, unit)
            dialog.rulesEnemyClearLabel.text = getString(R.string.rules_enemy_clear, unit)
            fields.zip(meters).forEach { (field, m) -> field.setText(number(units.fromMeters(m).roundToInt())) }
        }
        fun readMeters(field: EditText, fallback: Double) = field.text?.toString()?.toDoubleOrNull()?.let { units.toMeters(it) } ?: fallback
        fun readInt(field: EditText, fallback: Int) = field.text?.toString()?.toIntOrNull() ?: fallback
        val defaults = listOf(rules.leaveMarginMeters, rules.accuracyGateMeters, rules.edgeBufferMeters, rules.nearDistanceMeters,
            spawnRules.radiusMeters, spawnRules.enemyClearMeters)
        fun readAll() = fields.zip(defaults).map { (field, fallback) -> readMeters(field, fallback) }

        dialog.rulesPenaltyGroup.check(when (rules.penalty) {
            OutOfBoundsPenalty.DISABLE_BLASTER -> R.id.rules_penalty_disable
            OutOfBoundsPenalty.LOSE_HEALTH -> R.id.rules_penalty_health
            OutOfBoundsPenalty.ELIMINATED -> R.id.rules_penalty_eliminate
            OutOfBoundsPenalty.WARNING_ONLY -> R.id.rules_penalty_warning
        })
        dialog.rulesCountdownEt.setText(number(rules.countdownSeconds))
        dialog.rulesHealthEt.setText(number(rules.healthPerTick))
        dialog.rulesTickEt.setText(number(rules.tickSeconds))
        dialog.rulesGraceEt.setText(number(rules.graceSeconds))
        dialog.rulesOnlyNearSwitch.isChecked = rules.showOnlyWhenNear
        dialog.rulesWalkBackSwitch.isChecked = spawnRules.walkBack
        dialog.rulesStartAtSpawnSwitch.isChecked = spawnRules.startAtSpawn
        dialog.rulesShieldInSpawnSwitch.isChecked = spawnRules.shieldOnlyInSpawn
        dialog.rulesSecondsInSpawnEt.setText(number(spawnRules.secondsInSpawn))
        dialog.rulesPerTeamEt.setText(number(spawnRules.perTeam))
        dialog.rulesEnemyShareEt.setText(number((spawnRules.enemySpawnShare * 100).roundToInt()))
        dialog.rulesMoveMinutesEt.setText(number(spawnRules.moveMinutes))
        dialog.rulesCrossfadeEt.setText(number(spawnRules.crossfadeSeconds))
        dialog.rulesOldGraceEt.setText(number(spawnRules.oldSpawnGraceSeconds))
        dialog.rulesUnitsGroup.check(if (units == DistanceUnits.FEET) R.id.rules_units_feet else R.id.rules_units_meters)
        showDistances(defaults)
        dialog.rulesUnitsGroup.setOnCheckedChangeListener { _, id ->
            // Keep the distances, shown in the new units
            val meters = readAll()
            units = if (id == R.id.rules_units_feet) DistanceUnits.FEET else DistanceUnits.METERS
            showDistances(meters)
        }

        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setView(dialog.root)
            .setPositiveButton(R.string.field_dialog_save) { _, _ ->
                val distances = readAll()
                val updated = BorderRules(
                    countdownSeconds = readInt(dialog.rulesCountdownEt, rules.countdownSeconds),
                    penalty = when (dialog.rulesPenaltyGroup.checkedRadioButtonId) {
                        R.id.rules_penalty_health -> OutOfBoundsPenalty.LOSE_HEALTH
                        R.id.rules_penalty_eliminate -> OutOfBoundsPenalty.ELIMINATED
                        R.id.rules_penalty_warning -> OutOfBoundsPenalty.WARNING_ONLY
                        else -> OutOfBoundsPenalty.DISABLE_BLASTER
                    },
                    healthPerTick = readInt(dialog.rulesHealthEt, rules.healthPerTick),
                    tickSeconds = readInt(dialog.rulesTickEt, rules.tickSeconds),
                    showOnlyWhenNear = dialog.rulesOnlyNearSwitch.isChecked,
                    nearDistanceMeters = distances[3],
                    graceSeconds = readInt(dialog.rulesGraceEt, rules.graceSeconds),
                    leaveMarginMeters = distances[0],
                    accuracyGateMeters = distances[1],
                    edgeBufferMeters = distances[2],
                    units = units,
                ).clamped()
                val updatedSpawns = SpawnRules(
                    walkBack = dialog.rulesWalkBackSwitch.isChecked,
                    startAtSpawn = dialog.rulesStartAtSpawnSwitch.isChecked,
                    secondsInSpawn = readInt(dialog.rulesSecondsInSpawnEt, spawnRules.secondsInSpawn),
                    radiusMeters = distances[4],
                    perTeam = readInt(dialog.rulesPerTeamEt, spawnRules.perTeam),
                    enemySpawnShare = dialog.rulesEnemyShareEt.text?.toString()?.toIntOrNull()?.let { it / 100.0 } ?: spawnRules.enemySpawnShare,
                    enemyClearMeters = distances[5],
                    moveMinutes = readInt(dialog.rulesMoveMinutesEt, spawnRules.moveMinutes),
                    crossfadeSeconds = readInt(dialog.rulesCrossfadeEt, spawnRules.crossfadeSeconds),
                    oldSpawnGraceSeconds = readInt(dialog.rulesOldGraceEt, spawnRules.oldSpawnGraceSeconds),
                    shieldOnlyInSpawn = dialog.rulesShieldInSpawnSwitch.isChecked,
                ).clamped()
                saveConfig(config.copy(rules = updated, spawnRules = updatedSpawns))
                drawSpawns()
            }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .show()
    }


    // Saved fields

    private fun showPresets() {
        lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) { container.fieldPresets.names() }
            val items = (listOf(getString(R.string.field_presets_save)) + names).toTypedArray()
            AlertDialog.Builder(this@FieldMapActivity, R.style.AppDialogTheme)
                .setTitle(R.string.field_presets_title)
                .setItems(items) { _, which ->
                    if (which == 0) askPresetName() else presetOptions(names[which - 1])
                }
                .setNegativeButton(R.string.field_dialog_cancel, null)
                .show()
        }
    }

    private fun askPresetName() {
        val dialog = DialogFieldNameBinding.inflate(layoutInflater)
        dialog.fieldNameEt.setText(config.name)
        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setView(dialog.root)
            .setPositiveButton(R.string.field_dialog_save) { _, _ ->
                val name = dialog.fieldNameEt.text.toString().trim()
                if (name.isNotEmpty()) savePreset(name)
            }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .show()
    }


    private fun savePreset(name: String) {
        val named = config.copy(name = name)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    container.fieldPresets.save(name, container.serverFieldStore.load(), named)
                    container.serverFieldConfig.save(named)
                    true
                } catch (e: IOException) {
                    false
                }
            }
            Toast.makeText(applicationContext, if (ok) getString(R.string.field_presets_saved, name) else getString(R.string.field_presets_error),
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun presetOptions(name: String) {
        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(name)
            .setItems(arrayOf(getString(R.string.field_presets_load), getString(R.string.field_presets_delete))) { _, which ->
                if (which == 0) loadPreset(name) else deletePreset(name)
            }
            .setNegativeButton(R.string.field_dialog_cancel, null)
            .show()
    }

    private fun loadPreset(name: String) {
        binding.fieldToolGroup.clearChecked()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val (pack, saved) = container.fieldPresets.load(name) ?: return@withContext null
                    if (pack != null) container.serverFieldStore.save(pack) else container.serverFieldStore.clear()
                    container.serverFieldConfig.save(saved)
                    saved
                } catch (e: IOException) {
                    null
                }
            }
            if (loaded == null) {
                Toast.makeText(applicationContext, R.string.field_presets_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            config = loaded
            container.fieldImageryServer.onFieldChanged()
            drawBorder()
            drawSpawns()
            showStatus()
            (loaded.border?.bounds ?: photo?.bounds)?.let { moveTo(it) }
            Toast.makeText(applicationContext, getString(R.string.field_presets_loaded, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePreset(name: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { container.fieldPresets.delete(name) }
            Toast.makeText(applicationContext, getString(R.string.field_presets_deleted, name), Toast.LENGTH_SHORT).show()
        }
    }

    // Location

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked first
    private fun lastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission") // checked first
    private fun goToMyLocation() {
        if (!hasLocationPermission()) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        lastKnownLocation()?.let { moveTo(GeoMath.squareAround(LatLon(it.latitude, it.longitude), START_VIEW_METERS)) }
        val manager = getSystemService(LocationManager::class.java) ?: return
        val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        val mainExecutor: Executor = ContextCompat.getMainExecutor(this)
        LocationManagerCompat.getCurrentLocation(manager, provider, null as android.os.CancellationSignal?, mainExecutor,
            androidx.core.util.Consumer<Location?> { fix ->
                if (fix != null) {
                    moveTo(GeoMath.squareAround(LatLon(fix.latitude, fix.longitude), START_VIEW_METERS))
                } else if (lastKnownLocation() == null) {
                    Toast.makeText(applicationContext, R.string.field_map_no_location, Toast.LENGTH_SHORT).show()
                }
            })
    }

    private companion object {
        const val PHOTO_SOURCE = "photo-edge"
        const val BORDER_SOURCE = "border"
        const val DRAFT_SOURCE = "draft"
        const val POINTS_SOURCE = "draft-points"
        const val BORDER_COLOR = "#FF5252"
        const val DRAFT_COLOR = "#FF9800"
        const val PHOTO_COLOR = "#4CAF50"

        const val START_VIEW_METERS = 400.0
        const val MIN_RADIUS_METERS = 5.0
        val US_CENTER = LatLon(39.5, -98.35)
        const val US_ZOOM = 3.0
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 20.0

        const val WALK_INTERVAL_MILLIS = 1_000L
        const val WALK_MIN_STEP_METERS = 2f
        const val WALK_ACCURACY_METERS = 20f
        const val WALK_TOLERANCE_METERS = 3.0

        /** A walk that ends this close to where it started is closed there. */
        const val CLOSE_LOOP_METERS = 15.0

        /** A whole number for a number field: plain digits, which it reads back as typed. */
        fun number(value: Int): String = String.format(Locale.ROOT, "%d", value)
    }
}
