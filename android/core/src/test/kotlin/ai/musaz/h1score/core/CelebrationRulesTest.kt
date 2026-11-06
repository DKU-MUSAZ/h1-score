package ai.musaz.h1score.core

import org.junit.Test
import kotlin.test.*

class CelebrationRulesTest {
    @Test fun `unmeasured result stays neutral rather than sad`() {
        assertEquals(DriveMood.NEUTRAL, DriveMood.forScore(null))
        assertEquals(DriveMood.TEARY, DriveMood.forScore(74))
        assertEquals(DriveMood.ENCOURAGING, DriveMood.forScore(75))
        assertEquals(DriveMood.ENCOURAGING, DriveMood.forScore(89))
        assertEquals(DriveMood.CELEBRATING, DriveMood.forScore(90))
    }
    @Test fun `badges only celebrate new qualified achievements`() {
        assertTrue(BadgeRules.newAwards(false, "poi", 100, 1, emptySet()).isEmpty())
        val first=BadgeRules.newAwards(true, "poi", 96, 1, emptySet())
        assertEquals(setOf("first-drive", "visit:poi", "top-three"), first.map { it.id }.toSet())
        assertTrue(BadgeRules.newAwards(true, "poi", 96, 1, first.map { it.id }.toSet()).isEmpty())
        val next=BadgeRules.newAwards(true, "next", 80, null, first.map { it.id }.toSet())
        assertEquals(listOf("visit:next"),next.map { it.id })
    }
    @Test fun `low score cannot earn top three badge`() {
        assertFalse(BadgeRules.newAwards(true,"poi",84,1,emptySet()).any {it.id=="top-three"})
    }
    @Test fun `demo profiles exercise three real score outcomes`() {
        // 192 samples: 3.2 km personal replay; 624: full 10 km replay.
        listOf(192, 624).forEach { sampleCount ->
            val scores=DemoDrive.entries.map { profile ->
                ScoreEngine.evaluate((0 until sampleCount).map { profile.sample(it) }).score!!
            }
            assertTrue(scores[0]>=90, "Celebration profile, $sampleCount samples: ${scores[0]}")
            assertTrue(scores[1] in 75..89, "Encouraging profile, $sampleCount samples: ${scores[1]}")
            assertTrue(scores[2]<75, "Teary profile, $sampleCount samples: ${scores[2]}")
        }
    }
}
