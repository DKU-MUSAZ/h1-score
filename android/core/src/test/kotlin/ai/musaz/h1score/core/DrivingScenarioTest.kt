package ai.musaz.h1score.core

import org.junit.Test
import kotlin.test.*

class DrivingScenarioTest {
    @Test fun compliantAndSustainedSpeedingTripsProduceDifferentResults() {
        val compliant = ScoreEngine.evaluate((0..750).map { Sample(it * 1_000L, 14.0, 60) })
        val speeding = ScoreEngine.evaluate((0..750).map { Sample(it * 1_000L, 22.0, 60) })

        assertTrue(compliant.valid)
        assertEquals(100, compliant.score)
        assertEquals(0.0, compliant.overLimitDistanceM)
        assertTrue(speeding.valid)
        assertEquals(30, speeding.score)
        assertEquals(speeding.validDistanceM, speeding.overLimitDistanceM)
        val ranking = Settlement.rank(listOf(Entry("speeding", requireNotNull(speeding.score)), Entry("a", 92), Entry("b", 89)))
        assertEquals(0, ranking.first { it.id == "speeding" }.miles)
    }

    @Test fun rapidAccelerationCostsPointsEvenBelowTheSpeedLimit() {
        val samples = (0..800).map {
            Sample(it * 1_000L, if (it % 4 < 2) 10.0 else 16.0, 60)
        }
        val result = ScoreEngine.evaluate(samples)

        assertTrue(result.valid)
        assertEquals(100.0, result.compliance)
        assertEquals(200, result.accelerationEvents)
        assertTrue(requireNotNull(result.score) < 100)
    }
}
