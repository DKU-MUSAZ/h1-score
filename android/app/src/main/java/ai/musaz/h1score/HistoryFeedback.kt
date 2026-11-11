package ai.musaz.h1score

import ai.musaz.h1score.core.ScoreReport
import ai.musaz.h1score.core.TurnVerdict
import java.util.Locale

internal data class HistoryFeedbackMetric(val label: String, val value: String, val detail: String)

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
    val metrics = listOf(
        HistoryFeedbackMetric("과속", overDistance, "제한속도 초과 거리"),
        HistoryFeedbackMetric("급가속", if (longitudinalPending && report.accelerationEvents == 0) "판정 보류" else "${report.accelerationEvents}회", "기록된 급가속"),
        HistoryFeedbackMetric("급감속", when {
            bonus && !longitudinalPending -> "${decelerations}회"
            decelerations > 0 -> "확인 ${decelerations}건"
            longitudinalPending -> "판정 보류"
            else -> "확인 내역 없음"
        }, if (bonus) "해당 구간 가점 제외" else "저장된 구간 · 감점 제외"),
    )
    val good = buildList {
        if (report.score != null && report.validDistanceM > 0 && (!bonus || report.longitudinalReady)) {
            if (report.overLimitDistanceM == 0.0) {
                add("기록된 구간에서 제한속도를 넘지 않았어요.")
            } else {
                // The compliance score is weighted by excess speed; it is not a distance percentage.
                val withinLimit = (report.validDistanceM - report.overLimitDistanceM).coerceAtLeast(0.0)
                if (withinLimit > 0) add("제한속도를 지킨 구간이 ${historyFeedbackDistance(withinLimit)} 있었어요.")
            }
            if (bonus && report.accelerationEvents == 0 && decelerations == 0)
                add("기록된 구간에서 급가감속 없이 주행했어요.")
            else if (report.accelerationEvents == 0) add("기록된 구간에서 급가속 없이 주행했어요.")
        }
    }.ifEmpty { listOf("현재 기록만으로 이어갈 습관을 판단하기 어려워요.") }
    val improvements = buildList {
        if (report.overLimitDistanceM > 0) {
            add("제한속도를 초과한 거리가 ${overDistance}예요. 제한속도 표지를 확인하며 주행해 주세요.")
        }
        if (report.accelerationEvents > 0) {
            add("급가속 ${report.accelerationEvents}회가 기록됐어요. 가속 페달을 천천히 밟아 보세요.")
        }
        if (decelerations > 0) {
            add("급감속 ${decelerations}건이 확인됐어요. 앞차와 거리를 충분히 두고 도로 상황을 미리 살펴보세요.")
        }
        if (bonus) {
            val unused = report.turns.count { it.verdict == TurnVerdict.UNUSED }
            val mismatched = report.turns.count { it.verdict == TurnVerdict.MISMATCHED }
            if (unused > 0) add("방향지시등을 사용하지 않은 회전 ${unused}건이 확인됐어요. 회전 전에 방향을 알려 주세요.")
            if (mismatched > 0) add("회전 방향과 다른 방향지시등 ${mismatched}건이 확인됐어요. 회전할 방향을 확인해 주세요.")
        }
    }.ifEmpty {
        listOf(if (report.score != null && bonus) "확인된 과속·급가감속·회전 감점은 없어요. 지금의 운전 습관을 이어가 보세요."
            else if (report.score != null) "기록된 과속·급가속은 없어요. 지금의 운전 습관을 이어가 보세요."
            else "충분한 주행 기록이 쌓이면 개선할 점을 다시 확인해 주세요.")
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
