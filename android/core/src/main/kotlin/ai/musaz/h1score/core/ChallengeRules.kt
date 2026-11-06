package ai.musaz.h1score.core

import kotlin.math.max
import kotlin.math.roundToInt

enum class Phase { IDLE, RESERVED, RECORDING, AWAITING_PARK, FINISHED, CANCELLED }

data class ChallengeSession(
    val id: String,
    val destinationId: String,
    val initialDistanceM: Double,
    val phase: Phase = Phase.IDLE,
    val startCount: Int = 0,
) {
    val rankingEligible: Boolean get() = initialDistanceM >= 10_000
    fun accept(parked: Boolean): ChallengeSession = if (!parked || phase != Phase.IDLE) this else
        copy(phase = if (rankingEligible) Phase.RESERVED else Phase.RECORDING,
            startCount = if (rankingEligible) 0 else 1)

    fun update(remainingM: Double, arrived: Boolean, parked: Boolean): ChallengeSession {
        if (phase in listOf(Phase.IDLE, Phase.CANCELLED, Phase.FINISHED)) return this
        if (arrived) return copy(phase = if (parked) Phase.FINISHED else Phase.AWAITING_PARK)
        if (phase == Phase.RESERVED && remainingM.isFinite() && remainingM in 0.0..10_000.0)
            return copy(phase = Phase.RECORDING, startCount = startCount + 1)
        return this
    }
    fun changeDestination(next: String) = if (next != destinationId) copy(phase = Phase.CANCELLED) else this
}

data class Sample(val atMs: Long, val speedMps: Double?, val limitKph: Int?)
data class ScoreReport(
    val score: Int?, val compliance: Double, val smoothness: Double,
    val validDistanceM: Double, val coverage: Double, val valid: Boolean,
    val accelerationEvents: Int, val overLimitDistanceM: Double,
)

/** Prototype v0.1. These coefficients are hypotheses, not validated risk estimates. */
object ScoreEngine {
    fun evaluate(samples: List<Sample>): ScoreReport {
        var expectedMs = 0.0; var validMs = 0.0; var distance = 0.0
        var overspeedPenalty = 0.0; var overDistance = 0.0; var accelerationDistance = 0.0
        var accelerationEvents = 0; var wasAccelerating = false
        samples.zipWithNext().forEach { (a, b) ->
            val dt = (b.atMs - a.atMs) / 1000.0
            if (dt <= 0) return@forEach
            expectedMs += dt
            val v0 = a.speedMps; val v1 = b.speedMps; val limit = b.limitKph
            if (dt > 2.5 || v0 == null || v1 == null || !v0.isFinite() || !v1.isFinite() ||
                v0 !in 0.0..80.0 || v1 !in 0.0..80.0 || limit == null || limit !in 1..150) {
                wasAccelerating = false; return@forEach
            }
            validMs += dt
            val segment = (v0 + v1) / 2 * dt
            distance += segment
            val excess = max(0.0, v1 * 3.6 - limit)
            if (excess > 0) overDistance += segment
            overspeedPenalty += segment * (excess / 15.0).coerceIn(0.0, 1.0)
            val rapid = (v1 - v0) / dt > 2.5
            if (rapid) {
                accelerationDistance += segment
                if (!wasAccelerating) accelerationEvents++
            }
            wasAccelerating = rapid
        }
        val coverage = if (expectedMs > 0) validMs / expectedMs else 0.0
        val compliance = if (distance > 0) (100 - overspeedPenalty / distance * 100).coerceIn(0.0,100.0) else 0.0
        val smoothness = if (distance > 0) (100 - accelerationDistance / distance * 100).coerceIn(0.0,100.0) else 0.0
        val score = if (distance >= 500 && coverage >= .9) (compliance * .7 + smoothness * .3).roundToInt() else null
        return ScoreReport(score, compliance, smoothness, distance, coverage,
            distance >= 9_000 && coverage >= .9, accelerationEvents, overDistance)
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
