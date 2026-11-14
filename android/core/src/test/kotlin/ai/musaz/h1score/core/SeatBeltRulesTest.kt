package ai.musaz.h1score.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeatBeltRulesTest {
    private fun observation(
        at: Long,
        gear: BeltGear? = BeltGear.DRIVE,
        speed: Double? = 1.0,
        driver: Boolean? = true,
        occupied: Boolean? = false,
        passenger: Boolean? = null,
    ) = BeltObservation(at, gear, at, speed, at, driver, at, occupied, at, passenger, at)

    @Test fun parkedAndStoppedUnbuckledDoNotStartViolation() {
        val monitor = SeatBeltMonitor()
        listOf(BeltGear.PARK, BeltGear.NEUTRAL, BeltGear.DRIVE, BeltGear.REVERSE).forEachIndexed { i, gear ->
            assertEquals(BeltVerdict.SAFE, monitor.accept(observation(i * 250L, gear, 0.0, false)).verdict)
        }
        assertFalse(monitor.decision.hadUnverifiedMotion)
    }

    @Test fun departureAlreadyUnbuckledRequiresHalfSecondAndDistinctSamples() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(0, speed = 0.0, driver = false))
        assertEquals(BeltVerdict.PENDING, monitor.accept(observation(250, driver = false)).verdict)
        assertEquals(BeltVerdict.PENDING, monitor.accept(observation(749, driver = false)).verdict)
        assertEquals(BeltDecision(BeltVerdict.DISQUALIFIED, BeltSeat.DRIVER), monitor.accept(observation(750, driver = false)))
    }

    @Test fun exactlyTwoSamplesFiveHundredMillisecondsApartConfirm() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(0, driver = false))
        assertEquals(BeltVerdict.DISQUALIFIED, monitor.accept(observation(500, driver = false)).verdict)
    }

    @Test fun cachedBuckleDoesNotConfirmEvenWithFreshOtherSignals() {
        val monitor = SeatBeltMonitor()
        for (time in 0L..1500L step 250) {
            assertEquals(BeltVerdict.PENDING, monitor.accept(observation(time, driver = false).copy(driverAtMs = 0)).verdict)
        }
        assertEquals(BeltVerdict.DISQUALIFIED, monitor.accept(observation(1750, driver = false)).verdict)
    }

    @Test fun sampleReceivedSoonAfterFirstCannotConfirmByLaterCachedRepetition() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(0, driver = false))
        monitor.accept(observation(100, driver = false))
        assertEquals(BeltVerdict.PENDING, monitor.accept(observation(500, driver = false).copy(driverAtMs = 100)).verdict)
        assertEquals(BeltVerdict.DISQUALIFIED, monitor.accept(observation(600, driver = false)).verdict)
    }

    @Test fun signedReverseAndPositiveReverseBothCountAsMovement() {
        listOf(-0.10001, 0.10001).forEach { speed ->
            val monitor = SeatBeltMonitor()
            monitor.accept(observation(0, BeltGear.REVERSE, speed, false))
            assertEquals(BeltVerdict.DISQUALIFIED, monitor.accept(observation(500, BeltGear.REVERSE, speed, false)).verdict)
        }
    }

    @Test fun movementThresholdIsStrictInBothDirections() {
        listOf(-0.1, 0.0, 0.1).forEach { speed ->
            val monitor = SeatBeltMonitor()
            assertEquals(BeltVerdict.SAFE, monitor.accept(observation(0, speed = speed, driver = false)).verdict)
        }
    }

    @Test fun parkAndNeutralMotionAreUnverifiableNotDisqualified() {
        listOf(BeltGear.PARK, BeltGear.NEUTRAL).forEach { gear ->
            val monitor = SeatBeltMonitor()
            assertEquals(BeltDecision(BeltVerdict.UNVERIFIABLE, hadUnverifiedMotion = true), monitor.accept(observation(0, gear, driver = false)))
            assertEquals(BeltVerdict.UNVERIFIABLE, monitor.accept(observation(500, gear, driver = false)).verdict)
        }
    }

    @Test fun freshEmptyPassengerDoesNotNeedBuckleButDriverAlwaysDoes() {
        val monitor = SeatBeltMonitor()
        assertEquals(BeltVerdict.SAFE, monitor.accept(observation(0)).verdict)
        monitor.accept(observation(250, driver = false))
        assertEquals(BeltSeat.DRIVER, monitor.accept(observation(750, driver = false)).seat)
    }

    @Test fun occupiedPassengerUnbuckledConfirmsPassengerViolation() {
        val monitor = SeatBeltMonitor()
        assertEquals(BeltDecision(BeltVerdict.PENDING, BeltSeat.PASSENGER), monitor.accept(observation(0, occupied = true, passenger = false)))
        assertEquals(BeltDecision(BeltVerdict.DISQUALIFIED, BeltSeat.PASSENGER), monitor.accept(observation(500, occupied = true, passenger = false)))
    }

    @Test fun occupiedPassengerCachedBuckleCannotConfirm() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(0, occupied = true, passenger = false))
        assertEquals(BeltVerdict.PENDING, monitor.accept(observation(500, occupied = true, passenger = false).copy(passengerAtMs = 0)).verdict)
    }

    @Test fun unknownOccupancyIsUnverifiableEvenWithFastenedPassenger() {
        val monitor = SeatBeltMonitor()
        assertEquals(BeltDecision(BeltVerdict.UNVERIFIABLE, hadUnverifiedMotion = true), monitor.accept(observation(0, occupied = null, passenger = true)))
    }

    @Test fun occupancyCannotReleasePassengerDuringMovement() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(0, occupied = true, passenger = true))
        assertEquals(BeltVerdict.UNVERIFIABLE, monitor.accept(observation(250, occupied = false, passenger = false)).verdict)
        assertEquals(BeltVerdict.UNVERIFIABLE, monitor.accept(observation(750, occupied = false, passenger = false)).verdict)
        assertTrue(monitor.decision.hadUnverifiedMotion)
        assertEquals(BeltVerdict.PENDING, monitor.accept(observation(1000, occupied = true, passenger = false)).verdict)
    }

    @Test fun stableEmptyPassengerWhileStoppedReleasesAfterTwoSeconds() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(0, occupied = true, passenger = true))
        for (time in 250L..2250L step 250) monitor.accept(observation(time, speed = 0.0))
        assertEquals(BeltDecision(BeltVerdict.SAFE), monitor.accept(observation(2500)))
    }

    @Test fun shortEmptyStopDoesNotReleasePassenger() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(0, occupied = true, passenger = true))
        for (time in 250L..2000L step 250) monitor.accept(observation(time, speed = 0.0))
        assertEquals(BeltVerdict.UNVERIFIABLE, monitor.accept(observation(2250)).verdict)
    }

    @Test fun repeatedEmptyObservationCannotReleasePassenger() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(0, occupied = true, passenger = true))
        for (time in 250L..2250L step 250) monitor.accept(observation(time, speed = 0.0).copy(occupancyAtMs = 250))
        assertEquals(BeltVerdict.UNVERIFIABLE, monitor.accept(observation(2500)).verdict)
    }

    @Test fun stopFasteningOrUnknownBuckleRestartsConfirmation() {
        listOf<(BeltObservation) -> BeltObservation>(
            { it.copy(speedMps = 0.0) }, { it.copy(driverBuckled = true) }, { it.copy(driverBuckled = null) },
        ).forEach { interrupt ->
            val monitor = SeatBeltMonitor()
            monitor.accept(observation(0, driver = false))
            monitor.accept(interrupt(observation(250, driver = false)))
            assertEquals(BeltVerdict.PENDING, monitor.accept(observation(500, driver = false)).verdict)
            assertEquals(BeltVerdict.PENDING, monitor.accept(observation(750, driver = false)).verdict)
            assertEquals(BeltVerdict.DISQUALIFIED, monitor.accept(observation(1000, driver = false)).verdict)
        }
    }

    @Test fun observationGapCannotConfirmViolationAcrossMissingInterval() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(0, driver = false))
        val afterGap = monitor.accept(observation(501, driver = false))
        assertEquals(BeltVerdict.PENDING, afterGap.verdict)
        assertTrue(afterGap.hadUnverifiedMotion)
        assertEquals(BeltVerdict.DISQUALIFIED, monitor.accept(observation(1001, driver = false)).verdict)
    }

    @Test fun freshSampleAfterSourceGapCannotBridgeExpiredBuckleEvidence() {
        val monitor = SeatBeltMonitor()
        for (time in 1000L..3500L step 250) {
            monitor.accept(observation(time, driver = false).copy(driverAtMs = 1000))
        }
        val afterGap = monitor.accept(observation(3750, driver = false))
        assertEquals(BeltVerdict.PENDING, afterGap.verdict)
        assertTrue(afterGap.hadUnverifiedMotion)
        assertEquals(BeltVerdict.DISQUALIFIED, monitor.accept(observation(4250, driver = false)).verdict)
    }

    @Test fun freshSpeedAfterSourceGapCannotBridgeMissingMotionEvidence() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(1000, driver = false))
        monitor.accept(observation(1250, driver = false).copy(speedAtMs = 1000))
        val afterGap = monitor.accept(observation(1501, driver = false))
        assertEquals(BeltVerdict.PENDING, afterGap.verdict)
        assertTrue(afterGap.hadUnverifiedMotion)
    }

    @Test fun freshnessIncludesBoundaryAndRejectsOneMillisecondOlder() {
        listOf<Pair<Long, (BeltObservation, Long) -> BeltObservation>>(
            2500L to { o, stamp -> o.copy(gearAtMs = stamp) },
            500L to { o, stamp -> o.copy(speedAtMs = stamp) },
            2500L to { o, stamp -> o.copy(driverAtMs = stamp) },
            2500L to { o, stamp -> o.copy(occupancyAtMs = stamp) },
            2500L to { o, stamp -> o.copy(passengerAtMs = stamp) },
        ).forEach { (age, change) ->
            val observation = observation(3000, occupied = true, passenger = true)
            assertEquals(BeltVerdict.SAFE, SeatBeltMonitor().accept(change(observation, 3000 - age)).verdict)
            assertEquals(BeltVerdict.UNVERIFIABLE, SeatBeltMonitor().accept(change(observation, 2999 - age)).verdict)
        }
    }

    @Test fun futureSamplesAndInvalidSpeedsAreUnverifiable() {
        listOf<(BeltObservation) -> BeltObservation>(
            { it.copy(gearAtMs = 1) }, { it.copy(speedAtMs = 1) },
            { it.copy(driverAtMs = 1) }, { it.copy(occupancyAtMs = 1) },
            { it.copy(passengerAtMs = 1) },
            { it.copy(speedMps = Double.NaN) }, { it.copy(speedMps = Double.POSITIVE_INFINITY) },
        ).forEach { invalid ->
            assertEquals(BeltVerdict.UNVERIFIABLE, SeatBeltMonitor().accept(invalid(observation(0, occupied = true, passenger = true))).verdict)
        }
    }

    @Test fun stalePrerequisiteResetsPendingAndUnknownMovingHistorySticks() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(1000, driver = false))
        assertEquals(BeltVerdict.UNVERIFIABLE, monitor.accept(observation(1250, driver = false).copy(speedAtMs = 749)).verdict)
        assertEquals(BeltVerdict.PENDING, monitor.accept(observation(1500, driver = false)).verdict)
        val safe = monitor.accept(observation(1750))
        assertEquals(BeltVerdict.SAFE, safe.verdict)
        assertTrue(safe.hadUnverifiedMotion)
    }

    @Test fun lateObservationCannotConfirmOrEraseTerminalDecision() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(1000, driver = false))
        assertEquals(BeltVerdict.UNVERIFIABLE, monitor.accept(observation(900, driver = false)).verdict)
        assertEquals(BeltVerdict.PENDING, monitor.accept(observation(1250, driver = false)).verdict)
        assertEquals(BeltVerdict.DISQUALIFIED, monitor.accept(observation(1750, driver = false)).verdict)
        assertEquals(BeltVerdict.DISQUALIFIED, monitor.accept(observation(0)).verdict)
    }

    @Test fun delayedBuckleTimestampDoesNotExtendAConfirmationWindow() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(1000, driver = false))
        assertEquals(BeltVerdict.UNVERIFIABLE, monitor.accept(observation(1250, driver = false).copy(driverAtMs = 999)).verdict)
        assertEquals(BeltVerdict.PENDING, monitor.accept(observation(1500, driver = false)).verdict)
    }

    @Test fun terminalDecisionIsStickyAndResetClearsBothLatches() {
        val monitor = SeatBeltMonitor()
        monitor.accept(observation(0, occupied = null))
        monitor.accept(observation(250, driver = false))
        val disqualified = monitor.accept(observation(750, driver = false))
        assertEquals(BeltVerdict.DISQUALIFIED, disqualified.verdict)
        assertTrue(disqualified.hadUnverifiedMotion)
        assertEquals(disqualified, monitor.accept(observation(1000, speed = 0.0)))
        monitor.reset()
        assertEquals(BeltDecision(BeltVerdict.SAFE), monitor.decision)
        assertEquals(BeltDecision(BeltVerdict.SAFE), monitor.accept(observation(0)))
    }

    @Test fun missingDataAtKnownStopDoesNotTaintTrip() {
        val monitor = SeatBeltMonitor()
        assertEquals(BeltDecision(BeltVerdict.SAFE), monitor.accept(observation(0, gear = null, speed = 0.0, driver = null, occupied = null)))
    }
}
