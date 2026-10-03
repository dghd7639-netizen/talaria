package app.hermes.mobile.data

import app.hermes.mobile.pairing.DeviceConnection
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
import java.io.IOException

class PhoneRoomsApiTest {
    private class Harness(json: Json = Json) {
        val calls = mutableListOf<Request>()
        val replies = ArrayDeque<Pair<Int, String>>()
        var failure: IOException? = null
        var expired = 0
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"),
            OkHttpClient.Builder().addInterceptor { chain ->
                calls += chain.request()
                failure?.let { throw it }
                val (status, body) = replies.removeFirst()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Response")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build(), json, onAuthenticationExpired = { expired++ })
        fun reply(body: String, status: Int = 200) { replies.addLast(status to body) }
    }

    private fun Request.bodyJson() = Json.parseToJsonElement(Buffer().also { body!!.writeTo(it) }.readUtf8()).jsonObject

    private fun methods(api: BridgeApi): List<() -> Any> = listOf(
        { api.phoneCapabilities() }, { api.phoneRooms() }, { api.phoneRoom("r") }, { api.phoneEvents("r") },
        { api.createPhoneRoom("Discussion", listOf(PhoneMemberInput("writer"), PhoneMemberInput("review"))) },
        { api.sendPhoneMessage("r", "Hello") }, { api.stopPhoneRoom("r") }, { api.disbandPhoneRoom("r") },
    )

    @Test fun allEightMethodsUseExactPathsAuthBodiesAndLongDoubleFields() {
        val h = Harness()
        h.reply("""{"available":true,"driver":true,"features":["monotonic_log"],"extra":1}""")
        h.reply("""{"rooms":[{"room_id":"r","name":"讨论","member_count":2,"updated_at":1.25}],"next_offset":5000000000}""")
        h.reply("""{"room_id":"r","name":"讨论","members":[{"member_id":"m1","profile":"writer","handle":"writer","display_name":"写手"}],
            "latest_seq":5000000001,"updated_at":1.75,"driver_status":{"running":true,"working":false,"blocked":true}}""")
        h.reply("""{"events":[{"seq":5000000002,"event_id":"e1","kind":"message.member","actor":{"kind":"member","id":"m1"},
            "text":"回复","created_at":2.25}],"cursor":5000000002,"latest_seq":5000000003,"has_more":true}""")
        h.reply("""{"room_id":"created","name":"新群聊","member_count":2,"updated_at":3.25}""", 201)
        h.reply("""{"accepted":true,"event_id":"user:sent","driver_started":false}""")
        h.reply("""{"cancelled":5000000000}""")
        h.reply("""{"disbanded":true}""")
        assertEquals(PhoneCapabilities(true, true, listOf("monotonic_log")), h.api.phoneCapabilities())
        val page = h.api.phoneRooms(limit = 20, offset = 5000000000L)
        assertEquals(5000000000L, page.nextOffset)
        assertEquals(1.25, page.rooms.single().updatedAt, 0.0)
        val id = "room/段?#%"
        val room = h.api.phoneRoom(id)
        assertEquals(5000000001L, room.latestSeq)
        assertEquals(1.75, room.updatedAt, 0.0)
        assertEquals(PhoneMember("m1", "writer", "writer", "写手"), room.members.single())
        assertEquals(PhoneDriverStatus(true, false, true), room.driverStatus)
        val events = h.api.phoneEvents(id, sinceSeq = 5000000000L, limit = 15)
        assertEquals(PhoneEvent(5000000002, "e1", "message.member", PhoneActor("member", "m1"), "回复", 2.25),
            events.events.single())
        assertEquals(5000000002L, events.cursor)
        assertEquals(5000000003L, events.latestSeq)
        assertTrue(events.hasMore)
        assertEquals("created", h.api.createPhoneRoom("  新群聊  ", listOf(
            PhoneMemberInput("writer", displayName = "写手"), PhoneMemberInput("review", "editor"))).roomId)
        val text = "  @writer 你好\n保留空格  "
        assertEquals(PhoneSendResult(true, "user:sent", false), h.api.sendPhoneMessage(id, text))
        assertEquals(5000000000L, h.api.stopPhoneRoom(id).cancelled)
        assertTrue(h.api.disbandPhoneRoom(id).disbanded)

        assertEquals(listOf("GET", "GET", "GET", "GET", "POST", "POST", "POST", "DELETE"), h.calls.map { it.method })
        val encoded = "/v1/groups/room%2F%E6%AE%B5%3F%23%25"
        assertEquals(listOf("/v1/groups/capabilities", "/v1/groups", encoded, "$encoded/events", "/v1/groups",
            "$encoded/messages", "$encoded/stop", encoded), h.calls.map { it.url.encodedPath })
        assertEquals("include_disbanded=false&limit=20&offset=5000000000", h.calls[1].url.encodedQuery)
        assertEquals("since_seq=5000000000&limit=15", h.calls[3].url.encodedQuery)
        assertEquals(id, h.calls[2].url.pathSegments.last())
        h.calls.forEach { assertEquals("Bearer secret", it.header("Authorization")) }
        h.calls.take(4).forEach { assertNull(it.body) }
        val body = h.calls[4].bodyJson()
        assertEquals(setOf("name", "members"), body.keys)
        assertEquals("新群聊", body["name"]!!.jsonPrimitive.content)
        val members = body["members"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("profile", "display_name"), members[0].keys)
        assertEquals(setOf("profile", "handle"), members[1].keys)
        assertEquals("写手", members[0]["display_name"]!!.jsonPrimitive.content)
        assertEquals("editor", members[1]["handle"]!!.jsonPrimitive.content)
        assertEquals(setOf("text"), h.calls[5].bodyJson().keys)
        assertEquals(text, h.calls[5].bodyJson()["text"]!!.jsonPrimitive.content)
        assertEquals(0L, h.calls[6].body!!.contentLength())
        // OkHttp always attaches an empty body to DELETE.
        assertEquals(0L, h.calls[7].body?.contentLength() ?: 0L)
    }

