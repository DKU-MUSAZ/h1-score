package ai.musaz.h1score

import org.junit.Assert.*
import org.junit.Test

class RehearsalBeltTest {
    @Test fun explicitRehearsalUsesFreshLampAndNeverInventsMissingInput() {
        val v=LiveVehicle(belt=false,beltAt=1000,beltWarningOffAt=1000)
        assertEquals(false,rehearsalBelt(v,false,1000).belt)
        assertEquals(true,rehearsalBelt(v,true,1000).belt)
        assertEquals(false,rehearsalBelt(v.copy(belt=true,beltWarningOnAt=1001),true,1001).belt)
        assertNull(rehearsalBelt(v,true,3501).belt)
        assertNull(rehearsalBelt(LiveVehicle(),true,1000).belt)
    }
}
