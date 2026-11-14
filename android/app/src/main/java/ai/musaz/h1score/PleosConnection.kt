package ai.musaz.h1score

import ai.musaz.h1score.core.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.car.hardware.CarPropertyValue
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import kotlinx.coroutines.channels.Channel
import java.time.Instant
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async

internal val hangangVenue = GeoPoint(37.52639977, 126.93360958)
internal const val SCORING_SPEED_REQUEST_HZ = 20f
// NaviHelper 3.0.5 sends the road limit every second, including unchanged values.
// Allow a bounded delivery delay; other navigation/vehicle updates never extend this lease.
internal const val NAVIGATION_SPEED_LIMIT_MAX_AGE_MS = 5_000L

data class LiveVehicle(
    val destination: String? = null, val destinationId: String? = null,
    val remainingM: Double? = null, val destinationAt: Long = 0,
    val speedMps: Double? = null, val speedAt: Long = 0,
    val speedTimestampNanos: Long = 0,
    // Signed AAOS speed is kept separately: belt validity includes reversing, scoring stays unchanged.
    val motionSpeedMps: Double? = null, val motionSpeedAt: Long = 0,
    val limitKph: Int? = null, val limitAt: Long = 0,
    val gearCode: Int? = null, val gearPark: Boolean? = null, val uxRestricted: Boolean? = null,
    val gearAt: Long = 0,
    val belt: Boolean? = null, val beltAt: Long = 0, val beltWarningOnAt: Long? = null,
    val passengerOccupied: Boolean? = null, val passengerOccupiedAt: Long = 0,
    val passengerBelt: Boolean? = null, val passengerBeltAt: Long = 0,
    val beltWarningOffAt: Long? = null, val brake: Boolean? = null, val brakeAt: Long = 0,
    val turnSignal: String? = null, val turnAt: Long = 0,
    val hazardOn: Boolean? = null, val hazardAt: Long = 0,
    val gps: GpsFix? = null, val forward: Boolean? = null,
    // Raw Pleos steering-wheel angle. Console tests on Connect 2.1 observed left positive.
    val steeringDeg: Double? = null, val steeringAt: Long = 0,
    val instruction: TurnDirection? = null, val instructionDistanceM: Double? = null, val instructionAt: Long = 0,
    val odometer: Float? = null, val odometerAt: Long = 0,
    val destinationPoint: GeoPoint? = null, val arrivedAt: Instant? = null,
    val arrived: Boolean = false, val routeCancelled: Boolean = false,
    val statuses: Map<String,String> = emptyMap(),
) {
    fun beltObservation(now: Long) = BeltObservation(now, when (gearCode) {
        VehicleGear.GEAR_PARK -> BeltGear.PARK
        VehicleGear.GEAR_NEUTRAL -> BeltGear.NEUTRAL
        VehicleGear.GEAR_DRIVE -> BeltGear.DRIVE
        VehicleGear.GEAR_REVERSE -> BeltGear.REVERSE
        else -> null
    }, gearAt, motionSpeedMps, motionSpeedAt, belt, beltAt,
        passengerOccupied, passengerOccupiedAt, passengerBelt, passengerBeltAt)
    fun turnObservation(now: Long) = TurnObservation(now,gps,
        speedMps.takeIf { now-speedAt in 0..750 },forward==true && now-gearAt in 0..2500,
        steeringDeg.takeIf { now-steeringAt in 0..750 }?.unaryMinus(),
        turnSignal.takeIf { now-turnAt in 0..1500 }?.let { runCatching { SignalState.valueOf(it) }.getOrNull() },
        instruction,instructionDistanceM,instructionAt,turnAt,steeringAt,
        hazardOn.takeIf { now-hazardAt in 0..750 })
    val parked: Boolean get() = isParked(SystemClock.elapsedRealtime())
    fun isParked(now: Long) = now-speedAt in 0..2500 && gearPark == true && uxRestricted == false && speedMps?.let { it < .1 } == true
    fun freshBelt(now: Long): Boolean? = belt.takeIf { now - beltAt in 0..2500 } ?:
        if (beltWarningOnAt?.let { now-it in 0..2500 } == true) false
        else if (beltWarningOffAt?.let { now-it in 0..2500 } == true) true else null
    // A silent warning is not proof of a fastened belt while parked.
    val beltConditionMet: Boolean get() = belt == true && SystemClock.elapsedRealtime() - beltAt in 0..2500
    fun freshSpeed(now: Long) = speedMps.takeIf { now - speedAt in 0..2_500 }
    fun freshLimit(now: Long) = limitKph.takeIf { now - limitAt in 0..NAVIGATION_SPEED_LIMIT_MAX_AGE_MS }
}

/** Main-thread buffer: wakeups never remove a sample before its collector actually runs. */
internal class ScoringSampleBuffer(private val onOverflow: () -> Unit = {}) {
    private val pending = ArrayDeque<Sample>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    private var lostSamples = false

