package ai.musaz.h1score.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

data class ChallengeRound(val name: String, val opensAt: Instant, val closesAt: Instant) {
    val closingLabel: String get() = clockFormat.format(closesAt)
    val hoursLabel: String get() = "${clockFormat.format(opensAt)}–$closingLabel"

    fun contains(time: Instant): Boolean = time >= opensAt && time < closesAt

    companion object {
        val seoul: ZoneId = ZoneId.of("Asia/Seoul")
        private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(seoul)
        private val slots = listOf(Triple("출근", 6, 9), Triple("점심", 11, 14), Triple("퇴근", 17, 20))

        fun currentOrNext(now: Instant): ChallengeRound {
            val today = now.atZone(seoul).toLocalDate()
            return sequenceOf(today, today.plusDays(1)).flatMap { date ->
                slots.asSequence().map { (name, start, end) ->
                    ChallengeRound(name, date.atTime(LocalTime.of(start, 0)).atZone(seoul).toInstant(),
                        date.atTime(LocalTime.of(end, 0)).atZone(seoul).toInstant())
                }
            }.first { now < it.closesAt }
        }
    }
}

data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun includes(destination: GeoPoint, radiusMeters: Double = 1_000.0): Boolean {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            destination.latitude !in -90.0..90.0 || destination.longitude !in -180.0..180.0) return false
        val lat = Math.toRadians(destination.latitude - latitude)
        val lon = Math.toRadians(destination.longitude - longitude)
        val a = sin(lat / 2).pow(2) + cos(Math.toRadians(latitude)) *
            cos(Math.toRadians(destination.latitude)) * sin(lon / 2).pow(2)
        return 12_742_000 * asin(sqrt(a.coerceIn(0.0, 1.0))) <= radiusMeters
    }
}
