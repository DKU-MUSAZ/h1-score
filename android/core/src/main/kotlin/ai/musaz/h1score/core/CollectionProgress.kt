package ai.musaz.h1score.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

enum class SpotTour(val id: String, val city: String, val location: GeoPoint) {
    SEOUL("spot-seoul", "서울", GeoPoint(37.52128, 127.03439)),
    GOYANG("spot-goyang", "고양", GeoPoint(37.66626, 126.74770)),
    BUSAN("spot-busan", "부산", GeoPoint(35.17671, 129.11512));

    val destinationName get() = "현대 모터스튜디오 $city"

    companion object {
        const val REQUIRED_VISITS = 3
    }

    fun matches(name: String?, point: GeoPoint?): Boolean =
        point?.let { location.includes(it, radiusMeters = 150.0) }
            ?: (name?.filter(Char::isLetterOrDigit) == destinationName.filter(Char::isLetterOrDigit))
}

data class CollectionProgress(
    val scoresByDay: Map<LocalDate, Int> = emptyMap(),
    val visitsBySpot: Map<SpotTour, Set<LocalDate>> = emptyMap()
) {
    private fun monday(day: LocalDate) = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val challengeDays get() = scoresByDay.size
    fun scoreDays(minimum: Int) = scoresByDay.values.count { it >= minimum }
    val bestWeekDays get() = scoresByDay.keys.groupingBy(::monday).eachCount().values.maxOrNull() ?: 0
    val challengeWeeks get() = scoresByDay.keys.map(::monday).toSet().size
    fun scoreWeeks(minimum: Int) = scoresByDay.asSequence().filter { it.value >= minimum }
        .map { monday(it.key) }.toSet().size

    fun weeklyScoreAverage(today: LocalDate): Int? {
        val start = monday(today)
        val scores = scoresByDay.filterKeys { it >= start && it <= today }.values
        return if (scores.isEmpty()) null else scores.average().roundToInt()
    }

    fun visits(spot: SpotTour) = visitsBySpot[spot].orEmpty().size

    fun recordChallenge(day: LocalDate, score: Int): CollectionProgress {
        if (score !in 0..100 || score <= (scoresByDay[day] ?: -1)) return this
        return copy(scoresByDay = scoresByDay + (day to score))
    }

    fun recordVisit(spot: SpotTour, day: LocalDate): CollectionProgress {
        val days = visitsBySpot[spot].orEmpty()
        if (day in days || days.size >= SpotTour.REQUIRED_VISITS) return this
        return copy(visitsBySpot = visitsBySpot + (spot to (days + day)))
    }
}
