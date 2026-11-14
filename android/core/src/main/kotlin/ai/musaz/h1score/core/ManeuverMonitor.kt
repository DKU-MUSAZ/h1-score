package ai.musaz.h1score.core

/** Same observation pipeline is used by the game and console diagnostics. */
class ManeuverMonitor {
    private val turns=TurnDetector()
    private val lanes=LaneChangeDetector()
    private val counts=mutableMapOf<TurnReason,Int>()
    val events get()=(turns.events+lanes.events).sortedBy{it.startedAtMs}
    val diagnostics get()=counts.toMap()
    val status get()=turns.status
    fun reset() { turns.reset();lanes.reset();counts.clear() }
    fun interrupt() { turns.interrupt();lanes.interrupt() }
    fun accept(o:TurnObservation) {
        turns.accept(o)
        if(turns.observing) lanes.interrupt() else lanes.accept(o)
        if(o.forward && (o.speedMps ?: 0.0)>=2)
            counts[turns.status]=(counts[turns.status] ?: 0)+1
    }
}
