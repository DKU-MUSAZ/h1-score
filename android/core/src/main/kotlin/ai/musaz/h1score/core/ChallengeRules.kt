package ai.musaz.h1score.core

import kotlin.math.min
import kotlin.math.roundToInt

enum class Phase { IDLE, RESERVED, RECORDING, AWAITING_PARK, FINISHED, CANCELLED, DISQUALIFIED }

data class ChallengeSession(
    val id: String,
    val destinationId: String,
    val initialDistanceM: Double,
    val phase: Phase = Phase.IDLE,
    val startCount: Int = 0,
    val venueId: String? = null,
    val competitionId: String? = null,
) {
    val rankingEligible: Boolean get() = initialDistanceM >= 10_000
    fun accept(parked: Boolean): ChallengeSession = if (!parked || phase != Phase.IDLE) this else
        copy(phase = if (rankingEligible) Phase.RESERVED else Phase.RECORDING,
            startCount = if (rankingEligible) 0 else 1)

    fun update(remainingM: Double, arrived: Boolean, parked: Boolean): ChallengeSession {
        if (phase in listOf(Phase.IDLE, Phase.CANCELLED, Phase.FINISHED, Phase.DISQUALIFIED)) return this
        if (arrived) return copy(phase = if (parked) Phase.FINISHED else Phase.AWAITING_PARK)
        if (phase == Phase.RESERVED && remainingM.isFinite() && remainingM in 0.0..10_000.0)
            return copy(phase = Phase.RECORDING, startCount = startCount + 1)
        return this
    }
    fun enforceBelt(decision: BeltDecision): ChallengeSession =
        if (decision.verdict == BeltVerdict.DISQUALIFIED &&
            phase in listOf(Phase.RESERVED, Phase.RECORDING, Phase.AWAITING_PARK))
            copy(phase = Phase.DISQUALIFIED) else this
    fun changeDestination(next: String) = if (next != destinationId) copy(phase = Phase.CANCELLED) else this
}

data class Sample(
    val atMs: Long,
    val speedMps: Double?,
    val limitKph: Int?,
    /** Source timestamp, not the time at which the app polled or received this sample. */
    val speedTimestampNanos: Long = atMs * 1_000_000L,
    val brakePressed: Boolean? = null,
    val brakeObservedAtMs: Long? = if (brakePressed != null) atMs else null,
)
data class KmBonus(val kilometer: Int, val distanceM: Double, val validDistanceM: Double,
                   val speedPoints: Double, val accelerationPoints: Double) {
    val points get() = speedPoints + accelerationPoints
}
enum class DrivingEventType { OVERSPEED, RAPID_ACCELERATION, RAPID_DECELERATION }
/** Approximate position within valid sampled distance; not a GPS location. */
data class DrivingEvent(
    val type: DrivingEventType, val elapsedMs: Long, val distanceM: Double,
    val fromKph: Double, val toKph: Double, val limitKph: Int,
)
data class ScoreReport(
    val score: Int?, val compliance: Double, val smoothness: Double,
    val validDistanceM: Double, val coverage: Double, val valid: Boolean,
    val accelerationEvents: Int, val overLimitDistanceM: Double,
    val events: List<DrivingEvent> = emptyList(),
    val speedPoints: Double = 0.0, val accelerationPoints: Double = 0.0,
    val kilometers: List<KmBonus> = emptyList(),
    val brakingEvents: Int = 0, val turns: List<TurnEvent> = emptyList(),
    val longitudinalEvents: List<LongitudinalEvent> = emptyList(),
    val longitudinalCoverage: Double = 0.0,
    val longitudinalReady: Boolean = false,
    val longitudinalSampleRateHz: Double = 0.0,
    val scoringVersion: String = LEGACY_SCORING_VERSION,
) {
    // Braking is metadata on a deceleration event, never a second scored event.
    val decelerationEvents get() = brakingEvents
    val earnedPoints get() = speedPoints + accelerationPoints
    val turnPenalty get() = turns.sumOf { it.penalty }.coerceAtMost(10.0)
    val totalPoints get() = (ScoreEngine.START_POINTS + earnedPoints - turnPenalty).coerceIn(0.0,100.0)
}