    @Test fun createOmitsNullOptionalsEvenWhenCallerEncodesDefaultsAndPreservesExplicitFields() {
        val h = Harness(Json { encodeDefaults = true })
        h.reply("""{"room_id":"r"}""", 201)
        h.api.createPhoneRoom("Room", listOf(PhoneMemberInput("writer"),
            PhoneMemberInput("review", handle = "", displayName = "  ")))
        val members = h.calls.single().bodyJson()["members"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("profile"), members[0].keys)
        assertEquals("", members[1]["handle"]!!.jsonPrimitive.content)
        assertEquals("  ", members[1]["display_name"]!!.jsonPrimitive.content)
    }

    @Test fun optionalMissingNullAndUnknownResponseFieldsUseDefaults() {
        val h = Harness()
        h.reply("""{"available":null,"driver":null,"features":null,"unknown":1}""")
        h.reply("""{"rooms":[{"room_id":"r","members":[{}, {"member_id":null,"profile":null,"handle":null,"display_name":null}],
            "latest_seq":null,"driver_status":null,"unknown":true}],"next_offset":null}""")
        h.reply("""{"room_id":"r","unknown":{"private":1}}""")
        h.reply("""{"events":[],"cursor":5000000000,"latest_seq":5000000000,"has_more":false,"unknown":true}""")
        assertEquals(PhoneCapabilities(), h.api.phoneCapabilities())
        val page = h.api.phoneRooms()
        assertNull(page.nextOffset)
        val room = page.rooms.single()
        assertEquals(listOf(PhoneMember(), PhoneMember()), room.members)
        assertNull(room.latestSeq)
        assertNull(room.driverStatus)
        assertEquals(PhoneRoom("r"), h.api.phoneRoom("r"))
        assertEquals(PhoneEventPage(emptyList(), 5000000000, 5000000000, false), h.api.phoneEvents("r", 5000000000))
    }

