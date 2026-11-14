package ai.musaz.h1score.core

import kotlin.math.*

enum class TurnDirection { LEFT, RIGHT }
enum class SignalState { OFF, LEFT, RIGHT }
enum class TurnVerdict { MATCHED, UNUSED, MISMATCHED, UNVERIFIABLE }
data class GpsFix(val atMs: Long, val latitude: Double, val longitude: Double,
                  val accuracyM: Double, val bearingDeg: Double, val bearingAccuracyDeg: Double) {
    fun usable(now: Long) = now-atMs in 0..1500 && latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 && accuracyM.isFinite() && accuracyM in 0.0..10.0 &&
        bearingDeg.isFinite() && bearingDeg in 0.0..360.0 && bearingAccuracyDeg.isFinite() && bearingAccuracyDeg in 0.0..15.0
}
data class TurnObservation(val atMs: Long, val gps: GpsFix?, val speedMps: Double?,
    val forward: Boolean, val steeringDeg: Double?, val signal: SignalState?,
    val instruction: TurnDirection?, val instructionDistanceM: Double?, val instructionAtMs: Long)
data class TurnEvent(val startedAtMs: Long, val endedAtMs: Long, val direction: TurnDirection,
                     val verdict: TurnVerdict) {
    val penalty get() = when(verdict) { TurnVerdict.UNUSED -> 1.0; TurnVerdict.MISMATCHED -> 2.0; else -> 0.0 }
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
    fun reset() { history.clear(); candidate=null; lastAt=null; cooldownUntil=Long.MIN_VALUE; needsNeutral=true; events.clear() }
    fun interrupt() {
        candidate?.let { events += TurnEvent(it.start.atMs,lastAt ?: it.start.atMs,it.direction,TurnVerdict.UNVERIFIABLE) }
        candidate=null;history.clear();needsNeutral=true;lastAt=null
    }
    private fun good(o: TurnObservation) = o.gps?.usable(o.atMs)==true && o.forward &&
        o.speedMps?.let { it.isFinite() && it in 2.0..25.0 }==true &&
        o.steeringDeg?.let { it.isFinite() && abs(it)<=60 }==true && o.signal!=null
    fun accept(o: TurnObservation) {
        if(lastAt?.let { o.atMs<=it }==true) return
        val gap=lastAt?.let { o.atMs-it>1500 }==true
        lastAt=o.atMs
        val c=candidate
        if(c!=null) {
            c.observations += o
            if(gap || !good(o) || o.atMs-c.start.atMs>20_000) {
                events += TurnEvent(c.start.atMs,o.atMs,c.direction,TurnVerdict.UNVERIFIABLE)
                candidate=null;needsNeutral=true
            } else {
                if(abs(o.steeringDeg!!)<=2) { if(c.neutralAt==null)c.neutralAt=o.atMs } else c.neutralAt=null
                if(c.neutralAt?.let { o.atMs-it>=1000 }==true) {
                    events += finish(c,o)
                    candidate=null;cooldownUntil=o.atMs+3000;needsNeutral=false
                }
            }
        } else {
            if(gap) { history.clear();needsNeutral=true }
            if(good(o) && abs(o.steeringDeg!!)<=2) needsNeutral=false
            val dir=o.steeringDeg?.let { if(it<=-5)TurnDirection.LEFT else if(it>=5)TurnDirection.RIGHT else null }
            val before=history.lastOrNull { it.atMs<o.atMs && o.atMs-it.atMs<=1500 && good(it) && abs(it.steeringDeg!!)<=2 }
            if(!needsNeutral && o.atMs>=cooldownUntil && good(o) && dir!=null && before!=null &&
                o.instruction==dir && o.atMs-o.instructionAtMs in 0..3000 &&
                o.instructionDistanceM?.let { it.isFinite() && it in 0.0..40.0 }==true) {
                candidate=Candidate(o,dir,before.gps!!,(history.toList()+o).toMutableList())
                needsNeutral=true
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
        val sustained=active.any { it.atMs-c.start.atMs>=500 && (it.steeringDeg!!*sign)>=5 }
        // Countersteering or a location/signal gap is insufficient evidence for a penalty.
        val reliable=c.observations.filter { it.atMs>=c.start.atMs }.all(::good) &&
            c.observations.zipWithNext().all { (a,b)->b.atMs-a.atMs<=1500 }
        val signals=c.observations.filter { it.atMs>=c.start.atMs-3000 }
        val matching=if(c.direction==TurnDirection.LEFT)SignalState.LEFT else SignalState.RIGHT
        val opposite=if(matching==SignalState.LEFT)SignalState.RIGHT else SignalState.LEFT
        val verdict=when {
            !reliable || !sustained || turn !in 35.0..135.0 || travel<15 ||
                active.any { it.steeringDeg!!*sign<=-5 } || signals.any { it.signal==null } -> TurnVerdict.UNVERIFIABLE
            signals.any { it.signal==matching } -> TurnVerdict.MATCHED
            signals.any { it.signal==opposite } -> TurnVerdict.MISMATCHED
            else -> TurnVerdict.UNUSED
        }
        return TurnEvent(c.start.atMs,end.atMs,c.direction,verdict)
    }
    private fun distance(a: GpsFix,b: GpsFix): Double {
        val dLat=Math.toRadians(b.latitude-a.latitude);val dLon=Math.toRadians(b.longitude-a.longitude)
        val h=sin(dLat/2).pow(2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(dLon/2).pow(2)
        return 6371000*2*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
}
