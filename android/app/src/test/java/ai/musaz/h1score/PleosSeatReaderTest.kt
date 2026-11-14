package ai.musaz.h1score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PleosSeatReaderTest {
    @Test fun vacantAndOccupiedFollowRawVehicleEnumRatherThanSdkBooleanConversion() {
        assertEquals(false, PleosSeatValueDecoder.occupancy("1"))
        assertEquals(true, PleosSeatValueDecoder.occupancy("2"))
    }

    @Test fun unknownOccupancyDoesNotBecomeEmptyOrOccupied() {
        for (value in listOf(null, "0", "-1", "3", "", "null", "true", "false", "1.0")) {
            assertNull(value, PleosSeatValueDecoder.occupancy(value))
        }
    }

    @Test fun buckleUsesItsOwnZeroOneContract() {
        assertEquals(false, PleosSeatValueDecoder.buckle("0"))
        assertEquals(true, PleosSeatValueDecoder.buckle("1"))
    }

    @Test fun buckleReadsActualPleosBooleanStrings() {
        assertEquals(false, PleosSeatValueDecoder.buckle("false"))
        assertEquals(true, PleosSeatValueDecoder.buckle("true"))
    }

    @Test fun unknownOrMalformedBuckleCannotInventAnUnbuckledViolation() {
        for (value in listOf(null, "2", "-1", "", "null", "TRUE", "FALSE", "1.0")) {
            assertNull(value, PleosSeatValueDecoder.buckle(value))
        }
    }
}
