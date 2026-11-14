package ai.musaz.h1score

import ai.musaz.h1score.core.ScoreReport
import ai.musaz.h1score.core.TurnVerdict
import ai.musaz.h1score.core.ManeuverKind
import java.util.Locale

internal data class HistoryFeedbackMetric(val label: String, val value: String, val detail: String, val points: String)

internal data class HistoryFeedback(
    val metrics: List<HistoryFeedbackMetric>,
    val goodPoints: List<String>,
    val improvements: List<String>,
    val note: String,
)

/** Presentation of a saved report only: never recalculate its score or rewards. */
internal fun historyFeedback(report: ScoreReport): HistoryFeedback {
    val bonus = report.usesDistanceBonus
    val decelerations = report.recordedDecelerations
    val longitudinalPending = bonus && !report.longitudinalReady
    val overDistance = historyFeedbackDistance(report.overLimitDistanceM)
    val turns = report.turns.filter { it.kind == ManeuverKind.TURN }
    val signalIssues = turns.count { it.penalty>0 }
    val verifiedTurns = turns.count { it.verdict != TurnVerdict.UNVERIFIABLE }
    val sharedBonus = "가감속 공통 +${scorePoints(report.accelerationPoints)}점"
    val penalty = if (report.turnPenalty > 0) "−${scorePoints(report.turnPenalty)}" else "0.00"
    fun metric(label: String, value: String, detail: String, points: String) =
        HistoryFeedbackMetric(label, value, detail, if (report.score == null) "점수 미확정" else points)

    val metrics = listOf(
        metric("과속", overDistance, "제한속도 초과 거리",
            if (bonus) "속도 가점 +${scorePoints(report.speedPoints)}점" else "속도 반영 +${scorePoints(report.compliance * .7)}점"),
        metric("급가속", if (longitudinalPending && report.accelerationEvents == 0) "판정 보류" else "${report.accelerationEvents}회",
            if (bonus) "해당 구간 가점 제외" else "기록된 급가속",
            if (bonus) sharedBonus else "가속 반영 +${scorePoints(report.smoothness * .3)}점"),
        metric("급감속", when {
            bonus && !longitudinalPending -> "${decelerations}회"
            decelerations > 0 -> "확인 ${decelerations}건"
            longitudinalPending -> "판정 보류"
            else -> "확인 내역 없음"
        }, if (bonus) "해당 구간 가점 제외" else "저장된 구간 · 감점 제외",
            if (bonus) sharedBonus else "점수 반영 없음"),
        metric("방향지시등",
            if (verifiedTurns == 0) "판정 없음" else "${signalIssues}건",
            "미사용·늦은 사용·반대 방향", if (bonus) "감점 ${penalty}점" else "점수 반영 없음"),
    )
    val good = buildList {
        if (report.score != null && report.validDistanceM > 0 && (!bonus || report.longitudinalReady)) {
            if (report.overLimitDistanceM == 0.0) {
                add("기록된 구간에서 제한속도를 넘지 않았어요.")
            } else {
                // The compliance score is weighted by excess speed; it is not a distance percentage.
                val withinLimit = (report.validDistanceM - report.overLimitDistanceM).coerceAtLeast(0.0)
                if (withinLimit > 0) add(HabitText.WITHIN_LIMIT.render("distance" to historyFeedbackDistance(withinLimit)))
            }
            if (bonus && report.accelerationEvents == 0 && decelerations == 0)
                add("기록된 구간에서 급가감속 없이 주행했어요.")
            else if (report.accelerationEvents == 0) add("기록된 구간에서 급가속 없이 주행했어요.")
            if (bonus && report.speedPoints > 0) add(HabitText.SPEED_BONUS.render("points" to scorePoints(report.speedPoints)))
            if (bonus && report.accelerationPoints > 0 && report.accelerationEvents == 0 && decelerations == 0)
                add(HabitText.SMOOTH_BONUS.render("points" to scorePoints(report.accelerationPoints)))
            val matched=turns.count { it.verdict == TurnVerdict.MATCHED }
            if (bonus && matched > 0) add(HabitText.MATCHED.render("count" to matched))
        }
    }.ifEmpty { listOf("현재 기록만으로 이어갈 습관을 판단하기 어려워요.") }
    val improvements = buildList {
        if (report.overLimitDistanceM > 0) {
            add(HabitText.OVERSPEED.render("distance" to overDistance))
        }
        if (report.accelerationEvents > 0) {
            add(HabitText.ACCELERATION.render("count" to report.accelerationEvents) +
                if (bonus && report.score != null && report.longitudinalReady)
                    " " + HabitText.SHARED_BONUS.render("points" to scorePoints(report.accelerationPoints)) else "")
        }
        if (decelerations > 0) {
            add(HabitText.BRAKING.render("count" to decelerations) +
                if (bonus && report.score != null && report.longitudinalReady && report.accelerationEvents == 0)
                    " " + HabitText.SHARED_BONUS.render("points" to scorePoints(report.accelerationPoints)) else "")
        }
        if (bonus) {
            val unused = turns.count { it.verdict == TurnVerdict.UNUSED }
            val mismatched = turns.count { it.verdict == TurnVerdict.MISMATCHED }
            val late = turns.count { it.verdict == TurnVerdict.LATE }
            if(late>0) add(HabitText.LATE.render("count" to late))
            if (unused > 0) add(HabitText.UNUSED.render("count" to unused))
            if (mismatched > 0) add(HabitText.MISMATCHED.render("count" to mismatched))
            if (report.turnPenalty > 0 && report.score != null) add(HabitText.TURN_PENALTY.render("points" to scorePoints(report.turnPenalty)))
        }
    }.ifEmpty {
        listOf(if (report.score != null && bonus) "확인된 과속·급가감속·회전 감점은 없어요. 확인된 구간의 운전 습관을 이어가 보세요."
            else if (report.score != null) "기록된 과속·급가속은 없어요. 지금의 운전 습관을 이어가 보세요."
            else "충분한 주행 기록이 쌓이면 개선할 점을 다시 확인해 주세요.")
    }.let { advice ->
        if (!bonus) advice else advice + when {
            turns.isEmpty() -> listOf("회전 판정 자료가 없어 방향지시등 사용 여부는 평가하지 않았어요.")
            turns.any { it.verdict == TurnVerdict.UNVERIFIABLE } -> listOf(HabitText.UNVERIFIED.render(
                "count" to turns.count { it.verdict == TurnVerdict.UNVERIFIABLE }))
            else -> emptyList()
        }
    }
    val note = if (bonus) {
        (if (report.score == null) "거리 또는 수신 품질이 부족해 일부 내역만 확인할 수 있어요. " else "유효한 수신 데이터 기준이에요. ") +
            "급가감속은 건당 추가 감점 없이 해당 거리의 가감속 가점만 제외해요. " +
            "브레이크를 밟았다는 이유만으로 감점하지 않아요. 회전은 GPS·내비·방향지시등 정보로 확인된 구간만 평가해요."
    } else (if (report.score == null) "거리 또는 수신 데이터가 부족해 일부 내역만 확인할 수 있어요. " else "유효한 수신 데이터 기준이에요. ") +
        "급감속은 저장된 내역 기준이며 전체 횟수는 확인할 수 없어요. 감점에는 반영하지 않아요."
    return HistoryFeedback(metrics, good, improvements, note)
}

private fun historyFeedbackDistance(meters: Double): String = when {
    meters <= 0 -> "0 m"
    meters < 1 -> "1 m 미만"
    else -> String.format(Locale.KOREA, "%,.0f m", meters)
}
