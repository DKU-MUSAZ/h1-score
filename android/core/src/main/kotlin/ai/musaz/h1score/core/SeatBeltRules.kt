package ai.musaz.h1score.core

import kotlin.math.abs

enum class BeltGear { PARK, NEUTRAL, DRIVE, REVERSE }
enum class BeltSeat { DRIVER, PASSENGER }
enum class BeltVerdict { SAFE, PENDING, UNVERIFIABLE, DISQUALIFIED }

data class BeltObservation(
    val atMs: Long,
    val gear: BeltGear?,
    val gearAtMs: Long,
    val speedMps: Double?,
    val speedAtMs: Long,
    val driverBuckled: Boolean?,
    val driverAtMs: Long,
    val passengerOccupied: Boolean?,
    val occupancyAtMs: Long,
    val passengerBuckled: Boolean?,
    val passengerAtMs: Long,
)

data class BeltDecision(
    val verdict: BeltVerdict,
    val seat: BeltSeat? = null,
    val hadUnverifiedMotion: Boolean = false,
)

/** Independent trip-validity monitor. All timestamps use the same monotonic clock. */
class SeatBeltMonitor {
    var decision: BeltDecision = BeltDecision(BeltVerdict.SAFE)
        private set

    private var lastAtMs: Long? = null
    private var lastMoving = false
    private var hadUnverifiedMotion = false
    private var passengerRequired = false
    private var emptySinceMs: Long? = null
    private var emptySampleMs: Long? = null
    private var driverCandidate: Candidate? = null
    private var passengerCandidate: Candidate? = null
    private val gearClock = SampleClock()
    private val speedClock = SampleClock()
    private val driverClock = SampleClock()
    private val occupancyClock = SampleClock()
    private val passengerClock = SampleClock()

    fun accept(o: BeltObservation): BeltDecision {
        if (decision.verdict == BeltVerdict.DISQUALIFIED) return decision

        val rawMoving = o.speedMps?.let { it.isFinite() && abs(it) > MOVEMENT_MPS } == true
        val previousAt = lastAtMs
        if (o.atMs < 0 || (previousAt != null && o.atMs < previousAt)) {
            clearCandidates()
            clearEmptyWindow()
            if (lastMoving || rawMoving) hadUnverifiedMotion = true
            return publish(BeltVerdict.UNVERIFIABLE)
        }

        val gear = o.gear.takeIf { gearClock.accept(o.atMs, o.gearAtMs, SEAT_MAX_AGE_MS) }
        val speed = o.speedMps.takeIf {
            speedClock.accept(o.atMs, o.speedAtMs, SPEED_MAX_AGE_MS) && it?.isFinite() == true
        }
        val driver = o.driverBuckled.takeIf { driverClock.accept(o.atMs, o.driverAtMs, SEAT_MAX_AGE_MS) }
        val occupied = o.passengerOccupied.takeIf { occupancyClock.accept(o.atMs, o.occupancyAtMs, SEAT_MAX_AGE_MS) }
        val passenger = o.passengerBuckled.takeIf { passengerClock.accept(o.atMs, o.passengerAtMs, SEAT_MAX_AGE_MS) }
        val moving = speed?.let { abs(it) > MOVEMENT_MPS }
        val driveGear = gear == BeltGear.DRIVE || gear == BeltGear.REVERSE

        // Cached reads can conceal a short expiry between two genuinely new samples.
        val motionGap = gearClock.hadGap || speedClock.hadGap
        if (motionGap || driverClock.hadGap) driverCandidate = null
        if (motionGap || occupancyClock.hadGap || passengerClock.hadGap) passengerCandidate = null
        if (motionGap || occupancyClock.hadGap) clearEmptyWindow()
        val requiredSourceGap = motionGap || driverClock.hadGap || occupancyClock.hadGap ||
            ((passengerRequired || occupied == true) && passengerClock.hadGap)
        if (requiredSourceGap && (lastMoving || moving == true)) hadUnverifiedMotion = true

        // A missing interval cannot be used as continuous unbuckled or empty-seat evidence.
        if (previousAt != null && o.atMs - previousAt > SPEED_MAX_AGE_MS) {
            clearCandidates()
            clearEmptyWindow()
            if (lastMoving || moving == true) hadUnverifiedMotion = true
        }
        lastAtMs = o.atMs
        if (occupied == true) passengerRequired = true

        if (moving == false) {
            lastMoving = false
            clearCandidates()
            updateStoppedOccupancy(o.atMs, occupied, o.occupancyAtMs)
            return publish(BeltVerdict.SAFE)
        }
        clearEmptyWindow()

        if (moving == null || !driveGear) {
            clearCandidates()
            if (moving == true || lastMoving || driveGear || rawMoving) hadUnverifiedMotion = true
            if (moving != null) lastMoving = moving
            return publish(BeltVerdict.UNVERIFIABLE)
        }
        lastMoving = true

        val passengerKnown = occupied != null && !(passengerRequired && occupied == false)
        val passengerNeeded = passengerRequired || occupied == true
        val unknown = driver == null || !passengerKnown || (passengerNeeded && passenger == null)
        if (unknown) hadUnverifiedMotion = true

        driverCandidate = nextCandidate(driverCandidate, driver == false, o.atMs, o.driverAtMs)
        passengerCandidate = nextCandidate(
            passengerCandidate,
            passengerKnown && passengerNeeded && passenger == false,
            o.atMs,
            o.passengerAtMs,
        )

        if (driverCandidate?.confirmed == true) return publish(BeltVerdict.DISQUALIFIED, BeltSeat.DRIVER)
        if (passengerCandidate?.confirmed == true) return publish(BeltVerdict.DISQUALIFIED, BeltSeat.PASSENGER)
        if (unknown) return publish(BeltVerdict.UNVERIFIABLE)
        if (driverCandidate != null) return publish(BeltVerdict.PENDING, BeltSeat.DRIVER)
        if (passengerCandidate != null) return publish(BeltVerdict.PENDING, BeltSeat.PASSENGER)
        return publish(BeltVerdict.SAFE)
    }

