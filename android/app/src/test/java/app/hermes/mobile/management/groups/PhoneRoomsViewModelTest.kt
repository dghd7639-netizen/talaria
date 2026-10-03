package app.hermes.mobile.management.groups

import androidx.lifecycle.ViewModelStore
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.data.CatalogItem
import app.hermes.mobile.data.PhoneActor
import app.hermes.mobile.data.PhoneEvent
import app.hermes.mobile.data.PhoneMember
import app.hermes.mobile.data.PhoneRoom
import app.hermes.mobile.pairing.DeviceConnection
import app.hermes.mobile.threads.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneRoomsViewModelTest {
    private class Harness(scope: TestScope) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val calls = mutableListOf<Request>()
        val eventReplies = ArrayDeque<Pair<Int, String>>()
        val stateReplies = ArrayDeque<Pair<Int, String>>()
        var available = true
        var listReply = 200 to """{"rooms":[{"room_id":"r","name":"讨论","member_count":2}],"next_offset":null}"""
        var profilesReply = 200 to """{"items":[{"id":"writer","title":"写手"},{"id":"review","title":"审稿员"},
            {"id":"disabled","title":"不可选","enabled":false}],"limited":false}"""
        var createReply = 201 to roomJson("created")
        var sendReply = 200 to """{"accepted":true,"event_id":"user:sent","driver_started":true}"""
        var stopReply = 200 to """{"cancelled":2}"""
        var disbandReply = 200 to """{"disbanded":true}"""
        var approveReply = 200 to """{"status":"resolved"}"""
        var roomRequestHook: (() -> Unit)? = null
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"),
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                calls += request
                val path = request.url.encodedPath
                val reply = when {
                    path == "/v1/groups/capabilities" -> 200 to """{"available":$available,"driver":true,"features":[]}"""
                    path == "/v1/catalog/profiles" -> profilesReply
                    path == "/v1/groups" && request.method == "POST" -> createReply
                    path == "/v1/groups" -> listReply
                    path.endsWith("/events") -> if (eventReplies.isNotEmpty()) eventReplies.removeFirst() else {
                        val cursor = request.url.queryParameter("since_seq")!!.toLong()
                        200 to """{"events":[],"cursor":$cursor,"latest_seq":$cursor,"has_more":false}"""
                    }
                    path.endsWith("/messages") -> sendReply
                    path.endsWith("/stop") -> stopReply
                    path.endsWith("/resolve") -> approveReply
                    request.method == "DELETE" -> disbandReply
                    else -> {
                        roomRequestHook?.invoke()
                        if (stateReplies.isNotEmpty()) stateReplies.removeFirst() else 200 to roomJson(request.url.pathSegments.last())
                    }
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.first).message("Response")
                    .body(reply.second.toResponseBody("application/json".toMediaType())).build()
            }.build(), Json)
        val vm = PhoneRoomsViewModel(api, dispatcher)
        val polls: List<Request> get() = calls.filter { it.url.encodedPath.endsWith("/events") }
        val states: List<Request> get() = calls.filter { it.method == "GET" &&
            it.url.pathSegments.size == 3 && it.url.pathSegments[1] == "groups" && it.url.pathSegments[2] != "capabilities" }
        fun open(id: String = "r", foreground: Boolean = true) {
            vm.setForeground(foreground)
            vm.openRoom(PhoneRoom(roomId = id, name = "讨论"))
        }
        fun events(vararg seq: Long, cursor: Long = seq.lastOrNull() ?: 0, more: Boolean = false) {
            eventReplies.addLast(200 to """{"events":[${seq.joinToString(",") {
                """{"seq":$it,"event_id":"e$it","kind":"message.member","actor":{"kind":"member","id":"m1"},"text":"回复","created_at":1.25}"""
            }}],"cursor":$cursor,"latest_seq":${if (more) cursor + 1 else cursor},"has_more":$more}""")
        }
        fun pending(vararg requestIds: String) {
            stateReplies.addLast(200 to approvalRoomJson(*requestIds))
        }
        companion object {
            fun approvalRoomJson(vararg requestIds: String) = """{"room_id":"r","name":"讨论","member_count":2,"members":[
                {"member_id":"m1","profile":"writer","handle":"writer","display_name":"写手"}],
                "driver_status":{"running":true,"working":false,"blocked":true,"approvals":[${requestIds.joinToString(",") {
                    """{"member_id":"m1","task_id":"t","execution_generation":1,"request_id":"$it","command":"rm -rf ./x",
                    "description":"recursive delete","choices":["once","deny"]}"""
                }}]}}"""
            fun roomJson(id: String) = """{"room_id":"$id","name":"讨论","member_count":2,"members":[
                {"member_id":"m1","profile":"writer","handle":"writer","display_name":"写手"},
                {"member_id":"m2","profile":"review","handle":"review"}],"updated_at":1.25,
                "driver_status":{"running":true,"working":true,"blocked":false}}"""
        }
    }

    private fun scenario(block: suspend TestScope.(Harness) -> Unit) = runTest {
        val h = Harness(this)
        Dispatchers.setMain(h.dispatcher)
        val store = ViewModelStore().apply { put("phone", h.vm) }
        try {
            block(h)
            h.calls.forEach {
                assertEquals("Bearer secret", it.header("Authorization"))
                assertFalse(it.url.encodedPath.startsWith("/v1/bot-groups"))
            }
        } finally { h.vm.close(); store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    private fun Request.bodyJson() = Json.parseToJsonElement(Buffer().also { body!!.writeTo(it) }.readUtf8()).jsonObject

    @Test fun listsRoomsAndPagesWithoutPollingOrDuplicateRefresh() = scenario { h ->
        h.listReply = 200 to """{"rooms":[{"room_id":"r","name":"讨论"}],"next_offset":50}"""
        h.vm.refresh(); h.vm.refresh(); runCurrent()
        assertEquals(2, h.calls.size)
        assertFalse(h.vm.state.value.empty)
        h.listReply = 200 to """{"rooms":[{"room_id":"r"},{"room_id":"s"},{"room_id":"old","disbanded":true}]}"""
        h.vm.loadMore(); runCurrent()
        assertEquals(listOf("r", "s"), h.vm.state.value.rooms.map { it.roomId })
        assertEquals("50", h.calls.last().url.queryParameter("offset"))
        advanceTimeBy(30_000); runCurrent(); assertEquals(3, h.calls.size)
        h.listReply = 200 to """{"rooms":[]}"""
        h.vm.refresh(); runCurrent(); assertTrue(h.vm.state.value.empty)
        h.available = false
        val before = h.calls.size
        h.vm.refresh(); runCurrent()
        assertEquals(before + 1, h.calls.size)
        assertFalse(h.vm.state.value.empty)
    }

    @Test fun createSelectsProfilesOmitsAutomaticHandlesAndOpensReturnedRoomOnce() = scenario { h ->
        h.vm.setForeground(true)
        h.vm.beginCreate(); runCurrent()
        assertEquals(listOf("writer", "review"), h.vm.state.value.profiles.map { it.id })
        h.vm.editName("  讨论组  ")
        h.vm.toggleProfile(h.vm.state.value.profiles[0])
        assertFalse(h.vm.state.value.canCreate)
        h.vm.toggleProfile(h.vm.state.value.profiles[1])
        assertEquals(listOf("writer", "review"), h.vm.state.value.expectedHandles)
        h.vm.editHandle("review", "editor")
        h.vm.create(); h.vm.create()
        assertTrue(h.vm.state.value.loading)
        runCurrent()
        val request = h.calls.single { it.method == "POST" && it.url.encodedPath == "/v1/groups" }
        val body = request.bodyJson()
        assertEquals(setOf("name", "members"), body.keys)
        assertEquals("讨论组", body["name"]!!.jsonPrimitive.content)
        val members = body["members"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("profile", "display_name"), members[0].keys)
        assertEquals("写手", members[0]["display_name"]!!.jsonPrimitive.content)
        assertEquals("editor", members[1]["handle"]!!.jsonPrimitive.content)
        assertEquals("created", h.vm.state.value.room?.roomId)
        assertEquals("0", h.polls.single().url.queryParameter("since_seq"))
        assertFalse(h.vm.state.value.creating)
    }

    @Test fun createFailureKeepsFormAndNeverRetriesWrite() = scenario { h ->
        h.vm.beginCreate(); runCurrent()
        h.vm.editName("保留名称")
        h.vm.state.value.profiles.forEach(h.vm::toggleProfile)
        h.createReply = 400 to """{"detail":{"code":"hermes_rejected","message":"private"}}"""
        h.vm.create(); runCurrent()
        assertTrue(h.vm.state.value.creating)
        assertEquals("保留名称", h.vm.state.value.name)
        assertEquals(2, h.vm.state.value.members.size)
        assertNotNull(h.vm.state.value.error)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, h.calls.count { it.method == "POST" })
    }

    @Test fun createBoundsDisplayNameByCodePointWithoutSplittingEmoji() = scenario { h ->
        h.profilesReply = 200 to """{"items":[{"id":"writer","title":"${"😀".repeat(81)}"},{"id":"review","title":""}]}"""
        h.vm.beginCreate(); runCurrent(); h.vm.editName("讨论")
        h.vm.state.value.profiles.forEach(h.vm::toggleProfile)
        h.vm.create(); runCurrent()
        val body = h.calls.single { it.method == "POST" }.bodyJson()
        val members = body["members"]!!.jsonArray.map { it.jsonObject }
        assertEquals("😀".repeat(80), members[0]["display_name"]!!.jsonPrimitive.content)
        assertEquals(setOf("profile"), members[1].keys)
    }

    @Test fun memberPickerCapsSixAndInvalidInputsDisableCreate() = scenario { h ->
        h.profilesReply = 200 to """{"items":[${(1..7).joinToString(",") { """{"id":"p$it","title":"P$it"}""" }}]}"""
        h.vm.beginCreate(); runCurrent(); h.vm.editName("组")
        h.vm.state.value.profiles.forEach(h.vm::toggleProfile)
        assertEquals(6, h.vm.state.value.members.size)
        h.vm.editHandle("p1", "all"); assertFalse(h.vm.state.value.canCreate)
        h.vm.editHandle("p1", "writer"); h.vm.editHandle("p2", "writer")
        assertFalse(h.vm.state.value.canCreate)
        h.vm.editHandle("p2", null); assertTrue(h.vm.state.value.canCreate)
        h.vm.editName("bad\nname"); assertFalse(h.vm.state.value.canCreate)
        h.vm.create(); runCurrent(); assertFalse(h.calls.any { it.method == "POST" })
    }

    @Test fun pollsChronologicallyDeduplicatesAndUpdatesDriverEveryTick() = scenario { h ->
        h.events(2, 1, cursor = 2)
        h.open(); runCurrent()
        assertEquals(listOf(1L, 2L), h.vm.state.value.events.map { it.seq })
        assertTrue(h.vm.state.value.room!!.driverStatus!!.working)
        h.eventReplies.addLast(200 to """{"events":[{"seq":3,"event_id":"e2","kind":"message.member",
            "actor":{"kind":"member","id":"m1"},"text":"重复","created_at":2.25},
            {"seq":4,"event_id":"e4","kind":"turn.failed","actor":{"kind":"gateway","id":"g"},"text":"","created_at":3.25},
            {"seq":5,"event_id":"e5","kind":"future.kind","actor":{"kind":"system","id":"s"},"text":"","created_at":4.25}],
            "cursor":5,"latest_seq":5,"has_more":false}""")
        h.stateReplies.addLast(200 to """{"room_id":"r","driver_status":{"running":true,"working":false,"blocked":true}}""")
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(1L, 2L, 4L, 5L), h.vm.state.value.events.map { it.seq })
        assertEquals(listOf(1L, 2L, 4L), h.vm.state.value.visibleEvents.map { it.seq })
        assertEquals(5L, h.vm.state.value.cursor)
        assertTrue(h.vm.state.value.room!!.driverStatus!!.blocked)
        assertEquals(2, h.states.size)
        assertEquals(listOf("0", "2"), h.polls.map { it.url.queryParameter("since_seq") })
    }

    @Test fun catchesUpImmediatelyPastFivePagesWithoutSkippingToLatestSeq() = scenario { h ->
        for (seq in 1L..7L) h.events(seq, more = seq < 7)
        h.open(); runCurrent()
        assertEquals(7, h.polls.size)
        assertEquals((0L..6L).map { it.toString() }, h.polls.map { it.url.queryParameter("since_seq") })
        assertEquals(7L, h.vm.state.value.cursor)
        advanceTimeBy(1_999); runCurrent(); assertEquals(7, h.polls.size)
        advanceTimeBy(1); runCurrent(); assertEquals(8, h.polls.size)
        assertEquals(7L, h.vm.state.value.cursor)
    }

    @Test fun failureBackoffPreservesCursorAndRecoversAtTenSeconds() = scenario { h ->
        h.events(7); h.open(); runCurrent()
        h.eventReplies.addLast(503 to """{"detail":{"code":"hermes_unavailable"}}""")
        advanceTimeBy(2_000); runCurrent()
        assertTrue(h.vm.state.value.unstable)
        assertEquals(7L, h.vm.state.value.cursor)
        advanceTimeBy(9_999); runCurrent(); assertEquals(2, h.polls.size)
        h.events(8)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("0", "7", "7"), h.polls.map { it.url.queryParameter("since_seq") })
        assertFalse(h.vm.state.value.unstable)
        assertNull(h.vm.state.value.pollError)
        assertEquals(8L, h.vm.state.value.cursor)
    }

    @Test fun expiredHistoryPreservesCursorWithoutSpinningAndReopenStartsAtZero() = scenario { h ->
        h.events(40); h.open(); runCurrent()
        h.eventReplies.addLast(410 to """{"detail":{"code":"group_history_expired"}}""")
        advanceTimeBy(2_000); runCurrent()
        assertEquals(40L, h.vm.state.value.cursor)
        assertEquals(2, h.polls.size)
        assertTrue(h.vm.state.value.pollError!!.contains("历史已过期"))
        advanceTimeBy(9_999); runCurrent(); assertEquals(2, h.polls.size)
        h.vm.back(); h.events(1); h.open(); runCurrent()
        assertEquals("0", h.polls.last().url.queryParameter("since_seq"))
        assertEquals(1L, h.vm.state.value.cursor)
    }

    @Test fun backgroundTabHideBackAndCloseStopPollingAndResumeKeepsCursor() = scenario { h ->
        h.events(3); h.open(); runCurrent()
        h.vm.setForeground(false)
        val before = h.calls.size
        advanceTimeBy(30_000); runCurrent(); assertEquals(before, h.calls.size)
        h.events(4); h.vm.setForeground(true); runCurrent()
        assertEquals("3", h.polls.last().url.queryParameter("since_seq"))
        h.vm.back(); advanceTimeBy(30_000); runCurrent(); assertEquals(2, h.polls.size)
        h.open(); h.vm.close(); runCurrent(); assertEquals(2, h.polls.size)
        assertNull(h.vm.state.value.room)
    }

    @Test fun cancelledResponseCannotOverwriteNewRoomOrReopenAfterClose() = scenario { h ->
        h.roomRequestHook = { h.roomRequestHook = null; h.vm.back(); h.open("new") }
        h.open(); runCurrent()
        assertEquals(listOf("r", "new"), h.states.map { it.url.pathSegments.last() })
        assertEquals("new", h.vm.state.value.room?.roomId)
        h.roomRequestHook = { h.vm.close() }
        h.vm.refresh(); runCurrent()
        assertNull(h.vm.state.value.room)
        advanceTimeBy(30_000); runCurrent(); assertEquals(1, h.polls.size)
    }

    @Test fun sendPreservesExactTextDisablesDuplicatesAndRefreshesImmediately() = scenario { h ->
        h.open(); runCurrent(); h.events(1)
        h.vm.edit("  @writer 你好\n "); h.vm.send(); h.vm.send()
        assertTrue(h.vm.state.value.sending)
        h.vm.edit("不能覆盖发送中的草稿"); runCurrent()
        val write = h.calls.single { it.url.encodedPath.endsWith("/messages") }
        assertEquals("  @writer 你好\n ", write.bodyJson()["text"]!!.jsonPrimitive.content)
        assertEquals("", h.vm.state.value.draft)
        assertFalse(h.vm.state.value.sending)
        assertEquals(2, h.polls.size)
    }

    @Test fun rejectedAndFailedSendsKeepDraftAndNeverRetryAutomatically() = scenario { h ->
        h.open(); runCurrent(); h.vm.edit("保留草稿")
        for (reply in listOf(200 to """{"accepted":false,"event_id":"x","driver_started":false}""",
            503 to """{"detail":{"code":"hermes_unavailable"}}""")) {
            h.sendReply = reply
            h.vm.send(); runCurrent()
            assertEquals("保留草稿", h.vm.state.value.draft)
            assertNotNull(h.vm.state.value.error)
            assertFalse(h.vm.state.value.sending)
        }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, h.calls.count { it.url.encodedPath.endsWith("/messages") })
    }

    @Test fun codePointLimitShowsErrorWithoutTruncatingAndWhitespaceCannotSend() = scenario { h ->
        h.open(); runCurrent()
        h.vm.edit("a".repeat(7_999) + "😀")
        assertEquals(8_000, h.vm.state.value.draftLength); assertTrue(h.vm.state.value.canSend)
        h.vm.edit("😀".repeat(8_001))
        assertEquals(8_001, h.vm.state.value.draftLength); assertNotNull(h.vm.state.value.messageError)
        h.vm.send(); runCurrent(); assertFalse(h.calls.any { it.url.encodedPath.endsWith("/messages") })
        h.vm.edit(" \n"); assertFalse(h.vm.state.value.canSend)
        h.vm.edit("x\u0000"); assertFalse(h.vm.state.value.canSend)
        h.vm.edit("x\uD800"); assertFalse(h.vm.state.value.canSend)
    }

    @Test fun mentionSuggestionsInsertAtCursorWithoutParsingOrChangingOtherText() = scenario { h ->
        h.open(); runCurrent()
        h.vm.edit("你好 @")
        assertEquals(listOf("all", "writer", "review"), h.vm.state.value.mentionHandles)
        h.vm.insertMention("writer")
        assertEquals("你好 @writer ", h.vm.state.value.draft)
        h.vm.edit("问 @re 后面的原文", 5)
        assertEquals(listOf("review"), h.vm.state.value.mentionHandles)
        h.vm.insertMention("review")
        assertEquals("问 @review 后面的原文", h.vm.state.value.draft)
        h.vm.edit("@all"); h.vm.insertMention("all"); assertEquals("@all ", h.vm.state.value.draft)
        h.vm.edit("name@example.org"); assertTrue(h.vm.state.value.mentionHandles.isEmpty())
        h.vm.edit("@writer "); assertTrue(h.vm.state.value.mentionHandles.isEmpty())
    }

    @Test fun stopIsSingleWriteRefreshesStatusAndPreservesDraftOnFailure() = scenario { h ->
        h.open(); runCurrent(); h.vm.edit("草稿")
        h.vm.stop(); h.vm.stop(); runCurrent()
        assertEquals(1, h.calls.count { it.url.encodedPath.endsWith("/stop") })
        assertTrue(h.vm.state.value.notice!!.contains("2"))
        assertFalse(h.vm.state.value.stopping)
        assertEquals("草稿", h.vm.state.value.draft)
        assertEquals(2, h.states.size)
        h.stopReply = 409 to """{"detail":{"code":"group_authority_conflict"}}"""
        h.vm.stop(); runCurrent(); assertTrue(h.vm.state.value.error!!.contains("控制权"))
        assertEquals("草稿", h.vm.state.value.draft)
    }

    @Test fun stopCanQueueDuringSendWithoutRepeatingEitherWrite() = scenario { h ->
        h.open(); runCurrent(); h.vm.edit("原文")
        h.vm.send(); h.vm.stop(); h.vm.stop()
        assertTrue(h.vm.state.value.sending)
        assertTrue(h.vm.state.value.stopping)
        runCurrent()
        assertEquals(1, h.calls.count { it.url.encodedPath.endsWith("/messages") })
        assertEquals(1, h.calls.count { it.url.encodedPath.endsWith("/stop") })
        assertFalse(h.vm.state.value.sending)
        assertFalse(h.vm.state.value.stopping)
    }

    @Test fun closeCancelsScheduledSendBeforeAnyWrite() = scenario { h ->
        h.open(); runCurrent(); h.vm.edit("原文"); h.vm.send(); h.vm.close(); runCurrent()
        assertFalse(h.calls.any { it.method == "POST" })
        assertFalse(h.vm.state.value.sending)
        assertNull(h.vm.state.value.room)
    }

    @Test fun closeCancelsSendQueuedBehindAnInFlightRoomRead() = scenario { h ->
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        h.roomRequestHook = {
            h.vm.edit("排队的原文")
            h.vm.send()
            assertTrue(h.vm.state.value.sending)
            h.vm.close()
        }
        h.open(); runCurrent()
        assertEquals(1, h.states.size)
        assertFalse(h.calls.any { it.method == "POST" })
        assertNull(h.vm.state.value.room)
        assertEquals("", h.vm.state.value.draft)
        assertFalse(h.vm.state.value.busy)
        advanceTimeBy(30_000); runCurrent(); assertEquals(1, h.calls.size)
    }

    @Test fun approvalsComeFromDriverStatusAndShowTheMember() = scenario { h ->
        h.pending("a1", "a2")
        h.open(); runCurrent()
        assertEquals(listOf("a1", "a2"), h.vm.state.value.approvals.map { it.requestId })
        assertEquals("rm -rf ./x", h.vm.state.value.approvals.first().command)
        assertEquals("写手", phoneMemberLabel(h.vm.state.value.approvals.first().memberId, h.vm.state.value.room!!))
    }

    @Test fun approvingPostsOnlyTheChoiceDropsTheCardAndStaleReadsCannotBringItBack() = scenario { h ->
        h.pending("a1")
        h.open(); runCurrent()
        // The next poll still lists the request (Hermes has not caught up yet).
        h.pending("a1")
        h.vm.resolveApproval("a1", "once"); runCurrent()
        val post = h.calls.single { it.url.encodedPath.endsWith("/resolve") }
        assertEquals("/v1/groups/r/approvals/a1/resolve", post.url.encodedPath)
        assertEquals(setOf("choice"), post.bodyJson().keys)
        assertEquals("once", post.bodyJson()["choice"]!!.jsonPrimitive.content)
        assertTrue(h.vm.state.value.approvals.isEmpty())
        assertNull(h.vm.state.value.resolvingRequestId)
        // The immediate refresh after approving returned the stale entry: it stays hidden.
        advanceTimeBy(2_000); runCurrent()
        assertTrue(h.vm.state.value.approvals.isEmpty())
        // Once Hermes stops listing it, a later identical request id may show again.
        h.pending(); advanceTimeBy(2_000); runCurrent()
        h.pending("a1"); advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("a1"), h.vm.state.value.approvals.map { it.requestId })
    }

    @Test fun denyIsSentAsDeny() = scenario { h ->
        h.pending("a1")
        h.open(); runCurrent()
        h.vm.resolveApproval("a1", "deny"); runCurrent()
        assertEquals("deny", h.calls.single { it.url.encodedPath.endsWith("/resolve") }
            .bodyJson()["choice"]!!.jsonPrimitive.content)
    }

    @Test fun staleOrFailedApprovalKeepsTheCardShowsTheMessageAndNeverRetries() = scenario { h ->
        h.pending("a1")
        h.open(); runCurrent()
        h.approveReply = 409 to """{"detail":{"code":"group_approval_stale","message":"private"}}"""
        h.vm.resolveApproval("a1", "once"); runCurrent()
        assertEquals(1, h.calls.count { it.url.encodedPath.endsWith("/resolve") })
        assertEquals("这个审批已失效，可能已超时。", h.vm.state.value.error)
        assertEquals(listOf("a1"), h.vm.state.value.approvals.map { it.requestId })
        assertNull(h.vm.state.value.resolvingRequestId)
        // A later successful poll that no longer lists it clears the card.
        h.pending(); advanceTimeBy(2_000); runCurrent()
        assertTrue(h.vm.state.value.approvals.isEmpty())
    }

    @Test fun doubleTapSendsOneRequestAndAChoiceTheMemberDidNotOfferIsNotSent() = scenario { h ->
        h.pending("a1")
        h.open(); runCurrent()
        h.vm.resolveApproval("a1", "always"); runCurrent()
        assertEquals(0, h.calls.count { it.url.encodedPath.endsWith("/resolve") })
        assertEquals(userMessage(BridgeRequestException(400, "choice_not_offered")), h.vm.state.value.error)
        h.vm.resolveApproval("a1", "once"); h.vm.resolveApproval("a1", "deny"); runCurrent()
        assertEquals(1, h.calls.count { it.url.encodedPath.endsWith("/resolve") })
    }

    @Test fun leavingTheRoomClearsApprovalStateAndReopeningShowsPendingAgain() = scenario { h ->
        h.pending("a1")
        h.open(); runCurrent()
        h.vm.resolveApproval("a1", "once"); runCurrent()
        h.vm.back(); runCurrent()
        assertTrue(h.vm.state.value.approvals.isEmpty())
        h.pending("a1"); h.vm.openRoom(PhoneRoom(roomId = "r", name = "讨论")); runCurrent()
        assertEquals(listOf("a1"), h.vm.state.value.approvals.map { it.requestId })
    }

    @Test fun disbandRequiresConfirmationRemovesRoomAndStopsReads() = scenario { h ->
        h.vm.refresh(); runCurrent(); val room = h.vm.state.value.rooms.single()
        h.vm.disband(); runCurrent(); assertFalse(h.calls.any { it.method == "DELETE" })
        h.vm.requestDisband(room); h.vm.cancelDisband(); h.vm.disband(); runCurrent()
        assertFalse(h.calls.any { it.method == "DELETE" })
        h.open(); runCurrent(); h.vm.requestDisband(room); h.vm.disband(); h.vm.disband(); runCurrent()
        assertEquals(1, h.calls.count { it.method == "DELETE" })
        assertNull(h.vm.state.value.room)
        assertTrue(h.vm.state.value.rooms.isEmpty())
        val before = h.calls.size
        advanceTimeBy(30_000); runCurrent(); assertEquals(before, h.calls.size)
    }

    @Test fun failedDisbandKeepsRoomAndDoesNotRetry() = scenario { h ->
        h.open(); runCurrent()
        h.disbandReply = 400 to """{"detail":{"code":"hermes_rejected"}}"""
        h.vm.requestDisband(h.vm.state.value.room!!); h.vm.disband(); runCurrent()
        assertEquals("r", h.vm.state.value.room?.roomId)
        assertNotNull(h.vm.state.value.error)
        advanceTimeBy(30_000); runCurrent(); assertEquals(1, h.calls.count { it.method == "DELETE" })
    }

    @Test fun phoneAndDesktopViewModelsNeverShareRoomsOrRequests() = scenario { h ->
        val desktop = GroupsViewModel(h.api, h.dispatcher)
        h.vm.beginCreate(); runCurrent(); h.vm.editName("手机草稿")
        desktop.refresh(); runCurrent()
        assertNull(desktop.state.value.room)
        assertTrue(desktop.state.value.rooms.isEmpty())
        assertEquals("手机草稿", h.vm.state.value.name)
        assertTrue(h.vm.state.value.creating)
        assertTrue(h.calls.any { it.url.encodedPath == "/v1/bot-groups" })
        h.calls.removeAll { it.url.encodedPath.startsWith("/v1/bot-groups") }
        desktop.close()
    }

    @Test fun handlePreviewValidationLabelsAndErrorsMatchSpec() {
        fun member(profile: String, handle: String? = null) = PhoneRoomMemberDraft(CatalogItem(profile, profile), handle)
        assertEquals(listOf("all-2", "everyone-2", "m--", "a-b", "a-b-2", "a-b-3"), expectedPhoneHandles(
            listOf(member("ALL"), member("EVERYONE"), member("中文"), member("a/b"), member("a b"), member("a?b"))))
        assertEquals(listOf("writer-2", "writer"), expectedPhoneHandles(listOf(member("writer"), member("other", "writer"))))
        assertEquals(listOf("x".repeat(32), "x".repeat(30) + "-2"), expectedPhoneHandles(
            listOf(member("x".repeat(33)), member("x".repeat(32) + "y"))))
        assertNotNull(phoneCreateError("组", listOf(member("a"))))
        assertNotNull(phoneCreateError("组", listOf(member("a", "UPPER"), member("b"))))
        assertNotNull(phoneCreateError("组", listOf(member("a", "same"), member("b", "same"))))
        assertNotNull(phoneCreateError("组\u200B", listOf(member("a"), member("b"))))
        assertNull(phoneCreateError("😀".repeat(200), listOf(member("a"), member("b"))))
        val room = PhoneRoom("r", members = listOf(PhoneMember("m", handle = "writer", displayName = "写手")))
        val event = PhoneEvent(1, "e", "message.member", PhoneActor("member", "m"), "回复", 1.25)
        assertEquals("写手", phoneActorLabel(event, room))
        assertEquals("writer", phoneActorLabel(event, room.copy(members = listOf(PhoneMember("m", handle = "writer")))))
        assertEquals("未知成员", phoneActorLabel(event, room.copy(members = emptyList())))
        assertEquals("我", phoneActorLabel(event.copy(kind = "message.user"), room))
        assertNull(phoneSystemLabel("future.kind"))
        assertTrue(phoneSystemLabel("turn.failed")!!.contains("失败"))
        listOf("invalid_group_request", "group_history_expired", "group_authority_conflict", "hermes_rejected", "hermes_unavailable",
            "group_approval_stale", "choice_not_offered")
            .forEach { code ->
                val message = userMessage(BridgeRequestException(400, code))
                assertTrue(message.any { it.code > 127 })
                assertFalse(message.contains(code))
            }
        assertEquals("这个审批已失效，可能已超时。", userMessage(BridgeRequestException(409, "group_approval_stale")))
        assertEquals("当前审批不支持该选项，请刷新后重试。", userMessage(BridgeRequestException(400, "choice_not_offered")))
    }

    @Test fun resolveApprovalSendsOnceOrDenyWithNoIdentityAndRemovesCardImmediately() = scenario { h ->
        h.pending("req1")
        h.open(); runCurrent()
        assertEquals(1, h.vm.state.value.approvals.size)
        val approval = h.vm.state.value.approvals.single()
        assertEquals("req1", approval.requestId)
        assertEquals(listOf("once", "deny"), approval.choices)

        h.vm.resolveApproval("req1", "once")
        assertTrue(h.vm.state.value.busy)
        assertEquals("req1", h.vm.state.value.resolvingRequestId)

        h.vm.resolveApproval("req1", "deny")

        runCurrent()
        val resolveCall = h.calls.single { it.url.encodedPath.endsWith("/resolve") }
        assertEquals("POST", resolveCall.method)
        assertEquals("/v1/groups/r/approvals/req1/resolve", resolveCall.url.encodedPath)
        val body = resolveCall.bodyJson()
        assertEquals(setOf("choice"), body.keys)
        assertEquals("once", body["choice"]!!.jsonPrimitive.content)

        assertTrue(h.vm.state.value.approvals.isEmpty())
        assertFalse(h.vm.state.value.busy)
        assertNull(h.vm.state.value.resolvingRequestId)
        assertNull(h.vm.state.value.error)
    }

    @Test fun resolveApprovalSuccessSuppressesCardEvenIfStalePollReturnsOldApproval() = scenario { h ->
        h.pending("req1")
        h.open(); runCurrent()
        assertEquals(1, h.vm.state.value.approvals.size)

        // The immediate refresh after approving still lists req1 (Hermes has not caught up).
        h.pending("req1")
        h.vm.resolveApproval("req1", "once")
        runCurrent()
        assertTrue(h.vm.state.value.approvals.isEmpty())

        h.pending("req1")
        advanceTimeBy(2_000); runCurrent()
        assertTrue(h.vm.state.value.approvals.isEmpty())

        h.pending()
        advanceTimeBy(2_000); runCurrent()
        assertTrue(h.vm.state.value.approvals.isEmpty())

        h.pending("req2")
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, h.vm.state.value.approvals.size)
        assertEquals("req2", h.vm.state.value.approvals.single().requestId)
    }

    @Test fun resolveApprovalFailureRetainsCardAndSetsMappedError() = scenario { h ->
        h.pending("req1")
        h.open(); runCurrent()
        assertEquals(1, h.vm.state.value.approvals.size)

        h.approveReply = 409 to """{"detail":{"code":"group_approval_stale"}}"""
        h.vm.resolveApproval("req1", "once")
        runCurrent()

        assertEquals(1, h.vm.state.value.approvals.size)
        assertFalse(h.vm.state.value.busy)
        assertNull(h.vm.state.value.resolvingRequestId)
        assertEquals("这个审批已失效，可能已超时。", h.vm.state.value.error)
    }

    @Test fun resolveApprovalRejectsUnofferedChoiceWithoutHttpAndHandlesMissingApproval() = scenario { h ->
        h.stateReplies.addLast(200 to """{"room_id":"r","name":"讨论","member_count":2,"members":[
            {"member_id":"m1","profile":"writer","handle":"writer","display_name":"写手"}],
            "driver_status":{"running":true,"working":false,"blocked":true,"approvals":[
                {"member_id":"m1","task_id":"t","execution_generation":1,"request_id":"req1",
                 "command":"ls","description":"列出文件","choices":["once"]}
            ]}}""")
        h.open(); runCurrent()

        val callsBefore = h.calls.size
        h.vm.resolveApproval("req1", "deny")
        runCurrent()
        assertEquals(callsBefore, h.calls.size)
        assertEquals(1, h.vm.state.value.approvals.size)
        assertEquals("当前审批不支持该选项，请刷新后重试。", h.vm.state.value.error)

        h.approveReply = 409 to """{"detail":{"code":"group_approval_stale"}}"""
        h.vm.resolveApproval("missing_req", "once")
        runCurrent()
        assertEquals("这个审批已失效，可能已超时。", h.vm.state.value.error)
    }

    @Test fun closeAndBackCancelInFlightApprovalResolve() = scenario { h ->
        h.pending("req1")
        h.open(); runCurrent()
        assertEquals(1, h.vm.state.value.approvals.size)

        h.vm.resolveApproval("req1", "once")
        assertTrue(h.vm.state.value.busy)
        h.vm.back(); runCurrent()
        assertNull(h.vm.state.value.room)
        assertNull(h.vm.state.value.resolvingRequestId)
        assertFalse(h.vm.state.value.busy)

        h.open(); runCurrent()
        h.vm.resolveApproval("req1", "once")
        h.vm.close(); runCurrent()
        assertNull(h.vm.state.value.room)
        assertNull(h.vm.state.value.resolvingRequestId)
        assertFalse(h.vm.state.value.busy)
    }

    @Test fun approvalPauseHintOnlyAppearsWhenBlockedWithoutApprovals() = scenario { h ->
        h.pending("req1")
        h.open(); runCurrent()
        val roomWithApprovals = h.vm.state.value.room!!
        assertTrue(roomWithApprovals.driverStatus!!.blocked)
        assertFalse(roomWithApprovals.driverStatus!!.working)
        assertEquals(1, h.vm.state.value.approvals.size)

        h.pending()
        advanceTimeBy(2_000); runCurrent()
        val roomWithoutApprovals = h.vm.state.value.room!!
        assertTrue(roomWithoutApprovals.driverStatus!!.blocked)
        assertFalse(roomWithoutApprovals.driverStatus!!.working)
        assertTrue(h.vm.state.value.approvals.isEmpty())

        h.stateReplies.addLast(200 to Harness.roomJson("r"))
        advanceTimeBy(2_000); runCurrent()
        val roomWorking = h.vm.state.value.room!!
        assertTrue(roomWorking.driverStatus!!.working)
        assertFalse(roomWorking.driverStatus!!.blocked)
    }
}
