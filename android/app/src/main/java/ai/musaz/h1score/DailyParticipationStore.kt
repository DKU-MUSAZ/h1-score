package ai.musaz.h1score

import android.content.SharedPreferences
import java.time.LocalDate

/** A confirmed reservation consumes the day, even after cancellation, disqualification or restart. */
internal class DailyParticipationStore(private val prefs: SharedPreferences) {
    private fun key(demo: Boolean) = "participation-days-${if (demo) "demo" else "live"}"
    private fun entries(demo: Boolean): Map<LocalDate, String> {
        val legacy = CompletedDriveStore(prefs).readAll().filter { it.demo == demo && !it.isHistoryExample }
            .sortedBy { it.completedAtEpochMs ?: Long.MAX_VALUE }.mapNotNull { drive ->
                val day = drive.participationDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: historyDate(drive.completedAtEpochMs)
                day?.let { it to drive.session.id }
            }.groupBy({ it.first }, { it.second }).mapValues { it.value.first() }
        val stored = prefs.getStringSet(key(demo), emptySet()).orEmpty().mapNotNull {
            val day = runCatching { LocalDate.parse(it.substringBefore('=')) }.getOrNull()
            val id = it.substringAfter('=', "")
            if (day != null && id.isNotBlank()) day to id else null
        }.toMap()
        return legacy + stored
    }
    fun available(demo: Boolean, day: LocalDate): Boolean = synchronized(lock) { day !in entries(demo) }
    fun dayFor(demo: Boolean, id: String): LocalDate? = synchronized(lock) {
        entries(demo).entries.firstOrNull { it.value == id }?.key
    }
    fun claim(demo: Boolean, day: LocalDate, id: String): Boolean = synchronized(lock) {
        val existing = entries(demo)
        if (day in existing || id in existing.values) return false
        prefs.edit().putStringSet(key(demo), (existing + (day to id)).map { "${it.key}=${it.value}" }.toSet()).commit()
    }
    private companion object { val lock = Any() }
}

internal enum class ResultRecognition(val label: String) {
    ACCEPTED("경기 인정"), DISQUALIFIED("안전벨트 실격"), UNVERIFIABLE("필수 차량 정보 확인 불가"),
    DAILY_LIMIT("하루 참가 제한"), OUTSIDE_ROUND("대회 시간 외 도착"),
    DEADLINE("시간 초과 실격"),
    INSUFFICIENT_DATA("참가 거리 또는 유효 기록 부족"), LEGACY("이전 기록")
}

/** Shared gate for collection progress, placement and mileage; never changes ScoreReport. */
internal fun recognizeResult(drive: CompletedDrive, ownsParticipation: Boolean, withinRound: Boolean): ResultRecognition = when {
    drive.missedDeadline -> ResultRecognition.DEADLINE
    drive.outcome == DriveOutcome.DISQUALIFIED -> ResultRecognition.DISQUALIFIED
    drive.outcome == DriveOutcome.UNVERIFIABLE -> ResultRecognition.UNVERIFIABLE
    !ownsParticipation -> ResultRecognition.DAILY_LIMIT
    !withinRound -> ResultRecognition.OUTSIDE_ROUND
    !drive.session.rankingEligible || !drive.report.valid || drive.report.score == null -> ResultRecognition.INSUFFICIENT_DATA
    else -> ResultRecognition.ACCEPTED
}