    fun reset() {
        lastAtMs = null
        lastMoving = false
        hadUnverifiedMotion = false
        passengerRequired = false
        clearCandidates()
        clearEmptyWindow()
        listOf(gearClock, speedClock, driverClock, occupancyClock, passengerClock).forEach { it.reset() }
        decision = BeltDecision(BeltVerdict.SAFE)
    }

    private fun publish(verdict: BeltVerdict, seat: BeltSeat? = null): BeltDecision {
        decision = BeltDecision(verdict, seat, hadUnverifiedMotion)
        return decision
    }

    private fun clearCandidates() {
        driverCandidate = null
        passengerCandidate = null
    }

    private fun clearEmptyWindow() {
        emptySinceMs = null
        emptySampleMs = null
    }

    private fun updateStoppedOccupancy(atMs: Long, occupied: Boolean?, sampleMs: Long) {
        if (!passengerRequired || occupied != false) {
            clearEmptyWindow()
            return
        }
        val since = emptySinceMs
        if (since == null) {
            emptySinceMs = atMs
            emptySampleMs = sampleMs
        } else if (sampleMs > emptySampleMs!! && sampleMs - since >= EMPTY_RELEASE_MS) {
            passengerRequired = false
            clearEmptyWindow()
        } else if (sampleMs > emptySampleMs!!) {
            emptySampleMs = sampleMs
        }
    }

    private fun nextCandidate(previous: Candidate?, unbuckled: Boolean, atMs: Long, sampleMs: Long): Candidate? {
        if (!unbuckled) return null
        if (previous == null) return Candidate(atMs, sampleMs)
        if (sampleMs <= previous.lastSampleMs) return previous
        // Confirmation must be supported by a newer buckle observation spanning the window.
        return previous.copy(lastSampleMs = sampleMs, confirmed = sampleMs - previous.startedAtMs >= CONFIRM_MS)
    }

    private data class Candidate(val startedAtMs: Long, val lastSampleMs: Long, val confirmed: Boolean = false)

    private class SampleClock {
        private var newestMs: Long? = null
        var hadGap: Boolean = false
            private set

        fun accept(atMs: Long, sampleMs: Long, maxAgeMs: Long): Boolean {
            hadGap = false
            if (sampleMs < 0 || sampleMs > atMs || atMs - sampleMs > maxAgeMs) return false
            if (newestMs?.let { sampleMs < it } == true) return false
            hadGap = newestMs?.let { sampleMs - it > maxAgeMs } == true
            newestMs = sampleMs
            return true
        }

        fun reset() {
            newestMs = null
            hadGap = false
        }
    }

    private companion object {
        const val MOVEMENT_MPS = 0.1
        const val SPEED_MAX_AGE_MS = 500L
        const val SEAT_MAX_AGE_MS = 2500L
        const val CONFIRM_MS = 500L
        const val EMPTY_RELEASE_MS = 2000L
    }
}
