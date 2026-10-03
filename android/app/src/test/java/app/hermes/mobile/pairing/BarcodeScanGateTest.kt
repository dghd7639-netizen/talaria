package app.hermes.mobile.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodeScanGateTest {
    @Test
    fun acceptsOnlyTheFirstNonBlankQrPayload() {
        val gate = BarcodeScanGate()

        assertNull(gate.accept("  "))
        assertEquals("pairing-json", gate.accept(" pairing-json "))
        assertNull(gate.accept("second-frame"))
    }
}
