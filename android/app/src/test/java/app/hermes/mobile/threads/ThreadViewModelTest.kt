package app.hermes.mobile.threads

import androidx.lifecycle.ViewModelStore
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeEventSocket
import app.hermes.mobile.data.EventConnection
import app.hermes.mobile.pairing.DeviceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {

    private class FakeBridgeEventSocket : BridgeEventSocket(
        DeviceConnection("https://example.test/".toHttpUrl(), "secret"),
        OkHttpClient(),
        Json { ignoreUnknownKeys = true },
    ) {
        var listener: ((MobileEvent) -> Unit)? = null

        override fun connect(after: Long, onEvent: (MobileEvent) -> Unit): EventConnection {
            listener = onEvent
            return EventConnection {}
        }

        fun emit(event: MobileEvent) {
            listener?.invoke(event)
        }
    }

    @Test
    fun completionDuringHistoryFetchPreservesEventMessageAndStoppedState() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val eventSocket = FakeBridgeEventSocket()
            val emitEventsDuringHistoryFetch = AtomicBoolean(false)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val responseBody = when {
                    path == "/v1/threads" ->
                        """{"items":[{"id":"t1","title":"Task 1","status":"running"}]}"""
                    path == "/v1/threads/t1/resume" ->
                        """{"status":"running"}"""
                    path == "/v1/threads/t1/messages" -> {
                        if (emitEventsDuringHistoryFetch.compareAndSet(true, false)) {
                            // Turn completes while history fetch is in-flight
                            eventSocket.emit(MobileEvent(id = 200L, threadId = "t1", type = "message.complete", payload = mapOf("text" to "Assistant reply from event")))
                            eventSocket.emit(MobileEvent(id = 201L, threadId = "t1", type = "turn.complete", payload = emptyMap()))
                        }
                        // Stale history returned by Bridge lacking the latest event message
                        """{"items":[{"id":"m1","role":"user","text":"Hello"}],"next_offset":null}"""
                    }
                    path == "/v1/approvals" ->
                        """{"items":[]}"""
                    path == "/v1/catalog/models" ->
                        """{"items":[]}"""
                    else -> """{}"""
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

            val api = BridgeApi(
                DeviceConnection("https://example.test/".toHttpUrl(), "secret"),
                client,
                Json { ignoreUnknownKeys = true },
            )
            val vm = ThreadViewModel(api, eventSocket)
            store.put("thread", vm)
            withTimeout(5_000) { vm.state.first { !it.loading && it.messages.isNotEmpty() } }
            emitEventsDuringHistoryFetch.set(true)
            vm.refresh()

            // Dispatchers.IO uses real threads, so wait for completion rather than virtual time.
            val state = withTimeout(5_000) {
                vm.state.first { !it.loading && 201L in it.seenEventIds }
            }
            assertEquals("t1", state.selectedThreadId)
            // Event message is preserved and not overwritten with stale history
            assertEquals(listOf("Hello", "Assistant reply from event"), state.messages.map { it.text })
            // Running state preserved as false from turn.complete, not overwritten by resume's "running"
            assertFalse(state.running)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun titleEventRefreshesThreadsWithoutReloadingMessages() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val eventSocket = FakeBridgeEventSocket()
            val threadsFetchCount = AtomicInteger()
            val messagesFetchCount = AtomicInteger()
            val currentTitle = AtomicReference("Initial Title")
            val currentUpdatedAt = AtomicReference(1790000000.5)

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val responseBody = when {
                    path == "/v1/threads" -> {
                        threadsFetchCount.incrementAndGet()
                        """{"items":[{"id":"t1","title":"${currentTitle.get()}","status":"idle","updated_at":${currentUpdatedAt.get()}}]}"""
                    }
                    path == "/v1/threads/t1/resume" ->
                        """{"status":"idle"}"""
                    path == "/v1/threads/t1/messages" -> {
                        messagesFetchCount.incrementAndGet()
                        """{"items":[{"id":"m1","role":"user","text":"Initial prompt"}],"next_offset":null}"""
                    }
                    path == "/v1/approvals" ->
                        """{"items":[]}"""
                    path == "/v1/catalog/models" ->
                        """{"items":[]}"""
                    else -> """{}"""
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

            val api = BridgeApi(
                DeviceConnection("https://example.test/".toHttpUrl(), "secret"),
                client,
                Json { ignoreUnknownKeys = true },
            )
            val vm = ThreadViewModel(api, eventSocket)
            store.put("thread", vm)
            withTimeout(5_000) { vm.state.first { !it.loading && it.messages.isNotEmpty() } }

            assertEquals(1, threadsFetchCount.get())
            assertEquals(1, messagesFetchCount.get())
            assertEquals(1, vm.state.value.messages.size)
            eventSocket.emit(MobileEvent(299, "t1", "message.complete", mapOf("text" to "Assistant reply")))
            val messages = vm.state.value.messages

            // When session.title arrives with new title
            currentTitle.set("Updated Title via Session")
            eventSocket.emit(MobileEvent(id = 300L, threadId = "t1", type = "session.title", payload = mapOf("title" to "Updated Title via Session")))
            withTimeout(5_000) { vm.state.first { !it.loading && threadsFetchCount.get() == 2 } }

            // Thread list was refreshed
            assertEquals(2, threadsFetchCount.get())
            // Messages were NOT reloaded
            assertEquals(1, messagesFetchCount.get())
            assertEquals("Updated Title via Session", vm.state.value.threads.single().title)
            assertEquals(messages, vm.state.value.messages)

            // A finished turn refreshes the list (so the thread moves up the recent groups)
            // without reloading messages.
            currentUpdatedAt.set(1790000600.25)
            eventSocket.emit(MobileEvent(id = 301L, threadId = "t1", type = "turn.complete", payload = emptyMap()))
            withTimeout(5_000) { vm.state.first { !it.loading && threadsFetchCount.get() == 3 } }
            assertEquals(1790000600.25, vm.state.value.threads.single().updatedAt, 0.0)
            assertEquals(1, messagesFetchCount.get())
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun historyApi(history: (String) -> String): BridgeApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/v1/threads" -> """{"items":[{"id":"t1","title":"First","status":"idle"},
                    {"id":"t2","title":"Streaming","status":"running"}]}"""
                "/v1/threads/t1/resume", "/v1/threads/t2/resume" -> """{"status":"running"}"""
                "/v1/threads/t1/messages" -> history("t1")
                "/v1/threads/t2/messages" -> history("t2")
                "/v1/approvals", "/v1/catalog/models" -> """{"items":[]}"""
                else -> error("Unexpected test request: ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client,
            Json { ignoreUnknownKeys = true })
    }

    @Test
    fun selectingStreamingThreadKeepsHistoryAndDeltasDuringFetch() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val eventSocket = FakeBridgeEventSocket()
            val api = historyApi { threadId ->
                if (threadId == "t2") {
                    eventSocket.emit(MobileEvent(500, "t2", "message.delta", mapOf("text" to "Current ")))
                    eventSocket.emit(MobileEvent(501, "t2", "message.delta", mapOf("text" to "reply")))
                }
                """{"items":[{"id":"m1","role":"user","text":"Earlier prompt"},
                    {"id":"m2","role":"assistant","text":"Earlier reply"},
                    {"id":"m3","role":"user","text":"Follow-up"}],"next_offset":null}"""
            }
            val vm = ThreadViewModel(api, eventSocket)
            store.put("thread", vm)
            withTimeout(5_000) { vm.state.first { !it.loading && it.messages.isNotEmpty() } }
            vm.select("t2")

            val state = withTimeout(5_000) {
                vm.state.first { !it.loading && it.selectedThreadId == "t2" && 501L in it.seenEventIds }
            }
            assertEquals(listOf(
                ChatMessage("m1", "user", "Earlier prompt"),
                ChatMessage("m2", "assistant", "Earlier reply"),
                ChatMessage("m3", "user", "Follow-up"),
            ), state.messages)
            assertEquals("Current reply", state.streamingText)
            assertTrue(state.running)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun completionAlreadyInFetchedHistoryIsNotDuplicated() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val eventSocket = FakeBridgeEventSocket()
            val api = historyApi { threadId ->
                eventSocket.emit(MobileEvent(600, threadId, "message.complete", mapOf("text" to "Completed reply")))
                eventSocket.emit(MobileEvent(601, threadId, "turn.complete", emptyMap()))
                // REST and event IDs differ even when they describe the same reply.
                """{"items":[{"id":"m1","role":"user","text":"Prompt"},
                    {"id":"history-600","role":"assistant","text":"Completed reply"}],"next_offset":null}"""
            }
            val vm = ThreadViewModel(api, eventSocket)
            store.put("thread", vm)

            val state = withTimeout(5_000) {
                vm.state.first { !it.loading && 601L in it.seenEventIds }
            }
            assertEquals(listOf(
                ChatMessage("m1", "user", "Prompt"),
                ChatMessage("history-600", "assistant", "Completed reply"),
            ), state.messages)
            assertEquals("", state.streamingText)
            assertFalse(state.running)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