    @Test fun stableErrorsAnd401CallbacksWorkForAllEightMethodsWithoutPrivateMessages() {
        val h = Harness()
        for ((status, code) in listOf(400 to "invalid_group_request", 400 to "hermes_rejected",
            409 to "group_authority_conflict", 410 to "group_history_expired", 503 to "hermes_unavailable", 401 to "unauthorized")) {
            methods(h.api).forEach { call ->
                h.reply("""{"detail":{"code":"$code","message":"private upstream error"}}""", status)
                val error = assertThrows(BridgeRequestException::class.java) { call() }
                assertEquals(status, error.status)
                assertEquals(code, error.code)
                assertFalse(error.message.orEmpty().contains("private"))
            }
        }
        assertEquals(8, h.expired)
    }

    @Test fun writeTransportFailuresAreNotRetriedByApi() {
        val h = Harness()
        h.failure = IOException("private transport error")
        methods(h.api).drop(4).forEach { call -> assertThrows(IOException::class.java) { call() } }
        assertEquals(listOf("POST", "POST", "POST", "DELETE"), h.calls.map { it.method })
    }

    @Test fun roomAndPaginationGuardsFailBeforeHttp() {
        val h = Harness()
        for (id in listOf("", " ", ".", "..")) {
            listOf<() -> Any>({ h.api.phoneRoom(id) }, { h.api.phoneEvents(id) },
                { h.api.sendPhoneMessage(id, "text") }, { h.api.stopPhoneRoom(id) }, { h.api.disbandPhoneRoom(id) })
                .forEach { call -> assertThrows(IllegalArgumentException::class.java) { call() } }
        }
        listOf<() -> Any>({ h.api.phoneRooms(limit = 0) }, { h.api.phoneRooms(limit = 201) },
            { h.api.phoneRooms(offset = -1) }, { h.api.phoneEvents("r", sinceSeq = -1) },
            { h.api.phoneEvents("r", limit = 0) }, { h.api.phoneEvents("r", limit = 201) })
            .forEach { call -> assertThrows(IllegalArgumentException::class.java) { call() } }
        assertTrue(h.calls.isEmpty())
    }

    @Test fun createValidationRejectsInvalidNamesCountsAndBlankProfilesBeforeHttp() {
        val h = Harness()
        val members = listOf(PhoneMemberInput("writer"), PhoneMemberInput("review"))
        listOf("", " ", "x".repeat(201), "bad\nname", "bad\rname", "bad\tname", "bad\u0000name", "bad\u200B", "bad\uD800")
            .forEach { name -> assertThrows(IllegalArgumentException::class.java) { h.api.createPhoneRoom(name, members) } }
        listOf(emptyList(), members.take(1), (1..7).map { PhoneMemberInput("p$it") },
            listOf(PhoneMemberInput(""), PhoneMemberInput("review"))).forEach {
            assertThrows(IllegalArgumentException::class.java) { h.api.createPhoneRoom("Room", it) }
        }
        assertTrue(h.calls.isEmpty())
    }

    @Test fun sendRejectsInvalidTextAndCountsUnicodeCodePointsWithoutTrimming() {
        val h = Harness()
        listOf("", " \n", "x".repeat(8001), "hello\u0000world", "bad\uD800text", "bad\uDC00text")
            .forEach { text -> assertThrows(IllegalArgumentException::class.java) { h.api.sendPhoneMessage("r", text) } }
        assertTrue(h.calls.isEmpty())
        val text = "😀".repeat(8000)
        h.reply("""{"accepted":true,"event_id":"e","driver_started":true}""")
        assertTrue(h.api.sendPhoneMessage("r", text).accepted)
        assertEquals(text, h.calls.single().bodyJson()["text"]!!.jsonPrimitive.content)
    }

