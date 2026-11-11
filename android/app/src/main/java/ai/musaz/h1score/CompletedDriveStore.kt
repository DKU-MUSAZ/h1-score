package ai.musaz.h1score

import android.content.SharedPreferences
import ai.musaz.h1score.core.*
import org.json.JSONArray
import org.json.JSONObject

internal data class CompletedDrive(
    val session: ChallengeSession, val report: ScoreReport, val name: String,
    val demo: Boolean, val ranking: List<RankedEntry>, val earned: Int, val missedDeadline: Boolean,
)

internal class CompletedDriveStore(private val prefs: SharedPreferences) {
    fun save(value: CompletedDrive) {
        val s=value.session; val r=value.report
        val json=JSONObject().apply {
            put("id",s.id);put("destinationId",s.destinationId);put("initialDistanceM",s.initialDistanceM)
            put("venueId",s.venueId);put("competitionId",s.competitionId)
            put("name",value.name);put("demo",value.demo);put("earned",value.earned);put("missedDeadline",value.missedDeadline)
            put("score",r.score);put("compliance",r.compliance);put("smoothness",r.smoothness)
            put("distance",r.validDistanceM);put("coverage",r.coverage);put("valid",r.valid)
            put("accelerationEvents",r.accelerationEvents);put("overLimitDistance",r.overLimitDistanceM)
            put("events",JSONArray().apply {r.events.forEach {event -> put(JSONObject().apply {
                put("type",event.type.name);put("elapsedMs",event.elapsedMs);put("distanceM",event.distanceM)
                put("fromKph",event.fromKph);put("toKph",event.toKph);put("limitKph",event.limitKph)
            })}})
            put("ranking",JSONArray().apply {value.ranking.forEach {put(JSONObject().apply {
                put("id",it.id);put("score",it.score);put("rank",it.rank);put("miles",it.miles)
            })}})
        }
        // Persist before a notification can outlive this process.
        prefs.edit().putString("last-completed-drive-v1",json.toString()).commit()
    }
    fun read(): CompletedDrive? = runCatching {
        val j=JSONObject(prefs.getString("last-completed-drive-v1",null) ?: return null)
        val rows=j.getJSONArray("ranking")
        CompletedDrive(
            ChallengeSession(j.getString("id"),j.getString("destinationId"),j.getDouble("initialDistanceM"),Phase.FINISHED,
                venueId=j.optString("venueId").takeIf {it.isNotEmpty()},competitionId=j.optString("competitionId").takeIf {it.isNotEmpty()}),
            ScoreReport(if(j.has("score")) j.getInt("score") else null,j.getDouble("compliance"),j.getDouble("smoothness"),
                j.getDouble("distance"),j.getDouble("coverage"),j.getBoolean("valid"),j.getInt("accelerationEvents"),j.getDouble("overLimitDistance"),
                j.optJSONArray("events")?.let {events -> List(events.length()) {index ->
                    val event=events.getJSONObject(index)
                    DrivingEvent(DrivingEventType.valueOf(event.getString("type")),event.getLong("elapsedMs"),
                        event.getDouble("distanceM"),event.getDouble("fromKph"),event.getDouble("toKph"),event.getInt("limitKph"))
                }} ?: emptyList()),
            j.getString("name"),j.getBoolean("demo"),List(rows.length()) {i->rows.getJSONObject(i).let {RankedEntry(it.getString("id"),it.getInt("score"),it.getInt("rank"),it.getInt("miles"))}},
            j.getInt("earned"),j.getBoolean("missedDeadline"))
    }.getOrNull()
}
