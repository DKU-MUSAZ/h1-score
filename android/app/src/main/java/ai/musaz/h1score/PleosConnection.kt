package ai.musaz.h1score

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
import ai.pleos.playground.navi.constants.NaviErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LiveVehicle(
    val destination: String? = null, val destinationId: String? = null,
    val remainingM: Double? = null, val destinationAt: Long = 0,
    val speedMps: Double? = null, val speedAt: Long = 0,
    val limitKph: Int? = null, val limitAt: Long = 0,
    val gearPark: Boolean? = null, val uxRestricted: Boolean? = null,
    val belt: Boolean? = null, val beltWarningOffAt: Long? = null, val brake: Boolean? = null,
    val turnSignal: String? = null, val odometer: Float? = null,
    val arrived: Boolean = false, val routeCancelled: Boolean = false,
    val statuses: Map<String,String> = emptyMap(),
) {
    val parked: Boolean get() = SystemClock.elapsedRealtime()-speedAt <= 2500 && gearPark == true && uxRestricted == false && speedMps?.let { it < .1 } == true
    val beltConditionMet: Boolean get() = belt == true ||
        beltWarningOffAt?.let { SystemClock.elapsedRealtime() - it <= 2_500 } == true
    fun freshSpeed(now: Long) = speedMps.takeIf { now - speedAt <= 2_500 }
    fun freshLimit(now: Long) = limitKph.takeIf { now - limitAt <= 120_000 }
}

