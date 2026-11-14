package ai.musaz.h1score

import ai.musaz.h1score.core.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class DailyParticipationTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("daily-test-${UUID.randomUUID()}", 0)
    private val day = LocalDate.of(2026, 11, 14)
    @After fun clean() { prefs.edit().clear().commit() }

    @Test fun reservationSurvivesRestartAndCancellationButNotAcrossModesOrDays() {
        assertTrue(DailyParticipationStore(prefs).claim(false, day, "first"))
        assertFalse(DailyParticipationStore(prefs).claim(false, day, "second"))
        assertEquals(day, DailyParticipationStore(prefs).dayFor(false, "first"))
        assertTrue(DailyParticipationStore(prefs).claim(true, day, "demo"))
        assertTrue(DailyParticipationStore(prefs).claim(false, day.plusDays(1), "tomorrow"))
    }

    @Test fun disqualifiedLegacyHistoryAlsoConsumesTheDay() {
        CompletedDriveStore(prefs).save(drive().copy(outcome = DriveOutcome.DISQUALIFIED))
        assertFalse(DailyParticipationStore(prefs).available(false, day))
        assertTrue(DailyParticipationStore(prefs).available(true, day))
    }

    @Test fun resultRecognitionAndBadgeProvenanceSurviveReload() {
        val saved = drive().copy(participationDay = day.toString(), recognition = ResultRecognition.ACCEPTED,
            awardedBadges = listOf(BadgeAward("score-90-5", "좋은 습관", "90점 이상 5일", 15)), badgeMileage = 15)
        CompletedDriveStore(prefs).save(saved)
        assertEquals(saved, CompletedDriveStore(prefs).read())
    }

    @Test fun onlyTheClaimedCompletedValidOnTimeGameIsRecognized() {
        val saved = drive()
        assertEquals(ResultRecognition.ACCEPTED, recognizeResult(saved, true, true))
        assertEquals(ResultRecognition.DAILY_LIMIT, recognizeResult(saved, false, true))
        assertEquals(ResultRecognition.OUTSIDE_ROUND, recognizeResult(saved, true, false))
        assertEquals(ResultRecognition.DISQUALIFIED, recognizeResult(saved.copy(outcome=DriveOutcome.DISQUALIFIED), true, true))
        assertEquals(ResultRecognition.UNVERIFIABLE, recognizeResult(saved.copy(outcome=DriveOutcome.UNVERIFIABLE), true, true))
        assertEquals(ResultRecognition.INSUFFICIENT_DATA, recognizeResult(saved.copy(report=saved.report.copy(valid=false)), true, true))
    }

    private fun drive() = CompletedDrive(ChallengeSession("first", "spot", 14_000.0, Phase.FINISHED),
        ScoreReport(95, 90.0, 100.0, 10_000.0, 1.0, true, 0, 100.0), "목적지", false,
        emptyList(), 0, false, completedAtEpochMs=day.atTime(12,0).atZone(ChallengeRound.seoul).toInstant().toEpochMilli())
}