    // Collect directly on Main; dequeue and the recorder's non-suspending callback are atomic
    // with respect to arrival handling on that same thread. Only the wakeup crosses a channel.
    val samples = kotlinx.coroutines.flow.flow {
        requireMain()
        for (signal in available) {
            while (true) {
                val sample = pending.removeFirstOrNull() ?: takeGap() ?: break
                emit(sample)
            }
        }
    }

    fun offer(sample: Sample) {
        requireMain()
        if (lostSamples) {
            if (pending.size >= 512) return
            pending.addLast(sample.copy(speedMps = null))
            lostSamples = false
        }
        if (pending.size < 512) pending.addLast(sample)
        else { lostSamples = true; onOverflow() }
        available.trySend(Unit)
    }

    fun drain(): List<Sample> {
        requireMain()
        return buildList {
            while (pending.isNotEmpty()) add(pending.removeFirst())
            takeGap()?.let(::add)
        }
    }

    fun clear() {
        requireMain()
        pending.clear(); lostSamples = false
        while (available.tryReceive().isSuccess) { /* discard stale wakeups */ }
    }

    private fun takeGap(): Sample? {
        if (!lostSamples) return null
        lostSamples = false
        val now = SystemClock.elapsedRealtimeNanos()
        return Sample(now / 1_000_000, null, null, speedTimestampNanos = now)
    }

    private fun requireMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Scoring samples must be consumed on the main thread" }
    }
}

/** Read-only adapter. No synthetic fallback values are emitted on connection failure. */
class PleosConnection(private val context: Context) {
    private val beltObservations = ArrayDeque<BeltObservation>()
    private var beltOverflow = false
    private val seatRequests = mutableMapOf<String, Long>()
    private val seatReader = PleosSeatReader(context, ::onSeatSourceUnavailable)

    private fun onSeatSourceUnavailable() {
        seatRequests.clear()
        updateState { it.copy(belt=null, beltAt=0, passengerBelt=null, passengerBeltAt=0,
            passengerOccupied=null, passengerOccupiedAt=0) }
    }

