// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.gps

import kotlin.math.cos
import kotlin.math.sqrt

/** GPS tab math, without Android (tested on the JVM). */
object GpsMath {
    private const val EARTH_RADIUS_M = 6_378_137.0

    /** A fix from sensor_msgs/NavSatFix. `accuracyM` < 0 = no known covariance. */
    data class Fix(val latitude: Double, val longitude: Double, val altitude: Double, val status: Int, val accuracyM: Double)

    /** NavSatStatus.status values: -1 no fix, 0 fix, 1 SBAS, 2 GBAS (RTK). */
    const val STATUS_NO_FIX = -1

    /**
     * Fix from an already parsed NavSatFix message (MsgValue), or null if it has no valid coordinates (NaN, out of
     * range or no fix: many drivers publish 0,0 or NaN while there are no satellites).
     */
    fun fixOf(msg: Map<String, Any?>): Fix? {
        val lat = (msg["latitude"] as? Number)?.toDouble() ?: return null
        val lon = (msg["longitude"] as? Number)?.toDouble() ?: return null
        val alt = (msg["altitude"] as? Number)?.toDouble() ?: Double.NaN
        val status = ((msg["status"] as? Map<*, *>)?.get("status") as? Number)?.toInt() ?: 0
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (status == STATUS_NO_FIX) return null
        val cov = msg["position_covariance"] as? List<*>
        val covType = (msg["position_covariance_type"] as? Number)?.toInt() ?: 0
        return Fix(lat, lon, alt, status, if (covType == 0 || cov == null) -1.0 else accuracy(cov.map { (it as? Number)?.toDouble() ?: 0.0 }))
    }

    /** Horizontal accuracy radius (m): the larger of the east and north standard deviations. */
    fun accuracy(covariance: List<Double>): Double {
        if (covariance.size < 5) return -1.0
        val v = maxOf(covariance[0], covariance[4])
        return if (v.isFinite() && v > 0) sqrt(v) else -1.0
    }

    /** Point `east`/`north` meters from (lat0, lon0) (local approximation, valid for a few km). */
    fun offset(lat0: Double, lon0: Double, east: Double, north: Double): Pair<Double, Double> {
        val dLat = Math.toDegrees(north / EARTH_RADIUS_M)
        val dLon = Math.toDegrees(east / (EARTH_RADIUS_M * cos(Math.toRadians(lat0))))
        return (lat0 + dLat) to (lon0 + dLon)
    }

    /** Approximate distance (m) between two nearby points. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val n = Math.toRadians(lat2 - lat1) * EARTH_RADIUS_M
        val e = Math.toRadians(lon2 - lon1) * EARTH_RADIUS_M * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(n * n + e * e)
    }
}
