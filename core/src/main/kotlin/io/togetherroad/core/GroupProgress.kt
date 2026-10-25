package io.togetherroad.core

import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double)
data class GroupMember(
    val id: String, val name: String, val initial: String, val color: Int,
    val point: GeoPoint?, val remainingMeters: Double?, val totalMeters: Double?,
    val etaMinutes: Int?, val stale: Boolean = false,
    val avatarEmoji:String="🚗",val stateEmoji:String="🚗",val convoy:String=""
) {
    val progress: Double? get() = journeyProgress(remainingMeters, totalMeters)
}

/** Unknown distance stays unknown; ETA must never be converted into made-up distance. */
fun journeyProgress(remainingMeters: Double?, totalMeters: Double?): Double? {
    if (remainingMeters == null || totalMeters == null || !remainingMeters.isFinite() ||
        !totalMeters.isFinite() || totalMeters <= 0 || remainingMeters < 0) return null
    return (1.0 - remainingMeters / totalMeters).coerceIn(0.0, 1.0)
}

class GeoRoute(val points: List<GeoPoint>) {
    init { require(points.size >= 2) }
    private val lengths = points.zipWithNext { a,b -> distance(a,b) }
    val lengthMeters = lengths.sum()
    fun pointAt(progress: Double): GeoPoint {
        var remaining = lengthMeters * progress.coerceIn(0.0,1.0)
        for (i in lengths.indices) {
            if (remaining <= lengths[i] || i == lengths.lastIndex) {
                val t = if (lengths[i] == 0.0) 0.0 else (remaining/lengths[i]).coerceIn(0.0,1.0)
                return GeoPoint(points[i].latitude+(points[i+1].latitude-points[i].latitude)*t,
                    points[i].longitude+(points[i+1].longitude-points[i].longitude)*t)
            }
            remaining-=lengths[i]
        }
        return points.last()
    }
    private fun distance(a: GeoPoint,b: GeoPoint): Double {
        val lat = Math.toRadians(b.latitude-a.latitude); val lon=Math.toRadians(b.longitude-a.longitude)
        val h=sin(lat/2).pow(2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(lon/2).pow(2)
        return 6_371_000.0*2*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
}

