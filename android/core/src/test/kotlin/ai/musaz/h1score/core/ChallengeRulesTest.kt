package ai.musaz.h1score.core

import org.junit.Test
import kotlin.test.*

class ChallengeRulesTest {
    @Test fun `unaccepted trip never records`() {
        val trip = ChallengeSession("trip", "poi", 15_000.0)
        assertEquals(Phase.IDLE, trip.update(9_000.0, false, false).phase)
    }
    @Test fun `acceptance reserves then ten km starts once`() {
        var trip = ChallengeSession("trip", "poi", 15_000.0).accept(parked = true)
        assertEquals(Phase.RESERVED, trip.phase)
        trip = trip.update(10_000.0, false, false)
        assertEquals(Phase.RECORDING, trip.phase)
        assertEquals(1, trip.startCount)
        trip = trip.update(10_100.0, false, false).update(9_999.0, false, false)
        assertEquals(1, trip.startCount)
    }
    @Test fun `moving acceptance is rejected`() {
        assertEquals(Phase.IDLE, ChallengeSession("trip", "poi", 15_000.0).accept(false).phase)
    }
    @Test fun `short journey is personal only`() {
        val trip = ChallengeSession("trip", "poi", 3_000.0).accept(true)
        assertFalse(trip.rankingEligible)
        assertEquals(Phase.RECORDING, trip.phase)
    }
    @Test fun `arrival without park keeps result locked`() {
        val trip = ChallengeSession("trip", "poi", 15_000.0).accept(true).update(9_000.0, false, false)
        val arrival = trip.update(0.0, true, false)
        assertEquals(Phase.AWAITING_PARK, arrival.phase)
        assertEquals(Phase.FINISHED, arrival.update(0.0, true, true).phase)
    }
    @Test fun `destination change cancels rather than carrying reward eligibility`() {
        val trip = ChallengeSession("trip", "poi", 15_000.0).accept(true)
        assertEquals(Phase.CANCELLED, trip.changeDestination("other").phase)
    }
    @Test fun `missing data is not a perfect score`() {
        val report = ScoreEngine.evaluate(listOf(Sample(0, 0.0, null), Sample(1000, 10.0, null)))
        assertNull(report.score)
        assertFalse(report.valid)
    }
    @Test fun `braking never incurs an acceleration penalty`() {
        val report = ScoreEngine.evaluate(listOf(Sample(0, 20.0, 80), Sample(1000, 10.0, 80)))
        assertEquals(100.0, report.smoothness)
    }
    @Test fun `tied first shares two slots and third keeps own slot`() {
        val ranked = Settlement.rank(listOf(Entry("a",96),Entry("b",96),Entry("c",90),Entry("d",88),Entry("e",86)))
        assertEquals(listOf(80,80,30,0,0), ranked.map { it.miles })
        assertEquals(listOf(1,1,3,4,5), ranked.map { it.rank })
    }
    @Test fun `low scoring winner and groups under three receive no miles`() {
        assertTrue(Settlement.rank(listOf(Entry("a",84),Entry("b",80),Entry("c",78))).all { it.miles == 0 })
        assertTrue(Settlement.rank(listOf(Entry("a",99),Entry("b",98))).all { it.miles == 0 })
    }
    @Test fun `invalid record does not enter ranked cohort`() {
        val ranked = Settlement.rank(listOf(Entry("a",99,false),Entry("b",95),Entry("c",92)))
        assertEquals(2, ranked.size)
        assertTrue(ranked.all { it.miles == 0 })
    }
    @Test fun `same settlement cannot credit twice`() {
        val ledger = MileageLedger()
        assertEquals(100, ledger.credit("settlement-1", 100))
        assertEquals(0, ledger.credit("settlement-1", 100))
        assertEquals(100, ledger.balance)
    }
    @Test fun `reward redemption deducts only an affordable cost`() {
        val ledger = MileageLedger()
        ledger.credit("drive", 80)
        assertFalse(ledger.spend(81))
        assertEquals(80, ledger.balance)
        assertTrue(ledger.spend(80))
        assertFalse(ledger.spend(80))
        assertEquals(0, ledger.balance)
    }
    @Test fun `same destination is paid once per day and daily limit is two`() {
        val ledger = MileageLedger()
        assertEquals(100, ledger.credit("1",100,"a","2026-11-05"))
        assertEquals(0, ledger.credit("2",100,"a","2026-11-05"))
        assertEquals(60, ledger.credit("3",60,"b","2026-11-05"))
        assertEquals(0, ledger.credit("4",100,"c","2026-11-05"))
        assertEquals(100, ledger.credit("5",100,"a","2026-11-06"))
    }
    @Test fun `missing intervals reduce coverage and invalidate ranking`() {
        val report=ScoreEngine.evaluate(listOf(Sample(0,20.0,80),Sample(1000,20.0,80),Sample(20000,20.0,80)))
        assertEquals(.05,report.coverage,.0001)
        assertFalse(report.valid)
        assertNull(report.score)
    }
    @Test fun `personal score needs distance but not ranking distance`() {
        val short=ScoreEngine.evaluate((0..20).map { Sample(it*1000L,20.0,80) })
        assertNull(short.score)
        val personal=ScoreEngine.evaluate((0..60).map { Sample(it*1000L,20.0,80) })
        assertEquals(100,personal.score)
        assertFalse(personal.valid)
    }
    @Test fun `stopped samples cannot inflate distance weighted compliance`() {
        val moving=listOf(Sample(0,20.0,50),Sample(1000,20.0,50))
        val withStops=moving+Sample(2000,0.0,50)+(3..100).map {Sample(it*1000L,0.0,50)}
        assertEquals(ScoreEngine.evaluate(withStops.take(3)).compliance,ScoreEngine.evaluate(withStops).compliance)
    }
}
