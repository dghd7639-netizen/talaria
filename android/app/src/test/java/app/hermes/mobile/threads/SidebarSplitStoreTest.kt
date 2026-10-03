package app.hermes.mobile.threads

import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarSplitStoreTest {
    @Test
    fun ratioUsesDefaultAndStaysWithinUsableBounds() {
        assertEquals(0.6f, sanitizeSidebarRatio(0.6f))
        assertEquals(0.6f, sanitizeSidebarRatio(Float.NaN))
        assertEquals(0.2f, sanitizeSidebarRatio(0.1f))
        assertEquals(0.8f, sanitizeSidebarRatio(0.9f))
    }

    @Test
    fun dragDeltaUpdatesAndBoundsRatio() {
        assertEquals(0.8f, ratioAfterDrag(0.6f, 100f, 500f))
        assertEquals(0.2f, ratioAfterDrag(0.6f, -300f, 500f))
        assertEquals(0.6f, ratioAfterDrag(0.6f, 100f, 0f))
    }
}
