package ai.musaz.h1score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileVehicleStatusTest {
    @Test fun unavailableOrPartialReadNeverReportsAllClosed() {
        assertNull(openingSummary(emptyMap()))
        assertNull(openingSummary(mapOf("left" to null)))
        assertEquals("일부 미확인", openingSummary(mapOf("left" to false, "right" to null)))
        assertEquals("1곳 열림 · 일부 미확인", openingSummary(mapOf("left" to true, "right" to null)))
        assertEquals("1곳 열림", openingSummary(mapOf("left" to true, "right" to false)))
        assertEquals("모두 닫힘", openingSummary(mapOf("left" to false, "right" to false)))
    }

    @Test fun lockAndLightLabelsDoNotInventSafeStatesWhenUnavailable() {
        assertNull(doorLockSummary(mapOf("left" to null)))
        assertEquals("일부 미확인", doorLockSummary(mapOf("left" to true, "right" to null)))
        assertEquals("1곳 잠금 해제", doorLockSummary(mapOf("left" to true, "right" to false)))
        assertEquals("모두 잠김", doorLockSummary(mapOf("left" to true)))
        assertNull(lightLabel("UNKNOWN"))
        assertEquals("꺼짐", lightLabel("OFF"))
        assertEquals("주간 주행등", lightLabel("DAYTIME_RUNNING"))
        assertNull(openLabel(null))
    }
}
