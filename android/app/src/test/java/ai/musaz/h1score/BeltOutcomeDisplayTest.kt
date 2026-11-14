package ai.musaz.h1score

import ai.musaz.h1score.core.*
import org.junit.Assert.*
import org.junit.Test

class BeltOutcomeDisplayTest {
    @Test fun outcomeTakesPrecedenceOverAnyStoredScore() {
        assertEquals("실격", historyResultLabel(drive(DriveOutcome.DISQUALIFIED)))
        assertEquals("확인 불가", historyResultLabel(drive(DriveOutcome.UNVERIFIABLE)))
        assertEquals("91점", historyResultLabel(drive(DriveOutcome.COMPLETED)))
    }

    @Test fun calendarDistinguishesMissingScoresFromBeltOutcomes() {
        val pending = drive(DriveOutcome.COMPLETED).let { it.copy(report = it.report.copy(score = null)) }
        assertEquals("미확정 · 실격", historyDayResults(listOf(pending, drive(DriveOutcome.DISQUALIFIED))))
        assertEquals("91점 · 확인 불가\n외 1경기", historyDayResults(listOf(
            drive(DriveOutcome.COMPLETED), drive(DriveOutcome.UNVERIFIABLE), pending)))
        assertEquals("91 · 91점", historyDayResults(listOf(drive(DriveOutcome.COMPLETED), drive(DriveOutcome.COMPLETED))))
    }

    @Test fun missingFailureReasonDoesNotHideAnInvalidOutcome() {
        assertNotNull(drive(DriveOutcome.DISQUALIFIED).beltOutcomeReason)
        assertNotNull(drive(DriveOutcome.UNVERIFIABLE).beltOutcomeReason)
        assertNull(drive(DriveOutcome.COMPLETED).beltOutcomeReason)
        assertEquals("조수석 안전벨트 미착용", drive(DriveOutcome.DISQUALIFIED)
            .copy(beltFailureReason = BeltFailureReason.PASSENGER_UNBUCKLED).beltOutcomeReason)
    }

    private fun drive(outcome: DriveOutcome) = CompletedDrive(
        ChallengeSession("belt", "destination", 14_000.0, Phase.FINISHED),
        ScoreReport(91, 88.0, 98.0, 10_300.0, 1.0, true, 0, 321.0),
        "목적지", true, emptyList(), 0, false, outcome = outcome,
    )
}
