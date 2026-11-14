package ai.musaz.h1score.core

import kotlin.math.*

/** Detects a lane-change-like path, not a lane boundary. Never produces a scored event. */
class LaneChangeDetector {
    private data class Candidate(val start: TurnObservation, val before: GpsFix,
        val direction: TurnDirection, val observations: MutableList<TurnObservation>,
        var counterAt: Long?=null, var neutralAt: Long?=null)
    private val history=ArrayDeque<TurnObservation>()
    private var candidate: Candidate?=null
    private var last: TurnObservation?=null
    private var cooldownUntil=Long.MIN_VALUE
    val events=mutableListOf<TurnEvent>()
    fun reset() { history.clear(); candidate=null; last=null; cooldownUntil=Long.MIN_VALUE; events.clear() }
    fun interrupt() { history.clear(); candidate=null; last=null }
    private fun good(o: TurnObservation)=turnInputReason(o)==TurnReason.NONE &&
        o.gps!!.accuracyM<=1.0 && o.speedMps!!>=5.0 &&
        !(o.instruction!=null && o.atMs-o.instructionAtMs in 0..10_000 &&
            o.instructionDistanceM?.let{it in 0.0..80.0}==true)
    fun accept(o: TurnObservation) {
        val previous=last
        if(previous!=null && o.atMs<=previous.atMs) return
        if(!good(o)) { interrupt(); return }
        if(previous!=null) {
            if(o.gps!!.atMs==previous.gps!!.atMs) return
            val dt=(o.gps.atMs-previous.gps.atMs)/1000.0
            if(dt<=0 || dt>1.5 || distance(previous.gps,o.gps)>max(o.speedMps!!,previous.speedMps!!)*dt*2+2) {
                interrupt(); return
            }
        }
        last=o
        val c=candidate
        if(c==null) {
            val neutral=history.filter{it.atMs>=o.atMs-2500}
            val stable=neutral.size>=6 && neutral.last().atMs-neutral.first().atMs>=1750 &&
                neutral.all{abs(it.steeringDeg!!)<=2 && abs(delta(it.gps!!.bearingDeg,neutral.first().gps!!.bearingDeg))<=5}
            val dir=if(o.steeringDeg!!<=-5) TurnDirection.LEFT else if(o.steeringDeg>=5) TurnDirection.RIGHT else null
            if(stable && dir!=null && o.atMs>=cooldownUntil) {
                candidate=Candidate(o,neutral.last().gps!!,dir,(history.toList()+o).toMutableList())
            }
        } else {
            c.observations+=o
            val sign=if(c.direction==TurnDirection.LEFT)-1 else 1
            val angle=o.steeringDeg!!*sign
            if(o.atMs-c.start.atMs>12_000 || abs(delta(o.gps!!.bearingDeg,c.before.bearingDeg))>25) {
                interrupt(); return
            }
            if(angle<=-5 && c.counterAt==null) c.counterAt=o.steeringAtMs
            if(c.counterAt!=null && abs(angle)<=2) {
                if(c.neutralAt==null) c.neutralAt=o.steeringAtMs
            } else c.neutralAt=null
            if(c.neutralAt?.let{o.steeringAtMs-it>=2000}==true) {
                val firstPhase=c.observations.filter{it.atMs>=c.start.atMs && it.steeringDeg!!*sign>=5}
                val secondPhase=c.observations.filter{it.steeringDeg!!*sign<=-5}
                fun sustained(list: List<TurnObservation>)=list.size>=3 && list.last().steeringAtMs-list.first().steeringAtMs>=500
                val dx=(o.gps.longitude-c.before.longitude)*111195*cos(Math.toRadians(c.before.latitude))
                val dy=(o.gps.latitude-c.before.latitude)*111195
                val heading=Math.toRadians(c.before.bearingDeg)
                val lateral=dx*cos(heading)-dy*sin(heading)
                val forward=dx*sin(heading)+dy*cos(heading)
                val endStable=c.observations.filter{it.atMs>=c.neutralAt!!}.all {
                    abs(delta(it.gps!!.bearingDeg,c.before.bearingDeg))<=10 }
                if(sustained(firstPhase) && sustained(secondPhase) && endStable && forward>=15 &&
                    lateral*sign in max(2.5,2*max(c.before.accuracyM,o.gps.accuracyM)+1)..5.0) {
                    events+=TurnEvent(c.start.atMs,o.atMs,c.direction,TurnVerdict.UNVERIFIABLE,
                        ManeuverKind.LANE_CHANGE,TurnReason.LANE_OBSERVATION_ONLY,
                        signalVerdict(c.observations,c.start.atMs,c.direction))
                }
                candidate=null; history.clear(); cooldownUntil=o.atMs+3000
            }
        }
        history.addLast(o)
        while(history.isNotEmpty() && o.atMs-history.first().atMs>3000) history.removeFirst()
    }
    private fun delta(a:Double,b:Double)=((a-b+540)%360)-180
    private fun distance(a:GpsFix,b:GpsFix):Double {
        val x=(b.longitude-a.longitude)*111195*cos(Math.toRadians(a.latitude))
        val y=(b.latitude-a.latitude)*111195
        return hypot(x,y)
    }
}
