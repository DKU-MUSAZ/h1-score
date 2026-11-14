package ai.musaz.h1score

/** Explicit debug filming input; never enabled by a release build or by default. */
internal fun rehearsalBelt(v: LiveVehicle, enabled: Boolean, now: Long): LiveVehicle {
    if (!enabled) return v
    val on=v.beltWarningOnAt?.takeIf { now-it in 0..2500 }
    val off=v.beltWarningOffAt?.takeIf { now-it in 0..2500 }
    val time=maxOf(on ?: 0, off ?: 0)
    return v.copy(belt=if(time==0L) null else off != null && (on==null || off>on), beltAt=time)
}
