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

package com.simplecoil.simplecoil.network.imagery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.simplecoil.simplecoil.domain.map.MapTilerImagery
import java.io.ByteArrayOutputStream

/** Stitches map tiles into a field's photo with Android's Bitmap (a missing tile stays dark). */
object AndroidTileStitcher : MapTilerFieldDownloader.TileStitcher {
    private const val JPEG_QUALITY = 85

    override fun widthOf(tile: ByteArray): Int? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(tile, 0, tile.size, options)
        return options.outWidth.takeIf { it > 0 }
    }

    override fun stitch(photo: MapTilerImagery.FieldPhoto, tiles: List<ByteArray?>): ByteArray {
        val range = photo.tiles
        val tilePx = range.tilePx
        // Drawn straight into the photo: each tile shifted by the crop and scaled to the photo's size
        val scale = photo.sizePx.toFloat() / photo.cropPx
        val out = Bitmap.createBitmap(photo.sizePx, photo.sizePx, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            tiles.forEachIndexed { i, bytes ->
                val tile = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@forEachIndexed
                val left = (i % range.columns) * tilePx - photo.cropLeft
                val top = (i / range.columns) * tilePx - photo.cropTop
                val dest = RectF(left * scale, top * scale, (left + tilePx) * scale, (top + tilePx) * scale)
                canvas.drawBitmap(tile, null, dest, paint)
                tile.recycle()
            }
            return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
        } finally {
            out.recycle()
        }
    }
}
