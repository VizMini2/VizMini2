// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.gps

import com.juancams.rviz2mini.platform.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A position (degrees). */
data class LatLon(val latitude: Double, val longitude: Double)

/**
 * Web map Mercator projection (that of the OpenStreetMap tiles): "world" coordinates in
 * pixels for a world size `world` (256 * 2^zoom).
 */
object WebMercator {
    const val MAX_LAT = 85.05112878

    fun x(lon: Double, world: Double) = (lon + 180.0) / 360.0 * world
    fun y(lat: Double, world: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * world
    }
    fun lon(x: Double, world: Double) = x / world * 360.0 - 180.0
    fun lat(y: Double, world: Double) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / world))))

    /** Meters per world pixel at that latitude. */
    fun metersPerPixel(lat: Double, world: Double) = cos(Math.toRadians(lat)) * 2 * PI * 6_378_137.0 / world
}

/**
 * OpenStreetMap tiles (streets), with a disk cache. Complies with the tile usage policy: own
 * User-Agent, local cache (reused for [MAX_AGE_MS]) and few downloads at once (done by the caller, one at a time
 * or two at a time). `cacheDir` is set by each platform.
 */
object OsmTiles {
    const val SIZE = 256
    const val MAX_ZOOM = 19
    const val ATTRIBUTION = "© OpenStreetMap contributors"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    @Volatile var cacheDir: File = File(com.juancams.rviz2mini.platform.Platform.cacheDir, "tiles")
    @Volatile var userAgent: String = "VizMini2 (ROS 2 viewer)"

    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

    private fun file(z: Int, x: Int, y: Int) = File(cacheDir, "$z/$x/$y.png")

    /** The tile's PNG bytes (from the cache if present and recent; otherwise from the network). Null if not possible. */
    fun load(z: Int, x: Int, y: Int): ByteArray? {
        val f = file(z, x, y)
        val cached = f.takeIf { it.isFile }
        if (cached != null && System.currentTimeMillis() - cached.lastModified() < MAX_AGE_MS) return try { cached.readBytes() } catch (_: Exception) { null }
        return try {
            http.newCall(Request.Builder().url("https://tile.openstreetmap.org/$z/$x/$y.png").header("User-Agent", userAgent).build()).execute().use { r ->
                if (!r.isSuccessful) return cached?.readBytes()
                val bytes = r.body?.bytes() ?: return cached?.readBytes()
                try { f.parentFile?.mkdirs(); f.writeBytes(bytes) } catch (_: Exception) {}
                bytes
            }
        } catch (e: Exception) {
            Log.w("VizMini2", "Tesela $z/$x/$y: ${e.message}")
            // No network: an old tile is better than none.
            try { cached?.readBytes() } catch (_: Exception) { null }
        }
    }
}
