package ai.musaz.h1score.core

import java.time.DayOfWeek
import java.time.Instant
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
        private val weekdaySlots = listOf(
            Triple("새벽", 0, 7),
            Triple("출근", 7, 10),
            Triple("주간", 10, 17),
            Triple("퇴근", 17, 20),
            Triple("야간", 20, 24),
        )
        private val weekendSlots = listOf(
            Triple("주말 새벽", 0, 8),
            Triple("주말 주간", 8, 16),
            Triple("주말 야간", 16, 24),
        )

        fun currentOrNext(now: Instant): ChallengeRound {
            val local = now.atZone(seoul)
            val slots = when (local.dayOfWeek) {
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> weekendSlots
                else -> weekdaySlots
            }
            val (name, start, end) = slots.first { (_, _, end) -> local.hour < end }
            val midnight = local.toLocalDate().atStartOfDay()
            return ChallengeRound(
                name,
                midnight.plusHours(start.toLong()).atZone(seoul).toInstant(),
                midnight.plusHours(end.toLong()).atZone(seoul).toInstant(),
            )
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
