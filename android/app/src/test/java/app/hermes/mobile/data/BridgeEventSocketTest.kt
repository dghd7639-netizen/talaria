package app.hermes.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeEventSocketTest {
    @Test
    fun onlyAuthenticationFailuresStopReconnect() {
        assertTrue(isAuthenticationExpired(401))
        assertTrue(isAuthenticationExpired(403))
        assertTrue(isAuthenticationExpired(4401))
        assertFalse(isAuthenticationExpired(503))
        assertFalse(isAuthenticationExpired(1006))
        assertFalse(isAuthenticationExpired(null))
    }
}
