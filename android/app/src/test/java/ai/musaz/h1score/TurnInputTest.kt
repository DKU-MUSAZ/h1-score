package ai.musaz.h1score

import org.junit.Assert.*
import org.junit.Test

class TurnInputTest {
    @Test fun pleosPositiveLeftBecomesCoreNegativeLeft() {
        val live = LiveVehicle(steeringDeg = 25.0, steeringAt = 1000)
        assertEquals(-25.0, live.turnObservation(1000).steeringDeg!!, 0.0)
        assertEquals(25.0, live.copy(steeringDeg = -25.0).turnObservation(1000).steeringDeg!!, 0.0)
    }
    @Test fun staleSteeringDoesNotBecomeAnObservation() {
        assertNull(LiveVehicle(steeringDeg = 25.0, steeringAt = 0).turnObservation(2000).steeringDeg)
    }
    @Test fun staleMotionCannotConfirmTurnButOtherScoringFreshnessIsUnchanged() {
        val live=LiveVehicle(speedMps=8.0,speedAt=1000,forward=true,gearAt=1000)
        assertEquals(8.0,live.freshSpeed(2000)!!,0.0)
        assertNull(live.turnObservation(2000).speedMps)
        assertFalse(live.copy(speedAt=4000).turnObservation(4000).forward)
        assertTrue(live.copy(speedAt=4000,gearAt=4000).turnObservation(4000).forward)
    }
    @Test fun hazardRequiresExplicitOffAndFreshEvidence() {
        assertEquals(false, decodeHazard("0"))
        assertEquals(true, decodeHazard("1"))
        assertNull(decodeHazard("2"))
        assertNull(decodeHazard(null))
        assertFalse(acceptTurnReply(1000,1600,900))
        assertFalse(acceptTurnReply(1000,1100,1200))
        assertTrue(acceptTurnReply(1000,1200,900))
    }
}
