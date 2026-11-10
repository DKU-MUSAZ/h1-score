package ai.musaz.h1score.core

import kotlin.math.*

data class ChallengeVenue(val id: String, val center: GeoPoint)
data class VenueMatch(val venue: ChallengeVenue, val distanceMeters: Double)

fun GeoPoint.distanceMetersTo(other: GeoPoint): Double {
    if (!latitude.isFinite() || !longitude.isFinite() || !other.latitude.isFinite() || !other.longitude.isFinite() ||
        latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
        other.latitude !in -90.0..90.0 || other.longitude !in -180.0..180.0) return Double.POSITIVE_INFINITY
    val lat = Math.toRadians(other.latitude - latitude)
    val lon = Math.toRadians(other.longitude - longitude)
    val a = sin(lat / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) * sin(lon / 2).pow(2)
    return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Destination, not current vehicle location. Exact ties use stable venue ID. */
fun nearestEligibleVenue(destination: GeoPoint?, venues: List<ChallengeVenue>, radiusMeters: Double = 1_000.0): VenueMatch? {
    if (destination == null || !radiusMeters.isFinite() || radiusMeters < 0) return null
    return venues.map { VenueMatch(it, destination.distanceMetersTo(it.center)) }
        .filter { it.distanceMeters.isFinite() }
        .minWithOrNull(compareBy<VenueMatch> { it.distanceMeters }.thenBy { it.venue.id })
        ?.takeIf { it.distanceMeters <= radiusMeters }
}
