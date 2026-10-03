package app.hermes.mobile.management.groups

import androidx.lifecycle.ViewModelStore
import app.hermes.mobile.data.BotGroupMemberDto
import app.hermes.mobile.data.BotGroupMessageDto
import app.hermes.mobile.data.BotGroupRoomDto
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.pairing.DeviceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsViewModelTest {
    private class Harness(scope: TestScope) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val calls = mutableListOf<Request>()
        val roomResponses = ArrayDeque<Pair<Int, String>>()
        var listResponse = 200 to """{"rooms":[{"room_id":"r","name":"纸模型讨论",
            "members":[{"name":"折纸员","local":true},{"name":"画图员","local":false}],
            "message_count":2,"omitted":4,"last_at":1790000000.25}]}"""
        var detail = """{"room_id":"r","name":"纸模型讨论","omitted":4,"total":2,
            "messages":[{"id":"b","from_kind":"member","from_name":"折纸员","text":"先画草图","at":2.75},
                        {"id":"a","from_kind":"user","text":"做一个纸模型","at":1.25}]}"""
        var onRoomRequest: (() -> Unit)? = null
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"),
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                calls += request
                val reply = when (request.url.encodedPath) {
                    "/v1/bot-groups" -> listResponse
                    else -> {
                        onRoomRequest?.invoke()
                        if (roomResponses.isNotEmpty()) roomResponses.removeFirst() else 200 to detail
                    }
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.first).message("Response")
                    .body(reply.second.toResponseBody("application/json".toMediaType())).build()
            }.build(), Json)
        val vm = GroupsViewModel(api, dispatcher)
        val reads: List<Request> get() = calls.filter { it.url.encodedPath.startsWith("/v1/bot-groups/") }
        fun open(foreground: Boolean = true) {
            vm.setForeground(foreground)
            vm.openRoom(BotGroupRoomDto(room_id = "r"))
        }
    }

    private fun scenario(block: suspend TestScope.(Harness) -> Unit) = runTest {
        val h = Harness(this)
        Dispatchers.setMain(h.dispatcher)
        try {
            block(h)
            h.calls.forEach {
                assertEquals("GET", it.method)
                assertTrue(it.url.encodedPath == "/v1/bot-groups" || it.url.encodedPath.startsWith("/v1/bot-groups/"))
                assertEquals("Bearer secret", it.header("Authorization"))
            }
        } finally { h.vm.close(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun loadsListAndEmptyStateWithoutDuplicateRequestsOrListPolling() = scenario { h ->
        assertFalse(h.vm.state.value.empty)
        h.vm.setForeground(true)
        h.vm.refresh(); h.vm.refresh()
        assertTrue(h.vm.state.value.loading)
        runCurrent()
        assertEquals(1, h.calls.size)
        val room = h.vm.state.value.rooms.single()
        assertEquals("纸模型讨论", room.name)
        assertEquals(2L, room.message_count)
        assertEquals(1790000000.25, room.last_at, 0.0)
        assertEquals(listOf(true, false), room.members.map { it.local })
        assertEquals("更早的 4 条消息未同步", groupOmittedNotice(room.omitted))
        assertFalse(h.vm.state.value.empty)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, h.calls.size)
        h.listResponse = 200 to """{"rooms":[]}"""
        h.vm.refresh(); runCurrent()
        assertTrue(h.vm.state.value.empty)
    }

    @Test fun failedListLoadDoesNotClaimEmptyAndManualRefreshRecovers() = scenario { h ->
        h.listResponse = 503 to """{"detail":{"code":"hermes_unavailable"}}"""
        h.vm.refresh(); runCurrent()
        assertFalse(h.vm.state.value.empty)
        assertNotNull(h.vm.state.value.error)
        assertFalse(h.vm.state.value.loading)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, h.calls.size)
        h.listResponse = 200 to """{"rooms":[]}"""
        h.vm.refresh(); runCurrent()
        assertNull(h.vm.state.value.error)
        assertTrue(h.vm.state.value.empty)
    }

    @Test fun opensChronologicallyAndKeepsMessagesWithoutUniqueIds() = scenario { h ->
        h.open(); runCurrent()
        val room = requireNotNull(h.vm.state.value.room)
        assertEquals("纸模型讨论", room.name)
        assertEquals(listOf("a", "b"), room.messages.map { it.id })
        assertEquals(listOf(1.25, 2.75), room.messages.map { it.at })
        assertEquals("更早的 4 条消息未同步", h.vm.state.value.omittedNotice)
        assertEquals("200", h.reads.single().url.queryParameter("limit"))
        h.detail = """{"messages":[{"id":"same","at":2},{"id":"same","at":2},
            {"id":"","at":3},{"at":4},{"at":5}],"total":5}"""
        h.vm.refresh(); h.vm.refresh(); runCurrent()
        assertEquals(2, h.reads.size)
        assertEquals(listOf("same", "same", "", null, null), h.vm.state.value.room!!.messages.map { it.id })
        assertNull(h.vm.state.value.omittedNotice)
    }

    @Test fun autoRefreshRequiresOpenRoomAndForegroundAndAlwaysDelays() = scenario { h ->
        h.open(foreground = false); runCurrent()
        assertEquals(1, h.reads.size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, h.reads.size)
        h.vm.setForeground(true); runCurrent()
        advanceTimeBy(9999); runCurrent()
        assertEquals(1, h.reads.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, h.reads.size)
        h.vm.setForeground(true) // Repeated lifecycle events must not restart the timer.
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, h.reads.size)
        h.vm.setForeground(false)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, h.reads.size)
        h.vm.setForeground(true)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(4, h.reads.size)
    }

    @Test fun failuresBackOffForThirtySecondsPreserveMessagesAndRecover() = scenario { h ->
        h.open(); runCurrent()
        val messages = h.vm.state.value.room!!.messages
        h.roomResponses.addLast(503 to """{"detail":{"code":"hermes_unavailable"}}""")
        advanceTimeBy(10_000); runCurrent()
        assertTrue(h.vm.state.value.unstable)
        assertNotNull(h.vm.state.value.error)
        assertEquals(messages, h.vm.state.value.room!!.messages)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(2, h.reads.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, h.reads.size)
        assertFalse(h.vm.state.value.unstable)
        assertNull(h.vm.state.value.error)
        advanceTimeBy(9999); runCurrent()
        assertEquals(3, h.reads.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(4, h.reads.size)
    }

    @Test fun failedOpenAlsoBacksOffAndRepeatedFailuresNeverSpin() = scenario { h ->
        repeat(3) { h.roomResponses.addLast(503 to """{"detail":{"code":"hermes_unavailable"}}""") }
        h.open(); runCurrent()
        assertTrue(h.vm.state.value.unstable)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(1, h.reads.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, h.reads.size)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, h.reads.size)
        h.vm.refresh(); runCurrent() // Manual retry need not wait out backoff.
        assertEquals(4, h.reads.size)
        assertFalse(h.vm.state.value.unstable)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(5, h.reads.size)
    }

    @Test fun backAndCloseStopRefreshAndReopenCleanly() = scenario { h ->
        h.open(); runCurrent()
        h.vm.back()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, h.reads.size)
        assertNull(h.vm.state.value.room)
        h.open(); runCurrent()
        h.vm.close()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, h.reads.size)
        assertNull(h.vm.state.value.room)
        assertFalse(h.vm.state.value.loading)
        h.open(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(4, h.reads.size)
    }

    @Test fun clearingViewModelScopeStopsRefresh() = scenario { h ->
        val store = ViewModelStore()
        store.put("groups", h.vm)
        h.open(); runCurrent()
        store.clear()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, h.reads.size)
        assertFalse(h.vm.state.value.loading)
    }

    @Test fun closingBeforeScheduledLoadPreventsRequests() = scenario { h ->
        h.open()
        h.vm.close(); runCurrent()
        assertTrue(h.calls.isEmpty())
        assertFalse(h.vm.state.value.loading)
        h.open(); runCurrent()
        assertEquals(1, h.reads.size)
    }

    @Test fun cancelledResponseCannotReopenRoomOrOverwriteNewSelection() = scenario { h ->
        h.onRoomRequest = {
            h.onRoomRequest = null
            h.vm.openRoom(BotGroupRoomDto(room_id = "new"))
        }
        h.open(); runCurrent()
        assertEquals(listOf("r", "new"), h.reads.map { it.url.pathSegments.last() })
        assertEquals("new", h.vm.state.value.room?.room_id)
        assertFalse(h.vm.state.value.loading)
        h.onRoomRequest = { h.vm.close() }
        h.vm.refresh(); runCurrent()
        assertNull(h.vm.state.value.room)
        assertFalse(h.vm.state.value.loading)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, h.reads.size)
    }

    @Test fun listAndRoomFormatWarningsFollowTheirOwnSnapshots() = scenario { h ->
        h.listResponse = 200 to """{"rooms":[{"room_id":"r"}],"format_warning":true}"""
        h.vm.refresh(); runCurrent()
        assertTrue(h.vm.state.value.formatWarning)
        h.detail = """{"format_warning":true,"messages":[]}"""
        h.open(); runCurrent()
        assertTrue(h.vm.state.value.formatWarning)
        h.detail = "{}"
        advanceTimeBy(10_000); runCurrent()
        assertFalse(h.vm.state.value.formatWarning)
        h.vm.back()
        assertTrue(h.vm.state.value.formatWarning)
        h.listResponse = 200 to "{}"
        h.vm.refresh(); runCurrent()
        assertFalse(h.vm.state.value.formatWarning)
    }

    @Test fun roomErrorsHaveChineseMessagesAndCanBeRetried() = scenario { h ->
        listOf(400 to "invalid_bot_group_request", 404 to "bot_group_not_found", 503 to "hermes_unavailable")
            .forEach { (status, code) ->
                h.roomResponses.addLast(status to """{"detail":{"code":"$code"}}""")
                h.open(); runCurrent()
                assertEquals(groupErrorMessage(BridgeRequestException(status, code)), h.vm.state.value.error)
                assertTrue(h.vm.state.value.error!!.any { it.code > 127 })
                assertFalse(h.vm.state.value.error!!.contains(code))
                assertFalse(h.vm.state.value.loading)
                h.vm.refresh(); runCurrent()
                assertNull(h.vm.state.value.error)
            }
    }

    @Test fun labelsOmittedNoticesAndLocalTimestampsHaveSafeFallbacks() {
        assertEquals("我", groupAuthorLabel(BotGroupMessageDto(from_kind = "user", from_name = "桌面用户")))
        assertEquals("折纸员", groupAuthorLabel(BotGroupMessageDto(from_kind = "member", from_name = "折纸员")))
        assertEquals("未知成员", groupAuthorLabel(BotGroupMessageDto(from_kind = "member")))
        assertEquals("其他", groupAuthorLabel(BotGroupMessageDto()))
        assertEquals("访客", groupAuthorLabel(BotGroupMessageDto(from_kind = "other", from_name = "访客")))
        assertEquals("折纸员", groupMemberLabel(BotGroupMemberDto(name = "折纸员", local = true)))
        assertEquals("画图员（非本机）", groupMemberLabel(BotGroupMemberDto(name = "画图员")))
        assertEquals("paper（非本机）", groupMemberLabel(BotGroupMemberDto(handle = "paper")))
        assertEquals("未知成员（非本机）", groupMemberLabel(BotGroupMemberDto()))
        assertNull(groupOmittedNotice(0))
        assertNull(groupOmittedNotice(-1))
        assertEquals("更早的 5000000000 条消息未同步", groupOmittedNotice(5000000000))
        listOf(null, 0.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { assertEquals("时间未知", groupTimestamp(it)) }
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"))
            assertEquals("1970-01-01 08:00:01", groupTimestamp(1.25))
        } finally { TimeZone.setDefault(original) }
    }
}
