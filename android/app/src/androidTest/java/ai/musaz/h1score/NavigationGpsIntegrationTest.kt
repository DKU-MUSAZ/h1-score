package ai.musaz.h1score

import android.location.Location
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.location.provider.ProviderProperties
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Device integration diagnostic. Location enters Android's provider, never the score model.
 * Navigation callbacks are supplied by the installed navigation app, never fabricated here.
 * Does not create a game, history entry or mileage award. */
class NavigationGpsIntegrationTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()

    @Test fun inspectNavigationAndLocation() {
        val runner=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit emulator GPS integration opt-in required",args.getString("liveGps")=="true")
        val mock=args.getString("gpsProvider","native")=="mock"
        val seconds=args.getString("seconds","60").toInt().coerceIn(10,180)
        val lm=runner.targetContext.getSystemService(LocationManager::class.java)
        lateinit var connection:PleosConnection
        lateinit var helper:NaviHelper
        var ready=false
        var current:CurrentLocationInfo?=null
        var tbt:List<TBTInfo> = emptyList()
        var lastGps:ai.musaz.h1score.core.GpsFix?=null
        var sharedPositionSamples=0
        var nmeaSamples=0
        var validNmeaSamples=0
        fun report(key:String,value:String)=runner.sendStatus(0,Bundle().apply{putString(key,value)})
        val nmeaListener=OnNmeaMessageListener { message,_ ->
            val raw=message.trim()
            nmeaSamples++
            val split=raw.indexOf('*')
            if(raw.startsWith('$') && split>1) {
                val checksum=raw.substring(1,split).fold(0){a,c->a xor c.code}
                if(raw.substring(split+1).toIntOrNull(16)==checksum)validNmeaSamples++
            }
            report("received-nmea",raw)
        }
        val listener=object:NaviHelperEventListener {
            override fun onCurrentLocationInfo(currentLocationInfo:CurrentLocationInfo) { current=currentLocationInfo }
            override fun onTBTInfo(tbtInfoList:List<TBTInfo>) {tbt=tbtInfoList;report("native-tbt",tbtInfoList.toString())}
            override fun onRouteStarted(routeStartInfo:RouteStartInfo) {report("native-route",routeStartInfo.toString())}
            override fun onError(errorCode:NaviErrorCode) {report("native-error",errorCode.toString())}
        }
        var providerAdded=false
        try {
            if(mock) {
                lm.addTestProvider("gps",ProviderProperties.Builder().setHasSatelliteRequirement(true)
                    .setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW).setAccuracy(ProviderProperties.ACCURACY_FINE).build())
                providerAdded=true
                lm.setTestProviderEnabled("gps",true)
            }
            runner.runOnMainSync {
                connection=PleosConnection(runner.targetContext);connection.start()
                lm.addNmeaListener(nmeaListener,android.os.Handler(android.os.Looper.getMainLooper()))
                helper=NaviHelper(runner.targetContext)
                helper.initialize({helper.addListener(listener);ready=true},{report("native-error",it.toString())})
            }
            var requested=false
            repeat(seconds*4) { index ->
                if(mock) {
                    val latitude=37.4981+if(index>=120)0.001 else 0.0
                    lm.setTestProviderLocation("gps",Location("gps").apply {
                        this.latitude=latitude;longitude=127.0631;altitude=30.0;accuracy=0.5f
                        bearing=0f;bearingAccuracyDegrees=2f;speed=8.333f;speedAccuracyMetersPerSecond=.1f
                        time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                    })
                }
                runner.runOnMainSync {
                    if(ready && index%4==0) {helper.getCurrentLocationInfo();helper.getTBTInfo()}
                    if(ready && !requested && index>=32 && args.getString("requestRoute")=="true") {
                        helper.requestRoute(RouteInfo(latitude=37.505,longitude=127.0631,
                            displayLatitude=null,displayLongitude=null,poiName="GPS 연동 검증 목적지",poiId="",poiSubId="",address=""))
                        requested=true
                    }
                    if(index%4==0) {
                        val live=connection.flow.value
                        lastGps=live.gps
                        val nav=current
                        if(live.gps!=null && nav!=null &&
                            kotlin.math.abs(live.gps.latitude-nav.latitude)<.0002 &&
                            kotlin.math.abs(live.gps.longitude-nav.longitude)<.0002)sharedPositionSamples++
                        report("gps-integration","tick=$index mock=$mock gps=${live.gps} nav=$current tbt=$tbt appTurn=${live.instruction} distance=${live.instructionDistanceM}")
                    }
                }
                Thread.sleep(250)
            }
            assertTrue("NaviHelper connection not established",ready)
            report("native-summary","sharedPositionSamples=$sharedPositionSamples nmea=$nmeaSamples validNmea=$validNmeaSamples gps=$lastGps nav=$current")
            assertTrue("Android GPS and built-in navigation did not share the injected position. See native-summary; this is NOT a turn-verdict pass.",sharedPositionSamples>=3)
        } finally {
            runner.runOnMainSync {
                if(args.getString("cancelRouteOnFinish")=="true")runCatching{helper.cancelRoute()}
                runCatching{helper.removeListener(listener)};runCatching{helper.release()}
                runCatching{connection.stop()}
                lm.removeNmeaListener(nmeaListener)
            }
            if(providerAdded)lm.removeTestProvider("gps")
        }
    }
}
