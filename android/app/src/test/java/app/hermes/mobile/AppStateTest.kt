package app.hermes.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateTest {
    @Test
    fun unpairedUserStartsOnPairing() {
        val state = AppState(isPaired = false, lastThreadId = null)

        assertEquals(RootDestination.Pairing, state.startDestination)
    }

    @Test
    fun pairedUserStartsOnLatestThread() {
        val state = AppState(isPaired = true, lastThreadId = "thread-1")

        assertEquals(RootDestination.Thread("thread-1"), state.startDestination)
    }

    @Test
    fun disconnectReturnsToPairingEvenWithPreviousThread() {
        val state = AppState(isPaired = true, lastThreadId = "thread-1")

        assertEquals(RootDestination.Pairing, state.copy(isPaired = false).startDestination)
    }
}
