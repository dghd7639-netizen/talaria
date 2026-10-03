package app.hermes.mobile.management.tools

import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.data.ToolsetDto
import app.hermes.mobile.pairing.DeviceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolsViewModelTest {
    @Test
    fun groupsUsePlatformLabelsAndKeepUnavailableToolsVisible() {
        val tool = ToolsetDto(name = "web", label = "网页", platformLabel = "本机", enabled = false)
        val fallback = ToolsetDto(name = "other", platform = "remote", enabled = true)
        val unnamed = ToolsetDto(enabled = false)
        val state = ToolsState(toolsets = listOf(tool, fallback, unnamed))
        assertEquals(listOf(tool), state.groups["本机"])
        assertEquals(listOf(fallback), state.groups["remote"])
        assertEquals(listOf(unnamed), state.groups["未分类"])
    }

    @Test
    fun loadBothListsToggleOptimisticallyRollbackAndGateAllRequests() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var fail = false
            var failMcpLoad = false
            var empty = false
            val requests = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += "${request.method} ${request.url.encodedPath}"
                val rejected = fail || (failMcpLoad && request.url.encodedPath == "/v1/mcp/servers")
                val body = when {
                    rejected -> """{"detail":{"code":"hermes_unavailable"}}"""
                    request.method == "PUT" -> {
                        val name = if (request.url.encodedPath.startsWith("/v1/tools/")) "web" else "local"
                        """{"ok":true,"name":"$name","enabled":false}"""
                    }
                    request.url.encodedPath == "/v1/tools/toolsets" ->
                        """[{"name":"web","label":"网页","enabled":true,"available":false,"configured":false}]"""
                    empty -> """{"servers":[]}"""
                    else -> """{"servers":[{"name":"local","enabled":true,"transport":"stdio","command_name":"uvx"}]}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (rejected) 503 else 200).message("Response")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val vm = ToolsViewModel(BridgeApi(
                DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json), dispatcher)
            failMcpLoad = true
            vm.refresh()
            vm.refresh()
            assertTrue(vm.state.value.loading)
            advanceUntilIdle()
            assertEquals(2, requests.size)
            assertFalse(vm.state.value.loaded)
            assertEquals("Mac 上的 Hermes 暂时不可用。", vm.state.value.error)
            failMcpLoad = false
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.state.value.loaded)
            assertEquals("web", vm.state.value.toolsets.single().name)
            assertEquals("local", vm.state.value.servers.single().name)
            val tool = vm.state.value.toolsets.single()
            val server = vm.state.value.servers.single()
            fail = true
            vm.setToolsetEnabled(tool, false)
            assertFalse(vm.state.value.toolsets.single().enabled)
            vm.setToolsetEnabled(tool, true)
            vm.setMcpEnabled(server, false)
            vm.requestTest(server)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(5, requests.size)
            assertTrue(vm.state.value.toolsets.single().enabled)
            assertTrue(vm.state.value.servers.single().enabled)
            assertNull(vm.state.value.pendingTest)
            assertFalse(vm.state.value.loading)
            vm.setMcpEnabled(server, false)
            assertFalse(vm.state.value.servers.single().enabled)
            vm.setToolsetEnabled(tool, false)
            vm.setMcpEnabled(server, true)
            advanceUntilIdle()
            assertEquals(6, requests.size)
            assertTrue(vm.state.value.servers.single().enabled)
            assertNotNull(vm.state.value.error)
            fail = false
            vm.setToolsetEnabled(tool, false)
            advanceUntilIdle()
            assertFalse(vm.state.value.toolsets.single().enabled)
            assertNull(vm.state.value.error)
            vm.setMcpEnabled(server, false)
            advanceUntilIdle()
            assertFalse(vm.state.value.servers.single().enabled)
            assertNull(vm.state.value.error)
            empty = true
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.state.value.servers.isEmpty())
            assertTrue(vm.state.value.loaded)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun testsRequireFirstConfirmationSupportCancelAndShowSuccessOrSafeFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var fail = false
            var malformedSuccess = false
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                val request = chain.request()
                val body = when {
                    request.method == "POST" && fail -> """{"detail":{"code":"hermes_rejected","message":"secret raw error"}}"""
                    request.method == "POST" && malformedSuccess -> """{"ok":false}"""
                    request.method == "POST" -> """{"ok":true,"tool_count":7,"prompts":2,"resources":0}"""
                    request.url.encodedPath == "/v1/tools/toolsets" -> "[]"
                    else -> """{"servers":[{"name":"local","enabled":false}]}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (request.method == "POST" && fail) 400 else 200).message("Response")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val vm = ToolsViewModel(BridgeApi(
                DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json), dispatcher)
            vm.refresh()
            advanceUntilIdle()
            val server = vm.state.value.servers.single()
            vm.confirmTest()
            vm.requestTest(server)
            assertEquals("local", vm.state.value.pendingTest)
            vm.refresh()
            vm.setMcpEnabled(server, true)
            advanceUntilIdle()
            assertEquals(2, requests)
            vm.cancelTest()
            assertNull(vm.state.value.pendingTest)
            vm.confirmTest()
            advanceUntilIdle()
            assertEquals(2, requests)
            vm.requestTest(server)
            vm.confirmTest()
            assertEquals("local", vm.state.value.testing)
            assertTrue(vm.state.value.loading)
            vm.confirmTest()
            vm.requestTest(server)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(3, requests)
            assertEquals("连接成功：7 个工具、2 个提示、0 个资源", vm.state.value.testResults["local"])
            assertNull(vm.state.value.testing)
            assertFalse(vm.state.value.loading)
            // Reopening the dialog refreshes data, but does not reset this session confirmation.
            vm.refresh()
            advanceUntilIdle()
            fail = true
            vm.requestTest(server)
            assertNull(vm.state.value.pendingTest)
            assertNull(vm.state.value.testResults["local"])
            assertEquals("local", vm.state.value.testing)
            advanceUntilIdle()
            assertEquals(6, requests)
            assertEquals("连接失败：Hermes 拒绝了操作，请在 Mac 上检查配置。", vm.state.value.testResults["local"])
            assertFalse(vm.state.value.loading)
            assertNull(vm.state.value.testing)
            fail = false
            malformedSuccess = true
            vm.requestTest(server)
            advanceUntilIdle()
            assertTrue(vm.state.value.testResults.getValue("local").startsWith("连接失败："))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun managementErrorsHaveChineseMessagesWithoutUpstreamDetails() {
        val expected = mapOf(
            "toolset_not_found" to "工具集不存在，可能已被移除，请刷新列表。",
            "mcp_not_found" to "MCP 服务器不存在，可能已被移除，请刷新列表。",
            "invalid_toolset_request" to "工具集请求无效，请刷新列表后重试。",
            "invalid_mcp_request" to "MCP 请求无效，请刷新列表后重试。",
            "hermes_rejected" to "Hermes 拒绝了操作，请在 Mac 上检查配置。",
            "hermes_unavailable" to "Mac 上的 Hermes 暂时不可用。",
        )
        expected.forEach { (code, message) ->
            assertEquals(message, toolsErrorMessage(BridgeRequestException(400, code)))
        }
    }
}
