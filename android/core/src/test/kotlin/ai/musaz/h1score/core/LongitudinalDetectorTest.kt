package ai.musaz.h1score.core

import org.junit.Test
import kotlin.test.*

class LongitudinalDetectorTest {
    private fun events(samples: List<Sample>) = LongitudinalDetector.evaluate(samples).events
    private fun accelerating(rate: Double, durationMs: Long, stepMs: Long = 100) =
        SpeedTrace().drive(durationMs, rate, stepMs).samples
    private fun decelerating(rate: Double, durationMs: Long) =
        SpeedTrace(initialSpeedMps = 25.0).drive(durationMs, rate).samples

    @Test fun accelerationRequiresBothInclusiveMagnitudeAndFullSecond() {
        assertEquals(1, events(accelerating(2.5, 1000)).size)
        assertTrue(events(accelerating(2.499, 1500)).isEmpty())
        assertTrue(events(accelerating(2.5, 999)).isEmpty())
        assertTrue(events(accelerating(4.0, 400)).isEmpty())
    }

    @Test fun elapsedTimeDoesNotDependOnNumberOfSamples() {
        assertTrue(events(accelerating(3.0, 900)).isEmpty()) // Ten samples span only 0.9 seconds.
        val irregular = LongitudinalDetector.evaluate(accelerating(3.0, 1000, stepMs = 143))
        assertEquals(1, irregular.events.size)
        assertFalse(irregular.ready, "A detected event does not establish sufficient sample rate for scoring")
    }

    @Test fun decelerationUsesOneFullSecondAverageWithoutAnAdditionalDwell() {
        assertEquals(1, events(decelerating(-3.0, 1000)).size)
        assertTrue(events(decelerating(-2.999, 1500)).isEmpty())
        assertTrue(events(decelerating(-3.0, 999)).isEmpty())
        assertTrue(events(decelerating(-3.0, 500)).isEmpty())
        assertTrue(events(decelerating(-5.0, 300)).isEmpty())
    }

    @Test fun sustainedEventIsNotCountedOncePerSample() {
        val detected = events(accelerating(3.0, 4000))
        assertEquals(1, detected.size)
        assertEquals(LongitudinalKind.ACCELERATION, detected.single().kind)
    }

    @Test fun mergeIncludesExactlyTwoSecondsButNotLonger() {
        fun driveWithGap(gapMs: Long) = SpeedTrace().drive(500).drive(1200, 3.0)
            .drive(gapMs).drive(1200, 3.0).drive(500).samples
        assertEquals(1, events(driveWithGap(2000)).size)
        assertEquals(2, events(driveWithGap(2001)).size)
    }

    @Test fun twoShortUnqualifiedBurstsDoNotAddUpToOneEvent() {
        val trace = SpeedTrace().drive(500).drive(600, 3.0).drive(200)
            .drive(600, 3.0).drive(500)
        assertTrue(events(trace.samples).isEmpty())
    }

    @Test fun oppositeConfirmedEventSeparatesAccelerations() {
        val trace = SpeedTrace().drive(500).drive(1200, 3.0)
            .drive(1000, -3.5).drive(1200, 3.0).drive(500)
        val detected = events(trace.samples)
        assertEquals(2, detected.count { it.kind == LongitudinalKind.ACCELERATION })
        assertEquals(1, detected.count { it.kind == LongitudinalKind.DECELERATION })
    }

    @Test fun missingDataCannotJoinOtherwiseNearbyEvents() {
        val complete = SpeedTrace().drive(500).drive(1200, 3.0)
            .drive(1000).drive(1200, 3.0).drive(500).samples
        assertEquals(1, events(complete).size)
        val interrupted = complete.filterNot { it.atMs in 1900..2300 }
        assertEquals(2, events(interrupted).size)
    }

    @Test fun missingDataCannotCompleteAnUnfinishedCandidate() {
        val complete = accelerating(3.0, 1500)
        val interrupted = complete.filterNot { it.atMs in 500..900 }
        assertTrue(events(interrupted).isEmpty())
    }

    @Test fun brakeAnnotationDoesNotCreateAnotherDeceleration() {
        val samples = SpeedTrace(initialSpeedMps = 25.0).drive(400, -3.5)
            .drive(500, -5.5).drive(400, -3.5).samples
        val pressed = samples.map { it.copy(brakePressed = true, brakeObservedAtMs = it.atMs) }
        val released = samples.map { it.copy(brakePressed = false, brakeObservedAtMs = it.atMs) }
        val unknown = samples.map { it.copy(brakePressed = null) }
        assertEquals(1, events(pressed).size)
        assertEquals(1, events(released).size)
        assertEquals(1, events(unknown).size)
        assertEquals(true, events(pressed).single().brakePressed)
        assertEquals(false, events(released).single().brakePressed)
        assertNull(events(unknown).single().brakePressed)
    }

    @Test fun sourceTimestampWinsOverAppReceptionTime() {
        val source = accelerating(3.0, 1000)
        val delayedReception = source.mapIndexed { index, sample ->
            sample.copy(atMs = 50_000L + index * 500, speedTimestampNanos = sample.atMs * 1_000_000)
        }
        assertEquals(events(source), events(delayedReception))
    }

