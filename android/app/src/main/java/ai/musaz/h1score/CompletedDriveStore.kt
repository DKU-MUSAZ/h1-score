package ai.musaz.h1score

import android.content.SharedPreferences
import ai.musaz.h1score.core.*
import org.json.JSONArray
import org.json.JSONObject

internal data class CompletedDrive(
    val session: ChallengeSession, val report: ScoreReport, val name: String,
    val demo: Boolean, val ranking: List<RankedEntry>, val earned: Int, val missedDeadline: Boolean,
    val completedAtEpochMs: Long? = null,
    val scoringVersion: String = "speed70-accel30-v1",
)

internal class CompletedDriveStore(private val prefs: SharedPreferences) {
    fun save(value: CompletedDrive) = synchronized(lock) {
        val existing = readAllLocked()
        val previous = existing.firstOrNull { it.session.id == value.session.id }
        val saved = value.copy(completedAtEpochMs = value.completedAtEpochMs ?: previous?.completedAtEpochMs)
        val records = newestFirst(listOf(saved) + existing.filterNot { it.session.id == saved.session.id })
        // One synchronous edit keeps the history and notification's latest result together.
        prefs.edit()
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
            put("completedAtEpochMs", value.completedAtEpochMs); put("scoringVersion", value.scoringVersion)
            put("score", r.score); put("compliance", r.compliance); put("smoothness", r.smoothness)
            put("distance", r.validDistanceM); put("coverage", r.coverage); put("valid", r.valid)
            put("accelerationEvents", r.accelerationEvents); put("overLimitDistance", r.overLimitDistanceM)
            put("events", JSONArray().apply { r.events.forEach { event -> put(JSONObject().apply {
                put("type", event.type.name); put("elapsedMs", event.elapsedMs); put("distanceM", event.distanceM)
                put("fromKph", event.fromKph); put("toKph", event.toKph); put("limitKph", event.limitKph)
            }) } })
            put("ranking", JSONArray().apply { value.ranking.forEach { put(JSONObject().apply {
                put("id", it.id); put("score", it.score); put("rank", it.rank); put("miles", it.miles)
            }) } })
        }
    }

    private fun decode(j: JSONObject): CompletedDrive? = runCatching {
        val rows = j.getJSONArray("ranking")
        val events = j.optJSONArray("events")
        CompletedDrive(
            session = ChallengeSession(
                j.getString("id"), j.getString("destinationId"), j.getDouble("initialDistanceM"), Phase.FINISHED,
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
            ),
            name = j.getString("name"),
            demo = j.getBoolean("demo"),
            ranking = List(rows.length()) { index -> rows.getJSONObject(index).let {
                RankedEntry(it.getString("id"), it.getInt("score"), it.getInt("rank"), it.getInt("miles"))
            } },
            earned = j.getInt("earned"),
            missedDeadline = j.getBoolean("missedDeadline"),
            completedAtEpochMs = if (j.has("completedAtEpochMs") && !j.isNull("completedAtEpochMs")) j.getLong("completedAtEpochMs") else null,
            scoringVersion = j.optString("scoringVersion", "speed70-accel30-v1"),
        )
    }.getOrNull()

    private companion object {
        const val HISTORY_KEY = "completed-drives-v1"
        const val LATEST_KEY = "last-completed-drive-v1"
        val lock = Any()
    }
}
