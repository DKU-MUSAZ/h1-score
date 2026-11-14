package ai.musaz.h1score

import ai.musaz.h1score.core.*
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** HYBRID validation: real console steering/signal/hazard/speed, synthetic GPS and navigation.
 * Never starts a game or writes reward/history data. Not an end-to-end navigation test. */
class TurnConsoleReplayTest {
    @Test fun replayTurnsWithActualSdkSignals() {
        val runner=InstrumentationRegistry.getInstrumentation()
        val only=InstrumentationRegistry.getArguments().getString("case","all")
        val repetitions=InstrumentationRegistry.getArguments().getString("repetitions","1").toInt().coerceIn(1,10)
        val commandTimeoutMs=InstrumentationRegistry.getArguments().getString("commandTimeoutSeconds","30").toLong().coerceIn(5,60)*1000
        lateinit var connection:PleosConnection
        runner.runOnMainSync{connection=PleosConnection(runner.targetContext);connection.start()}
        fun command(mode:String,angle:Int,hazard:Boolean=false,onObservation:(TurnObservation)->Unit={}) {
            val requestedAt=SystemClock.elapsedRealtime()
            runner.sendStatus(0,Bundle().apply{putString("console-command","$mode,$angle,$hazard")})
            val until=SystemClock.elapsedRealtime()+commandTimeoutMs
            while(SystemClock.elapsedRealtime()<until) {
                var ready=false
                runner.runOnMainSync {
                    val o=connection.flow.value.turnObservation(SystemClock.elapsedRealtime())
                    onObservation(o)
                    val signal=when(mode){"Off"->SignalState.OFF;"BlinkLeft"->SignalState.LEFT;else->SignalState.RIGHT}
                    ready=(hazard || o.signal==signal) && o.steeringDeg?.let{kotlin.math.abs(it-angle)<.5}==true && o.hazardOn==hazard &&
                        o.forward && o.speedMps?.let{abs(it-30.0/3.6)<.2}==true
                }
                if(ready) {
                    runner.sendStatus(0,Bundle().apply{putString("command-ready","angle=$angle waitMs=${SystemClock.elapsedRealtime()-requestedAt}")})
                    return
                }
                Thread.sleep(100)
            }
            fail("Actual SDK did not match console command: $mode,$angle,$hazard")
        }
        try {
            Thread.sleep(8000)
            val failures=mutableListOf<String>()
            for(scenario in listOf("left-match","right-match","unused","opposite","late","hazard","missing-gps",
                "auto-cancel","hazard-during","curve-no-nav","poor-gps").filter{only=="all" || only==it}) {
                val dir=if(scenario=="right-match")TurnDirection.RIGHT else TurnDirection.LEFT
                val sign=if(dir==TurnDirection.LEFT)-1 else 1
                val correct=if(sign<0)"BlinkLeft" else "BlinkRight"
                val mode=when(scenario){"left-match","right-match","hazard","missing-gps","auto-cancel","hazard-during"->correct;"opposite"->"BlinkRight";else->"Off"}
                val detector=ManeuverMonitor()
                val turnInputs=mutableListOf<TurnObservation>()
                runner.sendStatus(0,Bundle().apply{putString("hybrid-scenario",scenario)})
                command(mode,0,scenario=="hazard")
                val start=SystemClock.elapsedRealtime()
                var previousAt:Long?=null
                var forwardM=0.0
                fun observeTurn(actual:TurnObservation,index:Int) {
                    val now=actual.atMs
                    if(previousAt?.let{now-it<200}==true)return
                    previousAt?.let{forwardM+=8.0*(now-it)/1000.0}
                    previousAt=now
                    val progress=((index-12)/12.0).coerceIn(0.0,1.0)
                    val gps=if(scenario=="missing-gps")null else GpsFix(now,
                        37.0+forwardM/111195,127.0+sign*progress*.0003,if(scenario=="poor-gps")30.0 else 3.0,
                        (sign*90*progress+360)%360,5.0)
                    val input=actual.copy(gps=gps,instruction=if(scenario=="curve-no-nav")null else dir,
                        instructionDistanceM=20.0,instructionAtMs=now)
                    turnInputs+=input
                    detector.accept(input)
                }
                for(i in 0..32) {
                    val observing:(TurnObservation)->Unit={observeTurn(it,i-1)}
                    if(i==12) command(mode,sign*25,scenario=="hazard",observing)
                    if(i==18 && scenario=="late") command(correct,sign*25,onObservation=observing)
                    if(i==18 && scenario=="auto-cancel") command("Off",sign*25,onObservation=observing)
                    if(i==18 && scenario=="hazard-during") command(mode,sign*25,true,observing)
                    if(i==25) command(when(scenario){"late"->correct;"auto-cancel"->"Off";else->mode},0,
                        scenario in listOf("hazard","hazard-during"),observing)
                    runner.runOnMainSync {
                        observeTurn(connection.flow.value.turnObservation(SystemClock.elapsedRealtime()),i)
                    }
                    Thread.sleep(250)
                }
                val expected=when(scenario){"left-match","right-match","auto-cancel"->TurnVerdict.MATCHED;"unused"->TurnVerdict.UNUSED;
                    "opposite"->TurnVerdict.MISMATCHED;"late"->TurnVerdict.LATE;else->null}
                val passed=if(expected==null) detector.events.none{it.penalty>0} && when(scenario) {
                    "hazard","hazard-during" -> detector.diagnostics.containsKey(TurnReason.HAZARD_OR_UNKNOWN)
                    "missing-gps","poor-gps" -> detector.diagnostics.containsKey(TurnReason.GPS_UNAVAILABLE)
                    "curve-no-nav" -> detector.events.isEmpty()
                    else -> true
                } else detector.events.singleOrNull()?.verdict==expected
                if(!passed) {
                    failures+="$scenario expected=$expected events=${detector.events} diagnostics=${detector.diagnostics}"
                    turnInputs.forEachIndexed { i,o -> runner.sendStatus(0,Bundle().apply{putString("turn-input","$scenario $i: $o")}) }
                }
                runner.sendStatus(0,Bundle().apply{putString("hybrid-result",
                    "$scenario: events=${detector.events} diagnostics=${detector.diagnostics} elapsed=${SystemClock.elapsedRealtime()-start}")})
            }
            for(attempt in 1..repetitions) for(sign in listOf(-1,1).filter{only=="all" || only=="lanes"}) {
                val detector=ManeuverMonitor()
                val inputs=mutableListOf<TurnObservation>()
                runner.sendStatus(0,Bundle().apply{putString("hybrid-scenario","lane-candidate-$sign attempt=$attempt")})
                command("Off",0)
                var forwardM=0.0
                var loggedStart=false
                // Unlike command acknowledgements, observation must never pause while a console write is pending.
                // Only the path is synthetic; steering/signal/hazard/motion remain actual SDK values.
                fun observe(actual:TurnObservation,index:Int) {
                    val previous=inputs.lastOrNull()
                    val now=actual.atMs
                    if(previous!=null && now-previous.atMs<200)return
                    if(previous!=null)forwardM+=8.0*(now-previous.atMs)/1000.0
                    val p=((index-8)/16.0).coerceIn(0.0,1.0)
                    val x=sign*3.5*(1-cos(PI*p))/2
                    val bearing=(Math.toDegrees(atan2(sign*3.5*PI/32*sin(PI*p),2.0))+360)%360
                    val gps=GpsFix(now,37+forwardM/111195,127+x/(111195*cos(Math.toRadians(37.0))),.5,bearing,2.0)
                    val input=actual.copy(gps=gps,instruction=null,instructionDistanceM=null,instructionAtMs=0)
                    if(!loggedStart && abs(input.steeringDeg ?: 0.0)>=5) {
                        val neutral=inputs.filter{it.atMs>=now-2500}
                        val span=if(neutral.isEmpty())0 else neutral.last().atMs-neutral.first().atMs
                        runner.sendStatus(0,Bundle().apply{putString("lane-start-evidence",
                            "attempt=$attempt sign=$sign gap=${now-(previous?.atMs ?: now)} count=${neutral.size} span=$span angle=${input.steeringDeg}")})
                        loggedStart=true
                    }
                    inputs+=input
                    detector.accept(input)
                }
                for(i in 0..40) {
                    if(i==8) command("Off",sign*8,onObservation={observe(it,i-1)})
                    if(i==16) command("Off",-sign*8,onObservation={observe(it,i-1)})
                    if(i==24) command("Off",0,onObservation={observe(it,i-1)})
                    runner.runOnMainSync {
                        observe(connection.flow.value.turnObservation(SystemClock.elapsedRealtime()),i)
                    }
                    Thread.sleep(250)
                }
                if(detector.events.isEmpty()) inputs.forEachIndexed { i,o ->
                    runner.sendStatus(0,Bundle().apply{putString("lane-input","$i: $o")})
                }
                val event=detector.events.singleOrNull()
                if(event?.kind!=ManeuverKind.LANE_CHANGE || event.direction!=(if(sign<0)TurnDirection.LEFT else TurnDirection.RIGHT))
                    failures+="attempt=$attempt sign=$sign events=${detector.events} diagnostics=${detector.diagnostics}"
                assertTrue(detector.events.all{it.penalty==0.0})
                runner.sendStatus(0,Bundle().apply{putString("hybrid-result",
                    "lane-candidate-$sign attempt=$attempt maxGapMs=${inputs.zipWithNext().maxOf{(a,b)->b.atMs-a.atMs}}: ${detector.events}")})
            }
            assertTrue(failures.joinToString("\n"),failures.isEmpty())
        } finally {runner.runOnMainSync{connection.stop()}}
    }
}
