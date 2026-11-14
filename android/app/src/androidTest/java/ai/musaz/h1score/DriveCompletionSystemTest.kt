package ai.musaz.h1score

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ai.musaz.h1score.core.ChallengeRound
import ai.musaz.h1score.core.Phase
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.EventListener
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import android.accessibilityservice.AccessibilityServiceInfo
import org.junit.Test
import org.junit.Before
import org.junit.After

/** Real Android notification/Activity integration; vehicle arrival is explicitly injected. */
class DriveCompletionSystemTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as H1Application
    private val device get() = UiDevice.getInstance(instrumentation)
    private var backupReady=false
    private var priorSetup=false
    private var priorResult:String?=null
    private var priorHistory:String?=null
    private var priorVoice:Boolean?=null
    @Before fun preserveUserSetup() {
        assumeTrue("Pleos device integration is opt-in", InstrumentationRegistry.getArguments().getString("liveDevice")=="true")
        val p=app.getSharedPreferences("h1-demo-v1",0)
        priorSetup=p.getBoolean("first-run-complete-v1",false)
        priorResult=p.getString("last-completed-drive-v1",null)
        priorHistory=p.getString("completed-drives-v1",null)
        priorVoice = if (p.contains("voice")) p.getBoolean("voice", false) else null
        backupReady=true
        val accessibility=instrumentation.uiAutomation.serviceInfo
        accessibility.flags=accessibility.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        instrumentation.uiAutomation.serviceInfo=accessibility
        p.edit().putBoolean("first-run-complete-v1",true).commit()
    }
    @After fun restoreUserSetup() {
        if(!backupReady) return
        app.getSharedPreferences("h1-demo-v1",0).edit().putBoolean("first-run-complete-v1",priorSetup).apply {
            if(priorResult==null) remove("last-completed-drive-v1") else putString("last-completed-drive-v1",priorResult)
            if(priorHistory==null) remove("completed-drives-v1") else putString("completed-drives-v1",priorHistory)
            priorVoice?.let { putBoolean("voice", it) } ?: remove("voice")
        }.commit()
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + if(InstrumentationRegistry.getArguments().getString("manualNotificationTap")=="true") 60_000 else 15_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, predicate())
    }
    private fun launch() {
        app.startActivity(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(app.packageName)), 45_000))
    }

    @Test fun arrivalWaitsForParkThenNotifiesAndTapOpensResult() {
        val vm = app.model
        RecordingService.clearResult(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val route = LiveVehicle(destination="알림 검증 · 여의도", destinationId="notification-test",
            destinationPoint=hangangVenue, remainingM=10_001.0, destinationAt=SystemClock.elapsedRealtime(),
            speedMps=0.0, speedAt=SystemClock.elapsedRealtime(), gearPark=true, uxRestricted=false, belt=true, beltAt=SystemClock.elapsedRealtime())
        main {
            vm.connection.stop()
            vm.receiveVehicle(route)
            if (vm.activeJourney) vm.cancel()
            if (vm.demo) vm.switchSource()
            vm.accept(ChallengeRound("알림 검증", Instant.now().minusSeconds(60), Instant.now().plusSeconds(600)))
            assertEquals(Phase.RESERVED, vm.phase)
            assertEquals(0, vm.session?.startCount)
        }
        main {
            if (!vm.voiceEnabled) vm.toggleVoice()
            assertTrue(vm.voiceEnabled)
            vm.leaveForeground()
        }
        SystemClock.sleep(1500)
        main {
            vm.connection.stop()
            val threshold = route.copy(remainingM=10_000.0, destinationAt=SystemClock.elapsedRealtime(), gearPark=false)
            vm.receiveVehicle(threshold)
            assertEquals(Phase.RECORDING, vm.phase)
            vm.receiveVehicle(threshold.copy(remainingM=9_999.0))
            assertEquals(1, vm.session?.startCount)
        }
        // Leave time for the background utterance to finish before ending the service.
        SystemClock.sleep(7_000)
        main {
            val now = SystemClock.elapsedRealtime()
            vm.receiveVehicle(route.copy(gearPark=false, speedAt=now, beltAt=now,
                arrived=true, arrivedAt=Instant.now()))
            assertEquals(Phase.AWAITING_PARK, vm.phase)
        }
        assertFalse(manager.activeNotifications.any { it.id == 11 })
        main {
            val now = SystemClock.elapsedRealtime()
            vm.receiveVehicle(route.copy(speedAt=now, beltAt=now, arrived=true, arrivedAt=Instant.now()))
        }
        waitFor("Completion notification was not posted") { manager.activeNotifications.any { it.id == 11 } }
        val completion = manager.activeNotifications.single { it.id == 11 }
        assertNotNull(completion.notification.contentIntent)
        // A notification must open the result explicitly, even if the last screen changed.
        main { vm.navigate(Screen.HISTORY) }
        device.openNotification()
        device.waitForIdle()
        device.dumpWindowHierarchy(File(app.filesDir, "completion-notification.xml"))
        if(InstrumentationRegistry.getArguments().getString("manualNotificationTap")=="true") {
            device.takeScreenshot(File(app.filesDir, "completion-notification.png"))
            instrumentation.sendStatus(2,android.os.Bundle().apply {putString("stream","MANUAL_READY: tap the visible 결과 보기 action.\n")})
        } else if(InstrumentationRegistry.getArguments().getString("pleosNotificationLayout")=="true") {
            // Observed OEM overlay is rendered on display 0; accessibility may expose a stale/different window.
            assertEquals(1920,device.displayWidth);assertEquals(1080,device.displayHeight)
            device.takeScreenshot(File(app.filesDir, "completion-notification.png"))
            device.click(960,264)
        } else {
            val result=device.wait(Until.findObject(By.text("결과 보기")),10_000)
            assertNotNull("Notification action must be accessible",result)
            device.takeScreenshot(File(app.filesDir, "completion-notification.png"))
            result!!.click()
        }
        waitFor("Notification tap must open RESULT, not the previously selected screen") { vm.screen == Screen.RESULT }
        SystemClock.sleep(2000)
        device.takeScreenshot(File(app.filesDir, "completion-result.png"))
        assertTrue("Result screen must actually be visible after notification tap",device.wait(Until.hasObject(By.text("챌린지 결과")),15_000))
        assertEquals(Phase.FINISHED, vm.phase)
        assertFalse(manager.activeNotifications.any { it.id == 11 })
        val wallet = device.wait(Until.findObject(By.text("마일리지")), 10_000)
        assertNotNull("Mileage tab must be visible on the result screen", wallet)
        wallet!!.click()
        waitFor("Mileage tab must open the wallet") { vm.screen == Screen.WALLET }
        assertTrue(device.wait(Until.hasObject(By.text("차곡차곡, 내 마일리지")), 10_000))
        device.takeScreenshot(File(app.filesDir, "completion-mileage.png"))
    }

    @Test fun actualNativeSearchReceivesKeyword() {
        val connection = app.model.connection
        main {connection.start()}
        waitFor("NaviHelper did not connect") { connection.flow.value.statuses["NaviHelper"]?.contains("연결됨") == true || connection.flow.value.statuses["NaviHelper"]?.contains("목적지 수신") == true }
        main { assertTrue(connection.openNavigation(true, "서울역")) }
        val searchResult = By.pkg("ai.umos.maps.android.navigation.app").textContains("서울역")
        assertTrue("Native search must receive the original keyword", device.wait(Until.hasObject(searchResult), 15_000))
        device.dumpWindowHierarchy(File(app.filesDir,"native-search.xml"))
        device.takeScreenshot(File(app.filesDir,"native-search.png"))
    }

    @Test fun pleosSpeechProducesPlaybackCallbacks() {
        val initialized = CountDownLatch(1)
        val done = CountDownLatch(1)
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = TextToSpeech(app, Mode.ON_DEVICE)
        val listener = object : EventListener {
            override fun onStart() { events += "start" }
            override fun onReady() { events += "ready" }
            override fun onDone() { events += "done"; done.countDown() }
            override fun onError(errorType: ErrorType) { events += "error:${errorType.name}"; done.countDown() }
            override fun onStop() { events += "stop" }
            override fun onUpdatedRms(rms: Double) { if(rms > 0 && "positive-rms" !in events) events += "positive-rms" }
        }
        try {
            main { engine.initialize(object : OnInitializeListener {
                override fun onSuccess() { events += "initialized"; initialized.countDown() }
                override fun onFailure(errorType: ErrorType) { events += "init-error:${errorType.name}"; initialized.countDown() }
            }) }
            assertTrue("TTS initialization timeout", initialized.await(20, TimeUnit.SECONDS))
            main { engine.addEventListener(listener); engine.speak("마지막 십 킬로미터입니다. 무사즈 기록을 시작해요.", true) }
            assertTrue("TTS playback timeout: $events", done.await(30, TimeUnit.SECONDS))
            assertTrue("No playback start: $events", "start" in events)
            assertTrue("No successful completion: $events", "done" in events)
        } finally {
            File(app.filesDir,"speech-events.txt").writeText(events.joinToString("\n"))
            main { engine.removeEventListener(listener); engine.release() }
        }
    }
}
