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
    @Test fun `demo profiles use the new scoring engine at ten Hz`() {
        val scores = DemoDrive.entries.map { profile ->
            ScoreEngine.evaluate((0 until 6240).map { profile.sample(it) })
        }
        assertTrue(scores.all { it.scoringVersion == ScoreEngine.VERSION && it.longitudinalReady })
        assertTrue(scores[0].score!! > scores[1].score!!)
        assertTrue(scores[1].score!! > scores[2].score!!)
        assertEquals(60, scores[2].score)
        val short = ScoreEngine.evaluate((0 until 1920).map { DemoDrive.GOOD.sample(it) })
        assertTrue(short.score!! < scores[0].score!!)
        assertFalse(short.valid)
    }
}
