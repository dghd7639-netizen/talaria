package app.hermes.mobile.management.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.data.HubFindingDto
import app.hermes.mobile.data.HubMetadataDto
import app.hermes.mobile.data.SkillDto
import app.hermes.mobile.pairing.DeviceConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
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
class SkillsHubViewModelTest {
    private fun client(h: (Request, String) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val buf = Buffer().also { req.body?.writeTo(it) }
            val (code, res) = h(req, buf.readUtf8())
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code)
                .message(if (code in 200..299) "OK" else "Err")
                .body(res.toResponseBody("application/json".toMediaType())).build()
        }.build()

    private fun vm(c: OkHttpClient, d: CoroutineDispatcher) =
        SkillsViewModel(BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "s"), c, Json), d)

    private fun runHubTest(block: suspend TestScope.(CoroutineDispatcher) -> Unit) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(d)
        try { block(d) } finally { Dispatchers.resetMain() }
    }

    @Test
    fun searchExplicitEmptySourcesFeaturedAndShowHubAlwaysSources() = runHubTest { d ->
        val calls = mutableListOf<String>()
        val c = client { req, _ ->
            calls += "${req.method} ${req.url.encodedPath}?${req.url.query.orEmpty()}"
            when (req.url.encodedPath) {
                "/v1/skills/hub/sources" -> 200 to """{"sources":[{"id":"all","label":"全","searchable":true}],"index_available":true,"featured":[{"name":"f1","identifier":"f1","trust_level":"trusted"}]}"""
                "/v1/skills/hub/search" -> 200 to """{"results":[{"name":"r1","identifier":"r1","trust_level":"community"}]}"""
                else -> 404 to """{"detail":{"code":"not_found"}}"""
            }
        }
        val v = vm(c, d)
        v.showHub(); advanceUntilIdle()
        assertTrue(v.state.value.discovering)
        assertEquals("f1", v.state.value.hubSources.featured.single().identifier)
        assertEquals("/v1/skills/hub/sources?", calls.last().substringAfter("GET "))

        v.editHubQuery("r1"); v.chooseHubSource("community"); v.searchHub(); advanceUntilIdle()
        assertEquals("r1", v.state.value.hubResults.single().identifier)
        assertTrue(calls.last().contains("q=r1") && calls.last().contains("source=community"))

        v.showInstalled(); assertFalse(v.state.value.discovering)
        calls.clear()
        v.showHub(); advanceUntilIdle() // Always sources request, never implicit search for retained query
        assertEquals(listOf("GET /v1/skills/hub/sources?"), calls)

        v.editHubQuery("   "); v.searchHub(); advanceUntilIdle()
        assertTrue(calls.last().startsWith("GET /v1/skills/hub/sources") && v.state.value.hubResults.isEmpty())

        v.editHubQuery("x".repeat(250)); assertEquals(200, v.state.value.hubQuery.length)
        v.chooseHubSource("all"); assertEquals("all", v.state.value.hubSource)
    }

    @Test
    fun previewScanReceiptAndInstallGates() = runHubTest { d ->
        val calls = mutableListOf<String>()
        var scanAllowed = true
        val c = client { req, _ ->
            calls += "${req.method} ${req.url.encodedPath}"
            when (req.url.encodedPath) {
                "/v1/skills/hub/preview" -> 200 to """{"identifier":"p.1","name":"p1","trust_level":"community"}"""
                "/v1/skills/hub/scan" -> 200 to (if (scanAllowed) """{"identifier":"p.1","trust_level":"community","verdict":"safe","allowed":true,"findings":[],"scan_id":"rcpt_1"}"""
                    else """{"identifier":"p.1","trust_level":"community","verdict":"dangerous","allowed":false,"findings":[],"scan_id":""}""")
                else -> 404 to """{"detail":{"code":"not_found"}}"""
            }
        }
        val v = vm(c, d)
        v.scanHub(); v.installHub(); advanceUntilIdle()
        assertTrue(calls.isEmpty() && !v.state.value.canInstall)

        v.previewHub(HubMetadataDto(identifier = "p.1", name = "p1", trust_level = "community")); advanceUntilIdle()
        assertEquals("p.1", v.state.value.hubPreview?.identifier); assertFalse(v.state.value.canInstall)
        v.installHub(); advanceUntilIdle(); assertEquals(1, calls.size) // Before-scan install call makes no request

        scanAllowed = false; v.scanHub(); advanceUntilIdle() // Blocked scan from actual API (allowed=false)
        assertFalse(v.state.value.scan!!.allowed); assertFalse(v.state.value.canInstall)
        v.acknowledgeRisk(true); assertFalse(v.state.value.canInstall)
        val beforeBlocked = calls.size
        v.installHub(); advanceUntilIdle(); assertEquals(beforeBlocked, calls.size)

        scanAllowed = true; v.scanHub(); advanceUntilIdle() // Allowed scan requires acknowledgement for community
        val scan = v.state.value.scan!!
        assertEquals("rcpt_1", scan.scan_id)
        assertTrue(scan.allowed && v.state.value.needsAcknowledgement && !v.state.value.canInstall)
        v.installHub(); advanceUntilIdle(); assertEquals(beforeBlocked + 1, calls.size)

        v.acknowledgeRisk(true); assertTrue(v.state.value.canInstall)
        assertFalse(v.state.value.copy(scan = scan.copy(identifier = "mismatch")).canInstall)
        assertFalse(v.state.value.copy(scan = scan.copy(scan_id = "")).canInstall)
        val builtinSafe = v.state.value.copy(scan = scan.copy(trust_level = "builtin", verdict = "safe", findings = emptyList()), acknowledged = false)
        assertTrue(!builtinSafe.needsAcknowledgement && builtinSafe.canInstall)
        assertTrue(builtinSafe.copy(forceAcknowledgement = true).needsAcknowledgement)

        // 2 asserts: trusted+caution and any findings needs ack
        assertTrue(v.state.value.copy(scan = scan.copy(trust_level = "trusted", verdict = "caution", findings = emptyList())).needsAcknowledgement)
        assertTrue(v.state.value.copy(scan = scan.copy(trust_level = "trusted", verdict = "safe", findings = listOf(HubFindingDto()))).needsAcknowledgement)
    }

    @Test
    fun riskNotAcknowledgedKeepsReceiptAndRetryWorks() = runHubTest { d ->
        val installBodies = mutableListOf<String>()
        val c = client { req, body ->
            when (req.url.encodedPath) {
                "/v1/skills/hub/preview" -> 200 to """{"identifier":"p.r","name":"pr","trust_level":"trusted"}"""
                "/v1/skills/hub/scan" -> 200 to """{"identifier":"p.r","trust_level":"trusted","verdict":"safe","allowed":true,"findings":[],"scan_id":"rcpt_keep"}"""
                "/v1/skills/hub/install" -> {
                    installBodies += body
                    val ack = Json.parseToJsonElement(body).jsonObject["acknowledge_risk"]?.jsonPrimitive?.booleanOrNull == true
                    if (!ack) 409 to """{"detail":{"code":"risk_not_acknowledged"}}"""
                    else 200 to """{"action_id":"act_retry","status":"started"}"""
                }
                "/v1/skills/hub/actions/act_retry" -> 200 to """{"running":false,"exit_code":0,"log_tail":"done"}"""
                "/v1/skills" -> 200 to """[]"""
                else -> 404 to """{"detail":{"code":"not_found"}}"""
            }
        }
        val v = vm(c, d)
        v.previewHub(HubMetadataDto(identifier = "p.r", name = "pr", trust_level = "trusted")); advanceUntilIdle()
        v.scanHub(); advanceUntilIdle()
        assertFalse(v.state.value.needsAcknowledgement); assertTrue(v.state.value.canInstall)
        v.installHub(); advanceUntilIdle()

        val firstBody = Json.parseToJsonElement(installBodies.first()).jsonObject
        assertEquals("rcpt_keep", firstBody["scan_id"]?.jsonPrimitive?.content)
        assertEquals(false, firstBody["acknowledge_risk"]?.jsonPrimitive?.booleanOrNull)

        assertEquals("rcpt_keep", v.state.value.scan?.scan_id)
        assertTrue(v.state.value.forceAcknowledgement && !v.state.value.acknowledged && !v.state.value.canInstall)
        assertEquals("请阅读扫描结果并勾选风险确认后重试安装。", v.state.value.error)

        v.installHub(); advanceUntilIdle(); assertEquals(1, installBodies.size)

        v.acknowledgeRisk(true); assertTrue(v.state.value.canInstall)
        v.installHub(); advanceUntilIdle(); assertEquals(2, installBodies.size)
        val secondBody = Json.parseToJsonElement(installBodies.last()).jsonObject
        assertEquals("rcpt_keep", secondBody["scan_id"]?.jsonPrimitive?.content)
        assertEquals(true, secondBody["acknowledge_risk"]?.jsonPrimitive?.booleanOrNull)
        assertNull(v.state.value.scan); assertTrue(v.state.value.action?.succeeded == true)
    }

    @Test
    fun installFailuresResetReceiptAndState() = runHubTest { d ->
        for (errCode in listOf("scan_expired", "scan_mismatch", "scan_required", "scan_blocked")) {
            val c = client { req, _ ->
                when (req.url.encodedPath) {
                    "/v1/skills/hub/preview" -> 200 to """{"identifier":"p.f","name":"pf","trust_level":"community"}"""
                    "/v1/skills/hub/scan" -> 200 to """{"identifier":"p.f","trust_level":"community","verdict":"safe","allowed":true,"findings":[],"scan_id":"rcpt_f"}"""
                    "/v1/skills/hub/install" -> 409 to """{"detail":{"code":"${errCode}"}}"""
                    else -> 404 to """{"detail":{"code":"not_found"}}"""
                }
            }
            val v = vm(c, d)
            v.previewHub(HubMetadataDto(identifier = "p.f", name = "pf", trust_level = "community")); advanceUntilIdle()
            v.scanHub(); advanceUntilIdle()
            v.acknowledgeRisk(true); v.installHub(); advanceUntilIdle()
            assertNull(v.state.value.scan)
            assertFalse(v.state.value.acknowledged || v.state.value.forceAcknowledgement || v.state.value.canInstall)
            assertEquals(skillErrorMessage(BridgeRequestException(409, errCode)), v.state.value.error)
        }
    }

    @Test
    fun pollingCompletionFailureNullExitAndTimeoutWithVirtualTime() = runHubTest { d ->
        var runState = true; var exitVal: Int? = null; var actionPolls = 0; var skillsRefreshes = 0
        val c = client { req, _ ->
            when (req.url.encodedPath) {
                "/v1/skills/hub/preview" -> 200 to """{"identifier":"p.p","name":"pp","trust_level":"trusted"}"""
                "/v1/skills/hub/scan" -> 200 to """{"identifier":"p.p","trust_level":"trusted","verdict":"safe","allowed":true,"findings":[],"scan_id":"rcpt_p"}"""
                "/v1/skills/hub/install" -> 200 to """{"action_id":"act_p","status":"started"}"""
                "/v1/skills/hub/actions/act_p" -> { actionPolls++; 200 to """{"running":${runState},"exit_code":${exitVal ?: "null"},"log_tail":"line ${actionPolls}"}""" }
                "/v1/skills" -> { skillsRefreshes++; 200 to """[{"name":"pp","enabled":true,"usage":0,"provenance":"hub"}]""" }
                else -> 404 to """{"detail":{"code":"not_found"}}"""
            }
        }
        val v = vm(c, d)
        val prep = {
            v.previewHub(HubMetadataDto(identifier = "p.p", name = "pp", trust_level = "trusted"))
            advanceUntilIdle()
            v.scanHub(); advanceUntilIdle()
        }

        // 1. Success: poll at t0 returns running=true; duplicate install/scan/refresh dropped; running=false at 1500ms
        prep(); runState = true; exitVal = null; actionPolls = 0; skillsRefreshes = 0
        v.installHub()
        runCurrent() // executes up to delay(1500L) after first poll
        assertEquals(1, actionPolls); assertTrue(v.state.value.loading && v.state.value.action?.running == true)
        v.installHub(); v.scanHub(); v.refresh(); runCurrent()
        assertEquals(1, actionPolls); assertEquals(0, skillsRefreshes)

        runState = false; exitVal = 0
        advanceTimeBy(1_500L); advanceUntilIdle()
        assertEquals(2, actionPolls); assertEquals(1, skillsRefreshes)
        val countAfterSuccess = actionPolls
        advanceTimeBy(5_000L); assertEquals(countAfterSuccess, actionPolls) // no more polling requests
        assertFalse(v.state.value.loading); assertEquals("line 2", v.state.value.action?.logTail)
        assertTrue(v.state.value.action?.succeeded == true); assertEquals("安装成功。", v.state.value.action?.message)
        assertEquals("pp", v.state.value.skills.single().name)

        // 2. Failure exit_code != 0
        prep(); runState = false; exitVal = 2; actionPolls = 0
        v.installHub(); advanceUntilIdle()
        val countAfterFail = actionPolls
        advanceTimeBy(5_000L); assertEquals(countAfterFail, actionPolls)
        assertFalse(v.state.value.loading); assertFalse(v.state.value.action?.succeeded == true)
        assertEquals("安装失败（退出码：2）。", v.state.value.action?.message)

        // 3. Null exit_code is failure
        prep(); runState = false; exitVal = null; actionPolls = 0
        v.installHub(); advanceUntilIdle()
        val countAfterNull = actionPolls
        advanceTimeBy(5_000L); assertEquals(countAfterNull, actionPolls)
        assertFalse(v.state.value.loading); assertFalse(v.state.value.action?.succeeded == true)
        assertEquals("安装失败（退出码：未知）。", v.state.value.action?.message)

        // 4. Timeout past 300,000 ms stops polling and retains log tail
        prep(); runState = true; exitVal = null; actionPolls = 0
        v.installHub(); advanceTimeBy(300_000L); advanceUntilIdle()
        val countAfterTimeout = actionPolls
        advanceTimeBy(10_000L); assertEquals(countAfterTimeout, actionPolls) // no requests after timeout
        assertFalse(v.state.value.loading); assertFalse(v.state.value.action?.running == true)
        assertTrue(v.state.value.action?.logTail?.isNotBlank() == true)
        assertTrue(v.state.value.action?.message?.contains("超时") == true)
    }

    @Test
    fun concurrencyGatingAndViewModelStoreCancellation() = runHubTest { d ->
        var searchReqs = 0; var pollCount = 0
        val c = client { req, _ ->
            when (req.url.encodedPath) {
                "/v1/skills/hub/search" -> { searchReqs++; 200 to """{"results":[]}""" }
                "/v1/skills/hub/preview" -> 200 to """{"identifier":"p.c","name":"pc","trust_level":"trusted"}"""
                "/v1/skills/hub/scan" -> 200 to """{"identifier":"p.c","trust_level":"trusted","verdict":"safe","allowed":true,"findings":[],"scan_id":"rcpt_c"}"""
                "/v1/skills/hub/install" -> 200 to """{"action_id":"act_c","status":"started"}"""
                "/v1/skills/hub/actions/act_c" -> { pollCount++; 200 to """{"running":true,"exit_code":null,"log_tail":"..."}""" }
                else -> 404 to """{"detail":{"code":"not_found"}}"""
            }
        }
        val v = vm(c, d)
        v.editHubQuery("q1"); v.searchHub(); assertTrue(v.state.value.loading)
        v.searchHub() // dropped while loading
        v.editHubQuery("q2") // blocked while loading
        assertEquals("q1", v.state.value.hubQuery); advanceUntilIdle()
        assertEquals(1, searchReqs)

        val store = ViewModelStore()
        val f = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm(c, d) as T
        }
        val storeVm = ViewModelProvider(store, f)[SkillsViewModel::class.java]
        storeVm.previewHub(HubMetadataDto(identifier = "p.c", name = "pc", trust_level = "trusted")); advanceUntilIdle()
        storeVm.scanHub(); advanceUntilIdle()
        storeVm.installHub(); advanceTimeBy(100)
        assertEquals(1, pollCount)
        store.clear() // cancels viewModelScope
        advanceUntilIdle(); assertEquals(1, pollCount)
    }

    @Test
    fun uninstallHubRequiresConfirmationAndChecksProvenance() = runHubTest { d ->
        var uninstallCalls = 0
        val c = client { req, _ ->
            when {
                req.url.encodedPath.endsWith("/content") -> 200 to """{"name":"${req.url.encodedPath.split("/")[3]}","content":"# skill"}"""
                req.url.encodedPath == "/v1/skills/hub/uninstall" -> { uninstallCalls++; 200 to """{"action_id":"act_u","status":"started"}""" }
                req.url.encodedPath == "/v1/skills/hub/actions/act_u" -> 200 to """{"running":false,"exit_code":0,"log_tail":"ok"}"""
                req.url.encodedPath == "/v1/skills" -> 200 to "[]"
                else -> 404 to """{"detail":{"code":"not_found"}}"""
            }
        }
        val v = vm(c, d)
        v.select(SkillDto("bundled.skill", enabled = true, usage = 0, provenance = "bundled")); advanceUntilIdle()
        assertEquals("bundled.skill", v.state.value.selected?.name)
        v.requestUninstall(); assertFalse(v.state.value.confirmingUninstall)
        v.uninstallHub(); assertEquals(0, uninstallCalls)

        v.select(SkillDto("hub.skill", enabled = true, usage = 0, provenance = "hub")); advanceUntilIdle()
        assertEquals("hub.skill", v.state.value.selected?.name)
        v.uninstallHub(); assertEquals(0, uninstallCalls) // no confirmation -> no-op

        v.requestUninstall(); assertTrue(v.state.value.confirmingUninstall)
        v.cancelUninstall(); assertFalse(v.state.value.confirmingUninstall)

        v.requestUninstall(); assertTrue(v.state.value.confirmingUninstall)
        v.uninstallHub()
        runCurrent() // coroutine launches and executes confirmingUninstall = false
        assertFalse(v.state.value.confirmingUninstall); advanceUntilIdle()

        assertEquals(1, uninstallCalls); assertEquals("卸载成功。", v.state.value.action?.message)
    }

    @Test
    fun skillErrorsMapAllNewCodesToChineseMessages() = listOf(
        Triple(409, "scan_required", "扫描凭证已失效，请重新进行安全扫描。"), Triple(409, "scan_expired", "扫描结果已过期，请重新进行安全扫描。"),
        Triple(409, "scan_mismatch", "扫描结果与当前技能或设备不匹配，请重新扫描。"), Triple(409, "scan_blocked", "已被安全扫描拦截，无法安装，请重新扫描查看结果。"),
        Triple(409, "risk_not_acknowledged", "请阅读扫描结果并勾选风险确认后重试安装。"), Triple(409, "skill_not_hub", "只能卸载社区安装的技能；该技能可能已被移除，请刷新列表。"),
        Triple(404, "hub_action_not_found", "找不到此操作，可能已过期或 Bridge 已重启，请刷新技能列表。"), Triple(404, "skill_not_found", "技能不存在，可能已被移除，请刷新列表。"),
        Triple(400, "invalid_skill_request", "技能请求无效，请检查名称和内容后重试。"), Triple(400, "hermes_rejected", "Hermes 拒绝了修改，请检查技能格式或写入保护。"),
        Triple(503, "hermes_unavailable", "Mac 上的 Hermes 暂时不可用。")
    ).forEach { (status, code, msg) -> assertEquals(msg, skillErrorMessage(BridgeRequestException(status, code))) }
}
