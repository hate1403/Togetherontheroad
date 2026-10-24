package io.togetherroad.core
import org.junit.Assert.*
import org.junit.Test

class DriveTest {
    @Test fun `unapproved and expired route requests are rejected`() {
        val d = ReplayDrive(); d.start(); val p = d.propose(0)
        assertThrows(IllegalStateException::class.java) { d.applying("A", p.id, 1) }
        assertThrows(IllegalArgumentException::class.java) { d.approve("A", p.id, 120_000) }
    }
    @Test fun `approval applies only to own vehicle and completion needs callback`() {
        val d = ReplayDrive(); d.start(); val p = d.propose(0)
        d.approve("A", p.id, 1); d.applying("A", p.id, 2)
        assertEquals(ApplyState.APPLYING, p.states["A"])
        assertEquals(ApplyState.PENDING, p.states["B"])
        d.complete("A", p.id, false); assertEquals(ApplyState.FAILED, p.states["A"])
    }
    @Test fun `old callbacks cannot complete a newer proposal`() {
        val d = ReplayDrive(); d.start(); val p = d.propose(0)
        d.approve("A", p.id, 1); d.applying("A", p.id, 2)
        val newer = d.propose(3); d.complete("A", p.id, true)
        assertEquals(ApplyState.PENDING, newer.states["A"])
    }
    @Test fun `ending clears all coordinates and consent`() {
        val d = ReplayDrive(); d.start(); d.propose(0); d.stop()
        assertFalse(d.active); assertFalse(d.consent); assertTrue(d.vehicles.isEmpty()); assertNull(d.proposal)
    }
    @Test fun `ranking responds to the delayed vehicle`() {
        val d = ReplayDrive(); d.start()
        assertEquals("demo-1", d.candidates().first().place.id)
        d.delayB(); assertEquals("demo-2", d.candidates().first().place.id)
    }
}
