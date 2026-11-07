package ai.musaz.h1score.core

import org.junit.Test
import kotlin.test.*

class RollingDecelerationTest {
    private fun decelerations(samples: List<Sample>) = LongitudinalDetector.evaluate(samples).events
        .filter { it.kind == LongitudinalKind.DECELERATION }

    @Test fun aHalfSecondDropCanQualifyOnceTheOneSecondWindowIsComplete() {
        val trace = SpeedTrace(initialSpeedMps = 25.0).drive(500, -6.0)
        assertTrue(decelerations(trace.samples).isEmpty())
        trace.drive(500)
        assertEquals(1, decelerations(trace.samples).size)
    }

    @Test fun theOldBriefStrongThresholdAloneNoLongerQualifies() {
        val trace = SpeedTrace(initialSpeedMps = 25.0).drive(300, -5.0).drive(700)
        assertTrue(decelerations(trace.samples).isEmpty()) // Only 1.5 m/s lost over the full second.
    }

    @Test fun rollingWindowUsesExactInterpolatedStartRatherThanNearestSample() {
        val qualifies = interpolatedWindow(finalSpeedMps = 16.8)
        val event = decelerations(qualifies).single()
        assertEquals(50_000_000L, event.qualifyingIntervals.first().startedAtNanos)
        assertEquals(1_050_000_000L, event.qualifyingIntervals.last().endedAtNanos)

        // At t=50 ms the speed is 19.8, so 16.9 gives -2.9 m/s², not -3.1 from t=0.
        assertTrue(decelerations(interpolatedWindow(finalSpeedMps = 16.9)).isEmpty())
    }

    @Test fun exactSourceNanosecondsAreUsedEvenWhenAppTimesAreUnrelated() {
        val original = interpolatedWindow(finalSpeedMps = 16.8)
        val delayed = original.mapIndexed { index, sample ->
            sample.copy(atMs = 40_000L + index * 900, speedTimestampNanos = sample.atMs * 1_000_000)
        }
        assertEquals(decelerations(original), decelerations(delayed))
    }

    @Test fun missingIntervalRequiresOneFullSecondOfFreshHistory() {
        val source = SpeedTrace(initialSpeedMps = 25.0).drive(2000, -3.0).samples
        val interrupted = source.filterNot { it.atMs in 500..900 }
        assertTrue(decelerations(interrupted.dropLast(1)).isEmpty())
        val recovered = decelerations(interrupted).single()
        assertEquals(1_000_000_000L, recovered.qualifyingIntervals.first().startedAtNanos)
    }

    @Test fun duplicateAndBackwardRecordsCannotBeInterpolatedAcross() {
        val source = SpeedTrace(initialSpeedMps = 25.0).drive(1600, -3.0).samples
        listOf(500L, 400L).forEach { insertedAt ->
            val interrupted = buildList {
                source.forEach { sample ->
                    add(sample)
                    if (sample.atMs == 500L) add(sample.copy(atMs = insertedAt,
                        speedTimestampNanos = insertedAt * 1_000_000))
                }
            }
            assertTrue(decelerations(interrupted.dropLast(1)).isEmpty())
            assertEquals(1, decelerations(interrupted).size)
        }
    }

    @Test fun nullAndInvalidSpeedResetTheEntireAverageWindow() {
        val source = SpeedTrace(initialSpeedMps = 25.0).drive(1600, -3.0).samples
        listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { invalid ->
            val interrupted = source.map { if (it.atMs == 500L) it.copy(speedMps = invalid) else it }
            assertTrue(decelerations(interrupted.dropLast(1)).isEmpty(), "Invalid speed: $invalid")
            assertEquals(1, decelerations(interrupted).size)
        }
    }

    @Test fun overlappingWindowsProduceOneEventAndOneExcludedDistance() {
        val samples = SpeedTrace(initialSpeedMps = 30.0, limitKph = 120).drive(2000, -3.0).samples
        assertEquals(1, decelerations(samples).size)
        val report = ScoreEngine.evaluate(samples)
        assertEquals(1, report.decelerationEvents)
        assertEquals(0.0, report.accelerationPoints, 1e-9)
        assertEquals(54.0 / 1000 * ScoreEngine.SPEED_PER_KM, report.speedPoints, 1e-9)
    }

    @Test fun steadyTailInsideAConfirmedWindowRetainsItsExactBonus() {
        val samples = SpeedTrace(initialSpeedMps = 25.0).drive(500, -6.0).drive(500).samples
        val event = decelerations(samples).single()
        assertEquals(500_000_000L, event.qualifyingIntervals.sumOf { it.durationNanos })
        assertTrue(event.qualifyingIntervals.all { it.endedAtNanos <= 500_000_000L })
        val report = ScoreEngine.evaluate(samples)
        // 0.5 seconds of steady 22 m/s travels 11 metres, independently of the preceding brake.
        assertEquals(11.0 / 1000 * ScoreEngine.ACCELERATION_PER_KM, report.accelerationPoints, 1e-9)
    }