    @Test fun duplicateAndReversedTimestampsCannotManufactureAnEvent() {
        val samples = accelerating(3.0, 600)
        val repeated = samples.flatMap { listOf(it, it) }
        assertTrue(events(repeated).isEmpty())
        assertTrue(events(samples.reversed()).isEmpty())
    }

    @Test fun invalidSpeedCannotBridgeCandidates() {
        val source = accelerating(3.0, 1500)
        listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { invalid ->
            val damaged = source.map { if (it.atMs in 500..900) it.copy(speedMps = invalid) else it }
            assertTrue(events(damaged).isEmpty(), "Invalid speed: $invalid")
        }
    }

    @Test fun accelerationStillRejectsOneSpeedSpikeByMedianAndDuration() {
        val source = SpeedTrace().drive(2500).samples
        val spike = source.map { if (it.atMs == 1000L) it.copy(speedMps = it.speedMps!! + 4.0) else it }
        assertTrue(events(spike).none { it.kind == LongitudinalKind.ACCELERATION })
    }

    @Test fun speedLimitIsNotNeededToDetectMotionEvents() {
        val samples = accelerating(3.0, 1200).map { it.copy(limitKph = null) }
        assertEquals(1, events(samples).size)
        val report = ScoreEngine.evaluate(samples)
        assertEquals(1, report.accelerationEvents)
        assertEquals(0.0, report.earnedPoints)
    }

    @Test fun oneHzCannotClaimSmoothDriving() {
        val samples = (0..1000).map { Sample(it * 1000L, 10.0, 50) }
        val analysis = LongitudinalDetector.evaluate(samples)
        assertFalse(analysis.ready)
        assertTrue(analysis.events.isEmpty())
        val report = ScoreEngine.evaluate(samples)
        assertFalse(report.longitudinalReady)
        assertEquals(0.0, report.accelerationPoints)
    }

    @Test fun aSingleCoarseIntervalCannotProveSustainedStrongDeceleration() {
        val coarse = listOf(Sample(0, 20.0, 100), Sample(300, 18.5, 100))
        val analysis = LongitudinalDetector.evaluate(coarse)
        assertTrue(analysis.events.isEmpty())
        assertFalse(analysis.ready)

        // Later high-rate observations must not turn the earlier two-point estimate into an event.
        val recovered = coarse + (1..20).map { Sample(300L + it * 100L, 18.5, 100) }
        val recoveredAnalysis = LongitudinalDetector.evaluate(recovered)
        assertTrue(recoveredAnalysis.ready)
        assertTrue(recoveredAnalysis.events.isEmpty())
    }

    @Test fun consistentlyCoarseDataCannotClaimFullSmoothnessDespiteCompleteCoverage() {
        listOf(250L, 300L).forEach { stepMs ->
            val samples = SpeedTrace(initialSpeedMps = 10.0, limitKph = 50)
                .drive(1_000_000, stepMs = stepMs).samples
            val analysis = LongitudinalDetector.evaluate(samples)
            assertEquals(1.0, analysis.coverage, 1e-8)
            assertFalse(analysis.ready, "$stepMs ms spacing must not pass the sampling quality gate")
            val report = ScoreEngine.evaluate(samples)
            assertFalse(report.longitudinalReady)
            assertFalse(report.valid)
            assertNull(report.score)
            assertEquals(0.0, report.accelerationPoints)
        }
    }

    @Test fun genuineTenHzDataRetainsTheFullPerfectTripScore() {
        val samples = SpeedTrace(initialSpeedMps = 10.0, limitKph = 50).drive(1_000_000).samples
        val report = ScoreEngine.evaluate(samples)
        assertTrue(report.longitudinalReady)
        assertTrue(report.valid)
        assertEquals(100, report.score)
        assertEquals(15.0, report.accelerationPoints, 1e-8)
        assertEquals(10.0, report.longitudinalSampleRateHz, 1e-8)
    }

    @Test fun mergingEventsDoesNotRemoveBonusFromTheCleanGap() {
        val samples = SpeedTrace().drive(1200, 3.0).drive(1000).drive(1200, 3.0).samples
        val report = ScoreEngine.evaluate(samples)
        assertEquals(1, report.accelerationEvents)
        assertTrue(report.accelerationPoints > 0.0, "The valid, steady gap must still earn distance bonus")
        assertTrue(report.accelerationPoints < report.validDistanceM / 1000 * ScoreEngine.ACCELERATION_PER_KM)
    }

    @Test fun motionBeyondTenKilometersCannotChangeTheResult() {
        val inWindow = (0..10000).map { Sample(it * 100L, 10.0, 100) }
        val outside = (1..20).map { Sample(1_000_000L + it * 100L, 10.0 + it * 0.3, 100) }
        assertEquals(1, events(outside).size)
        val expected = ScoreEngine.evaluate(inWindow)
        val extended = ScoreEngine.evaluate(inWindow + outside)
        assertEquals(expected.score, extended.score)
        assertEquals(expected.earnedPoints, extended.earnedPoints, 1e-8)
        assertEquals(0, extended.accelerationEvents)
        assertEquals(10_000.0, extended.validDistanceM, 1e-8)
    }
}
