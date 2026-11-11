package ai.musaz.h1score.core

import org.junit.Test
import kotlin.test.*

class ScoringHistoryCompatibilityTest {
    @Test fun legacyConstructorsKeepTheirVersionAndSavedValues() {
        val report = ScoreReport(92, 89.0, 100.0, 10300.0, 1.0, true, 0, 1134.0)
        assertEquals(LEGACY_SCORING_VERSION, report.scoringVersion)
        assertEquals(92, report.score)
        assertTrue(report.events.isEmpty())
    }

    @Test fun onlyConfirmedLongitudinalEventsAppearInHistory() {
        val brief = SpeedTrace(10.0, 80).drive(600, 3.0).drive(1200)
        assertTrue(ScoreEngine.evaluate(brief.samples).events.none { it.type == DrivingEventType.RAPID_ACCELERATION })
        val confirmed = SpeedTrace(10.0, 80).drive(1200, 3.0).drive(1500).drive(1200, -3.0)
        val report = ScoreEngine.evaluate(confirmed.samples)
        assertEquals(ScoreEngine.VERSION, report.scoringVersion)
        assertEquals(listOf(DrivingEventType.RAPID_ACCELERATION, DrivingEventType.RAPID_DECELERATION), report.events.map { it.type })
        assertTrue(report.events.all { it.elapsedMs >= 0 && it.distanceM in 0.0..report.validDistanceM })
    }

    @Test fun historyListLimitDoesNotLimitScoredEventTotals() {
        val trace = SpeedTrace(10.0, 80)
        repeat(200) { trace.drive(1100, 3.0).drive(1100, -3.0).drive(1100) }
        val report = ScoreEngine.evaluate(trace.samples)
        assertEquals(100, report.events.size)
        assertTrue(report.accelerationEvents > 100)
        assertTrue(report.brakingEvents > 100)
        assertEquals(report.accelerationEvents + report.brakingEvents, report.longitudinalEvents.size)
    }

    @Test fun mergedDecelerationsDisplayARealDeceleratingSpanDespiteRecoveryBetweenThem() {
        // 72 → 61.2, recover to 86.4 in half a second, then decelerate to 75.6 km/h.
        // The short recovery is not a confirmed acceleration event, so both decelerations
        // are one scored event even though the overall ending speed exceeds its start.
        val trace = SpeedTrace(20.0, 100).drive(2000)
            .drive(1000, -3.0).drive(500, 14.0).drive(1000, -3.0)
        val report = ScoreEngine.evaluate(trace.samples)
        assertEquals(0, report.accelerationEvents)
        assertEquals(1, report.decelerationEvents)
        val merged = report.longitudinalEvents.single()
        assertEquals(2, merged.qualifyingIntervals.size)
        val display = report.events.single { it.type == DrivingEventType.RAPID_DECELERATION }
        assertEquals(72.0, display.fromKph, 1e-8)
        assertEquals(61.2, display.toKph, 1e-8)
        assertTrue(display.toKph < display.fromKph)
        assertEquals(2000L, display.elapsedMs)
        assertEquals(40.0, display.distanceM, 1e-8)
    }

    @Test fun overspeedHistoryUsesTheSameCrossingAndDistanceAsScoring() {
        val trace = SpeedTrace(10.0, 54).drive(1000, 10.0)
        val report = ScoreEngine.evaluate(trace.samples)
        val event = report.events.single { it.type == DrivingEventType.OVERSPEED }
        assertEquals(500L, event.elapsedMs)
        assertEquals(6.25, event.distanceM, 1e-8)
        assertTrue(event.toKph > event.limitKph)
    }

    @Test fun sdkFrontWheelPolarityIsUsedForTurnCandidates() {
        val detector = TurnDetector()
        fun observation(at: Long, angle: Double, instruction: TurnDirection) = TurnObservation(
            at, GpsFix(at, 37.0, 127.0, 3.0, 0.0, 5.0), 8.0, true, angle,
            SignalState.OFF, instruction, 20.0, at)
        detector.accept(observation(0, 0.0, TurnDirection.RIGHT))
        detector.accept(observation(250, 8.0, TurnDirection.RIGHT))
        detector.interrupt()
        assertEquals(TurnDirection.RIGHT, detector.events.single().direction)
        detector.reset()
        detector.accept(observation(0, 0.0, TurnDirection.LEFT))
        detector.accept(observation(250, -8.0, TurnDirection.LEFT))
        detector.interrupt()
        assertEquals(TurnDirection.LEFT, detector.events.single().direction)
    }
}
