package ai.musaz.h1score

import android.content.SharedPreferences
import ai.musaz.h1score.core.*
import org.json.JSONArray
import org.json.JSONObject

internal enum class DriveOutcome(val label: String) {
    COMPLETED("완료"), DISQUALIFIED("실격"), UNVERIFIABLE("확인 불가"),
}

internal enum class BeltFailureReason(val label: String) {
    DRIVER_UNBUCKLED("운전석 안전벨트 미착용"),
    PASSENGER_UNBUCKLED("조수석 안전벨트 미착용"),
    TELEMETRY_UNAVAILABLE("안전벨트·주행 데이터 확인 불가"),
}

internal data class CompletedDrive(
    val session: ChallengeSession, val report: ScoreReport, val name: String,
    val demo: Boolean, val ranking: List<RankedEntry>, val earned: Int, val missedDeadline: Boolean,
    val completedAtEpochMs: Long? = null,
    val scoringVersion: String = report.scoringVersion,
    val badgeMileage: Int = 0,
    val outcome: DriveOutcome = DriveOutcome.COMPLETED,
    val beltFailureReason: BeltFailureReason? = null,
    val participationDay: String? = null,
    val recognition: ResultRecognition = ResultRecognition.LEGACY,
    val awardedBadges: List<BadgeAward> = emptyList(),
)

internal class CompletedDriveStore(private val prefs: SharedPreferences) {
    fun save(value: CompletedDrive, editor: SharedPreferences.Editor = prefs.edit()) = synchronized(lock) {
        val existing = readAllLocked()
        val previous = existing.firstOrNull { it.session.id == value.session.id }
        val saved = value.copy(completedAtEpochMs = value.completedAtEpochMs ?: previous?.completedAtEpochMs)
        val records = newestFirst(listOf(saved) + existing.filterNot { it.session.id == saved.session.id })
        // One synchronous edit keeps the history and notification's latest result together.
        editor
            .putString(HISTORY_KEY, encodeList(records).toString())
            .putString(LATEST_KEY, encode(saved).toString())
            .commit()
        Unit
    }

    fun readAll(): List<CompletedDrive> = synchronized(lock) {
        val records = readAllLocked()
        val storedIds = readHistory().map { it.session.id }.toSet()
        if (records.any { it.session.id !in storedIds }) {
            // Old results have no completion time. Preserve them without inventing a date.
            prefs.edit().putString(HISTORY_KEY, encodeList(records).toString()).commit()
        }
        records
    }

    // Notifications still open the last saved result, including pre-history installations.
    fun read(): CompletedDrive? = synchronized(lock) {
        readLatest() ?: readAllLocked().firstOrNull()
    }

    private fun readAllLocked(): List<CompletedDrive> {
        val records = readHistory().distinctBy { it.session.id }
        val legacy = readLatest()
        return newestFirst(if (legacy != null && records.none { it.session.id == legacy.session.id }) {
            records + legacy
        } else records)
    }

    private fun readHistory(): List<CompletedDrive> = runCatching {
        val raw = prefs.getString(HISTORY_KEY, null) ?: return emptyList()
        val rows = JSONArray(raw)
        List(rows.length()) { index -> rows.optJSONObject(index)?.let(::decode) }.filterNotNull()
    }.getOrDefault(emptyList())

    private fun readLatest(): CompletedDrive? = runCatching {
        prefs.getString(LATEST_KEY, null)?.let { decode(JSONObject(it)) }
    }.getOrNull()

    private fun newestFirst(records: List<CompletedDrive>) =
        records.sortedByDescending { it.completedAtEpochMs ?: Long.MIN_VALUE }

    private fun encodeList(records: List<CompletedDrive>) =
        JSONArray().apply { records.forEach { put(encode(it)) } }

