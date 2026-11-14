package ai.musaz.h1score.core

import kotlin.math.*

enum class TurnDirection { LEFT, RIGHT }
enum class SignalState { OFF, LEFT, RIGHT }
enum class TurnVerdict { MATCHED, UNUSED, MISMATCHED, UNVERIFIABLE, LATE }
enum class ManeuverKind { TURN, LANE_CHANGE }
enum class TurnReason { NONE, GPS_UNAVAILABLE, MOTION_UNAVAILABLE, STEERING_UNAVAILABLE,
    SIGNAL_UNAVAILABLE, HAZARD_OR_UNKNOWN, NO_TURN_CONTEXT, OBSERVING, INCOMPLETE,
    GEOMETRY_UNCERTAIN, SIGNAL_HISTORY_INSUFFICIENT, LANE_OBSERVATION_ONLY }
data class GpsFix(val atMs: Long, val latitude: Double, val longitude: Double,
                  val accuracyM: Double, val bearingDeg: Double, val bearingAccuracyDeg: Double) {
    fun usable(now: Long) = now-atMs in 0..1500 && latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 && accuracyM.isFinite() && accuracyM in 0.0..10.0 &&
        bearingDeg.isFinite() && bearingDeg in 0.0..360.0 && bearingAccuracyDeg.isFinite() && bearingAccuracyDeg in 0.0..15.0
}
data class TurnObservation(val atMs: Long, val gps: GpsFix?, val speedMps: Double?,
    val forward: Boolean, val steeringDeg: Double?, val signal: SignalState?,
    val instruction: TurnDirection?, val instructionDistanceM: Double?, val instructionAtMs: Long,
    val signalAtMs: Long = atMs, val steeringAtMs: Long = atMs,
    val hazardOn: Boolean? = false)
data class TurnEvent(val startedAtMs: Long, val endedAtMs: Long, val direction: TurnDirection,
                     val verdict: TurnVerdict, val kind: ManeuverKind = ManeuverKind.TURN,
                     val reason: TurnReason = TurnReason.NONE,
                     val observedSignal: TurnVerdict? = null) {
    val penalty get() = if(kind == ManeuverKind.LANE_CHANGE) 0.0 else when(verdict) {
        TurnVerdict.UNUSED, TurnVerdict.LATE -> 1.0; TurnVerdict.MISMATCHED -> 2.0; else -> 0.0 }
}

/** Signal intent must precede the manoeuvre; receiving a cached sample again adds no duration. */
internal fun signalVerdict(observations: List<TurnObservation>, start: Long, direction: TurnDirection): TurnVerdict {
    val relevant=observations.filter { it.atMs >= start-3000 }
    if(relevant.any { it.signal==null || it.hazardOn!=false || it.atMs-it.signalAtMs !in 0..750 })
        return TurnVerdict.UNVERIFIABLE
    // Use the same freshness lease as individual readings. A 250ms poll can skip a delivery;
    // 504ms between unchanged readings is not evidence that the driver turned the signal off.
    if(relevant.distinctBy{it.signalAtMs}.zipWithNext().any{(a,b)->b.signalAtMs-a.signalAtMs !in 1..750})
        return TurnVerdict.UNVERIFIABLE
    val before=relevant.filter { it.signalAtMs <= start }.distinctBy { it.signalAtMs }
    if(before.isEmpty() || start-before.first().signalAtMs<500)
        return TurnVerdict.UNVERIFIABLE
    val matching=if(direction==TurnDirection.LEFT) SignalState.LEFT else SignalState.RIGHT
    val opposite=if(matching==SignalState.LEFT) SignalState.RIGHT else SignalState.LEFT
    val lastActive=before.lastOrNull { it.signal!=SignalState.OFF && it.signalAtMs>=start-1000 }
    fun sustained(state: SignalState): Boolean {
        var runStart: Long?=null
        var previous: Long?=null
        var qualifiedAt: Long?=null
        for(o in before) {
            if(o.signal==state) {
                if(runStart==null || previous?.let { o.signalAtMs-it>750 }==true) runStart=o.signalAtMs
                if(o.signalAtMs-runStart!!>=500) qualifiedAt=o.signalAtMs
            } else runStart=null
            previous=o.signalAtMs
        }
        return qualifiedAt?.let { start-it in 0..1000 }==true
    }
    if(lastActive?.signal==opposite) return if(sustained(opposite)) TurnVerdict.MISMATCHED else TurnVerdict.UNVERIFIABLE
    if(sustained(matching)) return TurnVerdict.MATCHED
    return if(relevant.any { it.atMs>start && it.signal==matching }) TurnVerdict.LATE else TurnVerdict.UNUSED
}

