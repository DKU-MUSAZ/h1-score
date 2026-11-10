package ai.musaz.h1score.core
import org.junit.Assert.*
import org.junit.Test
class SavedPlaceTest {
    @Test fun nameAloneIsNotADestination() {
        assertNull(SavedPlace.from("집", null))
        assertNull(SavedPlace.from("", GeoPoint(37.5,127.0)))
        assertNull(SavedPlace.from("학교", GeoPoint(Double.NaN,127.0)))
        assertNull(SavedPlace.from("학교", GeoPoint(91.0,127.0)))
    }
    @Test fun preservesRealCoordinatesAndTrimsName() {
        val p=SavedPlace.from(" 서울역 ",GeoPoint(37.5547,126.9707))!!
        assertEquals("서울역",p.name)
        assertEquals(37.5547,p.point.latitude,0.0)
    }
    @Test fun shortcutLabelsRejectEmptyLongOrControlText() {
        assertEquals("학교",shortcutLabel(" 학교 "))
        assertNull(shortcutLabel(" "))
        assertNull(shortcutLabel("학교\n"))
        assertNull(shortcutLabel("가".repeat(13)))
    }
}
