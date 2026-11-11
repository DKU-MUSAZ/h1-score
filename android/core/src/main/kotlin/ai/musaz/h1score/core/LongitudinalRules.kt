package ai.musaz.h1score.core

import kotlin.math.max

/** Trial settings, not SDK safety limits. Version the filter and thresholds together. */
object LongitudinalRules {
    const val VERSION = "longitudinal-v2"
    const val ACCELERATION_MPS2 = 2.5
    const val ACCELERATION_DURATION_NS = 1_000_000_000L
    const val DECELERATION_MPS2 = -3.0
    const val DECELERATION_WINDOW_NS = 1_000_000_000L
    const val MAX_SAMPLE_GAP_NS = 300_000_000L
    const val MERGE_GAP_NS = 2_000_000_000L
    const val MIN_COVERAGE = .9
    // Prototype quality guards for a requested 10 Hz stream, not SDK/safety standards.
    // A tolerated isolated 300 ms gap is not evidence that a sustained 3.3 Hz feed suffices.
    const val MIN_SAMPLE_RATE_HZ = 8.0
    const val MIN_EVENT_INTERVALS = 3
    // Brake SDK timestamps are receive times, so this is corroboration, never causal proof.
    const val BRAKE_FRESHNESS_MS = 1_500L
}

enum class LongitudinalKind { ACCELERATION, DECELERATION }

data class LongitudinalSpan(val startedAtNanos: Long, val endedAtNanos: Long) {
    val durationNanos get() = endedAtNanos - startedAtNanos
}

data class LongitudinalEvent(
    val kind: LongitudinalKind,
    val startedAtNanos: Long,
    val endedAtNanos: Long,
    /** Acceleration: peak median-filtered rate. Deceleration: lowest qualifying 1 s mean. */
    val peakAccelerationMps2: Double,
    val brakePressed: Boolean?,
    /** Confirmed acceleration runs or negative raw-speed portions of qualifying deceleration windows. */
    val qualifyingIntervals: List<LongitudinalSpan>,
) {
    val startedAtMs get() = startedAtNanos / 1_000_000L
    val endedAtMs get() = endedAtNanos / 1_000_000L
}

data class LongitudinalInterval(
    /** Index of the second speed sample; interval i is [samples[i-1], samples[i]]. */
    val sampleIndex: Int,
    val startedAtNanos: Long,
    val endedAtNanos: Long,
    val accelerationMps2: Double?,
    val section: Int,
    val abrupt: Boolean = false,
    /** Exact excluded portions: a trailing window can start partway through this interval. */
    val abruptSpans: List<LongitudinalSpan> = emptyList(),
) {
    val assessable get() = accelerationMps2 != null
}

data class LongitudinalAnalysis(
    val events: List<LongitudinalEvent>,
    val coverage: Double,
    val ready: Boolean,
    val intervals: List<LongitudinalInterval>,
    /** Usable interval count / usable elapsed seconds; gaps are separately reflected in coverage. */
    val sampleRateHz: Double = 0.0,
)

/** Batch detection lets a completed run mark its beginning, without erasing earlier events. */
object LongitudinalDetector {
    private const val EPSILON = 1e-9 // Floating point arithmetic only, not a measurement tolerance.
    private data class Portion(val intervalIndex: Int, val span: LongitudinalSpan)
    private data class Detection(
        val kind: LongitudinalKind, val section: Int, val portions: List<Portion>, val peak: Double,
    ) {
        val start get() = portions.first().span.startedAtNanos
        val end get() = portions.last().span.endedAtNanos
    }

    private fun union(spans: List<LongitudinalSpan>): List<LongitudinalSpan> {
        val merged = ArrayList<LongitudinalSpan>()
        for (span in spans.sortedBy { it.startedAtNanos }) {
            val last = merged.lastOrNull()
            if (last != null && span.startedAtNanos <= last.endedAtNanos)
                merged[merged.lastIndex] = last.copy(endedAtNanos = max(last.endedAtNanos, span.endedAtNanos))
            else merged += span
        }
        return merged
    }

    fun evaluate(samples: List<Sample>): LongitudinalAnalysis {
        if (samples.size < 2) return LongitudinalAnalysis(emptyList(), 0.0, false, emptyList())
        val goodSample = BooleanArray(samples.size)
        var highWater = Long.MIN_VALUE
        var expectedNanos = 0L
        samples.forEachIndexed { index, sample ->
            val time = sample.speedTimestampNanos
            val monotonic = time >= 0 && (index == 0 || time > highWater)
            goodSample[index] = monotonic && sample.speedMps?.let { it.isFinite() && it in 0.0..80.0 } == true
            if (time >= 0) {
                if (highWater >= 0 && time > highWater) expectedNanos += time - highWater
                highWater = max(highWater, time)
            }
        }
        var section = 0
        var goodNanos = 0L
        var goodIntervalCount = 0
        val history = ArrayDeque<Double>(3)
        val intervals = ArrayList<LongitudinalInterval>(samples.size - 1)
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            val dt = b.speedTimestampNanos - a.speedTimestampNanos
            val usable = goodSample[i - 1] && goodSample[i] && dt in 1..LongitudinalRules.MAX_SAMPLE_GAP_NS
            val acceleration = if (usable) {
                val raw = (requireNotNull(b.speedMps) - requireNotNull(a.speedMps)) / (dt / 1e9)
                history.addLast(raw)
                if (history.size > 3) history.removeFirst()
                goodNanos += dt
                goodIntervalCount++
                // Valid raw edge estimates initialize the causal filter. A perfect trip therefore
                // does not permanently lose bonus for its first two samples. No missing samples
                // are synthesized. Thereafter use the median of the latest three interval rates.
                if (history.size == 3) history.sorted()[1] else raw
            } else {
                history.clear(); section++; null
            }
            intervals += LongitudinalInterval(i, a.speedTimestampNanos, b.speedTimestampNanos, acceleration, section)
        }
        val detections = ArrayList<Detection>()
        fun collectAcceleration() {
            var start = -1
            fun finish(end: Int) {
                if (start >= 0 && end - start + 1 >= LongitudinalRules.MIN_EVENT_INTERVALS &&
                    intervals[end].endedAtNanos - intervals[start].startedAtNanos >= LongitudinalRules.ACCELERATION_DURATION_NS) {
                    val portions = (start..end).map { i -> Portion(i,
                        LongitudinalSpan(intervals[i].startedAtNanos, intervals[i].endedAtNanos)) }
                    detections += Detection(LongitudinalKind.ACCELERATION, intervals[start].section, portions,
                        (start..end).maxOf { requireNotNull(intervals[it].accelerationMps2) })
                }
                start = -1
            }
            for (i in intervals.indices) {
                val value = intervals[i].accelerationMps2
                if (value != null && value >= LongitudinalRules.ACCELERATION_MPS2 - EPSILON) {
                    if (start < 0) start = i
                } else if (start >= 0) finish(i - 1)
            }
            if (start >= 0) finish(intervals.lastIndex)
        }
        collectAcceleration()

