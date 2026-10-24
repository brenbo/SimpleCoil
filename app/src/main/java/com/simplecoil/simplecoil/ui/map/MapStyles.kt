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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.simplecoil.simplecoil.domain.map.UsgsImagery
import com.simplecoil.simplecoil.network.imagery.FieldPack
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource

/**
 * The map styles (map design: Map engine): a dark background with the field's own photo on top.
 * Players' phones never load anything from the internet, so they don't tell anyone where the
 * game is; only the server's field picker does, when the host goes looking for the field.
 */
object MapStyles {
    const val BACKGROUND_COLOR = "#1a1a2e"
    const val BACKGROUND_LAYER = "background"
    const val BROWSE_SOURCE = "usgs-browse"
    const val FIELD_SOURCE = "field-photo"
    const val FIELD_LAYER = "field-photo"

    /** The play screen's style: just the background, until the field photo arrives. */
    fun playStyle(): Style.Builder = Style.Builder().fromJson(styleJson(null))

    /**
     * The server's field picker: aerial tiles for finding the field. MapTiler's satellite when the
     * build has its key (256-pixel tiles, one request each), USGS's imagery with roads and names otherwise.
     */
    fun browseStyle(): Style.Builder = Style.Builder().fromJson(styleJson(
        MapImagery.browseTileJson?.let { tileJson -> JSONObject().put("url", tileJson) }
            ?: JSONObject().put("tiles", JSONArray().put(UsgsImagery.BROWSE_TILES)).put("maxzoom", UsgsImagery.TILES_MAX_ZOOM)))

    private fun styleJson(browse: JSONObject?): String {
        val sources = JSONObject()
        val layers = JSONArray()
            .put(JSONObject().put("id", BACKGROUND_LAYER).put("type", "background")
                .put("paint", JSONObject().put("background-color", BACKGROUND_COLOR)))
        if (browse != null) {
            sources.put(BROWSE_SOURCE, browse.put("type", "raster").put("tileSize", 256))
            layers.put(JSONObject().put("id", BROWSE_SOURCE).put("type", "raster").put("source", BROWSE_SOURCE))
        }
        return JSONObject()
            .put("version", 8)
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    /** The photo in [pack], or null if it won't decode. Off the main thread: it can be a few MB. */
    fun decodeFieldPhoto(pack: FieldPack): Bitmap? =
        BitmapFactory.decodeByteArray(pack.image, 0, pack.image.size)

    /**
     * Shows [photo] where [pack] says it goes, replacing any field photo already shown; null
     * removes it. Main thread.
     */
    fun showFieldPhoto(style: Style, pack: FieldPack?, photo: Bitmap?) {
        if (pack == null || photo == null) {
            style.getLayer(FIELD_LAYER)?.let { style.removeLayer(it) }
            style.getSource(FIELD_SOURCE)?.let { style.removeSource(it) }
            return
        }
        val b = pack.info.bounds
        val quad = LatLngQuad(LatLng(b.north, b.west), LatLng(b.north, b.east), LatLng(b.south, b.east), LatLng(b.south, b.west))
        val existing = style.getSourceAs<ImageSource>(FIELD_SOURCE)
        if (existing != null) {
            existing.setCoordinates(quad)
            existing.setImage(photo)
            return
        }
        style.addSource(ImageSource(FIELD_SOURCE, quad, photo))
        val below = if (style.getLayer(BROWSE_SOURCE) != null) BROWSE_SOURCE else BACKGROUND_LAYER
        style.addLayerAbove(RasterLayer(FIELD_LAYER, FIELD_SOURCE).withProperties(PropertyFactory.rasterFadeDuration(0f)), below)
    }
}
