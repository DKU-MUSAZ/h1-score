package ai.musaz.h1score.core

import org.junit.Assert.*
import org.junit.Test

class VenueMatchingTest {
    private val a=ChallengeVenue("a",GeoPoint(0.0,0.0))
    private fun east(meters: Double)=GeoPoint(0.0,Math.toDegrees(meters/6_371_000.0))
    @Test fun overlapChoosesClosestNotFirstOrMostPopular() {
        val b=ChallengeVenue("b",east(1200.0))
        assertEquals("b",nearestEligibleVenue(east(800.0),listOf(a,b))?.venue?.id)
        assertEquals("a",nearestEligibleVenue(east(400.0),listOf(b,a))?.venue?.id)
    }
    @Test fun boundaryIsInclusiveAndOutsideIsRejected() {
        assertNotNull(nearestEligibleVenue(east(999.999),listOf(a)))
        assertEquals(1000.0,east(1000.0).distanceMetersTo(a.center),0.000001)
        assertNotNull(nearestEligibleVenue(east(1000.0),listOf(a)))
        assertNull(nearestEligibleVenue(east(1000.001),listOf(a)))
    }
    @Test fun exactTieUsesStableIdIndependentOfOrder() {
        val b=ChallengeVenue("b",a.center)
        assertEquals("a",nearestEligibleVenue(a.center,listOf(b,a))?.venue?.id)
        assertEquals("a",nearestEligibleVenue(a.center,listOf(a,b))?.venue?.id)
    }
    @Test fun invalidOrMissingCoordinatesNeverJoin() {
        assertNull(nearestEligibleVenue(null,listOf(a)))
        assertNull(nearestEligibleVenue(GeoPoint(Double.NaN,0.0),listOf(a)))
        assertNull(nearestEligibleVenue(GeoPoint(91.0,0.0),listOf(a)))
        assertNull(nearestEligibleVenue(a.center,emptyList()))
        assertNull(nearestEligibleVenue(a.center,listOf(ChallengeVenue("bad",GeoPoint(0.0,Double.POSITIVE_INFINITY)))))
    }
    @Test fun zeroDistanceAndDestinationChangeAreReevaluated() {
        assertEquals(0.0,nearestEligibleVenue(a.center,listOf(a))!!.distanceMeters,0.0)
        assertNull(nearestEligibleVenue(east(2000.0),listOf(a)))
    }
}