    @Test fun resolvePhoneApprovalUsesExactPathAuthBodyAndOnlyChoice() {
        val h = Harness()
        h.reply("""{"status":"resolved"}""")
        h.reply("""{"status":"resolved"}""")
        val roomId = "room/段?#%"
        val requestId = "req/1?#%"
        val resOnce = h.api.resolvePhoneApproval(roomId, requestId, "once")
        assertEquals(PhoneApprovalResult("resolved"), resOnce)
        val resDeny = h.api.resolvePhoneApproval(roomId, requestId, "deny")
        assertEquals(PhoneApprovalResult("resolved"), resDeny)

        assertEquals(listOf("POST", "POST"), h.calls.map { it.method })
        val expectedPath = "/v1/groups/room%2F%E6%AE%B5%3F%23%25/approvals/req%2F1%3F%23%25/resolve"
        assertEquals(listOf(expectedPath, expectedPath), h.calls.map { it.url.encodedPath })
        h.calls.forEach {
            assertEquals("Bearer secret", it.header("Authorization"))
            val body = it.bodyJson()
            assertEquals(setOf("choice"), body.keys)
            assertFalse(body.containsKey("member_id"))
            assertFalse(body.containsKey("task_id"))
            assertFalse(body.containsKey("execution_generation"))
            assertFalse(body.containsKey("request_id"))
        }
        assertEquals("once", h.calls[0].bodyJson()["choice"]!!.jsonPrimitive.content)
        assertEquals("deny", h.calls[1].bodyJson()["choice"]!!.jsonPrimitive.content)
    }

    @Test fun resolvePhoneApprovalGuardsFailBeforeHttp() {
        val h = Harness()
        for (id in listOf("", " ", ".", "..")) {
            assertThrows(IllegalArgumentException::class.java) { h.api.resolvePhoneApproval(id, "req1", "once") }
            assertThrows(IllegalArgumentException::class.java) { h.api.resolvePhoneApproval("r", id, "once") }
        }
        for (choice in listOf("", " ", "approve", "always", "allow", "reject", "ONCE", "DENY")) {
            assertThrows(IllegalArgumentException::class.java) { h.api.resolvePhoneApproval("r", "req1", choice) }
        }
        assertTrue(h.calls.isEmpty())
    }

    @Test fun resolvePhoneApprovalErrorsAndTransportNoRetry() {
        val h = Harness()
        for ((status, code) in listOf(400 to "choice_not_offered", 409 to "group_approval_stale",
            503 to "hermes_unavailable", 401 to "unauthorized")) {
            h.reply("""{"detail":{"code":"$code","message":"private error"}}""", status)
            val error = assertThrows(BridgeRequestException::class.java) {
                h.api.resolvePhoneApproval("r", "req1", "once")
            }
            assertEquals(status, error.status)
            assertEquals(code, error.code)
            assertFalse(error.message.orEmpty().contains("private"))
        }
        assertEquals(1, h.expired)

        h.failure = IOException("private transport error")
        assertThrows(IOException::class.java) { h.api.resolvePhoneApproval("r", "req1", "once") }
        assertEquals(5, h.calls.size)
    }

    @Test fun driverStatusApprovalsParsingWithLongGenerationAndMissingOptionals() {
        val h = Harness()
        h.reply("""{"room_id":"r","driver_status":{"running":true,"working":false,"blocked":true,"approvals":[
            {"member_id":"m1","task_id":"t1","execution_generation":5000000000,"request_id":"req1",
             "command":"rm -rf /tmp","description":"清理临时文件","tool_name":"terminal","choices":["once","deny"],"unknown":123},
            {"request_id":"req2","choices":["once"]}
        ]}}""")
        val room = h.api.phoneRoom("r")
        val approvals = room.driverStatus!!.approvals
        assertEquals(2, approvals.size)
        assertEquals(
            PhoneApproval(
                memberId = "m1",
                taskId = "t1",
                executionGeneration = 5000000000L,
                requestId = "req1",
                command = "rm -rf /tmp",
                description = "清理临时文件",
                toolName = "terminal",
                choices = listOf("once", "deny"),
            ),
            approvals[0]
        )
        assertEquals(
            PhoneApproval(
                memberId = "",
                taskId = "",
                executionGeneration = 0L,
                requestId = "req2",
                command = "",
                description = "",
                toolName = null,
                choices = listOf("once"),
            ),
            approvals[1]
        )
    }
}
