package ai.musaz.h1score

import ai.musaz.h1score.core.DrivingEvent
import ai.musaz.h1score.core.DrivingEventType
import ai.musaz.h1score.core.ScoreReport
import ai.musaz.h1score.core.ScoreEngine
import ai.musaz.h1score.core.TurnDirection
import ai.musaz.h1score.core.TurnEvent
import ai.musaz.h1score.core.TurnVerdict
import org.junit.Assert.*
import org.junit.Test

class HistoryFeedbackTest {
    @Test fun cappedEventsDoNotReplaceAggregateDistanceOrAccelerationCount() {
        val events = List(97) { event(DrivingEventType.OVERSPEED) } +
            List(3) { event(DrivingEventType.RAPID_DECELERATION) }
        val report = report(overDistance = 1_234.0, accelerations = 12, events = events)
        val original = report.copy(events = report.events.toList())

        val feedback = historyFeedback(report)

        assertEquals("1,234 m", feedback.metric("과속").value)
        assertEquals("12회", feedback.metric("급가속").value)
        assertEquals("확인 3건", feedback.metric("급감속").value)
        assertTrue(feedback.metric("급감속").detail.contains("감점 제외"))
        assertTrue(feedback.note.contains("전체 횟수는 확인할 수 없어요"))
        assertTrue(feedback.improvements.any { it.contains("1,234 m") })
        assertTrue(feedback.improvements.any { it.contains("급가속 12회") })
        assertTrue(feedback.improvements.any { it.contains("급감속 3건이 확인") })
        assertEquals(original, report)
    }

    @Test fun cleanRecordedDrivingGetsEvidenceBasedPraiseWithoutAssumingNoBraking() {
        val feedback = historyFeedback(report(overDistance = 0.0, accelerations = 0))

        assertEquals("0 m", feedback.metric("과속").value)
        assertEquals("0회", feedback.metric("급가속").value)
        assertEquals("확인 내역 없음", feedback.metric("급감속").value)
        assertTrue(feedback.goodPoints.any { it.contains("제한속도를 넘지 않았어요") })
        assertTrue(feedback.goodPoints.any { it.contains("급가속 없이") })
        assertFalse(feedback.goodPoints.any { it.contains("급감속") || it.contains("제동") })
        assertTrue(feedback.note.contains("전체 횟수는 확인할 수 없어요"))
    }

    @Test fun noObservedDecelerationIsNotReportedAsZeroEvenWhenOtherEventsReachTheCap() {
        listOf(emptyList(), List(100) { event(DrivingEventType.OVERSPEED) }).forEach { events ->
            val feedback = historyFeedback(report(overDistance = 3_000.0, events = events))
            assertEquals("확인 내역 없음", feedback.metric("급감속").value)
            assertFalse(feedback.goodPoints.any { it.contains("급감속") || it.contains("제동") })
            assertTrue(feedback.note.contains("전체 횟수는 확인할 수 없어요"))
        }
    }

    @Test fun unscoredDataDoesNotProduceSafeDrivingPraise() {
        val feedback = historyFeedback(report(overDistance = 0.0, accelerations = 0)
            .copy(score = null, validDistanceM = 20.0, coverage = 0.2, valid = false))

        assertEquals(listOf("현재 기록만으로 이어갈 습관을 판단하기 어려워요."), feedback.goodPoints)
        assertTrue(feedback.note.contains("거리 또는 수신 데이터가 부족"))
        assertFalse(feedback.improvements.any { it.contains("지금의 운전 습관을 이어가") })
    }

    @Test fun positiveSubMeterOverspeedIsNotRoundedToNoOverspeed() {
        val feedback = historyFeedback(report(overDistance = 0.4, accelerations = 0))

        assertEquals("1 m 미만", feedback.metric("과속").value)
        assertTrue(feedback.improvements.any { it.contains("1 m 미만") })
        assertFalse(feedback.goodPoints.any { it.contains("제한속도를 넘지 않았어요") })
    }

    @Test fun praiseUsesRecordedDistanceInsteadOfTreatingComplianceScoreAsAPercentage() {
        val feedback = historyFeedback(report(overDistance = 4_000.0, accelerations = 2)
            .copy(compliance = 99.0, validDistanceM = 10_000.0))

        assertTrue(feedback.goodPoints.any { it.contains("6,000 m") })
        assertFalse(feedback.goodPoints.any { it.contains("99%") || it.contains("급가속 없이") })
    }

