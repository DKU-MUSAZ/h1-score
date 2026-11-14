package ai.musaz.h1score

import ai.pleos.playground.vehicle.IVehicleService
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.callback.IGetPropertyResult
import ai.pleos.playground.vehicle.constant.PleosPropertyIds
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper

internal object PleosSeatValueDecoder {
    fun occupancy(raw: String?): Boolean? = when (raw?.toIntOrNull()) {
        1 -> false // VehicleSeatOccupancyState.VACANT
        2 -> true // VehicleSeatOccupancyState.OCCUPIED
        else -> null // UNKNOWN (0), unsupported states, and unavailable or malformed values.
    }

    fun buckle(raw: String?): Boolean? = when (raw) {
        "false", "0" -> false
        "true", "1" -> true
        else -> null
    }
}

/** Reads the SDK's public, read-only AIDL contract without its lossy Boolean conversion. */
internal class PleosSeatReader(context: Context, private val onUnavailable: () -> Unit = {}) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var service: IVehicleService? = null
    private var connection: ServiceConnection? = null
    private var generation = 0L

    fun start(): Unit = onMain {
        if (connection != null) return@onMain
        val epoch = ++generation
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) = onMain {
                if (generation == epoch && connection === this) service = IVehicleService.Stub.asInterface(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) = onMain {
                if (generation == epoch && connection === this) {
                    service = null
                    onUnavailable()
                }
            }

            override fun onBindingDied(name: ComponentName) = onMain {
                if (generation == epoch && connection === this) {
                    onUnavailable()
                    stop()
                    start()
                }
            }

            override fun onNullBinding(name: ComponentName) = onMain {
                if (generation == epoch && connection === this) {
                    onUnavailable()
                    stop()
                }
            }
        }
        connection = next
        val intent = Intent("ai.pleos.playground.vehicle.VEHICLE_START").setComponent(ComponentName(
            "ai.pleos.playground.service.vehicle", "ai.pleos.playground.service.vehicle.VehicleService"))
        val bound = runCatching { app.bindService(intent, next, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound && connection === next) {
            connection = null
            service = null
            onUnavailable()
        }
    }

    fun stop(): Unit = onMain {
        generation++
        service = null
        val old = connection
        connection = null
        if (old != null) runCatching { app.unbindService(old) }
        Unit
    }

    fun readOccupancy(area: SeatArea, onResult: (Boolean?) -> Unit, onFailed: (Exception) -> Unit) =
        readRaw(PleosPropertyIds.SEAT_OCCUPANCY.name, areaMask(area),
            { onResult(PleosSeatValueDecoder.occupancy(it)) }, onFailed)

    fun readBuckle(area: SeatArea, onResult: (Boolean?) -> Unit, onFailed: (Exception) -> Unit) =
        readRaw(PleosPropertyIds.SEAT_BELT_BUCKLED.name, areaMask(area),
            { onResult(PleosSeatValueDecoder.buckle(it)) }, onFailed)

    /** Seat-only raw values are exposed for capability diagnostics and hardware verification. */
    fun readRaw(property: String, areaId: Int, onResult: (String?) -> Unit, onFailed: (Exception) -> Unit) = onMain {
        if (property !in setOf(PleosPropertyIds.SEAT_OCCUPANCY.name, PleosPropertyIds.SEAT_BELT_BUCKLED.name) || areaId <= 0) {
            onFailed(IllegalArgumentException("Unsupported seat property or area"))
            return@onMain
        }
        val source = service
        if (source == null) {
            onResult(null)
            return@onMain
        }
        val epoch = generation
        var completed = false
        fun complete(action: () -> Unit) = onMain {
            if (!completed && generation == epoch && service === source) {
                completed = true
                action()
            }
        }
        val callback = object : IGetPropertyResult.Stub() {
            override fun onResult(eventKey: String?, eventAreaId: Int, raw: String?) = complete {
                if (eventKey == property && eventAreaId == areaId) onResult(raw)
                else onFailed(IllegalStateException("Seat response does not match requested property and area"))
            }

            override fun onFailed(eventKey: String?, eventAreaId: Int, error: String?) = complete {
                onFailed(IllegalStateException(error ?: "Seat read failed"))
            }
        }
        try {
            // Vehicle 2.3.2 SeatImpl -> service.b$d: property.name, integer type 3, OR-ed seat area.
            source.getPropertyValue(property, 3, areaId, callback)
        } catch (error: Exception) {
            complete { onFailed(error) }
        }
    }

    private fun areaMask(area: SeatArea): Int = area.values.fold(0) { mask, value -> mask or value.id }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}