    @Test fun increasingAndSteadyPartsOfAConfirmedWindowKeepTheirBonus() {
        val samples = SpeedTrace(initialSpeedMps = 25.0).drive(400, -10.0)
            .drive(200, 2.0).drive(400).samples
        val report = ScoreEngine.evaluate(samples)
        assertEquals(1, report.decelerationEvents)
        assertEquals(0, report.accelerationEvents)
        // Gentle increase: average 21.2 * 0.2 = 4.24 m; steady tail: 21.4 * 0.4 = 8.56 m.
        assertEquals(12.8 / 1000 * ScoreEngine.ACCELERATION_PER_KM, report.accelerationPoints, 1e-9)
    }

    @Test fun thePartialIntervalBeforeTheWindowKeepsItsExactBonus() {
        val report = ScoreEngine.evaluate(interpolatedWindow(finalSpeedMps = 16.8))
        assertEquals(1, report.decelerationEvents)
        // The confirmed window starts at 50 ms; 0..50 ms travels (20 + 19.8)/2 * .05 = .995 m.
        assertEquals(0.995 / 1000 * ScoreEngine.ACCELERATION_PER_KM, report.accelerationPoints, 1e-9)
        assertEquals(19.27 / 1000 * ScoreEngine.SPEED_PER_KM, report.speedPoints, 1e-9)
    }

    @Test fun partialWindowExclusionStillRequiresSpeedComplianceForSmoothBonus() {
        val samples = interpolatedWindow(finalSpeedMps = 16.8).map {
            it.copy(speedMps = requireNotNull(it.speedMps) + 0.15, limitKph = 72)
        }
        val report = ScoreEngine.evaluate(samples)
        assertEquals(1, report.decelerationEvents)
        // 20.15 -> 19.75 m/s crosses the 20 m/s limit at 37.5 ms. Only 37.5..50 ms
        // is both compliant and outside the confirmed deceleration window: .2496875 metres.
        assertEquals(0.2496875 / 1000 * ScoreEngine.ACCELERATION_PER_KM, report.accelerationPoints, 1e-9)
        // Total distance 19.4275 m minus the initial .7528125 m above the speed limit.
        assertEquals(18.6746875 / 1000 * ScoreEngine.SPEED_PER_KM, report.speedPoints, 1e-9)
    }

    @Test fun independentDecelerationsStillUseTheTwoSecondMergeBoundary() {
        fun trace(gap: Long) = SpeedTrace(initialSpeedMps = 25.0).drive(1000, -3.0)
            .drive(gap).drive(1000, -3.0).samples
        assertEquals(1, decelerations(trace(2000)).size)
        assertEquals(2, decelerations(trace(2001)).size)
        val mergedReport = ScoreEngine.evaluate(trace(2000))
        // The merged event contains a two-second steady gap at 22 m/s: all 44 metres earn bonus.
        assertEquals(44.0 / 1000 * ScoreEngine.ACCELERATION_PER_KM, mergedReport.accelerationPoints, 1e-9)
    }

    @Test fun limitDataIsNotRequiredForTheDecelerationAverage() {
        val samples = SpeedTrace(initialSpeedMps = 25.0, limitKph = null).drive(1000, -3.0).samples
        val report = ScoreEngine.evaluate(samples)
        assertEquals(1, report.decelerationEvents)
        assertEquals(0.0, report.earnedPoints)
        assertNull(report.score)
    }

    @Test fun aWindowThatOnlyQualifiesAfterTenKilometersDoesNotChangeTheScore() {
        val samples = SpeedTrace(initialSpeedMps = 10.0).drive(999_500).drive(1000, -3.0).samples
        assertEquals(1, decelerations(samples).size)
        val report = ScoreEngine.evaluate(samples)
        assertEquals(10_000.0, report.validDistanceM, 1e-7)
        assertEquals(0, report.decelerationEvents)
        assertEquals(15.0, report.accelerationPoints, 1e-7)
        assertEquals(100, report.score)
    }

    private fun interpolatedWindow(finalSpeedMps: Double): List<Sample> = buildList {
        add(Sample(0, 20.0, 100))
        for (at in 100L..900L step 100) {
            add(Sample(at, 19.6 + (finalSpeedMps - 19.6) * (at - 100) / 950.0, 100))
        }
        add(Sample(1050, finalSpeedMps, 100))
    }
}
