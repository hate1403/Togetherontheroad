package io.togetherroad.core
import org.junit.Assert.*
import org.junit.Test

class GroupProgressTest {
    @Test fun `remaining distance controls vertical progress without ETA assumptions`() {
        assertEquals(.75,journeyProgress(25_000.0,100_000.0)!!,.000001)
        assertEquals(1.0,journeyProgress(0.0,100_000.0)!!,.000001)
        assertEquals(0.0,journeyProgress(120_000.0,100_000.0)!!,.000001)
        assertNull(journeyProgress(null,100.0));assertNull(journeyProgress(10.0,0.0))
        assertNull(journeyProgress(Double.NaN,100.0));assertNull(journeyProgress(-1.0,100.0))
    }
    @Test fun `route interpolation clamps endpoints and progresses with distance`() {
        val r=GeoRoute(listOf(GeoPoint(0.0,0.0),GeoPoint(0.0,1.0),GeoPoint(0.0,2.0)))
        assertEquals(0.0,r.pointAt(-1.0).longitude,0.000001)
        assertEquals(1.0,r.pointAt(.5).longitude,0.000001)
        assertEquals(2.0,r.pointAt(2.0).longitude,0.000001)
    }
}