    @Test fun newScoringUsesCompleteDecelerationCountAndExplainsLostBonus() {
        val report = report(events = listOf(event(DrivingEventType.RAPID_DECELERATION))).copy(
            scoringVersion = ScoreEngine.VERSION,
            brakingEvents = 12,
            longitudinalReady = true,
            longitudinalCoverage = 1.0,
            longitudinalSampleRateHz = 10.0,
            turns = listOf(
                TurnEvent(1_000, 3_000, TurnDirection.RIGHT, TurnVerdict.UNUSED),
                TurnEvent(5_000, 8_000, TurnDirection.LEFT, TurnVerdict.MISMATCHED),
            ),
        )
        val before = report.copy()
        val feedback = historyFeedback(report)

        assertEquals("12회", feedback.metric("급감속").value)
        assertEquals("해당 구간 가점 제외", feedback.metric("급감속").detail)
        assertTrue(feedback.note.contains("건당 추가 감점 없이"))
        assertFalse(feedback.note.contains("전체 횟수는 확인할 수 없어요"))
        assertTrue(feedback.improvements.any { it.contains("방향지시등을 사용하지 않은 회전 1건") })
        assertTrue(feedback.improvements.any { it.contains("다른 방향지시등 1건") })
        assertEquals(3.0, report.turnPenalty, 0.0)
        assertEquals(before, report)
    }

    @Test fun newLowRateDataDoesNotClaimZeroAbruptEventsOrSafeDriving() {
        val report = report().copy(
            score = null,
            scoringVersion = ScoreEngine.VERSION,
            longitudinalReady = false,
            longitudinalSampleRateHz = 1.0,
        )
        val feedback = historyFeedback(report)

        assertEquals("판정 보류", feedback.metric("급가속").value)
        assertEquals("판정 보류", feedback.metric("급감속").value)
        assertTrue(feedback.metrics.all { it.points == "점수 미확정" })
        assertEquals(listOf("현재 기록만으로 이어갈 습관을 판단하기 어려워요."), feedback.goodPoints)
        assertTrue(scorePendingMessage(report).contains("8Hz"))
        assertFalse(feedback.improvements.any { it.contains("지금의 운전 습관을 이어가") })
    }

    @Test fun metricPointsUseTheRecordedBonusesAndCappedTurnPenalty() {
        val report = report().copy(
            scoringVersion = ScoreEngine.VERSION,
            speedPoints = 22.17,
            accelerationPoints = 13.3,
            longitudinalReady = true,
            turns = List(6) { TurnEvent(1_000, 3_000, TurnDirection.LEFT, TurnVerdict.MISMATCHED) },
        )

        val feedback = historyFeedback(report)

        assertEquals("속도 가점 +22.17점", feedback.metric("과속").points)
        assertEquals("가감속 공통 +13.30점", feedback.metric("급가속").points)
        assertEquals(feedback.metric("급가속").points, feedback.metric("급감속").points)
        assertEquals("감점 −10.00점", feedback.metric("방향지시등").points)
    }

    @Test fun legacyMetricPointsFollowTheOriginalWeights() {
        val feedback = historyFeedback(report())

        assertEquals("속도 반영 +62.30점", feedback.metric("과속").points)
        assertEquals("가속 반영 +30.00점", feedback.metric("급가속").points)
        assertEquals("점수 반영 없음", feedback.metric("급감속").points)
        assertEquals("점수 반영 없음", feedback.metric("방향지시등").points)
    }

    @Test fun formulaAndDecelerationExplanationFollowTheSavedScoringVersion() {
        val legacy = report()
        val latest = legacy.copy(scoringVersion = ScoreEngine.VERSION)

        assertTrue(scoreFormula(legacy).contains("70%"))
        assertTrue(scoreFormula(legacy).contains("30%"))
        assertEquals("급감속 · 감점 제외", decelerationEventLabel(legacy))
        assertTrue(scoreFormula(latest).contains("기본 60점"))
        assertTrue(scoreFormula(latest).contains("최대 25점"))
        assertTrue(scoreFormula(latest).contains("최대 15점"))
        assertTrue(scoreFormula(latest).contains("최대 10점"))
        assertFalse(scoreFormula(latest).contains("70%"))
        assertEquals("급감속 · 가점 제외", decelerationEventLabel(latest))
    }

    @Test fun newGoodFeedbackRequiresKnownLongitudinalCoverage() {
        val latest = report().copy(scoringVersion = ScoreEngine.VERSION, longitudinalReady = true)
        val feedback = historyFeedback(latest)

        assertEquals("0회", feedback.metric("급감속").value)
        assertTrue(feedback.goodPoints.any { it.contains("급가감속 없이") })
        assertFalse(feedback.goodPoints.any { it.contains("방향지시등") })
    }

    private fun HistoryFeedback.metric(label: String) = metrics.single { it.label == label }

    private fun report(
        overDistance: Double = 0.0,
        accelerations: Int = 0,
        events: List<DrivingEvent> = emptyList(),
    ) = ScoreReport(92, 89.0, 100.0, 10_000.0, 1.0, true, accelerations, overDistance, events)

    private fun event(type: DrivingEventType) = DrivingEvent(type, 15_000, 321.0, 70.0, 83.0, 80)
}
