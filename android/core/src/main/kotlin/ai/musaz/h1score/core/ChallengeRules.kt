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
    fun enforceBelt(buckled: Boolean?): ChallengeSession =
        if (buckled == false && phase in listOf(Phase.RESERVED, Phase.RECORDING))
            copy(phase = Phase.DISQUALIFIED) else this
    fun changeDestination(next: String) = if (next != destinationId) copy(phase = Phase.CANCELLED) else this
}

data class Sample(val atMs: Long, val speedMps: Double?, val limitKph: Int?)
data class KmBonus(val kilometer: Int, val distanceM: Double, val validDistanceM: Double,
                   val speedPoints: Double, val accelerationPoints: Double) {
    val points get() = speedPoints + accelerationPoints
}
data class ScoreReport(
    val score: Int?, val compliance: Double, val smoothness: Double,
    val validDistanceM: Double, val coverage: Double, val valid: Boolean,
    val accelerationEvents: Int, val overLimitDistanceM: Double,
    val speedPoints: Double, val accelerationPoints: Double,
    val kilometers: List<KmBonus>,
    val brakingEvents: Int = 0, val turns: List<TurnEvent> = emptyList(),
) {
    val earnedPoints get() = speedPoints + accelerationPoints
    val turnPenalty get() = turns.sumOf { it.penalty }.coerceAtMost(10.0)
    val totalPoints get() = (ScoreEngine.START_POINTS + earnedPoints - turnPenalty).coerceIn(0.0,100.0)
}

/** v0.2: distance-earned bonus; coefficients are prototype policies, not risk estimates. */
object ScoreEngine {
    const val START_POINTS = 60.0
    const val SPEED_PER_KM = 2.5
    const val ACCELERATION_PER_KM = 1.5
    const val MAX_DISTANCE_M = 10_000.0
    fun evaluate(samples: List<Sample>, turns: List<TurnEvent> = emptyList()): ScoreReport {
        val bins = Array(10) { DoubleArray(4) } // travelled, valid, speed bonus, acceleration bonus
        var expectedSeconds = 0.0; var validSeconds = 0.0; var travelled = 0.0
        var validDistance = 0.0; var overDistance = 0.0; var compliantDistance = 0.0
        var smoothDistance = 0.0; var accelerationEvents = 0; var wasAccelerating = false
        var brakingEvents=0; var wasBraking=false; var scoredUntil=samples.firstOrNull()?.atMs ?: 0L
        fun add(distance: Double, valid: Boolean, compliant: Boolean, smooth: Boolean) {
            var left = min(distance, MAX_DISTANCE_M - travelled).coerceAtLeast(0.0)
            while (left > 1e-8 && travelled < MAX_DISTANCE_M - 1e-8) {
                val index = (travelled / 1000).toInt().coerceIn(0,9)
                val chunk = min(left, (index + 1) * 1000.0 - travelled)
                bins[index][0] += chunk
                if (valid) {
                    validDistance += chunk; bins[index][1] += chunk
                    if (compliant) {
                        compliantDistance += chunk; bins[index][2] += chunk / 1000 * SPEED_PER_KM
                        if (smooth) { smoothDistance += chunk; bins[index][3] += chunk / 1000 * ACCELERATION_PER_KM }
                    } else overDistance += chunk
                }
                travelled += chunk; left -= chunk
            }
        }
        for ((a,b) in samples.zipWithNext()) {
            if (travelled >= MAX_DISTANCE_M - 1e-8) break
            val dt = (b.atMs - a.atMs) / 1000.0
            if (dt <= 0) continue
            val v0=a.speedMps; val v1=b.speedMps; val limit=b.limitKph
            if (dt > 2.5 || v0 == null || v1 == null || !v0.isFinite() || !v1.isFinite() ||
                v0 !in 0.0..80.0 || v1 !in 0.0..80.0) {
                expectedSeconds += dt; wasAccelerating=false; wasBraking=false; continue
            }
            val distance=(v0+v1)/2*dt
            val usedFraction=if(distance > 0) min(1.0,(MAX_DISTANCE_M-travelled)/distance) else 1.0
            scoredUntil=a.atMs+((b.atMs-a.atMs)*usedFraction).toLong()
            expectedSeconds += dt*usedFraction
            if (limit == null || limit !in 1..150) {
                add(distance,false,false,false);wasAccelerating=false;wasBraking=false;continue
            }
            validSeconds += dt*usedFraction
            val accelerationRate=(v1-v0)/dt
            val rapid=accelerationRate > 2.5
            val braking=accelerationRate < -2.5
            if(braking && !wasBraking) brakingEvents++
            wasBraking=braking
            val smooth=!rapid && !braking
            if (rapid && !wasAccelerating) accelerationEvents++
            wasAccelerating=rapid
            val maxSpeed=limit/3.6
            // Linear interpolation splits an interval at a limit crossing before kilometre allocation.
            if ((v0 <= maxSpeed) != (v1 <= maxSpeed) && v0 != v1) {
                val t=dt*(maxSpeed-v0)/(v1-v0)
                add((v0+maxSpeed)/2*t,true,v0<=maxSpeed,smooth)
                add((maxSpeed+v1)/2*(dt-t),true,v1<=maxSpeed,smooth)
            } else add(distance,true,v1<=maxSpeed,smooth)
        }
        val coverage=if(expectedSeconds>0) validSeconds/expectedSeconds else 0.0
        val kilometers=bins.mapIndexedNotNull { i,v -> if(v[0] <= 1e-8) null else
            KmBonus(i+1,v[0],v[1],v[2].coerceIn(0.0,SPEED_PER_KM),v[3].coerceIn(0.0,ACCELERATION_PER_KM)) }
        val speed=kilometers.sumOf{it.speedPoints}.coerceIn(0.0,25.0)
        val acceleration=kilometers.sumOf{it.accelerationPoints}.coerceIn(0.0,15.0)
        val scoredTurns=turns.filter { it.startedAtMs >= (samples.firstOrNull()?.atMs ?: 0L) && it.endedAtMs<=scoredUntil }
        val penalty=scoredTurns.sumOf { it.penalty }.coerceAtMost(10.0)
        val raw=(START_POINTS+speed+acceleration-penalty).coerceIn(0.0,100.0)
        val score=if(samples.isEmpty()) 60 else if(validDistance >= 500 && coverage >= .9)
            raw.roundToInt().coerceIn(0,if(raw>=100-1e-8)100 else 99) else null
        return ScoreReport(score,
            if(validDistance>0)compliantDistance/validDistance*100 else 0.0,
            if(validDistance>0)smoothDistance/validDistance*100 else 0.0,
            validDistance,coverage,validDistance>=9000-1e-8&&coverage>=.9,
            accelerationEvents,overDistance,speed,acceleration,kilometers,brakingEvents,scoredTurns)
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