    // StateFlow may conflate an invalid read and its recovery. Preserve validity evidence.
    private fun updateState(change: (LiveVehicle) -> LiveVehicle) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { updateState(change) }
            return
        }
        val next=change(state.value)
        if (beltObservations.size == 512) { beltObservations.removeFirst(); beltOverflow=true }
        beltObservations.addLast(next.beltObservation(SystemClock.elapsedRealtime()))
        state.value=next
    }

    internal fun drainBeltObservations(): List<BeltObservation> {
        check(Looper.myLooper() == Looper.getMainLooper())
        val result=beltObservations.toList()
        beltObservations.clear()
        val lost=beltOverflow
        beltOverflow=false
        return if (lost && result.isNotEmpty()) listOf(result.first().copy(
            gear=BeltGear.DRIVE, gearAtMs=result.first().atMs, speedMps=null, driverBuckled=null, passengerOccupied=null)) + result else result
    }

    private fun readSeat(source: Vehicle, epoch: Int, key: String,
        request: ((Boolean?) -> Unit, (Exception) -> Unit) -> Unit,
        apply: (Boolean?, Long) -> Unit,
    ) {
        val requestedAt=SystemClock.elapsedRealtime()
        seatRequests[key]=requestedAt
        fun complete(value: Boolean?) = withCurrentVehicle(source, epoch) {
            if (seatRequests[key] == requestedAt) {
                // Response time is not the sample time. Ignore superseded replies and bound latency.
                val timely=SystemClock.elapsedRealtime()-requestedAt in 0..500
                apply(value.takeIf { timely }, if (timely && value != null) requestedAt else 0)
            }
        }
        runCatching { request({ complete(it) }, { complete(null) }) }.onFailure { complete(null) }
    }
    // Explicit emulator-only test capture, never a fallback for production telemetry.
    // Missing/invalid vehicle speed and source timestamps remain invalid in this mode.
    private var consoleCapture = false
    internal fun setConsoleCapture(enabled: Boolean) {
        val next = enabled && BuildConfig.DEBUG &&
            (Build.FINGERPRINT.contains("generic") || Build.HARDWARE in listOf("ranchu", "goldfish"))
        if (next == consoleCapture) return
        consoleCapture = next
        if (active) {
            unsubscribeSpeed()
            subscribeSpeed()
        }
    }

    /** Extra read-only telemetry requested only while My Page is visible. */
    internal suspend fun readProfileEnergy(): ProfileEnergy = kotlinx.coroutines.coroutineScope {
        val source=vehicle?.takeIf { active } ?: return@coroutineScope ProfileEnergy()
        val range = async { profileRead<Float> { ok, fail -> source.getOdometer().getRangeRemainingDistance(ok, fail) } }
        val locks = async {
            val areas = profileRead<List<DoorArea>> { ok, fail ->
                source.getDoor().checkDoorLockCapabilities({ capability, areas -> ok(areas.takeIf { readable(capability) }) }, fail)
            }.orEmpty()
            areas.map { area -> async {
                val locked = profileRead<Boolean> { ok, fail -> source.getDoor().getDoorLockState(area, ok, fail) }
                area.values.map { it.name to locked }
            } }.flatMap { it.await() }.toMap()
        }
        val chargePort = async { profileRead<Boolean> { ok, fail ->
            source.getEvBattery().checkEvChargePortOpenCapabilities({ capability ->
                if (readable(capability)) source.getEvBattery().isEvChargePortOpen(ok, fail) else ok(null)
            }, fail)
        } }
        val lowBeam = async { profileRead<String> { ok, fail ->
            source.getLight().checkLowBeamLightStateCapabilities({ capability, _ ->
                if (readable(capability)) source.getLight().getCurrentLowBeamLightState({ ok(it?.name) }, fail) else ok(null)
            }, fail)
        } }
        val highBeam = async { profileRead<String> { ok, fail ->
            source.getLight().checkHighBeamLightStateCapabilities({ capability, _ ->
                if (readable(capability)) source.getLight().getCurrentHighBeamLightState({ ok(it?.name) }, fail) else ok(null)
            }, fail)
        } }
        val doors = async {
            val areas = profileRead<Map<DoorArea, AreaBounds<Int>>> { ok, fail ->
                source.getDoor().checkDoorPositionCapabilities({ capability, areas -> ok(areas.takeIf { readable(capability) }) }, fail)
            }.orEmpty()
            areas.map { (area, bounds) -> async {
                val open = profileRead<Int> { ok, fail -> source.getDoor().getCurrentDoorPosition(area, ok, fail) }
                    ?.takeIf { it in bounds.minValue..bounds.maxValue }?.let { it != 0 }
                area.values.map { it.name to open }
            } }.flatMap { it.await() }.toMap()
        }
        val windows = async {
            val areas = profileRead<Map<WindowArea, AreaBounds<Int>>> { ok, fail ->
                source.getWindow().checkWindowPositionCapabilities({ capability, areas -> ok(areas.takeIf { readable(capability) }) }, fail)
            }.orEmpty()
            areas.map { (area, bounds) -> async {
                val open = profileRead<Int> { ok, fail -> source.getWindow().getCurrentWindowPosition(area, ok, fail) }
                    ?.takeIf { it in bounds.minValue..bounds.maxValue }?.let { it != 0 }
                area.values.map { it.name to open }
            } }.flatMap { it.await() }.toMap()
        }
        val battery=async { profileRead<Int> { ok, fail -> source.getEvBattery().getEvBatteryStateOfCharge(ok,fail) } }
        val result=ProfileEnergy(
            rangeMeters = range.await()?.takeIf { it.isFinite() && it >= 0f },
            doorOpen = doors.await(), windowOpen = windows.await(), doorLocked = locks.await(),
            chargePortOpen = chargePort.await(), lowBeam = lowBeam.await(), highBeam = highBeam.await(),
            battery=battery.await()?.takeIf { it in 0..100 },
        )
        if(active && vehicle===source) result else ProfileEnergy()
    }

    private suspend fun <T> profileRead(request: ((T?)->Unit,(Exception)->Unit)->Unit): T? {
        val response=kotlinx.coroutines.CompletableDeferred<T?>()
        return kotlinx.coroutines.withTimeoutOrNull(1500) {
            runCatching { request({ response.complete(it) }, { response.complete(null) }) }
                .onFailure { response.complete(null) }
            response.await()
        }
    }

    private val state = MutableStateFlow(LiveVehicle())
    val flow = state.asStateFlow()
    // UI state may conflate updates; this buffer preserves each observation for scoring.
    private val sampleBuffer = ScoringSampleBuffer { status("주행 표본", "처리 지연 · 누락 구간 평가 제외") }
    val samples = sampleBuffer.samples
    private var speedCallback: CarPropertyManager.CarPropertyEventCallback? = null
    private var speedSubscriptionGeneration = 0
    private var brakeListener: BrakePedalStatusListener? = null
    private var lastSpeedReceivedNs = 0L
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var naviEvents: NaviHelperEventListener? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var active = false
    private var naviReady = false
    private var brakeReady = false
    private var turnReady = false
    private var steeringReady = false
    private val locations=context.getSystemService(LocationManager::class.java)
    private val locationListener=object: LocationListener {
        override fun onLocationChanged(location: Location) { if (active) receiveLocation(location) }
        override fun onProviderDisabled(provider: String) { updateState { it.copy(gps=null) };status("GPS","위치 제공 중단 · 회전 감점 보류") }
        override fun onProviderEnabled(provider: String) {}
        override fun onStatusChanged(provider: String?,status: Int,extras: android.os.Bundle?) {}
    }
    internal fun receiveLocation(location: Location) {
        val fix=if(location.hasAccuracy() && location.hasBearing() && location.hasBearingAccuracy()) GpsFix(
            location.elapsedRealtimeNanos/1_000_000,location.latitude,location.longitude,location.accuracy.toDouble(),
            location.bearing.toDouble(),location.bearingAccuracyDegrees.toDouble()) else null
        updateState { it.copy(gps=fix) }
        status("GPS",if(fix?.usable(SystemClock.elapsedRealtime())==true) "회전 판정 가능" else "정확도 부족 · 회전 감점 보류")
    }
    fun refreshLocationPermission() {
        if(!active)return
        if(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { locations.removeUpdates(locationListener) }
            updateState { it.copy(gps=null) }
            status("GPS","정확한 위치 권한 필요 · 회전 감점 보류");return
        }
        runCatching { locations.removeUpdates(locationListener); locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,250L,0f,locationListener,Looper.getMainLooper()) }
            .onFailure { status("GPS","위치 미수신 · 회전 감점 보류") }
    }
    private var odoReady = false
    private var driverSeat: SeatArea? = null
    private var passengerSeat: SeatArea? = null
    private var passengerOccupancySeat: SeatArea? = null
    private val useEmulatorTelltale = BuildConfig.DEBUG && Build.HARDWARE == "ranchu"
    private var observedSeatBeltTelltale = false
    private var cycles = 0
    private fun status(key: String, value: String) { if(state.value.statuses[key] != value.take(140)) android.util.Log.d("H1Sdk", "$key: $value"); updateState { it.copy(statuses = it.statuses + (key to value.take(140))) } }
    private fun readable(c: CapabilityConfig) = c.accessType in listOf(AccessType.READ,AccessType.READ_WRITE)
    private fun withCurrentNavigation(source: NaviHelper, epoch: Int, action: () -> Unit) {
        val update = Runnable { if (active && epoch == generation && navi === source) action() }
        if (Looper.myLooper() == Looper.getMainLooper()) update.run() else main.post(update)
    }

    private fun withCurrentVehicle(source: Vehicle, epoch: Int, action: () -> Unit) {
        val update = Runnable { if (active && epoch == generation && vehicle === source) action() }
        if (Looper.myLooper() == Looper.getMainLooper()) update.run() else main.post(update)
    }

    /** Flushes every still-pending source sample before an arrival closes the recording. */
    fun drainPendingSamples(): List<Sample> = sampleBuffer.drain()

    private fun enqueueSample(sample: Sample) = sampleBuffer.offer(sample)

    private fun markSpeedUnavailable(message: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        enqueueSample(Sample(now / 1_000_000, null, null, speedTimestampNanos = now))
        updateState { it.copy(speedMps = null, speedTimestampNanos = 0, motionSpeedMps = null, motionSpeedAt = 0) }
        status("차량 속도", message)
    }

    @Suppress("DEPRECATION")
    private fun unsubscribeSpeed() {
        // Reconnect may reuse the same manager. Invalidate even callbacks already queued.
        speedSubscriptionGeneration++
        val previous = speedCallback
        speedCallback = null
        previous?.let { callback -> runCatching { properties?.unregisterCallback(callback) } }
    }

    @Suppress("DEPRECATION") // API 34 uses registerCallback; newer subscribePropertyEvents is unavailable.
    private fun subscribeSpeed() {
        val manager = properties ?: return
        if (!active || speedCallback != null) return
        val epoch = generation
        val subscription = ++speedSubscriptionGeneration
        runCatching {
            val config = manager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED)
                ?: error("차량 속도 속성 미지원 또는 읽기 권한 없음")
            check(config.maxSampleRate > 0f) { "연속 속도 표본 미지원" }
            // Pleos may deliver fewer callbacks than requested. Normal recording needs the
            // same request headroom as console capture; actual >=8 Hz is still verified.
            val requestedRate = SCORING_SPEED_REQUEST_HZ
            val rate = requestedRate.coerceIn(config.minSampleRate, config.maxSampleRate)
            val callback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (!active || epoch != generation || subscription != speedSubscriptionGeneration || properties !== manager ||
                        value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
                    val receivedNs = SystemClock.elapsedRealtimeNanos()
                    val receivedMs = receivedNs / 1_000_000
                    lastSpeedReceivedNs = receivedNs
                    val timestamp = value.timestamp
                    val timely = timestamp > 0 && timestamp <= receivedNs && receivedNs - timestamp <= 500_000_000
                    val speed = if (value.status == CarPropertyValue.STATUS_AVAILABLE && timely)
                        (value.value as? Float)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..80.0 }
                        else null
                    val motionSpeed = if (value.status == CarPropertyValue.STATUS_AVAILABLE && timely)
                        (value.value as? Float)?.toDouble()?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 80.0 }
                        else null
                    // Invalid observations retain a barrier; never give them a synthetic speed.
                    val sampleNs = if (timely) timestamp else receivedNs
                    val current = state.value
                    val brakeFresh = receivedMs - current.brakeAt in 0..1500
                    enqueueSample(Sample(sampleNs / 1_000_000,
                        speed.takeIf { consoleCapture || current.freshBelt(receivedMs) == true },
                        current.freshLimit(receivedMs), speedTimestampNanos = sampleNs,
                        brakePressed = current.brake.takeIf { brakeFresh },
                        brakeObservedAtMs = current.brakeAt.takeIf { brakeFresh }))
                    // Enqueue first so a resulting arrival/phase update can drain the entire trace.
                    updateState { it.copy(speedMps = speed, speedAt = if (speed != null) timestamp / 1_000_000 else 0,
                        motionSpeedMps = motionSpeed, motionSpeedAt = if (motionSpeed != null) timestamp / 1_000_000 else 0,
                        speedTimestampNanos = if (speed != null) timestamp else 0) }
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    if (active && epoch == generation && subscription == speedSubscriptionGeneration && properties === manager &&
                        propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) markSpeedUnavailable("신호 오류 · 판정 보류")
                }
            }
            speedCallback = callback
            check(manager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, rate)) { "속도 구독 실패" }
            status("차량 속도", "원본 시각으로 ${rate}Hz 요청 · 실제 간격 검증")
        }.onFailure {
            unsubscribeSpeed()
            status("차량 속도", "구독 불가 · ${it.javaClass.simpleName}")
        }
    }

    private fun subscribeBrake(source: Vehicle) {
        if (!active || vehicle !== source || brakeListener != null || !brakeReady) return
        val listener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) {
                val receivedAt = SystemClock.elapsedRealtime()
                main.post {
                    if (active && vehicle === source && brakeListener === this)
                        updateState { it.copy(brake = status, brakeAt = receivedAt) }
                }
            }
            override fun onFailed(exception: Exception) {
                main.post {
                    if (active && vehicle === source && brakeListener === this)
                        updateState { it.copy(brake = null, brakeAt = 0) }
                }
            }
        }
        runCatching {
            // This API has no vehicle timestamp: brakeAt records receipt time only.
            brakeListener = listener
            source.getBrake().registerBrakePedalStatus(listener)
        }.onFailure { brakeListener = null; status("Brake", "변화 구독 실패 · 상태 조회로 보조 확인") }
    }

    fun refreshVehiclePermissions() {
        if (!active) return
        subscribeSpeed()
        capabilities()
    }
    private fun navigationEvents(source: NaviHelper, epoch: Int) = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = withCurrentNavigation(source, epoch) {
            updateState { it.copy(limitKph=null,limitAt=0,instruction=null,instructionAt=0) }
        }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val receivedAt = SystemClock.elapsedRealtime()
            val next=tbtInfoList.firstOrNull()
            val direction=when(next?.type) {
                TurnType.TURN_LEFT,TurnType.TURN_SHARP_L -> TurnDirection.LEFT
                TurnType.TURN_RIGHT,TurnType.TURN_SHARP_R -> TurnDirection.RIGHT
                else -> null
            }
            withCurrentNavigation(source, epoch) {
                updateState { it.copy(instruction=direction,instructionDistanceM=next?.distance,instructionAt=receivedAt) }
                status("회전 안내",direction?.let { "$it · ${next?.distance}m" } ?: "좌우회전 판정 대기")
            }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val receivedAt = SystemClock.elapsedRealtime()
            val key = "${destinationInfo.poiProvider}:${destinationInfo.poiId}:${destinationInfo.latitude}:${destinationInfo.longitude}"
            val destination = destinationInfo.name.takeIf { it.isNotBlank() }
            val destinationId = key.takeIf { destination != null }
            withCurrentNavigation(source, epoch) {
                updateState {
                    // Some providers report the new destination without a preceding change
                    // event. Do not carry the old route's road limit into that destination.
                    val changed = it.destinationId != null && it.destinationId != destinationId
                    it.copy(destination = destination, destinationId = destinationId,
                        destinationPoint = GeoPoint(destinationInfo.latitude, destinationInfo.longitude),
                        routeCancelled = false, arrived = if(it.destinationId == key) it.arrived else false,
                        arrivedAt = it.arrivedAt.takeIf { _ -> it.destinationId == key },
                        remainingM = destinationInfo.remainDistance.toDouble().takeIf { d -> d >= 0 }, destinationAt = receivedAt,
                        limitKph = if(changed) null else it.limitKph, limitAt = if(changed) 0 else it.limitAt)
                }
                status("NaviHelper", "목적지 수신 · ${destinationInfo.remainDistance} m")
            }
        }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            val receivedAt = SystemClock.elapsedRealtime()
            withCurrentNavigation(source, epoch) {
                val validLimit = speedLimit.takeIf { it in 1..150 }
                updateState { it.copy(limitKph = validLimit, limitAt = if(validLimit != null) receivedAt else 0) }
                status("제한속도", "$speedLimit km/h · 콜백 수신")
            }
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            val receivedAt = Instant.now()
            withCurrentNavigation(source, epoch) {
                // Pending samples keep their own limit snapshot, so arrival can invalidate
                // future input while the recorder still drains its final pre-arrival samples.
                updateState { it.copy(arrived = true, arrivedAt = it.arrivedAt ?: receivedAt,limitKph=null,limitAt=0) }
            }
        }
        override fun onDestinationChanged() = withCurrentNavigation(source, epoch) {
            updateState { it.copy(arrived = false, arrivedAt = null, routeCancelled = false, limitKph = null,limitAt=0,instruction=null,instructionAt=0) }
            source.getDestinationInfo()
        }
        override fun onRouteCancelled() = withCurrentNavigation(source, epoch) {
            updateState { it.copy(destination = null,destinationId = null,remainingM = null,limitKph = null,limitAt=0,arrived = false,arrivedAt = null,destinationPoint = null,routeCancelled = true,instruction=null,instructionAt=0) }
        }
        override fun onReRouteEnded() = withCurrentNavigation(source, epoch) {
            updateState { it.copy(limitKph = null,limitAt=0,instruction=null,instructionAt=0) }
            source.getDestinationInfo()
        }
        override fun onNavigationStatus(status: NavigationStatus) = withCurrentNavigation(source, epoch) {
            if (status == NavigationStatus.INACTIVE || status == NavigationStatus.INITIALIZING)
                updateState { it.copy(limitKph=null,limitAt=0) }
        }
        override fun onError(errorCode: NaviErrorCode) = withCurrentNavigation(source, epoch) {
            // Low battery is an unrelated warning, not evidence that the road limit is stale.
            if (errorCode != NaviErrorCode.CAUSE_LOW_BATTERY)
                updateState { it.copy(limitKph=null,limitAt=0) }
            status("NaviHelper", "대기 / ${errorCode.name}")
        }
    }
    fun requestSavedRoute(place: ai.musaz.h1score.core.SavedPlace): Boolean {
        val helper = navi?.takeIf { naviReady } ?: return false
        return runCatching {
            helper.requestRoute(ai.pleos.playground.navi.data.RouteInfo(
                latitude=place.point.latitude, longitude=place.point.longitude,
                displayLatitude=null, displayLongitude=null, poiName=place.name,
                poiId="", poiSubId="", address=""))
            true
        }.getOrDefault(false)
    }
    fun requestChallengeRoute(): Boolean {
        val helper = navi?.takeIf { naviReady } ?: return false
        return runCatching {
            helper.requestRoute(ai.pleos.playground.navi.data.RouteInfo(
                latitude=hangangVenue.latitude, longitude=hangangVenue.longitude,
                displayLatitude=null, displayLongitude=null, poiName="여의도한강공원",
                poiId="", poiSubId="", address="서울 영등포구 여의동로 330"))
            true
        }.getOrDefault(false)
    }
    fun openNavigation(search: Boolean, keyword: String = ""): Boolean {
        val helper = navi?.takeIf { naviReady } ?: return false
        if (context.checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            helper.requestNavigationScreen(if (search) NavigationScreenRequest.SearchPlace(keyword) else NavigationScreenRequest.Foreground)
            true
        }.getOrDefault(false)
    }
    fun start() {
        if (active) return
        generation++
        sampleBuffer.clear()
        lastSpeedReceivedNs = SystemClock.elapsedRealtimeNanos()
        val connectionGeneration = generation
        observedSeatBeltTelltale = false
        active = true; cycles=0; brakeReady=false; turnReady=false; steeringReady=false; odoReady=false; driverSeat=null
        passengerSeat=null; passengerOccupancySeat=null
        seatReader.start()
        status("Vehicle", "서비스 연결 중")
        runCatching { vehicle = Vehicle(context).also { it.initialize() } }.onFailure { status("Vehicle", it.javaClass.simpleName) }
        runCatching {
            val helper = NaviHelper(context)
            val events = navigationEvents(helper, connectionGeneration)
            navi = helper
            naviEvents = events
            helper.initialize(onConnected = {
                withCurrentNavigation(helper, connectionGeneration) {
                    naviReady = true; helper.addListener(events); helper.getDestinationInfo(); status("NaviHelper", "연결됨 · 목적지 대기")
                }
            }, onFailed = { failure -> withCurrentNavigation(helper, connectionGeneration) {
                naviReady = false
                updateState { it.copy(limitKph=null,limitAt=0) }
                status("NaviHelper", "연결 실패 · ${failure.javaClass.simpleName}")
            } })
        }.onFailure {
            naviReady = false
            updateState { it.copy(limitKph=null,limitAt=0) }
            status("NaviHelper", it.javaClass.simpleName)
        }
        runCatching {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (!active || connectionGeneration != generation) return@createCar
                if (ready) {
                    val manager = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    if (properties !== manager) unsubscribeSpeed()
                    properties = manager
                    ux = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    status("AAOS", "Car service 연결됨")
                    subscribeSpeed()
                } else {
                    unsubscribeSpeed()
                    properties = null; ux = null
                    markSpeedUnavailable("서비스 연결 해제")
                    updateState { it.copy(gearCode=null,gearPark=null,forward=null,uxRestricted=null) }; status("AAOS", "연결 해제")
                }
            }
        }.onFailure { status("AAOS", "사용 불가 · ${it.javaClass.simpleName}") }
        refreshLocationPermission()
        main.post(poll);main.post(turnPoll)
    }
    private fun capabilities() {
        vehicle?.let { v ->
            val epoch = generation
            runCatching {
                v.getBrake().checkBrakePedalStatusCapabilities({ c -> main.post {
                    if (active && vehicle === v) {
                        brakeReady = readable(c); subscribeBrake(v); status("Brake", "권한 ${c.accessType}")
                    }
                } }, { status("Brake", it.javaClass.simpleName) })
                v.getSteeringWheel().checkSteeringWheelAngleCapabilities({ c, _ -> steeringReady=readable(c);status("Steering","권한 ${c.accessType}") }, { status("Steering",it.javaClass.simpleName) })
                v.getTurnSignal().checkTurnSignalStateCapabilities({ c -> turnReady = readable(c); status("TurnSignal", "권한 ${c.accessType}") }, { status("TurnSignal", it.javaClass.simpleName) })
                v.getOdometer().checkCurrentDistanceDrivenCapabilities({ c -> odoReady = readable(c); status("Odometer", "권한 ${c.accessType}") }, { status("Odometer", it.javaClass.simpleName) })
                v.getSeat().checkSeatBeltBuckledCapabilities({ c: CapabilityConfig, areas: List<SeatArea> ->
                    withCurrentVehicle(v, epoch) {
                        driverSeat = if (readable(c)) areas.firstOrNull { a -> a.values.size == 1 && a.values.contains(SeatValue.ROW_1_LEFT) } else null
                        passengerSeat = if (readable(c)) areas.firstOrNull { a -> a.values.size == 1 && a.values.contains(SeatValue.ROW_1_RIGHT) } else null
                        status("Seat", if(driverSeat != null) "앞좌석 왼쪽 지원 · LHD 시연" else "운전석 영역 미지원")
                    }
                }, { failure -> withCurrentVehicle(v, epoch) { status("Seat", failure.javaClass.simpleName) } })
                v.getSeat().checkSeatOccupancyCapabilities({ c, areas ->
                    withCurrentVehicle(v, epoch) {
                        passengerOccupancySeat = if (readable(c)) areas.firstOrNull { a -> a.values.size == 1 && a.values.contains(SeatValue.ROW_1_RIGHT) } else null
                        status("조수석 탑승", if (passengerOccupancySeat != null) "조회 지원" else "미지원 · 비탑승으로 추정하지 않음")
                    }
                }, { failure -> withCurrentVehicle(v, epoch) { status("조수석 탑승", failure.javaClass.simpleName) } })
            }.onFailure { status("Vehicle", it.javaClass.simpleName) }
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (cycles++ in listOf(1,3,7)) capabilities()
            if (speedCallback == null) subscribeSpeed()
            if (SystemClock.elapsedRealtimeNanos() - lastSpeedReceivedNs > 500_000_000) {
                // Repeated gap markers keep coverage honest during a prolonged outage.
                markSpeedUnavailable("속도 표본 미수신 · 판정 보류")
            }
            properties?.let { p ->
                runCatching {
                    val gear = p.getIntProperty(VehiclePropertyIds.GEAR_SELECTION,0)
                    val restricted = ux?.currentCarUxRestrictions?.isRequiresDistractionOptimization
                    updateState { it.copy(gearCode=gear,gearAt=SystemClock.elapsedRealtime(),gearPark=gear==VehicleGear.GEAR_PARK,forward=gear==VehicleGear.GEAR_DRIVE,uxRestricted=restricted) }
                    status("주차 상태", "gear=$gear · UX 제한=$restricted")
                }.onFailure { updateState { it.copy(gearCode=null,gearPark=null,forward=null,uxRestricted=null) }; status("주차 상태", "미확인 · ${it.javaClass.simpleName}") }
            }
            if(naviReady) runCatching { navi?.getDestinationInfo() }
            vehicle?.let { v ->
                val epoch = generation
                if (useEmulatorTelltale) {
                    runCatching {
                        v.getSafety().getTelltalesInfo({ info ->
                            val receivedAt = SystemClock.elapsedRealtime()
                            val warning = info?.telltales?.firstOrNull { it.type == TelltaleType.SEAT_BELT }?.state
                            withCurrentVehicle(v, epoch) {
                                if (warning != null) observedSeatBeltTelltale = true
                                // This emulator omits an extinguished telltale from successful reads.
                                val warningOff = warning == TelltaleState.OFF ||
                                    (info != null && warning == null && observedSeatBeltTelltale)
                                updateState { it.copy(beltWarningOffAt = if (warningOff) receivedAt else null,
                                    beltWarningOnAt = if (warning != null && warning != TelltaleState.OFF) receivedAt else null) }
                                status("벨트 경고등", if (warningOff) "OFF" else warning?.name ?: "미수신")
                            }
                        }, { failure ->
                            withCurrentVehicle(v, epoch) {
                                updateState { it.copy(beltWarningOffAt = null, beltWarningOnAt = null) }
                                status("벨트 경고등", failure.javaClass.simpleName)
                            }
                        })
                    }.onFailure { withCurrentVehicle(v, epoch) { updateState { it.copy(beltWarningOffAt = null, beltWarningOnAt = null) } } }
                }
                if(brakeReady) runCatching { v.getBrake().getCurrentBrakePedalStatus({ b ->
                    val receivedAt = SystemClock.elapsedRealtime()
                    main.post { if(active && vehicle===v) updateState { it.copy(brake=b,brakeAt=receivedAt) } }
                }, { main.post { if(active && vehicle===v) updateState { it.copy(brake=null,brakeAt=0) } } }) }
                if(odoReady) runCatching { v.getOdometer().getCurrentDistanceDriven({ d ->
                    val receivedAt = SystemClock.elapsedRealtime()
                    withCurrentVehicle(v, epoch) {
                        updateState { it.copy(odometer=d,odometerAt=receivedAt) }
                        status("Odometer","$d · 원시값")
                    }
                }, { status("Odometer",it.javaClass.simpleName) }) }
                driverSeat?.let { area -> readSeat(v, epoch, "driver-belt",
                    { ok, fail -> seatReader.readBuckle(area, ok, fail) }) { value, at ->
                    updateState { it.copy(belt=value, beltAt=at) }
                } }
                passengerSeat?.let { area -> readSeat(v, epoch, "passenger-belt",
                    { ok, fail -> seatReader.readBuckle(area, ok, fail) }) { value, at ->
                    updateState { it.copy(passengerBelt=value, passengerBeltAt=at) }
                } }
                passengerOccupancySeat?.let { area -> readSeat(v, epoch, "passenger-occupancy",
                    { ok, fail -> seatReader.readOccupancy(area, ok, fail) }) { value, at ->
                    updateState { it.copy(passengerOccupied=value, passengerOccupiedAt=at) }
                } }
            }
            main.postDelayed(this,1000)
        }
    }
    private val turnPoll=object: Runnable {
        override fun run() {
            if(!active)return
            vehicle?.let { v ->
                val epoch=generation
                val requestedAt=SystemClock.elapsedRealtime()
                seatReader.readRaw("HAZARD_LIGHTS_STATE",0,{ raw ->
                    withCurrentVehicle(v,epoch) {
                        if(acceptTurnReply(requestedAt,SystemClock.elapsedRealtime(),flow.value.hazardAt))
                            updateState { it.copy(hazardOn=decodeHazard(raw),hazardAt=requestedAt) }
                    }
                }, { withCurrentVehicle(v,epoch) {
                    if(acceptTurnReply(requestedAt,SystemClock.elapsedRealtime(),flow.value.hazardAt))
                        updateState { it.copy(hazardOn=null,hazardAt=requestedAt) }
                } })
                if(turnReady) runCatching { v.getTurnSignal().getCurrentTurnSignalState({ t ->
                    withCurrentVehicle(v,epoch) {
                        if(acceptTurnReply(requestedAt,SystemClock.elapsedRealtime(),flow.value.turnAt))
                            updateState { it.copy(turnSignal=t?.name,turnAt=requestedAt) }
                    }
                }, { withCurrentVehicle(v,epoch) {
                    if(acceptTurnReply(requestedAt,SystemClock.elapsedRealtime(),flow.value.turnAt))
                        updateState { it.copy(turnSignal=null,turnAt=requestedAt) }
                } }) }
                if(steeringReady) runCatching { v.getSteeringWheel().getCurrentSteeringWheelAngle({ angle ->
                    withCurrentVehicle(v,epoch) {
                        if(acceptTurnReply(requestedAt,SystemClock.elapsedRealtime(),flow.value.steeringAt))
                            updateState { it.copy(steeringDeg=angle?.toDouble(),steeringAt=requestedAt) }
                    }
                }, { withCurrentVehicle(v,epoch) {
                    if(acceptTurnReply(requestedAt,SystemClock.elapsedRealtime(),flow.value.steeringAt))
                        updateState { it.copy(steeringDeg=null,steeringAt=requestedAt) }
                } }) }
            }
            main.postDelayed(this,250)
        }
    }
    fun stop() {
        seatReader.stop()
        active=false; main.removeCallbacks(poll);main.removeCallbacks(turnPoll);runCatching { locations.removeUpdates(locationListener) }; naviReady=false
        generation++
        unsubscribeSpeed()
        sampleBuffer.clear()
        brakeListener?.let { listener -> runCatching { vehicle?.getBrake()?.unregisterBrakePedalStatus(listener) } }
        brakeListener = null
        naviEvents?.let { listener -> runCatching { navi?.removeListener(listener) } }
        naviEvents = null
        runCatching { navi?.release() }
        runCatching { vehicle?.release() }; runCatching { car?.disconnect() }
        vehicle=null; navi=null; car=null; properties=null; ux=null
        state.value = LiveVehicle(statuses=mapOf("연결" to "대기"))
    }
}
