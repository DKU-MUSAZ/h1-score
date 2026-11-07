package ai.musaz.h1score.core

/** Physical, piecewise-constant acceleration fixture. Time is independent of sample count. */
internal class SpeedTrace(
    initialSpeedMps: Double = 15.0,
    private val limitKph: Int? = 100,
) {
    private var atMs = 0L
    private var speedMps = initialSpeedMps
    val samples = mutableListOf(Sample(atMs, speedMps, limitKph))

    fun drive(durationMs: Long, accelerationMps2: Double = 0.0, stepMs: Long = 100): SpeedTrace {
        require(durationMs >= 0 && stepMs > 0)
        var remaining = durationMs
        while (remaining > 0) {
            val elapsed = minOf(stepMs, remaining)
            atMs += elapsed
            speedMps += accelerationMps2 * elapsed / 1000.0
            samples += Sample(atMs, speedMps, limitKph)
            remaining -= elapsed
        }
        return this
    }
}