    private fun encode(value: CompletedDrive): JSONObject {
        val s = value.session
        val r = value.report
        return JSONObject().apply {
            put("id", s.id); put("destinationId", s.destinationId); put("initialDistanceM", s.initialDistanceM)
            put("venueId", s.venueId); put("competitionId", s.competitionId)
            put("name", value.name); put("demo", value.demo); put("earned", value.earned); put("missedDeadline", value.missedDeadline)
            put("badgeMileage", value.badgeMileage)
            put("participationDay", value.participationDay)
            put("recognition", value.recognition.name)
            put("awardedBadges", JSONArray().apply { value.awardedBadges.forEach { award -> put(JSONObject().apply {
                put("id", award.id); put("title", award.title); put("description", award.description); put("mileage", award.mileage)
            }) } })
            put("outcome", value.outcome.name); put("beltFailureReason", value.beltFailureReason?.name)
            put("completedAtEpochMs", value.completedAtEpochMs); put("scoringVersion", value.scoringVersion)
            put("score", r.score); put("compliance", r.compliance); put("smoothness", r.smoothness)
            put("distance", r.validDistanceM); put("coverage", r.coverage); put("valid", r.valid)
            put("accelerationEvents", r.accelerationEvents); put("overLimitDistance", r.overLimitDistanceM)
            put("events", JSONArray().apply { r.events.forEach { event -> put(JSONObject().apply {
                put("type", event.type.name); put("elapsedMs", event.elapsedMs); put("distanceM", event.distanceM)
                put("fromKph", event.fromKph); put("toKph", event.toKph); put("limitKph", event.limitKph)
            }) } })
            if (value.scoringVersion != LEGACY_SCORING_VERSION) {
                put("speedPoints", r.speedPoints); put("accelerationPoints", r.accelerationPoints)
                put("brakingEvents", r.brakingEvents)
                put("longitudinalCoverage", r.longitudinalCoverage)
                put("longitudinalReady", r.longitudinalReady)
                put("longitudinalSampleRateHz", r.longitudinalSampleRateHz)
                put("kilometers", JSONArray().apply { r.kilometers.forEach { km -> put(JSONObject().apply {
                    put("kilometer", km.kilometer); put("distanceM", km.distanceM)
                    put("validDistanceM", km.validDistanceM); put("speedPoints", km.speedPoints)
                    put("accelerationPoints", km.accelerationPoints)
                }) } })
                put("turns", JSONArray().apply { r.turns.forEach { turn -> put(JSONObject().apply {
                    put("startedAtMs", turn.startedAtMs); put("endedAtMs", turn.endedAtMs)
                    put("direction", turn.direction.name); put("verdict", turn.verdict.name)
                    put("kind",turn.kind.name);put("reason",turn.reason.name)
                    put("observedSignal",turn.observedSignal?.name)
                }) } })
                put("turnDiagnostics",JSONObject().apply { r.turnDiagnostics.forEach{(reason,count)->put(reason.name,count)} })
                put("longitudinalEvents", JSONArray().apply { r.longitudinalEvents.forEach { event -> put(JSONObject().apply {
                    put("kind", event.kind.name); put("startedAtNanos", event.startedAtNanos)
                    put("endedAtNanos", event.endedAtNanos); put("peakAccelerationMps2", event.peakAccelerationMps2)
                    put("brakePressed", event.brakePressed)
                    put("qualifyingIntervals", JSONArray().apply { event.qualifyingIntervals.forEach { span -> put(JSONObject().apply {
                        put("startedAtNanos", span.startedAtNanos); put("endedAtNanos", span.endedAtNanos)
                    }) } })
                }) } })
            }
            put("ranking", JSONArray().apply { value.ranking.forEach { put(JSONObject().apply {
                put("id", it.id); put("score", it.score); put("rank", it.rank); put("miles", it.miles)
            }) } })
        }
    }

