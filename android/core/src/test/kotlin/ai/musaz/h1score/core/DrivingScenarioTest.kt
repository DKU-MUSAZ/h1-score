package ai.musaz.h1score.core

import org.junit.Test
import kotlin.test.*

class DrivingScenarioTest {
    @Test fun compliantAndSustainedSpeedingTripsProduceDifferentResults() {
        val compliant = ScoreEngine.evaluate((0..7500).map { Sample(it * 100L, 14.0, 60) })
        val speeding = ScoreEngine.evaluate((0..7500).map { Sample(it * 100L, 22.0, 60) })

        assertTrue(compliant.valid)
        assertEquals(100, compliant.score)
        assertEquals(0.0, compliant.overLimitDistanceM)
        assertTrue(speeding.valid)
        assertEquals(60, speeding.score)
        assertEquals(speeding.validDistanceM, speeding.overLimitDistanceM)
        val ranking = Settlement.rank(listOf(Entry("speeding", requireNotNull(speeding.score)), Entry("a", 92), Entry("b", 89)))
        assertEquals(0, ranking.first { it.id == "speeding" }.miles)
    }

    @Test fun rapidAccelerationCostsPointsEvenBelowTheSpeedLimit() {
        val trace = SpeedTrace(initialSpeedMps = 10.0, limitKph = 60)
        repeat(200) {
            trace.drive(1500, 3.0).drive(1500, -3.0).drive(2000)
        }
        val result = ScoreEngine.evaluate(trace.samples)

        assertTrue(result.valid)
        assertEquals(100.0, result.compliance)
        assertTrue(result.accelerationEvents > 0)
        assertTrue(requireNotNull(result.score) < 100)
    }
}