const val LEGACY_SCORING_VERSION = "speed70-accel30-v1"

/** Prototype distance bonuses; only confirmed longitudinal events exclude smooth bonus. */
object ScoreEngine {
    const val VERSION = "distance-bonus-longitudinal-v2-turn-v1"
    const val START_POINTS = 60.0
    const val SPEED_PER_KM = 2.5
    const val ACCELERATION_PER_KM = 1.5
    const val MAX_DISTANCE_M = 10_000.0
    private data class Segment(
        val index: Int, val v0: Double, val v1: Double, val seconds: Double,
        val distance: Double, val limit: Int?,
    )

    fun evaluate(samples: List<Sample>, turns: List<TurnEvent> = emptyList()): ScoreReport {
        // Bound both event detection and scoring to the same first 10 km. In particular, an
        // event that only qualifies after the boundary cannot remove earlier smooth bonus.
        val bounded = ArrayList<Sample>()
        val segments = ArrayList<Segment>()
        samples.firstOrNull()?.let { bounded += it }
        var travelled = 0.0
        var expectedSeconds = 0.0
        var validSeconds = 0.0
        var scoredUntil = samples.firstOrNull()?.atMs ?: 0L
        var highestTimestamp = samples.firstOrNull()?.speedTimestampNanos ?: Long.MIN_VALUE
        var previousTimestampValid = highestTimestamp >= 0
        for (index in 1 until samples.size) {
            if (travelled >= MAX_DISTANCE_M - 1e-8) break
            val a = samples[index - 1]; val b = samples[index]
            val nanos = b.speedTimestampNanos - a.speedTimestampNanos
            val nextTimestampValid = b.speedTimestampNanos >= 0 && b.speedTimestampNanos > highestTimestamp
            val pairTimestampValid = previousTimestampValid && nextTimestampValid
            val newlyObservedNanos = if (nextTimestampValid && highestTimestamp >= 0)
                b.speedTimestampNanos - highestTimestamp else 0L
            if (nextTimestampValid) highestTimestamp = b.speedTimestampNanos
            previousTimestampValid = nextTimestampValid
            if (!pairTimestampValid || nanos <= 0) {
                expectedSeconds += newlyObservedNanos / 1e9; bounded += b; continue
            }
            val dt = nanos / 1e9
            val v0 = a.speedMps; val v1 = b.speedMps
            if (dt > 2.5 || v0 == null || v1 == null || !v0.isFinite() || !v1.isFinite() ||
                v0 !in 0.0..80.0 || v1 !in 0.0..80.0) {
                expectedSeconds += dt; bounded += b; continue
            }
            val fullDistance = (v0 + v1) / 2 * dt
            val distance = min(fullDistance, MAX_DISTANCE_M - travelled).coerceAtLeast(0.0)
            var seconds = dt
            var endSpeed = v1
            var end = b
            if (distance < fullDistance) {
                // Find time at the distance boundary under the same linear speed model used
                // for distance and limit crossings; distance fraction is not time fraction.
                val rate = (v1 - v0) / dt
                var lo = 0.0; var hi = dt
                repeat(48) {
                    val middle = (lo + hi) / 2
                    if (v0 * middle + rate * middle * middle / 2 < distance) lo = middle else hi = middle
                }
                seconds = (lo + hi) / 2
                endSpeed = v0 + rate * seconds
                val endNs = a.speedTimestampNanos + (seconds * 1e9).toLong()
                end = b.copy(atMs = endNs / 1_000_000L, speedTimestampNanos = endNs,
                    // Clipping a coarse measurement must not manufacture a high-rate sample.
                    speedMps = endSpeed.takeIf { nanos <= LongitudinalRules.MAX_SAMPLE_GAP_NS })
            }
            bounded += end
            scoredUntil = end.atMs
            expectedSeconds += seconds
            if (b.limitKph?.let { it in 1..150 } == true) validSeconds += seconds
            segments += Segment(bounded.lastIndex, v0, endSpeed, seconds, distance, b.limitKph)
            travelled += distance
        }
        val longitudinal = LongitudinalDetector.evaluate(bounded)
        val byIndex = longitudinal.intervals.associateBy { it.sampleIndex }
        val bins = Array(10) { DoubleArray(4) } // travelled, valid, speed bonus, smooth bonus
        var allocated = 0.0
        var validDistance = 0.0
        var overDistance = 0.0
        var compliantDistance = 0.0
        var smoothDistance = 0.0
        fun add(distance: Double, valid: Boolean, compliant: Boolean, smooth: Boolean) {
            var left = min(distance, MAX_DISTANCE_M - allocated).coerceAtLeast(0.0)
            while (left > 1e-8 && allocated < MAX_DISTANCE_M - 1e-8) {
                val index = (allocated / 1000).toInt().coerceIn(0, 9)
                val chunk = min(left, (index + 1) * 1000.0 - allocated)
                bins[index][0] += chunk
                if (valid) {
                    validDistance += chunk; bins[index][1] += chunk
                    if (compliant) {
                        compliantDistance += chunk; bins[index][2] += chunk / 1000 * SPEED_PER_KM
                        if (smooth) { smoothDistance += chunk; bins[index][3] += chunk / 1000 * ACCELERATION_PER_KM }
                    } else overDistance += chunk
                }
                allocated += chunk; left -= chunk
            }
        }
        for (segment in segments) {
            val limit = segment.limit
            if (limit == null || limit !in 1..150) {
                add(segment.distance, false, false, false); continue
            }
            val interval = byIndex[segment.index]
            // An absent/coarse signal is unknown. The 300 ms gap tolerance alone must not
            // award smooth bonus to a feed that remains below the trial's effective 8 Hz.
            val smooth = longitudinal.sampleRateHz >= LongitudinalRules.MIN_SAMPLE_RATE_HZ &&
                interval?.assessable == true && !interval.abrupt
            val maxSpeed = limit / 3.6
            val excluded = interval?.abruptSpans.orEmpty()
            val wholeIntervalExcluded = excluded.size == 1 && interval != null &&
                excluded[0].startedAtNanos <= interval.startedAtNanos &&
                excluded[0].endedAtNanos >= interval.endedAtNanos
            if (excluded.isNotEmpty() && !wholeIntervalExcluded) {
                // A rolling window may start inside a raw interval. Split the same linear
                // distance model at that boundary, preserving bonus for the portion outside it.
                val observed = requireNotNull(interval)
                fun offset(nanos: Long): Double = when {
                    nanos <= observed.startedAtNanos -> 0.0
                    nanos >= observed.endedAtNanos -> segment.seconds
                    else -> ((nanos - observed.startedAtNanos) / 1e9).coerceIn(0.0, segment.seconds)
                }
                val ranges = excluded.map { offset(it.startedAtNanos) to offset(it.endedAtNanos) }
                val cuts = mutableListOf(0.0, segment.seconds)
                ranges.forEach { (start, end) -> cuts += start; cuts += end }
                if ((segment.v0 <= maxSpeed) != (segment.v1 <= maxSpeed) && segment.v0 != segment.v1)
                    cuts += segment.seconds * (maxSpeed - segment.v0) / (segment.v1 - segment.v0)
                fun speedAt(seconds: Double) = segment.v0 + (segment.v1 - segment.v0) * seconds / segment.seconds
                val times = cuts.distinct().sorted()
                var apportioned = 0.0
                for (index in 1 until times.size) {
                    val from = times[index - 1]; val to = times[index]
                    val middle = (from + to) / 2
                    val distance = if (index == times.lastIndex) (segment.distance - apportioned).coerceAtLeast(0.0)
                        else (speedAt(from) + speedAt(to)) / 2 * (to - from)
                    val excludedHere = ranges.any { (start, end) -> middle >= start && middle < end }
                    add(distance, true, speedAt(middle) <= maxSpeed,
                        longitudinal.sampleRateHz >= LongitudinalRules.MIN_SAMPLE_RATE_HZ &&
                            observed.assessable && !excludedHere)
                    apportioned += distance
                }
                continue
            }
            if ((segment.v0 <= maxSpeed) != (segment.v1 <= maxSpeed) && segment.v0 != segment.v1) {
                val time = segment.seconds * (maxSpeed - segment.v0) / (segment.v1 - segment.v0)
                val firstDistance = ((segment.v0 + maxSpeed) / 2 * time).coerceIn(0.0, segment.distance)
                add(firstDistance, true, segment.v0 <= maxSpeed, smooth)
                add(segment.distance - firstDistance, true, segment.v1 <= maxSpeed, smooth)
            } else add(segment.distance, true, segment.v1 <= maxSpeed, smooth)
        }
        val coverage = if (expectedSeconds > 0) (validSeconds / expectedSeconds).coerceIn(0.0, 1.0) else 0.0
        val kilometers = bins.mapIndexedNotNull { index, values -> if (values[0] <= 1e-8) null else
            KmBonus(index + 1, values[0], values[1], values[2].coerceIn(0.0, SPEED_PER_KM),
                values[3].coerceIn(0.0, ACCELERATION_PER_KM)) }
        val speed = kilometers.sumOf { it.speedPoints }.coerceIn(0.0, 25.0)
        val acceleration = kilometers.sumOf { it.accelerationPoints }.coerceIn(0.0, 15.0)
        val scoredTurns = turns.filter { it.startedAtMs >= (samples.firstOrNull()?.atMs ?: 0L) && it.endedAtMs <= scoredUntil }
        val penalty = scoredTurns.sumOf { it.penalty }.coerceAtMost(10.0)
        val raw = (START_POINTS + speed + acceleration - penalty).coerceIn(0.0, 100.0)
        val score = if (validDistance >= 500 && coverage >= .9 && longitudinal.ready)
            raw.roundToInt().coerceIn(0, if (raw >= 100 - 1e-8) 100 else 99) else null
        return ScoreReport(score,
            if (validDistance > 0) compliantDistance / validDistance * 100 else 0.0,
            if (validDistance > 0) smoothDistance / validDistance * 100 else 0.0,
            validDistance, coverage, validDistance >= 9000 - 1e-8 && coverage >= .9 && longitudinal.ready,
            longitudinal.events.count { it.kind == LongitudinalKind.ACCELERATION }, overDistance,
            historyEvents(bounded, segments, longitudinal.events), speed, acceleration, kilometers,
            longitudinal.events.count { it.kind == LongitudinalKind.DECELERATION }, scoredTurns,
            longitudinal.events, longitudinal.coverage, longitudinal.ready, longitudinal.sampleRateHz, VERSION)
    }
    /** Preserve the history UI's event model without changing or approximating the scoring totals. */
    private fun historyEvents(
        samples: List<Sample>, segments: List<Segment>, longitudinal: List<LongitudinalEvent>,
    ): List<DrivingEvent> {
        val origin = samples.firstOrNull()?.speedTimestampNanos ?: return emptyList()
        val result = mutableListOf<DrivingEvent>()
        var validDistance = 0.0
        var wasOverLimit = false
        var previousIndex = -1
        val distanceAtStart = mutableMapOf<Int, Double>()
        for (segment in segments) {
            distanceAtStart[segment.index] = validDistance
            val limit = segment.limit
            if (limit == null || limit !in 1..150) {
                wasOverLimit = false; previousIndex = segment.index; continue
            }
            if (segment.index != previousIndex + 1) wasOverLimit = false
            val maxSpeed = limit / 3.6
            if (maxOf(segment.v0, segment.v1) > maxSpeed && !wasOverLimit) {
                val seconds = if (segment.v0 > maxSpeed) 0.0 else
                    segment.seconds * (maxSpeed - segment.v0) / (segment.v1 - segment.v0)
                val startNs = samples[segment.index - 1].speedTimestampNanos
                val travelled = (segment.v0 + maxSpeed) / 2 * seconds
                result += DrivingEvent(DrivingEventType.OVERSPEED,
                    ((startNs - origin) / 1_000_000 + (seconds * 1000).toLong()).coerceAtLeast(0),
                    validDistance + travelled, segment.v0 * 3.6, maxOf(segment.v0, segment.v1) * 3.6, limit)
            }
            wasOverLimit = segment.v1 > maxSpeed
            validDistance += segment.distance
            previousIndex = segment.index
        }
        for (event in longitudinal) {
            // A merged event can contain recovery between confirmed spans. Show its first
            // confirmed span, not overall endpoints that could reverse the displayed change.
            val representative = event.qualifyingIntervals.firstOrNull() ?: continue
            val segment = segments.firstOrNull {
                representative.startedAtNanos >= samples[it.index - 1].speedTimestampNanos &&
                    representative.startedAtNanos < samples[it.index].speedTimestampNanos
            } ?: continue
            val limit = segment.limit?.takeIf { it in 1..150 } ?: continue
            val offset = ((representative.startedAtNanos - samples[segment.index - 1].speedTimestampNanos) / 1e9)
                .coerceIn(0.0, segment.seconds)
            val from = segment.v0 + (segment.v1 - segment.v0) * offset / segment.seconds
            val endSegment = segments.lastOrNull {
                representative.endedAtNanos > samples[it.index - 1].speedTimestampNanos &&
                    representative.endedAtNanos <= samples[it.index].speedTimestampNanos
            } ?: segment
            val endOffset = ((representative.endedAtNanos - samples[endSegment.index - 1].speedTimestampNanos) / 1e9)
                .coerceIn(0.0, endSegment.seconds)
            val to = endSegment.v0 + (endSegment.v1 - endSegment.v0) * endOffset / endSegment.seconds
            result += DrivingEvent(
                if (event.kind == LongitudinalKind.ACCELERATION) DrivingEventType.RAPID_ACCELERATION
                else DrivingEventType.RAPID_DECELERATION,
                ((representative.startedAtNanos - origin) / 1_000_000).coerceAtLeast(0),
                requireNotNull(distanceAtStart[segment.index]) + (segment.v0 + from) / 2 * offset,
                from * 3.6, to * 3.6, limit)
        }
        return result.sortedBy { it.elapsedMs }.take(100)
    }

}