    private fun decode(j: JSONObject): CompletedDrive? = runCatching {
        val rows = j.getJSONArray("ranking")
        val events = j.optJSONArray("events")
        val version = j.optString("scoringVersion", LEGACY_SCORING_VERSION)
        val outcome = DriveOutcome.entries.firstOrNull { it.name == j.optString("outcome") }
            ?: if (j.has("outcome")) DriveOutcome.UNVERIFIABLE else DriveOutcome.COMPLETED
        CompletedDrive(
            session = ChallengeSession(
                j.getString("id"), j.getString("destinationId"), j.getDouble("initialDistanceM"),
                if (outcome == DriveOutcome.DISQUALIFIED) Phase.DISQUALIFIED else Phase.FINISHED,
                venueId = j.optString("venueId").takeIf { it.isNotEmpty() },
                competitionId = j.optString("competitionId").takeIf { it.isNotEmpty() },
            ),
            report = ScoreReport(
                if (j.has("score") && !j.isNull("score")) j.getInt("score") else null,
                j.getDouble("compliance"), j.getDouble("smoothness"), j.getDouble("distance"),
                j.getDouble("coverage"), j.getBoolean("valid"), j.getInt("accelerationEvents"),
                j.getDouble("overLimitDistance"),
                if (events == null) emptyList() else List(events.length()) { index ->
                    val event = events.getJSONObject(index)
                    DrivingEvent(
                        DrivingEventType.valueOf(event.getString("type")), event.getLong("elapsedMs"),
                        event.getDouble("distanceM"), event.getDouble("fromKph"),
                        event.getDouble("toKph"), event.getInt("limitKph"),
                    )
                },
                speedPoints = j.optDouble("speedPoints", 0.0),
                accelerationPoints = j.optDouble("accelerationPoints", 0.0),
                kilometers = j.optJSONArray("kilometers").objects().map { km ->
                    KmBonus(km.getInt("kilometer"), km.getDouble("distanceM"), km.getDouble("validDistanceM"),
                        km.getDouble("speedPoints"), km.getDouble("accelerationPoints"))
                },
                brakingEvents = j.optInt("brakingEvents", 0),
                turns = j.optJSONArray("turns").objects().map { turn ->
                    TurnEvent(turn.getLong("startedAtMs"), turn.getLong("endedAtMs"),
                        TurnDirection.valueOf(turn.getString("direction")), TurnVerdict.valueOf(turn.getString("verdict")),
                        runCatching{ManeuverKind.valueOf(turn.optString("kind"))}.getOrDefault(ManeuverKind.TURN),
                        runCatching{TurnReason.valueOf(turn.optString("reason"))}.getOrDefault(TurnReason.NONE),
                        runCatching{TurnVerdict.valueOf(turn.optString("observedSignal"))}.getOrNull())
                },
                longitudinalEvents = j.optJSONArray("longitudinalEvents").objects().map { event ->
                    LongitudinalEvent(LongitudinalKind.valueOf(event.getString("kind")),
                        event.getLong("startedAtNanos"), event.getLong("endedAtNanos"), event.getDouble("peakAccelerationMps2"),
                        if (event.has("brakePressed") && !event.isNull("brakePressed")) event.getBoolean("brakePressed") else null,
                        event.optJSONArray("qualifyingIntervals").objects().map { span ->
                            LongitudinalSpan(span.getLong("startedAtNanos"), span.getLong("endedAtNanos"))
                        })
                },
                longitudinalCoverage = j.optDouble("longitudinalCoverage", 0.0),
                longitudinalReady = j.optBoolean("longitudinalReady", false),
                longitudinalSampleRateHz = j.optDouble("longitudinalSampleRateHz", 0.0),
                scoringVersion = version,
                turnDiagnostics = TurnReason.entries.mapNotNull{reason->
                    j.optJSONObject("turnDiagnostics")?.optInt(reason.name,0)?.takeIf{it>0}?.let{reason to it}
                }.toMap(),
            ),
            name = j.getString("name"),
            demo = j.getBoolean("demo"),
            ranking = List(rows.length()) { index -> rows.getJSONObject(index).let {
                RankedEntry(it.getString("id"), it.getInt("score"), it.getInt("rank"), it.getInt("miles"))
            } },
            earned = j.getInt("earned"),
            missedDeadline = j.getBoolean("missedDeadline"),
            completedAtEpochMs = if (j.has("completedAtEpochMs") && !j.isNull("completedAtEpochMs")) j.getLong("completedAtEpochMs") else null,
            scoringVersion = version,
            badgeMileage = j.optInt("badgeMileage"),
            outcome = outcome,
            beltFailureReason = BeltFailureReason.entries.firstOrNull { it.name == j.optString("beltFailureReason") },
            participationDay = j.optString("participationDay").takeIf { it.isNotBlank() && it != "null" },
            recognition = ResultRecognition.entries.firstOrNull { it.name == j.optString("recognition") } ?: ResultRecognition.LEGACY,
            awardedBadges = j.optJSONArray("awardedBadges").objects().map {
                BadgeAward(it.getString("id"), it.getString("title"), it.getString("description"), it.getInt("mileage"))
            },
        )
    }.getOrNull()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private companion object {
        const val HISTORY_KEY = "completed-drives-v1"
        const val LATEST_KEY = "last-completed-drive-v1"
        val lock = Any()
    }
}
