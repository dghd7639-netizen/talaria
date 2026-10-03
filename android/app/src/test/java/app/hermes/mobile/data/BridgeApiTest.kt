package app.hermes.mobile.data

import app.hermes.mobile.pairing.DeviceConnection
import app.hermes.mobile.attachments.AttachmentSource
import app.hermes.mobile.attachments.AttachmentUploader
import app.hermes.mobile.attachments.AttachmentValidationException
import app.hermes.mobile.attachments.MAX_ATTACHMENT_SIZE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeApiTest {
    @Test
    fun botGroupRequestsEncodeSegmentsAndParseDesktopPayloads() {
        val calls = mutableListOf<okhttp3.Request>()
        val member = """{"name":"纸飞机","handle":"paper","local":true,"extra":1}"""
        val payloads = ArrayDeque(listOf(
            """{"rooms":[{"room_id":"room.v2","name":"周末模型讨论","members":[$member],
                "message_count":5000000000,"omitted":5000000001,"last_at":1790000000.125,
                "revision":5000000002,"extra":{}}],"updated_at":1790000000.375,
                "format_warning":true,"extra":1}""",
            """{"room_id":"room.v2","name":"周末模型讨论","members":[$member],"omitted":5000000001,
                "revision":5000000002,"total":5000000000,"format_warning":true,"extra":{},
                "messages":[{"id":"m1","from_kind":"user","from_name":"桌面用户","text":"做一个纸模型",
                    "at":1790000000.25,"thread":"t1","truncated":true,"has_attachments":true,"extra":1},
                    {"id":"","from_kind":"member","from_name":"纸飞机","text":"先画草图",
                    "at":1790000000.75,"thread":"legacy"}]}""",
            "{}", "{}",
        ))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payloads.removeFirst().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)
        val page = api.botGroups()
        val room = page.rooms.single()
        assertEquals("周末模型讨论", room.name)
        assertEquals("room.v2", room.room_id)
        assertEquals(BotGroupMemberDto("纸飞机", "paper", true), room.members.single())
        assertEquals(5000000000L, room.message_count)
        assertEquals(5000000001L, room.omitted)
        assertEquals(5000000002L, room.revision)
        assertEquals(1790000000.125, room.last_at, 0.0)
        assertEquals(1790000000.375, page.updated_at, 0.0)
        assertTrue(page.format_warning)
        val id = "legacy/ 房间.v2?#%"
        val detail = api.botGroupRoom(id, limit = 500)
        assertEquals("room.v2", detail.room_id)
        assertEquals(room.name, detail.name)
        assertEquals(room.members, detail.members)
        assertEquals(room.omitted, detail.omitted)
        assertEquals(room.revision, detail.revision)
        assertEquals(room.message_count, detail.total)
        assertTrue(detail.format_warning)
        assertEquals(BotGroupMessageDto("m1", "user", "桌面用户", "做一个纸模型",
            1790000000.25, "t1", true, true), detail.messages[0])
        assertEquals("", detail.messages[1].id)
        assertEquals("member", detail.messages[1].from_kind)
        assertEquals("纸飞机", detail.messages[1].from_name)
        assertEquals(1790000000.75, detail.messages[1].at, 0.0)
        assertEquals(BotGroupDetailDto(), api.botGroupRoom("r"))
        assertEquals(BotGroupDetailDto(), api.botGroupRoom("r", limit = 1))
        assertEquals("/v1/bot-groups", calls[0].url.encodedPath)
        assertEquals(null, calls[0].url.encodedQuery)
        assertEquals("/v1/bot-groups/legacy%2F%20%E6%88%BF%E9%97%B4.v2%3F%23%25", calls[1].url.encodedPath)
        assertEquals(id, calls[1].url.pathSegments[2])
        assertEquals(listOf("limit=500", "limit=200", "limit=1"), calls.drop(1).map { it.url.encodedQuery })
        calls.forEach {
            assertEquals("GET", it.method)
            assertEquals(null, it.body)
            assertEquals("Bearer secret", it.header("Authorization"))
        }
    }

    @Test
    fun botGroupNullAndMissingFieldsUseSafeDefaults() {
        val payloads = ArrayDeque(listOf(
            "{}",
            """{"rooms":null,"updated_at":null,"format_warning":null}""",
            """{"rooms":[{}, {"room_id":null,"name":null,"members":null,"message_count":null,
                "omitted":null,"last_at":null,"revision":null}]}""",
            "{}",
            """{"room_id":null,"name":null,"members":null,"omitted":null,"revision":null,
                "messages":null,"total":null,"format_warning":null}""",
            """{"members":[{}, {"name":null,"handle":null,"local":null}],"messages":[{},
                {"id":null,"from_kind":null,"from_name":null,"text":null,"at":null,"thread":null,
                "truncated":null,"has_attachments":null}]}""",
        ))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payloads.removeFirst().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)
        repeat(2) { assertEquals(BotGroupsDto(), api.botGroups()) }
        assertEquals(listOf(BotGroupRoomDto(), BotGroupRoomDto()), api.botGroups().rooms)
        repeat(2) { assertEquals(BotGroupDetailDto(), api.botGroupRoom("r")) }
        val detail = api.botGroupRoom("r")
        assertEquals(listOf(BotGroupMemberDto(), BotGroupMemberDto()), detail.members)
        assertEquals(listOf(BotGroupMessageDto(), BotGroupMessageDto()), detail.messages)
    }

    @Test
    fun botGroupErrorCodesAndLimitValidationAreStable() {
        var status = 400
        var code = "invalid_bot_group_request"
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Error")
                .body("""{"detail":{"code":"$code","message":"invented private error"}}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)
        listOf(400 to "invalid_bot_group_request", 404 to "bot_group_not_found", 503 to "hermes_unavailable")
            .forEach { (http, errorCode) ->
                status = http; code = errorCode
                listOf<() -> Unit>({ api.botGroups() }, { api.botGroupRoom("r") }).forEach { request ->
                    try { request(); org.junit.Assert.fail("Expected Bridge error") }
                    catch (error: BridgeRequestException) {
                        assertEquals(http, error.status); assertEquals(errorCode, error.code)
                        assertTrue(!error.message.orEmpty().contains("invented private error"))
                    }
                }
            }
        val before = requests
        listOf<() -> Unit>(
            { api.botGroupRoom("r", limit = 0) }, { api.botGroupRoom("r", limit = 501) },
            { api.botGroupRoom("") }, { api.botGroupRoom(" ") },
            { api.botGroupRoom(".") }, { api.botGroupRoom("..") },
        ).forEach { invalid ->
            try { invalid(); org.junit.Assert.fail("Expected validation error") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(before, requests)
    }

    @Test
    fun toolsAndMcpUseExactAuthenticatedRequestsAndTolerantPayloads() {
        val calls = mutableListOf<okhttp3.Request>()
        val bodies = mutableListOf<String>()
        val name = "工具 box.v2 +?#"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request
            bodies += okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val body = when {
                request.method == "PUT" -> """{"ok":true,"name":"$name","enabled":false,"extra":1.5}"""
                request.method == "POST" -> """{"ok":true,"tool_count":12,"prompts":2,"resources":3,"extra":{}}"""
                request.url.encodedPath == "/v1/tools/toolsets" -> """[
                    {"name":"web","label":"网页","description":"搜索网页","platform":"local",
                     "platform_label":"本机","enabled":true,"available":true,"configured":false,
                     "tools":["search","fetch"],"extra":1.5},
                    {"name":"minimal","enabled":false}]
                """
                else -> """{"servers":[
                    {"name":"remote","enabled":true,"transport":"http","command_name":null,"url_host":"example.test","extra":1.5},
                    {"name":"local","enabled":false,"transport":"stdio","command_name":"uvx","url_host":null},
                    {"name":"minimal","enabled":false}],"extra":true}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        // Deliberately use strict Json: BridgeApi must enable unknown-key tolerance itself.
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)
        val toolsets = api.toolsets()
        assertEquals(ToolsetDto("web", "网页", "搜索网页", "local", "本机", true, true, false,
            listOf("search", "fetch")), toolsets[0])
        assertEquals(ToolsetDto(name = "minimal", enabled = false), toolsets[1])
        assertEquals(ToolEnabledDto(true, name, false), api.setToolsetEnabled(name, false))
        val servers = api.mcpServers().servers
        assertEquals(McpServerDto("remote", true, "http", null, "example.test"), servers[0])
        assertEquals(McpServerDto("local", false, "stdio", "uvx", null), servers[1])
        assertEquals(McpServerDto("minimal", false), servers[2])
        assertEquals(ToolEnabledDto(true, name, false), api.setMcpEnabled(name, false))
        assertEquals(McpTestDto(true, 12, 2, 3), api.testMcpServer(name))
        val encoded = "%E5%B7%A5%E5%85%B7%20box.v2%20+%3F%23"
        assertEquals(listOf("GET", "PUT", "GET", "PUT", "POST"), calls.map { it.method })
        assertEquals(listOf("/v1/tools/toolsets", "/v1/tools/toolsets/$encoded", "/v1/mcp/servers",
            "/v1/mcp/servers/$encoded/enabled", "/v1/mcp/servers/$encoded/test"), calls.map { it.url.encodedPath })
        assertEquals(listOf("", """{"enabled":false}""", "", """{"enabled":false}""", ""), bodies)
        calls.forEach { assertEquals("Bearer secret", it.header("Authorization")); assertEquals(null, it.url.query) }
        assertEquals(name, calls[1].url.pathSegments.last())
        assertEquals(name, calls[3].url.pathSegments[3])
        assertEquals(name, calls[4].url.pathSegments[3])
        // Encoding keeps even rejected legacy names in one path segment; Bridge validates the name.
        val legacyName = "legacy/ name?#%"
        api.setToolsetEnabled(legacyName, true)
        api.setMcpEnabled(legacyName, true)
        api.testMcpServer(legacyName)
        calls.takeLast(3).forEach {
            assertEquals(legacyName, it.url.pathSegments[3])
            assertEquals(null, it.url.query)
            assertEquals(null, it.url.fragment)
            assertTrue(it.url.encodedPath.contains("/legacy%2F%20name%3F%23%25"))
        }
        assertEquals(listOf("""{"enabled":true}""", """{"enabled":true}""", ""), bodies.takeLast(3))
    }

    @Test
    fun toolsDtosDefaultOptionalFieldsButRequireBooleanEnabledAndIntegerCounts() {
        var payload = "{}"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)
        assertEquals(emptyList<McpServerDto>(), api.mcpServers().servers)
        assertEquals(McpTestDto(), api.testMcpServer("server"))
        payload = """{"enabled":true}"""
        assertEquals(ToolEnabledDto(enabled = true), api.setToolsetEnabled("tool", true))
        assertEquals(ToolEnabledDto(enabled = true), api.setMcpEnabled("server", true))
        listOf("{}", """{"enabled":null}""", """{"enabled":1}""").forEach { item ->
            payload = "[$item]"
            assertTrue(runCatching { api.toolsets() }.isFailure)
            payload = """{"servers":[$item]}"""
            assertTrue(runCatching { api.mcpServers() }.isFailure)
            payload = item
            assertTrue(runCatching { api.setToolsetEnabled("tool", true) }.isFailure)
            assertTrue(runCatching { api.setMcpEnabled("server", true) }.isFailure)
        }
        listOf("tool_count", "prompts", "resources").forEach { field ->
            payload = """{"ok":true,"$field":1.5}"""
            assertTrue(runCatching { api.testMcpServer("server") }.isFailure)
        }
    }

    @Test
    fun toolsAndMcpErrorsKeepCodesAndExpireAuthenticationAcrossAllMethods() {
        listOf(404 to "toolset_not_found", 404 to "mcp_not_found", 400 to "invalid_toolset_request",
            400 to "invalid_mcp_request", 400 to "hermes_rejected", 503 to "hermes_unavailable",
            401 to "unauthorized").forEach { (status, code) ->
            var expirations = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Error")
                    .body("""{"detail":{"code":"$code","message":"private upstream text"}}"""
                        .toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json,
                { expirations++ })
            val actions: List<() -> Any> = listOf({ api.toolsets() }, { api.setToolsetEnabled("tool", false) },
                { api.mcpServers() }, { api.setMcpEnabled("server", false) }, { api.testMcpServer("server") })
            actions.forEach { action ->
                val failure = runCatching { action() }.exceptionOrNull() as BridgeRequestException
                assertEquals(status, failure.status)
                assertEquals(code, failure.code)
                assertTrue(!failure.message.orEmpty().contains("private upstream text"))
            }
            assertEquals(if (status == 401) 5 else 0, expirations)
        }
    }

    @Test
    fun skillsUseBareArrayTolerantDtosAndExactRequests() {
        val calls = mutableListOf<okhttp3.Request>()
        val bodies = mutableListOf<String>()
        val content = "---\nname: report.v2\ndescription: Report\n---\n中文正文\n"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request
            bodies += okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val body = when {
                request.url.encodedPath == "/v1/skills" -> """[
                    {"name":"report.v2","description":"Report","category":"productivity",
                     "enabled":true,"usage":17,"provenance":"agent","extra":{"count":1.5}},
                    {"name":"bundled","description":null,"category":null,
                     "enabled":false,"usage":0,"provenance":"bundled"},
                    {"name":"hub","enabled":true,"usage":123456,"provenance":"hub"}]
                """
                request.url.encodedPath.endsWith("/enabled") ->
                    """{"ok":true,"name":"report.v2","enabled":false,"extra":true}"""
                request.method == "GET" -> kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive("report.v2"))
                    put("content", kotlinx.serialization.json.JsonPrimitive(content))
                    put("extra", kotlinx.serialization.json.JsonPrimitive(1))
                }.toString()
                else -> """{"ok":true,"name":"report.v2","extra":1}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)
        val skills = api.skills()
        assertEquals(SkillDto("report.v2", "Report", "productivity", true, 17, "agent"), skills[0])
        assertEquals(null, skills[1].description)
        assertEquals(null, skills[1].category)
        assertEquals(null, skills[2].description)
        assertEquals(null, skills[2].category)
        assertEquals(123456, skills[2].usage)
        assertEquals(listOf("自建", "内置", "社区安装"), skills.map { it.provenanceLabel })
        assertEquals(SkillEnabledDto(true, "report.v2", false), api.setSkillEnabled("report.v2", false))
        assertEquals(SkillContentDto("report.v2", content), api.skillContent("report.v2"))
        assertEquals(SkillSavedDto(true, "report.v2"), api.updateSkillContent("report.v2", content))
        assertEquals(listOf("GET", "PUT", "GET", "PUT"), calls.map { it.method })
        assertEquals(listOf("/v1/skills", "/v1/skills/report.v2/enabled",
            "/v1/skills/report.v2/content", "/v1/skills/report.v2/content"), calls.map { it.url.encodedPath })
        calls.forEach { assertEquals("Bearer secret", it.header("Authorization")) }
        assertEquals("", bodies[0])
        assertEquals("", bodies[2])
        assertEquals(Json.parseToJsonElement("""{"enabled":false}"""), Json.parseToJsonElement(bodies[1]))
        assertEquals(kotlinx.serialization.json.buildJsonObject {
            put("content", kotlinx.serialization.json.JsonPrimitive(content))
        }, Json.parseToJsonElement(bodies[3]))

        // Legacy names still use a single encoded segment; the server decides whether to reject them.
        api.skillContent("legacy/ name?#%")
        api.setSkillEnabled("legacy/ name?#%", true)
        api.updateSkillContent("legacy/ name?#%", content)
        calls.takeLast(3).forEach {
            assertEquals("legacy/ name?#%", it.url.pathSegments[2])
            assertEquals(null, it.url.query)
            assertEquals(null, it.url.fragment)
            assertTrue(it.url.encodedPath.startsWith("/v1/skills/legacy%2F%20name%3F%23%25/"))
        }
    }

    @Test
    fun skillEndpointsRetainErrorCodesAndExpireAuthentication() {
        listOf(404 to "skill_not_found", 400 to "invalid_skill_request",
            400 to "hermes_rejected", 503 to "hermes_unavailable", 401 to "unauthorized").forEach { (status, code) ->
            var expired = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Error")
                    .body("""{"detail":{"code":"$code","extra":1}}"""
                        .toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json,
                onAuthenticationExpired = { expired++ })
            val actions: List<() -> Any> = listOf({ api.skills() }, { api.setSkillEnabled("report.v2", true) },
                { api.skillContent("report.v2") }, { api.updateSkillContent("report.v2", "正文") })
            actions.forEach { action ->
                val failure = runCatching { action() }.exceptionOrNull() as BridgeRequestException
                assertEquals(status, failure.status)
                assertEquals(code, failure.code)
            }
            assertEquals(if (status == 401) 4 else 0, expired)
        }
    }

    @Test
    fun auditEventsUseCursorAuthAndTolerantDtos() {
        val calls = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request()
            val body = if (calls.size == 1) """{"items":[
                {"id":122,"timestamp":"2026-09-29T08:00:00.123456+00:00","device_id":"phone-1",
                 "action":"cron.delete","target":"abc123","outcome":"success","detail":null,"extra":1.25},
                {"id":121,"timestamp":"2026-09-29T15:59:00+08:00","device_id":"phone-2",
                 "action":"cron.create","target":null,"outcome":"failure","detail":"hermes_unavailable"},
                {}],"next_before_id":120,"extra":0.5}""" else "{}"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)
        val page = api.auditEvents()
        assertEquals(AuditEventDto(122, "2026-09-29T08:00:00.123456+00:00", "phone-1",
            "cron.delete", "abc123", "success", null), page.items[0])
        assertEquals(null, page.items[1].target)
        assertEquals("hermes_unavailable", page.items[1].detail)
        assertEquals(AuditEventDto(), page.items[2])
        assertEquals(120L, page.nextBeforeId)
        assertEquals(AuditEventsDto(), api.auditEvents(limit = 25, beforeId = page.nextBeforeId))
        assertEquals(listOf("limit=50", "limit=25&before_id=120"), calls.map { it.url.encodedQuery })
        calls.forEach {
            assertEquals("GET", it.method)
            assertEquals("/v1/audit", it.url.encodedPath)
            assertEquals("Bearer secret", it.header("Authorization"))
        }
        assertTrue(runCatching { api.auditEvents(limit = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { api.auditEvents(limit = 101) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { api.auditEvents(beforeId = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(2, calls.size)
    }

    @Test
    fun auditErrorsRetainCodesAndChineseMessages() {
        listOf(
            Triple(400, "invalid_audit_request", "操作记录请求无效，请刷新后重试。"),
            Triple(503, "audit_storage_unavailable", "Mac 暂时无法读取操作记录，请稍后重试。"),
        ).forEach { (status, code, message) ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Error")
                    .body("""{"detail":{"code":"$code"}}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)
            val failure = runCatching { api.auditEvents() }.exceptionOrNull() as BridgeRequestException
            assertEquals(status, failure.status)
            assertEquals(code, failure.code)
            assertEquals(message, app.hermes.mobile.threads.userMessage(failure))
        }
    }

    @Test
    fun cronEndpointsPreserveBodiesAuthAndRealisticResponses() {
        val calls = mutableListOf<okhttp3.Request>()
        val bodies = mutableListOf<String>()
        val sample = """{"id":"job/一","profile":"default","name":"日报","prompt":"总结",
            "schedule":{"kind":"interval","minutes":30,"display":"every 30m","extra":0.25},
            "schedule_display":"every 30m","enabled":true,"state":"scheduled",
            "next_run_at":"2026-10-01T09:00:00+08:00","last_run_at":null,"unknown":1.5}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request
            bodies += okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val body = when {
                request.method == "DELETE" -> """{"ok":true,"extra":1}"""
                request.url.encodedPath.endsWith("/runs") -> """{"runs":[{"id":"cron_job_1","started_at":1790764152.097553,"ended_at":1790764153.25,"last_active":1790764153.125,"is_active":false,"archived":false,"extra":3.5},{}],"limit":20,"extra":true}"""
                request.method == "GET" && request.url.encodedPath == "/v1/cron/jobs" -> "[$sample,{}]"
                else -> sample
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)
        assertEquals(2, api.cronJobs().size)
        assertEquals("日报", api.cronJob("job/一").name)
        val runs = api.cronRuns("job/一", 20)
        assertEquals(1790764152.097553, runs.runs.first().startedAt!!, 0.000001)
        assertEquals(null, runs.runs.last().startedAt)
        api.createCronJob(CronCreateRequest(schedule = "every 30m", prompt = "总结", paused = true))
        val updates = Json.parseToJsonElement("""{"model":null,"skills":[],"name":"新名"}""") as kotlinx.serialization.json.JsonObject
        api.updateCronJob("job/一", updates)
        api.pauseCronJob("job/一")
        api.resumeCronJob("job/一")
        api.triggerCronJob("job/一")
        assertTrue(api.deleteCronJob("job/一").ok)
        assertEquals(listOf("GET", "GET", "GET", "POST", "PUT", "POST", "POST", "POST", "DELETE"), calls.map { it.method })
        assertEquals(listOf("/v1/cron/jobs", "/v1/cron/jobs/job%2F%E4%B8%80", "/v1/cron/jobs/job%2F%E4%B8%80/runs", "/v1/cron/jobs", "/v1/cron/jobs/job%2F%E4%B8%80", "/v1/cron/jobs/job%2F%E4%B8%80/pause", "/v1/cron/jobs/job%2F%E4%B8%80/resume", "/v1/cron/jobs/job%2F%E4%B8%80/trigger", "/v1/cron/jobs/job%2F%E4%B8%80"), calls.map { it.url.encodedPath })
        assertEquals("20", calls[2].url.queryParameter("limit"))
        assertEquals(Json.parseToJsonElement("""{"schedule":"every 30m","prompt":"总结","paused":true}"""), Json.parseToJsonElement(bodies[3]))
        assertEquals(Json.parseToJsonElement("""{"updates":{"model":null,"skills":[],"name":"新名"}}"""), Json.parseToJsonElement(bodies[4]))
        calls.forEach { assertEquals("Bearer secret", it.header("Authorization")) }
        listOf(5, 6, 7, 8).forEach { assertEquals("", bodies[it]) }
        assertTrue(runCatching { api.updateCronJob("x", Json.parseToJsonElement("""{"script":"bad"}""") as kotlinx.serialization.json.JsonObject) }.isFailure)
        assertTrue(runCatching { api.cronRuns("x", 101) }.isFailure)
        assertEquals(9, calls.size)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun cronUpdateKeepsExplicitNullEvenWhenJsonOmitsNullProperties() {
        var body = ""
        var expired = false
        var status = 200
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            body = okio.Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Response")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client,
            Json { explicitNulls = false; encodeDefaults = true }, { expired = true })
        api.createCronJob(CronCreateRequest("every 30m", prompt = "内容"))
        assertEquals(Json.parseToJsonElement("""{"schedule":"every 30m","prompt":"内容"}"""), Json.parseToJsonElement(body))
        api.updateCronJob("j", Json.parseToJsonElement("""{"model":null}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(Json.parseToJsonElement("""{"updates":{"model":null}}"""), Json.parseToJsonElement(body))
        status = 401
        assertTrue(runCatching { api.cronJobs() }.exceptionOrNull() is BridgeRequestException)
        assertTrue(expired)
    }

    @Test
    fun cronErrorsRetainCodesAndChineseMessages() {
        listOf(404 to "cron_job_not_found", 400 to "invalid_cron_request", 400 to "hermes_rejected", 503 to "hermes_unavailable").forEach { (status, code) ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Error")
                    .body("""{"detail":{"code":"$code"}}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)
            val failure = runCatching { api.cronJobs() }.exceptionOrNull() as BridgeRequestException
            assertEquals(status, failure.status)
            assertEquals(code, failure.code)
            assertTrue(app.hermes.mobile.threads.userMessage(failure) != "无法连接 Hermes")
        }
    }

    @Test
    fun uploadsStreamOrderedChunksThenCompleteAttachAndSend() = runBlocking {
        val bytes = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
        val paths = mutableListOf<String>()
        val chunks = mutableListOf<ByteArray>()
        var createBody = ""
        var attachBody = ""
        var opened = 0
        val progress = mutableListOf<Int>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer secret", request.header("Authorization"))
            paths += "${request.method} ${request.url.encodedPath}"
            val body = okio.Buffer().also { request.body?.writeTo(it) }
            val response = when (request.url.encodedPath) {
                "/v1/uploads" -> {
                    createBody = body.readUtf8()
                    """{"id":"u1","name":"normalized.txt","expires_at":1790764152.097553,"max_chunk_size":1048576}"""
                }
                "/v1/uploads/u1/chunks/0", "/v1/uploads/u1/chunks/1" -> {
                    assertEquals("application/octet-stream", request.body?.contentType().toString())
                    chunks += body.readByteArray()
                    """{"id":"u1","next_index":${chunks.size},"received_size":${chunks.sumOf { it.size }}}"""
                }
                "/v1/uploads/u1/complete" -> {
                    assertEquals(0L, body.size)
                    """{"id":"u1","status":"completed"}"""
                }
                "/v1/uploads/u1/attach" -> {
                    attachBody = body.readUtf8()
                    """{"id":"u1","thread_id":"stored-thread","live_session_id":"live","status":"attached","ref_text":"@file/ref"}"""
                }
                else -> {
                    assertEquals("""{"text":"Inspect\n\n@file/ref"}""", body.readUtf8())
                    """{"status":"streaming"}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.url.encodedPath == "/v1/uploads") 201 else 200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)
        val source = AttachmentSource("a.txt", bytes.size.toLong(), "text/plain") {
            opened++
            // A provider may return short reads; chunks must still be filled in order.
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 777))
            }
        }
        val uploaded = AttachmentUploader(api).upload("stored-thread", source) { progress += it }
        val attached = api.attachUpload(uploaded.id, "stored-thread")
        api.send("stored-thread", "Inspect\n\n${attached.refText}")

        assertEquals(2, opened)
        assertEquals("normalized.txt", uploaded.name)
        assertEquals(listOf(1048576, 17), chunks.map { it.size })
        assertArrayEquals(bytes, chunks[0] + chunks[1])
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(Json.parseToJsonElement("""{"thread_id":"stored-thread","name":"a.txt","size":1048593,"sha256":"$sha","mime_type":"text/plain"}"""), Json.parseToJsonElement(createBody))
        assertEquals("""{"thread_id":"stored-thread"}""", attachBody)
        assertEquals(listOf("POST /v1/uploads", "PUT /v1/uploads/u1/chunks/0", "PUT /v1/uploads/u1/chunks/1", "POST /v1/uploads/u1/complete", "POST /v1/uploads/u1/attach", "POST /v1/threads/stored-thread/messages"), paths)
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun emptyFileHashesAndCompletesWithoutSendingAnEmptyChunk() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            val body = okio.Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            val response = when (paths.last()) {
                "/v1/uploads" -> {
                    assertTrue(body.contains("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
                    assertTrue(body.contains("\"size\":0"))
                    """{"id":"empty","name":"empty.txt","expires_at":1790764152.097553,"max_chunk_size":1048576}"""
                }
                "/v1/uploads/empty/complete" -> """{"id":"empty","status":"completed"}"""
                else -> """{"id":"empty","thread_id":"stored","live_session_id":"live","status":"attached"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)
        AttachmentUploader(api).upload("stored", AttachmentSource("empty.txt", null, "text/plain") { ByteArrayInputStream(byteArrayOf()) }) {}
        assertEquals(null, api.attachUpload("empty", "stored").refText)
        assertEquals(listOf("/v1/uploads", "/v1/uploads/empty/complete", "/v1/uploads/empty/attach"), paths)
    }

    @Test
    fun invalidMetadataAndUnknownOversizedStreamsAreRejectedBeforeAnyRequest() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            throw IOException("Unexpected request")
        }.build()
        val uploader = AttachmentUploader(BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json))
        val invalid = listOf(
            AttachmentSource("large", MAX_ATTACHMENT_SIZE + 1, "text/plain") { error("Must reject before opening") },
            AttachmentSource("video", 1, "video/mp4") { error("Must reject before opening") },
        )
        invalid.forEach { source ->
            assertTrue(runCatching { uploader.upload("stored", source) {} }.exceptionOrNull() is AttachmentValidationException)
        }
        var closed = false
        val source = AttachmentSource("unknown", null, "text/plain") {
            object : InputStream() {
                var remaining = MAX_ATTACHMENT_SIZE + 1
                override fun read(): Int = if (remaining-- > 0) 0 else -1
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(remaining, length.toLong()).toInt()
                    remaining -= count
                    return count
                }
                override fun close() { closed = true }
            }
        }
        assertTrue(runCatching { uploader.upload("stored", source) {} }.exceptionOrNull() is AttachmentValidationException)
        assertTrue(closed)
        assertEquals(0, requests)
    }

    @Test
    fun chunkingHonorsSmallerServerLimitAndDetectsAChangedSource() = runBlocking {
        val sizes = mutableListOf<Int>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            sizes += okio.Buffer().also { request.body!!.writeTo(it) }.size.toInt()
            assertEquals("/v1/uploads/u/chunks/${sizes.lastIndex}", request.url.encodedPath)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":"u","next_index":${sizes.size},"received_size":${sizes.sum()}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)
        api.uploadChunks("u", ByteArrayInputStream(ByteArray(7)), 7, 3) {}
        assertEquals(listOf(3, 3, 1), sizes)
        val failure = runCatching { api.uploadChunks("u", ByteArrayInputStream(ByteArray(4)), 3, 10) {} }.exceptionOrNull()
        assertEquals("size_mismatch", (failure as BridgeRequestException).code)
        assertEquals(3, sizes.size)
    }

    @Test
    fun retryStartsAFreshUploadAndDoesNotCompleteTheFailedAttempt() = runBlocking {
        val paths = mutableListOf<String>()
        var attempt = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            paths += path
            val response = when {
                path == "/v1/uploads" -> {
                    attempt++
                    """{"id":"u$attempt","name":"file.txt","expires_at":1790764152.097553,"max_chunk_size":1048576}"""
                }
                path == "/v1/uploads/u1/chunks/0" -> """{"detail":{"code":"upload_storage_unavailable"}}"""
                path.endsWith("/complete") -> """{"id":"u2","status":"completed"}"""
                else -> """{"id":"u2","next_index":1,"received_size":3}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (path == "/v1/uploads/u1/chunks/0") 503 else 200).message("Response")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val uploader = AttachmentUploader(BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json))
        val source = AttachmentSource("file.txt", 3, "text/plain") { ByteArrayInputStream("abc".toByteArray()) }
        assertTrue(runCatching { uploader.upload("stored", source) {} }.exceptionOrNull() is BridgeRequestException)
        assertEquals("u2", uploader.upload("stored", source) {}.id)
        assertEquals(listOf("/v1/uploads", "/v1/uploads/u1/chunks/0", "/v1/uploads", "/v1/uploads/u2/chunks/0", "/v1/uploads/u2/complete"), paths)
    }

    @Test
    fun cancellingAnUploadCancelsItsHttpCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val call = AtomicReference<okhttp3.Call>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call())
            started.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            throw IOException("Cancelled")
        }.build()
        val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)
        val job = launch(Dispatchers.Default) { api.createUpload("stored", "file.txt", 0, "0".repeat(64), "text/plain") }
        try {
            withTimeout(5000) { started.await(); job.cancelAndJoin() }
            assertTrue(call.get().isCanceled())
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }

    @Test
    fun uploadErrorsKeepTheirStableCodeAndAuthenticationCallback() = runBlocking {
        listOf(409 to "checksum_mismatch", 413 to "upload_too_large", 415 to "unsupported_media_type", 404 to "upload_not_found", 401 to "unauthorized").forEach { (status, code) ->
            var expired = false
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Error")
                    .body("""{"detail":{"code":"$code"}}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json, { expired = true })
            val failure = runCatching { api.completeUpload("u") }.exceptionOrNull() as BridgeRequestException
            assertEquals(code, failure.code)
            assertEquals(status, failure.status)
            assertEquals(status == 401, expired)
        }
    }

    @Test
    fun directoriesUseAuthenticatedGetAndAnEncodedOptionalPath() {
        val calls = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    (if (chain.request().url.query == null) {
                        """{"items":[{"path":"/Users/mac/项目 & notes","name":"项目 & notes"}],"parent":null}"""
                    } else {
                        """{"items":[],"parent":"/Users/mac"}"""
                    }).toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)

        val home = api.directories()
        val path = "/Users/mac/项目 & notes"
        assertEquals(DirectoryListing(emptyList(), "/Users/mac"), api.directories(path))

        assertEquals(DirectoryListing(listOf(DirectoryEntry(path, "项目 & notes")), null), home)
        assertEquals("/v1/catalog/directories", calls[0].url.encodedPath)
        assertEquals(null, calls[0].url.query)
        assertEquals("/v1/catalog/directories", calls[1].url.encodedPath)
        assertEquals(path, calls[1].url.queryParameter("path"))
        assertEquals(setOf("path"), calls[1].url.queryParameterNames)
        calls.forEach {
            assertEquals("GET", it.method)
            assertEquals("Bearer secret", it.header("Authorization"))
        }
    }

    @Test
    fun directoryErrorsKeepTheirStatusAndCode() {
        listOf(403 to "directory_not_allowed", 404 to "directory_not_found", 503 to "hermes_unavailable").forEach { (status, code) ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("Error")
                    .body("""{"detail":{"code":"$code"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()
            val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)

            val failure = runCatching { api.directories("/Users/mac/missing") }.exceptionOrNull() as BridgeRequestException

            assertEquals(status, failure.status)
            assertEquals(code, failure.code)
        }
    }

    @Test
    fun creatingTaskSendsChosenDirectoryAlongsideModel() {
        var requestedBody = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("POST", chain.request().method)
            assertEquals("/v1/threads", chain.request().url.encodedPath)
            requestedBody = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"id":"task-1","title":"New task"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)

        api.create("Inspect", app.hermes.mobile.threads.ModelOption("model", "provider"), "/Users/mac/项目 & notes")

        assertEquals(
            Json.parseToJsonElement("""{"prompt":"Inspect","model":"model","provider":"provider","cwd":"/Users/mac/项目 & notes"}"""),
            Json.parseToJsonElement(requestedBody),
        )
    }

    @Test
    fun unauthorizedResponseExpiresStoredAuthentication() {
        var expired = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = BridgeApi(
            DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "expired"),
            client,
            Json,
            onAuthenticationExpired = { expired = true },
        )

        runCatching { api.listThreads() }

        assertEquals(true, expired)
    }

    @Test
    fun openingTaskResumesItsLiveSession() {
        var requestedPath = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedPath = chain.request().url.encodedPath
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"status\":\"running\"}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = BridgeApi(
            DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"),
            client,
            Json { ignoreUnknownKeys = true },
        )

        assertEquals("running", api.resume("thread-1"))
        assertEquals("/v1/threads/thread-1/resume", requestedPath)
    }

    @Test
    fun debugHttpConnectionKeepsAbsoluteMessageUrl() {
        var requestedUrl = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl = chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(202)
                .message("Accepted")
                .body("{\"status\":\"streaming\"}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = BridgeApi(
            DeviceConnection("http://10.0.2.2:8788/".toHttpUrl(), "secret"),
            client,
            Json { ignoreUnknownKeys = true },
        )

        api.send("thread-1", "Continue")

        assertEquals(
            "http://10.0.2.2:8788/v1/threads/thread-1/messages",
            requestedUrl,
        )
    }

    @Test
    fun creatingTaskUsesMacDefaultsWithoutExposingRuntimeFields() {
        var requestedPath = ""
        var requestedBody = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedPath = chain.request().url.encodedPath
            requestedBody = chain.request().body?.let { body ->
                okio.Buffer().also(body::writeTo).readUtf8()
            }.orEmpty()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"id\":\"task-1\",\"title\":\"New task\"}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = BridgeApi(
            DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"),
            client,
            Json { ignoreUnknownKeys = true },
        )

        api.create("Inspect the build", null)

        assertEquals("/v1/threads", requestedPath)
        assertEquals(
            "{\"prompt\":\"Inspect the build\"}",
            requestedBody,
        )
    }

    @Test
    fun resolvesApprovalWithSelectedChoice() {
        var requestedPath = ""
        var requestedBody = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedPath = chain.request().url.encodedPath
            requestedBody = chain.request().body?.let { body ->
                okio.Buffer().also(body::writeTo).readUtf8()
            }.orEmpty()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"status\":\"resolved\"}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = BridgeApi(
            DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"),
            client,
            Json { ignoreUnknownKeys = true },
        )

        api.resolveApproval("approval-1", "deny")

        assertEquals("/v1/approvals/approval-1/resolve", requestedPath)
        assertEquals("{\"choice\":\"deny\"}", requestedBody)
    }

    @Test
    fun searchUsesQueryParameterInsteadOfEncodedPath() {
        var requestedUrl = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl = chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"items":[],"total":0}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = BridgeApi(
            DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"),
            client,
            Json { ignoreUnknownKeys = true },
        )

        api.listThreads("drawer test")

        assertEquals(
            "https://mac-mini.tailnet.ts.net/v1/threads?q=drawer%20test",
            requestedUrl,
        )
    }

    @Test
    fun listThreadsParsesUpdatedAtAndDefaultsToZeroWhenMissing() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{
                        "items": [
                            {"id": "t1", "title": "With timestamp", "updated_at": 1790000000.5},
                            {"id": "t2", "title": "Missing timestamp"}
                        ],
                        "total": 2
                    }""".toResponseBody("application/json".toMediaType())
                )
                .build()
        }.build()
        val api = BridgeApi(
            DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"),
            client,
            Json { ignoreUnknownKeys = true },
        )

        val threads = api.listThreads()
        assertEquals(2, threads.size)
        assertEquals("t1", threads[0].id)
        assertEquals(1790000000.5, threads[0].updatedAt, 0.0)
        assertEquals("t2", threads[1].id)
        assertEquals(0.0, threads[1].updatedAt, 0.0)
    }

    @Test
    fun bridgeErrorCodeIsKeptForAHelpfulMessage() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(409)
                .message("Conflict")
                .body("""{"detail":{"code":"thread_open_elsewhere","message":"open elsewhere"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)

        val failure = runCatching { api.send("chat-1", "hi") }.exceptionOrNull()

        assertEquals("thread_open_elsewhere", (failure as BridgeRequestException).code)
        assertEquals(409, failure.status)
    }

    @Test
    fun modelOptionsCarryProviderAliasesAndTheMacDefault() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"items":[
                      {"model":"upstage/solar-pro4:free","provider":"nous","provider_label":"Nous Portal","label":"upstage/solar-pro4:free","current":true},
                      {"model":"gpt-6-astra","provider":"opencodex","provider_label":"opencodex","provider_aliases":["custom:opencodex","opencodex"],"label":"gpt-6-astra"}
                    ]}""".toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"), client, Json)

        val options = api.modelOptions()

        assertEquals(listOf(true, false), options.map { it.current })
        assertEquals(listOf("custom:opencodex", "opencodex"), options[1].aliases)
    }

    @Test
    fun renameArchiveAndDeleteUseThreadEndpoints() {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body?.writeTo(buffer)
            calls += "${chain.request().method} ${chain.request().url.encodedPath} ${buffer.readUtf8()}".trim()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .body("".toResponseBody(null))
                .build()
        }.build()
        val api = BridgeApi(
            DeviceConnection("https://mac-mini.tailnet.ts.net/".toHttpUrl(), "secret"),
            client,
            Json { ignoreUnknownKeys = true },
        )

        api.rename("t1", "新名")
        api.archive("t1")
        api.delete("t1")

        assertEquals(
            listOf(
                "PATCH /v1/threads/t1 {\"title\":\"新名\"}",
                "PATCH /v1/threads/t1 {\"archived\":true}",
                "DELETE /v1/threads/t1",
            ),
            calls,
        )
    }

    @Test
    fun skillsHubEndpointsUseExactAuthenticatedRequestsAndTolerantPayloads() {
        val calls = mutableListOf<okhttp3.Request>()
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            bodies += buffer.readUtf8()
            val body = when {
                request.url.encodedPath == "/v1/skills/hub/sources" -> """{
                    "sources":[{"id":"community","label":"社区","searchable":true,"available":true,"rate_limited":false,"extra":1}],
                    "index_available":true,
                    "featured":[{"name":"test","description":"测试","source":"community","identifier":"community/test:v1",
                                "trust_level":"trusted","repo":"https://github.com/example/test","tags":["util"],"extra":2}],
                    "extra":3
                }"""
                request.url.encodedPath == "/v1/skills/hub/search" -> """{
                    "results":[{"name":"test","description":"测试","source":"community","identifier":"community/test:v1",
                                "trust_level":"trusted","repo":null,"tags":[],"extra":4}],
                    "extra":5
                }"""
                request.url.encodedPath == "/v1/skills/hub/preview" -> """{
                    "name":"test","description":"测试","source":"community","identifier":"community/test:v1",
                    "trust_level":"trusted","repo":null,"tags":[],
                    "files":["SKILL.md","run.sh"],"skill_md":"# Skill","truncated":false,"extra":6
                }"""
                request.url.encodedPath == "/v1/skills/hub/scan" -> """{
                    "identifier":"community/test:v1","trust_level":"trusted","verdict":"safe","allowed":true,
                    "findings":[{"severity":"info","category":"general","message":"all clear","extra":7}],
                    "scan_id":"scan_token_abc123","expires_at":"2026-10-01T12:00:00Z","extra":8
                }"""
                request.url.encodedPath == "/v1/skills/hub/install" -> """{
                    "action_id":"skill-install-test-12345678","status":"started","extra":9
                }"""
                request.url.encodedPath.startsWith("/v1/skills/hub/actions/") -> """{
                    "running":false,"exit_code":0,"log_tail":"Installed successfully","extra":10
                }"""
                request.url.encodedPath == "/v1/skills/hub/uninstall" -> """{
                    "action_id":"skill-uninstall-test-87654321","status":"started","extra":11
                }"""
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)

        val sources = api.skillHubSources()
        assertEquals(1, sources.sources.size)
        assertEquals("community", sources.sources[0].id)
        assertEquals(true, sources.index_available)
        assertEquals(1, sources.featured.size)
        assertEquals("community/test:v1", sources.featured[0].identifier)
        assertEquals("trusted", sources.featured[0].trust_level)

        val search = api.searchSkillHub("report maker", source = "community", limit = 15)
        assertEquals(1, search.results.size)
        assertEquals("test", search.results[0].name)

        val preview = api.previewSkillHub("community/test:v1")
        assertEquals("community/test:v1", preview.identifier)
        assertEquals(listOf("SKILL.md", "run.sh"), preview.files)
        assertEquals("# Skill", preview.skill_md)
        assertEquals(false, preview.truncated)

        val scan = api.scanSkillHub("community/test:v1")
        assertEquals("community/test:v1", scan.identifier)
        assertEquals("trusted", scan.trust_level)
        assertEquals("safe", scan.verdict)
        assertEquals(true, scan.allowed)
        assertEquals(1, scan.findings.size)
        assertEquals("all clear", scan.findings[0].message)
        assertEquals("scan_token_abc123", scan.scan_id)
        assertEquals("2026-10-01T12:00:00Z", scan.expires_at)

        val installFalse = api.installSkillHub("community/test:v1", "scan_token_abc123", false)
        assertEquals("skill-install-test-12345678", installFalse.action_id)
        assertEquals("started", installFalse.status)

        val installTrue = api.installSkillHub("community/test:v1", "scan_token_abc123", true)
        assertEquals("skill-install-test-12345678", installTrue.action_id)

        val action = api.skillHubAction("skill-install-test-12345678")
        assertEquals(false, action.running)
        assertEquals(0, action.exit_code)
        assertEquals("Installed successfully", action.log_tail)

        val uninstall = api.uninstallSkillHub("test")
        assertEquals("skill-uninstall-test-87654321", uninstall.action_id)
        assertEquals("started", uninstall.status)

        assertEquals(listOf("GET", "GET", "GET", "POST", "POST", "POST", "GET", "POST"), calls.map { it.method })
        assertEquals(listOf(
            "/v1/skills/hub/sources",
            "/v1/skills/hub/search",
            "/v1/skills/hub/preview",
            "/v1/skills/hub/scan",
            "/v1/skills/hub/install",
            "/v1/skills/hub/install",
            "/v1/skills/hub/actions/skill-install-test-12345678",
            "/v1/skills/hub/uninstall"
        ), calls.map { it.url.encodedPath })

        calls.forEach {
            assertEquals("Bearer secret", it.header("Authorization"))
        }

        // Query parameters
        assertEquals("report maker", calls[1].url.queryParameter("q"))
        assertEquals("community", calls[1].url.queryParameter("source"))
        assertEquals("15", calls[1].url.queryParameter("limit"))
        assertEquals("q=report%20maker&source=community&limit=15", calls[1].url.encodedQuery)

        assertEquals("community/test:v1", calls[2].url.queryParameter("identifier"))
        assertEquals("identifier=community%2Ftest%3Av1", calls[2].url.encodedQuery)

        // Request bodies
        assertEquals("", bodies[0])
        assertEquals("", bodies[1])
        assertEquals("", bodies[2])
        assertEquals(Json.parseToJsonElement("""{"identifier":"community/test:v1"}"""), Json.parseToJsonElement(bodies[3]))
        // Explicit acknowledge_risk false MUST serialize!
        assertEquals(
            Json.parseToJsonElement("""{"identifier":"community/test:v1","scan_id":"scan_token_abc123","acknowledge_risk":false}"""),
            Json.parseToJsonElement(bodies[4])
        )
        assertEquals(
            Json.parseToJsonElement("""{"identifier":"community/test:v1","scan_id":"scan_token_abc123","acknowledge_risk":true}"""),
            Json.parseToJsonElement(bodies[5])
        )
        assertEquals("", bodies[6])
        assertEquals(Json.parseToJsonElement("""{"name":"test"}"""), Json.parseToJsonElement(bodies[7]))
    }

    @Test
    fun skillsHubActionEnforcesBoundedCallTimeout() {
        val timeouts = mutableListOf<Long>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            timeouts += chain.call().timeout().timeoutNanos()
            val body = when {
                chain.request().url.encodedPath.contains("/actions/") -> """{"running":true,"exit_code":null,"log_tail":""}"""
                else -> """{"sources":[],"index_available":false,"featured":[]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)

        api.skillHubSources()
        api.skillHubAction("skill-install-action-12345678")

        assertEquals(2, timeouts.size)
        // Sources uses default call timeout (0 nanos)
        assertEquals(0L, timeouts[0])
        // Action route opts into 15s bounded whole-call timeout
        assertEquals(TimeUnit.MILLISECONDS.toNanos(15_000L), timeouts[1])
    }

    @Test
    fun skillsHubDtosDefaultOptionalFieldsAndCoerceRealisticNulls() {
        var payload = "{}"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)

        // Sources defaults from empty JSON
        payload = "{}"
        val sources = api.skillHubSources()
        assertEquals(emptyList<HubSourceDto>(), sources.sources)
        assertEquals(false, sources.index_available)
        assertEquals(emptyList<HubMetadataDto>(), sources.featured)

        // SourceDto defaults and realistic nulls
        payload = """{"sources":[{"id":"s1","label":"L1"}],"index_available":false,"featured":[]}"""
        val s1 = api.skillHubSources().sources[0]
        assertEquals("s1", s1.id)
        assertEquals("L1", s1.label)
        assertEquals(false, s1.searchable)
        assertEquals(null, s1.available)
        assertEquals(null, s1.rate_limited)

        payload = """{"sources":[{"id":"s2","label":"L2","searchable":true,"available":null,"rate_limited":null}]}"""
        val s2 = api.skillHubSources().sources[0]
        assertEquals(null, s2.available)
        assertEquals(null, s2.rate_limited)
        assertEquals(true, s2.searchable)

        // Search & Metadata defaults with realistic nulls
        payload = """{"results":[{"name":null,"description":null,"source":null,"identifier":null,"trust_level":null,"repo":null,"tags":null}]}"""
        val meta = api.searchSkillHub("test").results[0]
        assertEquals("", meta.name)
        assertEquals("", meta.description)
        assertEquals("", meta.source)
        assertEquals("", meta.identifier)
        assertEquals("unknown", meta.trust_level)
        assertEquals(null, meta.repo)
        assertEquals(emptyList<String>(), meta.tags)

        // Preview defaults and realistic nulls
        payload = """{"name":"p","files":null,"skill_md":null,"truncated":false}"""
        val preview = api.previewSkillHub("p")
        assertEquals(emptyList<String>(), preview.files)
        assertEquals("", preview.skill_md)
        assertEquals(false, preview.truncated)

        // ActionDto defaults with null exit_code
        payload = """{"running":false,"exit_code":null,"log_tail":null}"""
        val action = api.skillHubAction("skill-install-test-12345678")
        assertEquals(false, action.running)
        assertEquals(null, action.exit_code)
        assertEquals("", action.log_tail)
    }

    @Test
    fun skillsHubRequiredFieldsFailWhenMissingOrNull() {
        var payload = "{}"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)

        // ScanDto requires allowed (missing or null must fail)
        listOf(
            "{}",
            """{"identifier":"id","trust_level":"safe","verdict":"safe"}""",
            """{"identifier":"id","trust_level":"safe","verdict":"safe","allowed":null}"""
        ).forEach { jsonStr ->
            payload = jsonStr
            assertTrue(runCatching { api.scanSkillHub("id") }.isFailure)
        }

        // ActionDto requires running (missing or null must fail)
        listOf(
            "{}",
            """{"exit_code":0,"log_tail":"done"}""",
            """{"running":null,"exit_code":0}"""
        ).forEach { jsonStr ->
            payload = jsonStr
            assertTrue(runCatching { api.skillHubAction("skill-install-action-12345678") }.isFailure)
        }
    }

    @Test
    fun skillsHubScanDtoRedactsScanIdInToString() {
        val scan = HubScanDto(
            identifier = "community/tool:v1",
            trust_level = "community",
            verdict = "caution",
            allowed = true,
            findings = listOf(HubFindingDto("warning", "permissions", "requests network")),
            scan_id = "super_secret_scan_token_12345",
            expires_at = "2026-10-01T00:00:00Z"
        )
        // scan_id is preserved for internal state and comparison
        assertEquals("super_secret_scan_token_12345", scan.scan_id)
        assertEquals(scan, HubScanDto(
            identifier = "community/tool:v1",
            trust_level = "community",
            verdict = "caution",
            allowed = true,
            findings = listOf(HubFindingDto("warning", "permissions", "requests network")),
            scan_id = "super_secret_scan_token_12345",
            expires_at = "2026-10-01T00:00:00Z"
        ))

        // toString() MUST redact the opaque scan_id
        val str = scan.toString()
        assertTrue(!str.contains("super_secret_scan_token_12345"))
        assertTrue(str.contains("scan_id=[REDACTED]"))
    }

    @Test
    fun skillsHubErrorsRetainCodesAndExpireAuthenticationAcrossAllMethods() {
        listOf(
            409 to "scan_required",
            409 to "scan_expired",
            409 to "scan_mismatch",
            409 to "skill_not_hub",
            404 to "skill_not_found",
            400 to "hermes_rejected",
            409 to "risk_not_acknowledged",
            409 to "scan_blocked",
            404 to "hub_action_not_found",
            404 to "skill_not_hub",
            400 to "invalid_skill_request",
            503 to "hermes_unavailable",
            401 to "unauthorized"
        ).forEach { (status, code) ->
            var expirations = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Error")
                    .body("""{"detail":{"code":"$code","message":"upstream details hidden"}}"""
                        .toResponseBody("application/json".toMediaType())).build()
            }.build()

            val api = BridgeApi(
                DeviceConnection("https://example.test/".toHttpUrl(), "secret"),
                client,
                Json,
                onAuthenticationExpired = { expirations++ }
            )

            val actions: List<() -> Any> = listOf(
                { api.skillHubSources() },
                { api.searchSkillHub("test") },
                { api.previewSkillHub("community/test:v1") },
                { api.scanSkillHub("community/test:v1") },
                { api.installSkillHub("community/test:v1", "scan_1", false) },
                { api.skillHubAction("skill-install-test-12345678") },
                { api.uninstallSkillHub("test") }
            )

            actions.forEach { action ->
                val failure = runCatching { action() }.exceptionOrNull() as BridgeRequestException
                assertEquals(status, failure.status)
                assertEquals(code, failure.code)
                assertTrue(!failure.message.orEmpty().contains("upstream details hidden"))
            }

            assertEquals(if (status == 401) 7 else 0, expirations)
        }
    }

    @Test
    fun skillsHubQueryAndPathEncodingPreservesSpecialCharacters() {
        val calls = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request()
            val body = when {
                chain.request().url.encodedPath == "/v1/skills/hub/search" -> """{"results":[]}"""
                chain.request().url.encodedPath == "/v1/skills/hub/preview" -> """{"name":"test","files":[],"skill_md":"","truncated":false}"""
                else -> """{"running":true,"exit_code":null,"log_tail":""}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)

        api.searchSkillHub("tool + math & test #1", source = "hub_v1", limit = 10)
        assertEquals("tool + math & test #1", calls[0].url.queryParameter("q"))
        assertEquals("hub_v1", calls[0].url.queryParameter("source"))
        assertEquals("10", calls[0].url.queryParameter("limit"))
        assertTrue(calls[0].url.encodedQuery!!.contains("q=tool%20%2B%20math%20%26%20test%20%231"))

        api.previewSkillHub("community/math:v1.0@user")
        assertEquals("community/math:v1.0@user", calls[1].url.queryParameter("identifier"))
        assertEquals("identifier=community%2Fmath%3Av1.0%40user", calls[1].url.encodedQuery)

        api.skillHubAction("skills-install-tool-abcdef12")
        assertEquals("/v1/skills/hub/actions/skills-install-tool-abcdef12", calls[2].url.encodedPath)
        assertEquals("skills-install-tool-abcdef12", calls[2].url.pathSegments.last())
        listOf(
            "official/security/foo" to "official%2Fsecurity%2Ffoo",
            "owner/repo@skill" to "owner%2Frepo%40skill",
        ).forEach { (identifier, encoded) ->
            api.previewSkillHub(identifier)
            assertEquals(identifier, calls.last().url.queryParameter("identifier"))
            assertEquals("identifier=$encoded", calls.last().url.encodedQuery)
        }
    }

    @Test
    fun skillsHubSearchRejectsInvalidInputsBeforeRequest() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"results\":[]}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)
        listOf("", "   ", "a".repeat(201)).forEach {
            assertTrue(runCatching { api.searchSkillHub(it) }.exceptionOrNull() is IllegalArgumentException)
        }
        listOf("", "bad/source", "a".repeat(51)).forEach {
            assertTrue(runCatching { api.searchSkillHub("q", source = it) }.exceptionOrNull() is IllegalArgumentException)
        }
        listOf(0, 51).forEach {
            assertTrue(runCatching { api.searchSkillHub("q", limit = it) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, requests)
    }

    @Test
    fun modelOptionsUnavailable503ThrowsBridgeRequestException() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Service Unavailable")
                .body("""{"detail":{"code":"hermes_unavailable","message":"Hermes is unavailable"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json { ignoreUnknownKeys = true })
        val exception = runCatching { api.modelOptions() }.exceptionOrNull()
        assertTrue(exception is BridgeRequestException)
        val bridgeException = exception as BridgeRequestException
        assertEquals(503, bridgeException.status)
        assertEquals("hermes_unavailable", bridgeException.code)
    }

}