data class Entry(val id: String, val score: Int, val valid: Boolean = true)
data class RankedEntry(val id: String, val score: Int, val rank: Int, val miles: Int)

object Settlement {
    fun rank(entries: List<Entry>): List<RankedEntry> {
        val sorted = entries.filter { it.valid }.distinctBy { it.id }.sortedByDescending { it.score }
        require(sorted.size <= 5) { "A prototype comparison group has at most five entries" }
        val slots = when (sorted.size) {
            3 -> listOf(60,20,0)
            4 -> listOf(80,40,20,0)
            5 -> listOf(100,60,30,0,0)
            else -> List(sorted.size) { 0 }
        }
        var offset = 0
        return sorted.groupBy { it.score }.flatMap { (score, tied) ->
            val rank = offset + 1
            val miles = if (score >= 85) slots.drop(offset).take(tied.size).sum() / tied.size else 0
            offset += tied.size
            tied.map { RankedEntry(it.id,it.score,rank,miles) }
        }
    }
}

class MileageLedger(initialBalance: Int = 0, initialIds: Set<String> = emptySet(), initialDailyKeys: Set<String> = emptySet()) {
    private val settled = initialIds.toMutableSet()
    private val daily = initialDailyKeys.toMutableSet()
    var balance = initialBalance; private set
    val settlementIds: Set<String> get() = settled.toSet()
    val dailyKeys: Set<String> get() = daily.toSet()
    fun spend(cost: Int): Boolean {
        require(cost > 0)
        if (balance < cost) return false
        balance -= cost
        return true
    }
    fun creditBadge(id: String, miles: Int): Int {
        require(miles > 0)
        if (!settled.add("badge:$id")) return 0
        balance += miles
        return miles
    }

    fun credit(id: String, miles: Int, destination: String = id, date: String = "demo"): Int {
        require(miles >= 0)
        if (!settled.add(id)) return 0
        if(miles == 0) return 0
        val key="$date|$destination"
        if(key in daily || daily.count { it.startsWith("$date|") } >=2) return 0
        daily.add(key)
        balance += miles
        return miles
    }
}