internal fun turnInputReason(o: TurnObservation): TurnReason = when {
    o.gps?.usable(o.atMs)!=true -> TurnReason.GPS_UNAVAILABLE
    !o.forward || o.speedMps?.let { it.isFinite() && it in 2.0..25.0 }!=true -> TurnReason.MOTION_UNAVAILABLE
    o.steeringDeg?.let { it.isFinite() && abs(it)<=540 }!=true || o.atMs-o.steeringAtMs !in 0..750 -> TurnReason.STEERING_UNAVAILABLE
    o.signal==null || o.atMs-o.signalAtMs !in 0..750 -> TurnReason.SIGNAL_UNAVAILABLE
    o.hazardOn!=false -> TurnReason.HAZARD_OR_UNKNOWN
    else -> TurnReason.NONE
}

/** Conservative prototype. Navigation confirms a turn context; GPS confirms the executed turn.
 * No road geometry, lane-change or U-turn inference is made from a steering angle alone. */
class TurnDetector {
    private data class Candidate(val start: TurnObservation, val direction: TurnDirection,
        val before: GpsFix, val observations: MutableList<TurnObservation>, var neutralAt: Long? = null)
    private val history = ArrayDeque<TurnObservation>()
    private var candidate: Candidate? = null
    private var lastAt: Long? = null
    private var cooldownUntil = Long.MIN_VALUE
    private var needsNeutral = true
    val events = mutableListOf<TurnEvent>()
    var status = TurnReason.NO_TURN_CONTEXT; private set
    val observing get() = candidate!=null
    fun reset() { history.clear(); candidate=null; lastAt=null; cooldownUntil=Long.MIN_VALUE; needsNeutral=true; events.clear(); status=TurnReason.NO_TURN_CONTEXT }
    fun interrupt() {
        candidate?.let { events += TurnEvent(it.start.atMs,lastAt ?: it.start.atMs,it.direction,TurnVerdict.UNVERIFIABLE,reason=TurnReason.INCOMPLETE) }
        candidate=null;history.clear();needsNeutral=true;lastAt=null
        status=TurnReason.INCOMPLETE
    }
    private fun good(o: TurnObservation) = turnInputReason(o)==TurnReason.NONE
    fun accept(o: TurnObservation) {
        if(lastAt?.let { o.atMs<=it }==true) return
        val gap=lastAt?.let { o.atMs-it>1500 }==true
        lastAt=o.atMs
        status=turnInputReason(o).let { if(it==TurnReason.NONE) TurnReason.NO_TURN_CONTEXT else it }
        val c=candidate
        if(c!=null) {
            c.observations += o
            if(gap || !good(o) || o.atMs-c.start.atMs>20_000) {
                events += TurnEvent(c.start.atMs,o.atMs,c.direction,TurnVerdict.UNVERIFIABLE,
                    reason=if(status==TurnReason.NO_TURN_CONTEXT) TurnReason.INCOMPLETE else status)
                candidate=null;needsNeutral=true
            } else {
                status=TurnReason.OBSERVING
                if(abs(o.steeringDeg!!)<=2) { if(c.neutralAt==null)c.neutralAt=o.atMs } else c.neutralAt=null
                if(c.neutralAt?.let { o.atMs-it>=1000 }==true) {
                    events += finish(c,o)
                    status=events.last().reason
                    candidate=null;cooldownUntil=o.atMs+3000;needsNeutral=false
                }
            }
        } else {
            if(gap) { history.clear();needsNeutral=true }
            if(good(o) && abs(o.steeringDeg!!)<=2) needsNeutral=false
            val dir=o.steeringDeg?.let { if(it<=-5)TurnDirection.LEFT else if(it>=5)TurnDirection.RIGHT else null }
            val before=history.lastOrNull { it.atMs<o.atMs && o.atMs-it.atMs<=1500 && good(it) && abs(it.steeringDeg!!)<=2 }
            if(!needsNeutral && o.atMs>=cooldownUntil && good(o) && dir!=null && before!=null &&
                o.instruction==dir && o.atMs-o.instructionAtMs in 0..10_000 &&
                o.instructionDistanceM?.let { it.isFinite() && it in 0.0..40.0 }==true) {
                candidate=Candidate(o,dir,before.gps!!,(history.toList()+o).toMutableList())
                needsNeutral=true
                status=TurnReason.OBSERVING
            }
        }
        history.addLast(o)
        while(history.isNotEmpty() && o.atMs-history.first().atMs>3000) history.removeFirst()
    }
    private fun finish(c: Candidate, end: TurnObservation): TurnEvent {
        val active=c.observations.filter { it.atMs>=c.start.atMs && abs(it.steeringDeg ?: 0.0)>=5 }
        val delta=((end.gps!!.bearingDeg-c.before.bearingDeg+540)%360)-180
        val sign=if(c.direction==TurnDirection.LEFT)-1 else 1
        val turn=delta*sign
        val travel=distance(c.before,end.gps)
        val sustained=active.any { it.steeringAtMs-c.start.steeringAtMs>=500 && (it.steeringDeg!!*sign)>=5 }
        // Countersteering or a location/signal gap is insufficient evidence for a penalty.
        val reliable=c.observations.filter { it.atMs>=c.start.atMs }.all(::good) &&
            c.observations.zipWithNext().all { (a,b)->b.atMs-a.atMs<=1500 }
        val uniqueGps=c.observations.filter { it.atMs>=c.start.atMs }.mapNotNull { it.gps?.atMs }.distinct()
        val gpsContinuous=c.observations.distinctBy { it.gps?.atMs }.zipWithNext().all { (a,b) ->
            val ga=a.gps; val gb=b.gps
            if(ga==null || gb==null) false else {
                val seconds=(gb.atMs-ga.atMs)/1000.0
                seconds>0 && seconds<=1.5 && distance(ga,gb)<=
                    max(a.speedMps ?: 0.0,b.speedMps ?: 0.0)*seconds*2+ga.accuracyM+gb.accuracyM
            }
        }
        val verdict=when {
            !reliable || !gpsContinuous || !sustained || turn !in 35.0..135.0 || travel<15 ||
                active.any { it.steeringDeg!!*sign<=-5 } || uniqueGps.size<3 -> TurnVerdict.UNVERIFIABLE
            else -> signalVerdict(c.observations,c.start.atMs,c.direction)
        }
        return TurnEvent(c.start.atMs,end.atMs,c.direction,verdict,
            reason=if(verdict==TurnVerdict.UNVERIFIABLE) {
                if(c.start.atMs-(c.observations.firstOrNull()?.signalAtMs ?: c.start.atMs)<500)
                    TurnReason.SIGNAL_HISTORY_INSUFFICIENT else TurnReason.GEOMETRY_UNCERTAIN
            } else TurnReason.NONE)
    }
    private fun distance(a: GpsFix,b: GpsFix): Double {
        val dLat=Math.toRadians(b.latitude-a.latitude);val dLon=Math.toRadians(b.longitude-a.longitude)
        val h=sin(dLat/2).pow(2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(dLon/2).pow(2)
        return 6371000*2*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
}