        // Deceleration has one criterion: raw speed change over exactly one second. A window
        // qualifies immediately; there is no second dwell timer or separate strong-braking rule.
        var sectionStart = 0 // Sample index following the last unusable interval.
        var left = 0
        for (right in 1 until samples.size) {
            val interval = intervals[right - 1]
            if (!interval.assessable) {
                sectionStart = right; left = right
                continue
            }
            val endNs = samples[right].speedTimestampNanos
            val startNs = endNs - LongitudinalRules.DECELERATION_WINDOW_NS
            if (startNs < samples[sectionStart].speedTimestampNanos) continue
            while (left + 1 < right && samples[left + 1].speedTimestampNanos <= startNs) left++
            val before = samples[left]; val after = samples[left + 1]
            // Subtract timestamps as Long before conversion, preserving precision at large uptimes.
            val fraction = (startNs - before.speedTimestampNanos).toDouble() /
                (after.speedTimestampNanos - before.speedTimestampNanos)
            val startSpeed = requireNotNull(before.speedMps) +
                (requireNotNull(after.speedMps) - before.speedMps) * fraction
            val average = (requireNotNull(samples[right].speedMps) - startSpeed) /
                (LongitudinalRules.DECELERATION_WINDOW_NS / 1e9)
            if (average > LongitudinalRules.DECELERATION_MPS2 + EPSILON) continue
            val portions = (left until right).mapNotNull { index ->
                // Normal or increasing speed inside the evidence window remains bonus-eligible.
                if (requireNotNull(samples[index + 1].speedMps) >= requireNotNull(samples[index].speedMps)) null
                else Portion(index, LongitudinalSpan(max(startNs, samples[index].speedTimestampNanos),
                    samples[index + 1].speedTimestampNanos))
            }
            if (portions.isNotEmpty())
                detections += Detection(LongitudinalKind.DECELERATION, interval.section, portions, average)
        }

        val excluded = Array(intervals.size) { ArrayList<LongitudinalSpan>() }
        detections.forEach { detection -> detection.portions.forEach { excluded[it.intervalIndex] += it.span } }
        data class Group(val kind: LongitudinalKind, val section: Int,
                         val detections: MutableList<Detection>, var end: Long)
        val groups = ArrayList<Group>()
        for (detection in detections.sortedWith(compareBy<Detection> { it.start }.thenByDescending { it.end })) {
            val last = groups.lastOrNull()
            val canMerge = last != null && last.kind == detection.kind && last.section == detection.section &&
                detection.start - last.end <= LongitudinalRules.MERGE_GAP_NS
            if (canMerge) {
                requireNotNull(last).detections += detection
                last.end = max(last.end, detection.end)
            } else groups += Group(detection.kind, detection.section, mutableListOf(detection), detection.end)
        }
        val events = groups.map { group ->
            val portions = group.detections.flatMap { it.portions }
            val spans = union(portions.map { it.span })
            val involved = portions.map { it.intervalIndex }.distinct()
            val brakeStates = involved.map { index ->
                val sample = samples[intervals[index].sampleIndex]
                sample.brakePressed.takeIf {
                    sample.brakeObservedAtMs?.let { at -> sample.atMs - at in 0..LongitudinalRules.BRAKE_FRESHNESS_MS } == true
                }
            }
            val brake = when {
                brakeStates.any { it == true } -> true
                brakeStates.isNotEmpty() && brakeStates.all { it == false } -> false
                else -> null
            }
            LongitudinalEvent(group.kind, spans.first().startedAtNanos, spans.last().endedAtNanos,
                if (group.kind == LongitudinalKind.ACCELERATION) group.detections.maxOf { it.peak }
                else group.detections.minOf { it.peak }, brake, spans)
        }
        val coverage = if (expectedNanos > 0) (goodNanos.toDouble() / expectedNanos).coerceIn(0.0, 1.0) else 0.0
        val sampleRateHz = if (goodNanos > 0) goodIntervalCount / (goodNanos / 1e9) else 0.0
        val ready = goodNanos > 0 && coverage >= LongitudinalRules.MIN_COVERAGE &&
            sampleRateHz >= LongitudinalRules.MIN_SAMPLE_RATE_HZ
        return LongitudinalAnalysis(events, coverage, ready,
            intervals.mapIndexed { i, interval -> interval.copy(abrupt = excluded[i].isNotEmpty(),
                abruptSpans = union(excluded[i])) }, sampleRateHz)
    }
}
