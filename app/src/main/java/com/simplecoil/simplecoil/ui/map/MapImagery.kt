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

import com.simplecoil.simplecoil.BuildConfig
import com.simplecoil.simplecoil.domain.map.MapTilerImagery
import com.simplecoil.simplecoil.network.imagery.AndroidTileStitcher
import com.simplecoil.simplecoil.network.imagery.FieldDownloader
import com.simplecoil.simplecoil.network.imagery.MapTilerFieldDownloader
import com.simplecoil.simplecoil.network.imagery.UsgsFieldDownloader

/**
 * Where this build's aerial imagery comes from: MapTiler (the US, Europe and beyond) when the
 * build has a MapTiler key, USGS (the US only) otherwise. The key comes from local.properties
 * or the MAPTILER_KEY environment variable at build time, never the source (app/build.gradle.kts).
 */
object MapImagery {
    private val key: String = BuildConfig.MAPTILER_KEY

    val usesMapTiler: Boolean get() = key.isNotEmpty()

    /** The server's field photo downloader. */
    fun downloader(): FieldDownloader =
        if (usesMapTiler) MapTilerFieldDownloader(key, AndroidTileStitcher) else UsgsFieldDownloader()

    /** The field picker's MapTiler TileJSON, or null when the picker uses USGS's tiles. */
    val browseTileJson: String? get() = if (usesMapTiler) MapTilerImagery.tileJsonUrl(key) else null
}
