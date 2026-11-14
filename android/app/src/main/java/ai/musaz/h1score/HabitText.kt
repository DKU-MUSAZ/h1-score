package ai.musaz.h1score

/** Deterministic, local copy templates. Values come only from the saved report, not AI guesses. */
internal enum class HabitText(private val template: String) {
    WITHIN_LIMIT("제한속도를 지킨 구간이 {distance} 있었어요."),
    SPEED_BONUS("제한속도 준수로 {points}점을 쌓았어요."),
    SMOOTH_BONUS("부드럽게 주행한 구간에서 가감속 공통 {points}점을 쌓았어요."),
    OVERSPEED("제한속도를 초과한 거리가 {distance}예요. 제한속도 표지를 확인하며 주행해 주세요."),
    ACCELERATION("급가속 {count}회가 기록됐어요. 가속 페달을 천천히 밟아 보세요."),
    BRAKING("급감속 {count}건이 확인됐어요. 앞차와 거리를 충분히 두고 도로 상황을 미리 살펴보세요."),
    SHARED_BONUS("급가감속 구간은 가점에서 제외됐고, 가감속 공통 가점은 {points}점이에요."),
    UNUSED("방향지시등을 사용하지 않은 회전 {count}건이 확인됐어요. 회전 전에 방향을 알려 주세요."),
    LATE("방향지시등을 늦게 켠 회전 {count}건이 확인됐어요. 방향을 바꾸기 전에 알려 주세요."),
    MISMATCHED("회전 방향과 다른 방향지시등 {count}건이 확인됐어요. 회전할 방향을 확인해 주세요."),
    TURN_PENALTY("방향지시등 감점은 합계 {points}점이에요."),
    MATCHED("확인된 회전 {count}건에서 방향지시등을 미리 맞게 켰어요."),
    UNVERIFIED("회전 {count}건은 자료가 부족해 판정하지 않았어요. 감점하지 않아요.");

    fun render(vararg values: Pair<String, Any>): String {
        val result=values.fold(template) { text, (key,value) -> text.replace("{$key}", value.toString()) }
        check('{' !in result && '}' !in result) { "Missing feedback value for $name" }
        return result
    }
}