/** Read-only adapter. No synthetic fallback values are emitted on connection failure. */
class PleosConnection(private val context: Context) {
    private val state = MutableStateFlow(LiveVehicle())
    val flow = state.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var active = false
    private var naviReady = false
    private var brakeReady = false
    private var turnReady = false
    private var odoReady = false
    private var driverSeat: SeatArea? = null
    private val useEmulatorTelltale = BuildConfig.DEBUG && Build.HARDWARE == "ranchu"
    private var observedSeatBeltTelltale = false
    private var cycles = 0
    private fun status(key: String, value: String) { if(state.value.statuses[key] != value.take(140)) android.util.Log.d("H1Sdk", "$key: $value"); state.update { it.copy(statuses = it.statuses + (key to value.take(140))) } }
    private fun readable(c: CapabilityConfig) = c.accessType in listOf(AccessType.READ,AccessType.READ_WRITE)
    private val events = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val key = "${destinationInfo.poiProvider}:${destinationInfo.poiId}:${destinationInfo.latitude}:${destinationInfo.longitude}"
            state.update { it.copy(destination = destinationInfo.name.takeIf { name -> name.isNotBlank() }, destinationId = key.takeIf { destinationInfo.name.isNotBlank() },
                routeCancelled = false, arrived = if(it.destinationId == key) it.arrived else false, remainingM = destinationInfo.remainDistance.toDouble().takeIf { d -> d >= 0 }, destinationAt = SystemClock.elapsedRealtime()) }
            status("NaviHelper", "목적지 수신 · ${destinationInfo.remainDistance} m")
        }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            state.update { it.copy(limitKph = speedLimit.takeIf { s -> s in 1..150 }, limitAt = SystemClock.elapsedRealtime()) }
            status("제한속도", "$speedLimit km/h · 콜백 수신")
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { state.update { it.copy(arrived = true) } }
        override fun onDestinationChanged() { state.update { it.copy(arrived = false, routeCancelled = false, limitKph = null) }; navi?.getDestinationInfo() }
        override fun onRouteCancelled() { state.update { it.copy(destination = null,destinationId = null,remainingM = null,limitKph = null,arrived = false,routeCancelled = true) } }
        override fun onReRouteEnded() { state.update { it.copy(limitKph = null) }; navi?.getDestinationInfo() }
        override fun onError(errorCode: NaviErrorCode) { status("NaviHelper", "대기 / ${errorCode.name}") }
    }
    fun requestChallengeRoute(): Boolean {
        val helper = navi?.takeIf { naviReady } ?: return false
        return runCatching {
            helper.requestRoute(ai.pleos.playground.navi.data.RouteInfo(
                latitude=37.52639977, longitude=126.93360958,
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
        observedSeatBeltTelltale = false
        active = true; cycles=0; brakeReady=false; turnReady=false; odoReady=false; driverSeat=null
        status("Vehicle", "서비스 연결 중")
        runCatching { vehicle = Vehicle(context).also { it.initialize() } }.onFailure { status("Vehicle", it.javaClass.simpleName) }
        runCatching {
            navi = NaviHelper(context).also { helper ->
                helper.initialize(onConnected = {
                    naviReady = true; helper.addListener(events); helper.getDestinationInfo(); status("NaviHelper", "연결됨 · 목적지 대기")
                }, onFailed = { status("NaviHelper", "연결 실패 · ${it.javaClass.simpleName}") })
            }
        }.onFailure { status("NaviHelper", it.javaClass.simpleName) }
        runCatching {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (ready) {
                    properties = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    ux = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    status("AAOS", "Car service 연결됨")
                } else { properties = null; ux = null; state.update { it.copy(speedMps=null,gearPark=null,uxRestricted=null) }; status("AAOS", "연결 해제") }
            }
        }.onFailure { status("AAOS", "사용 불가 · ${it.javaClass.simpleName}") }
        main.post(poll)
    }
    private fun capabilities() {
        vehicle?.let { v ->
            runCatching {
                v.getBrake().checkBrakePedalStatusCapabilities({ c -> brakeReady = readable(c); status("Brake", "권한 ${c.accessType}") }, { status("Brake", it.javaClass.simpleName) })
                v.getTurnSignal().checkTurnSignalStateCapabilities({ c -> turnReady = readable(c); status("TurnSignal", "권한 ${c.accessType}") }, { status("TurnSignal", it.javaClass.simpleName) })
                v.getOdometer().checkCurrentDistanceDrivenCapabilities({ c -> odoReady = readable(c); status("Odometer", "권한 ${c.accessType}") }, { status("Odometer", it.javaClass.simpleName) })
                v.getSeat().checkSeatBeltBuckledCapabilities({ c: CapabilityConfig, areas: List<SeatArea> ->
                    driverSeat = if (readable(c)) areas.firstOrNull { a -> a.values.contains(SeatValue.ROW_1_LEFT) } else null
                    status("Seat", if(driverSeat != null) "앞좌석 왼쪽 지원 · LHD 시연" else "운전석 영역 미지원")
                }, { status("Seat", it.javaClass.simpleName) })
            }.onFailure { status("Vehicle", it.javaClass.simpleName) }
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (cycles++ in listOf(1,3,7)) capabilities()
            properties?.let { p ->
                runCatching {
                    val speed = p.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED,0).toDouble()
                    state.update { it.copy(speedMps = speed.takeIf { s -> s.isFinite() && s >= 0 },speedAt = SystemClock.elapsedRealtime()) }
                    status("차량 속도", "%.2f m/s".format(speed))
                }.onFailure { state.update { it.copy(speedMps=null) }; status("차량 속도", "미수신 · ${it.javaClass.simpleName}") }
                runCatching {
                    val gear = p.getIntProperty(VehiclePropertyIds.GEAR_SELECTION,0)
                    val restricted = ux?.currentCarUxRestrictions?.isRequiresDistractionOptimization
                    state.update { it.copy(gearPark=gear==VehicleGear.GEAR_PARK,uxRestricted=restricted) }
                    status("주차 상태", "gear=$gear · UX 제한=$restricted")
                }.onFailure { state.update { it.copy(gearPark=null,uxRestricted=null) }; status("주차 상태", "미확인 · ${it.javaClass.simpleName}") }
            }
            if(naviReady) runCatching { navi?.getDestinationInfo() }
            vehicle?.let { v ->
                if (useEmulatorTelltale) {
                    runCatching {
                        v.getSafety().getTelltalesInfo({ info ->
                            val warning = info?.telltales?.firstOrNull { it.type == TelltaleType.SEAT_BELT }?.state
                            if (vehicle === v) {
                                if (warning != null) observedSeatBeltTelltale = true
                                // This emulator omits an extinguished telltale from successful reads.
                                val warningOff = warning == TelltaleState.OFF ||
                                    (info != null && warning == null && observedSeatBeltTelltale)
                                state.update { it.copy(beltWarningOffAt = if (warningOff) SystemClock.elapsedRealtime() else null) }
                                status("벨트 경고등", if (warningOff) "OFF" else warning?.name ?: "미수신")
                            }
                        }, {
                            if (vehicle === v) {
                                state.update { it.copy(beltWarningOffAt = null) }
                                status("벨트 경고등", it.javaClass.simpleName)
                            }
                        })
                    }.onFailure { state.update { it.copy(beltWarningOffAt = null) } }
                }
                if(brakeReady) runCatching { v.getBrake().getCurrentBrakePedalStatus({ b -> state.update { it.copy(brake=b) }; status("Brake", when(b) { true -> "밟음"; false -> "해제"; null -> "미확인" }) }, { status("Brake",it.javaClass.simpleName) }) }
                if(turnReady) runCatching { v.getTurnSignal().getCurrentTurnSignalState({ t -> state.update { it.copy(turnSignal=t?.name) }; status("TurnSignal",t?.name ?: "미확인") }, { status("TurnSignal",it.javaClass.simpleName) }) }
                if(odoReady) runCatching { v.getOdometer().getCurrentDistanceDriven({ d -> state.update { it.copy(odometer=d) }; status("Odometer","$d · 원시값") }, { status("Odometer",it.javaClass.simpleName) }) }
                driverSeat?.let { area -> runCatching { v.getSeat().getCurrentSeatBeltBuckledState(area,{ b -> state.update { it.copy(belt=b) }; status("Seat",when(b) { true -> "버클 잠김"; false -> "버클 열림"; null -> "미확인" }) },{ state.update { it.copy(belt=null) }; status("Seat",it.javaClass.simpleName) }) } }
            }
            main.postDelayed(this,1000)
        }
    }
    fun stop() {
        active=false; main.removeCallbacks(poll); naviReady=false
        runCatching { navi?.removeListener(events); navi?.release() }
        runCatching { vehicle?.release() }; runCatching { car?.disconnect() }
        vehicle=null; navi=null; car=null; properties=null; ux=null
        state.value = LiveVehicle(statuses=mapOf("연결" to "대기"))
    }
}
