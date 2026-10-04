package app.hermes.mobile.management.settings

import androidx.lifecycle.ViewModelStore
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.data.HermesSettingsApiTest
import app.hermes.mobile.data.HermesSettingsDto
import app.hermes.mobile.data.ProviderApiKey
import app.hermes.mobile.data.SettingsProfileDto
import app.hermes.mobile.data.SettingsProfilesDto
import app.hermes.mobile.data.SettingsProviderDto
import app.hermes.mobile.data.SettingsProviderSavedDto
import app.hermes.mobile.pairing.DeviceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HermesSettingsViewModelTest {
    @Test
    fun defaultProfileSelectionSwitchReloadAndRefreshPreserveSelection() = withViewModel { vm, backend ->
        vm.refresh()
        vm.refresh()
        vm.selectProfile("work")
        assertTrue(vm.state.value.busy)
        advanceUntilIdle()
        assertEquals("default", vm.state.value.profile)
        assertEquals(listOf("/v1/settings/profiles", "/v1/settings"), backend.requests.map { it.url.encodedPath })
        assertEquals("default", backend.requests.last().url.queryParameter("profile"))
        vm.selectProfile("work")
        assertNull(vm.state.value.settings)
        advanceUntilIdle()
        assertEquals("work", vm.state.value.settings?.profile)
        vm.refresh()
        advanceUntilIdle()
        assertEquals("work", vm.state.value.profile)
        assertEquals(3L, vm.state.value.contentRevision)
        assertEquals("work", backend.requests.last().url.queryParameter("profile"))
        val count = backend.requests.size
        vm.selectProfile("missing")
        vm.selectProfile("work")
        advanceUntilIdle()
        assertEquals(count, backend.requests.size)
    }

    @Test
    fun firstProfileFallbackEmptyListAndRemovedSelectionAreHandled() = withViewModel { vm, backend ->
        backend.profiles = listOf(SettingsProfileDto("work", false))
        vm.refresh()
        advanceUntilIdle()
        assertEquals("work", vm.state.value.profile)
        backend.profiles = listOf(SettingsProfileDto("default", true))
        vm.refresh()
        advanceUntilIdle()
        assertEquals("default", vm.state.value.profile)
        backend.profiles = emptyList()
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.profile)
        assertNull(vm.state.value.settings)
        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun profileErrorsClearOldDataAndRefreshRecovers() = withViewModel { vm, backend ->
        vm.refresh()
        advanceUntilIdle()
        backend.code = "profile_not_found"
        vm.selectProfile("work")
        advanceUntilIdle()
        assertNull(vm.state.value.settings)
        assertEquals("档案不存在，可能已被移除，请刷新列表。", vm.state.value.error)
        assertFalse(vm.state.value.loading)
        backend.code = null
        vm.refresh()
        advanceUntilIdle()
        assertEquals("work", vm.state.value.settings?.profile)
        assertNull(vm.state.value.error)
    }

    @Test
    fun invalidIntegersAndCuratorPairsNeverReachBridge() = withViewModel { vm, backend ->
        vm.refresh()
        advanceUntilIdle()
        val count = backend.requests.size
        listOf("" to "请输入整数。", "60.0" to "请输入整数。", "2147483648" to "请输入整数。",
            "9" to "数值超出允许范围", "601" to "数值超出允许范围").forEach { (input, error) ->
            vm.saveInteger("approvals.timeout", input)
            assertEquals(error, vm.state.value.error)
        }
        vm.saveInteger("curator.stale_after_days", "31")
        assertEquals("归档天数不能小于闲置天数。", vm.state.value.error)
        vm.saveInteger("curator.archive_after_days", "13")
        assertEquals("归档天数不能小于闲置天数。", vm.state.value.error)
        listOf("curator.stale_after_days", "curator.archive_after_days").forEach { key ->
            listOf("0", "366").forEach { vm.saveInteger(key, it); assertEquals("数值超出允许范围", vm.state.value.error) }
        }
        advanceUntilIdle()
        assertEquals(count, backend.requests.size)
        val entries = vm.state.value.settings!!.editable
        val timeout = entries.first { it.key == "approvals.timeout" }
        assertEquals("数值超出允许范围", settingValidationError(timeout.copy(min = 50, max = 100), "49", entries))
        assertNull(settingValidationError(timeout.copy(min = 50, max = 100), "100", entries))
        assertEquals("设置不可编辑，请刷新后重试。", settingValidationError(timeout.copy(min = null), "60", entries))
        assertEquals("设置不可编辑，请刷新后重试。", settingValidationError(timeout.copy(key = "unknown"), "60", entries))
    }

    @Test
    fun integerSavesUseBoundariesActualReadbackAndGateOtherControls() = withViewModel { vm, backend ->
        vm.refresh()
        advanceUntilIdle()
        // A server can normalize the requested value back to the previous one.
        backend.returnedValue = JsonPrimitive(60)
        vm.saveInteger("approvals.timeout", " 90 ")
        assertTrue(vm.state.value.saving)
        assertTrue(vm.state.value.busy)
        vm.refresh()
        vm.selectProfile("work")
        vm.chooseApprovalMode("off")
        vm.saveInteger("curator.stale_after_days", "20")
        advanceUntilIdle()
        assertEquals("default", vm.state.value.profile)
        assertFalse(vm.state.value.pendingOff)
        assertEquals(1, backend.bodies.size)
        assertEquals(JsonPrimitive(90), backend.bodies.single()["value"])
        assertEquals(JsonPrimitive(false), backend.bodies.single()["confirm"])
        assertEquals(JsonPrimitive(60), vm.state.value.settings!!.editable[1].value)
        assertEquals("已保存", vm.state.value.message)
        assertEquals(2L, vm.state.value.contentRevision)
        assertFalse(vm.state.value.busy)
        assertEquals(listOf(listOf("审批超时", "60")), vm.state.value.settings!!.overview.single().rows)
        backend.returnedValue = null
        listOf("approvals.timeout" to "10", "approvals.timeout" to "600", "curator.stale_after_days" to "1",
            "curator.archive_after_days" to "1", "curator.archive_after_days" to "365",
            "curator.stale_after_days" to "365").forEach { (key, input) ->
            vm.saveInteger(key, input)
            advanceUntilIdle()
            assertNull(vm.state.value.error)
            assertEquals(JsonPrimitive(input.toInt()), vm.state.value.settings!!.editable.first { it.key == key }.value)
        }
    }

    @Test
    fun offNeedsConfirmationIncludingAlreadyOffAndCancelNeverWrites() = withViewModel { vm, backend ->
        backend.settings = backend.settings.copy(editable = backend.settings.editable.map {
            if (it.key == "approvals.mode") it.copy(value = JsonPrimitive("off")) else it
        })
        vm.refresh()
        advanceUntilIdle()
        assertEquals(JsonPrimitive("off"), vm.state.value.settings!!.editable.first().value)
        vm.chooseApprovalMode("off")
        assertTrue(vm.state.value.pendingOff)
        vm.selectProfile("work")
        vm.refresh()
        advanceUntilIdle()
        assertTrue(backend.bodies.isEmpty())
        assertEquals("default", vm.state.value.profile)
        vm.cancelOff()
        vm.confirmOff()
        advanceUntilIdle()
        assertTrue(backend.bodies.isEmpty())
        vm.chooseApprovalMode("smart")
        advanceUntilIdle()
        assertEquals(JsonPrimitive("smart"), backend.bodies.last()["value"])
        assertEquals(JsonPrimitive(false), backend.bodies.last()["confirm"])
        vm.chooseApprovalMode("off")
        assertEquals(JsonPrimitive("smart"), vm.state.value.settings!!.editable.first().value)
        vm.confirmOff()
        vm.confirmOff()
        advanceUntilIdle()
        assertFalse(vm.state.value.pendingOff)
        assertEquals(2, backend.bodies.size)
        assertEquals(JsonPrimitive("off"), backend.bodies.last()["value"])
        assertEquals(JsonPrimitive(true), backend.bodies.last()["confirm"])
        assertEquals(JsonPrimitive("off"), vm.state.value.settings!!.editable.first().value)
    }

    @Test
    fun failedSavesKeepServerValueAndAllowRetry() = withViewModel { vm, backend ->
        vm.refresh()
        advanceUntilIdle()
        backend.code = "invalid_setting_value"
        vm.saveInteger("approvals.timeout", "90")
        advanceUntilIdle()
        assertEquals(JsonPrimitive(60), vm.state.value.settings!!.editable[1].value)
        assertEquals("数值超出允许范围", vm.state.value.error)
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.saving)
        backend.code = "confirm_required"
        vm.chooseApprovalMode("off")
        vm.confirmOff()
        advanceUntilIdle()
        assertEquals("此操作需要确认，请确认后重试。", vm.state.value.error)
        backend.code = null
        vm.chooseApprovalMode("off")
        vm.confirmOff()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(JsonPrimitive("off"), vm.state.value.settings!!.editable.first().value)
    }

    @Test
    fun providersSortAuthenticatedFirstAndFilterNameOrSlug() {
        val providers = listOf(SettingsProviderDto("zeta", "Zeta", false), SettingsProviderDto("openrouter", "路由服务", true),
            SettingsProviderDto("alpha", "Alpha", false), SettingsProviderDto("beta", "Beta", true))
        val state = HermesSettingsState(settings = Json.decodeFromString<HermesSettingsDto>(HermesSettingsApiTest.SETTINGS)
            .copy(providers = providers))
        assertEquals(listOf("beta", "openrouter", "alpha", "zeta"), state.filteredProviders.map { it.slug })
        assertEquals(listOf("openrouter"), state.copy(providerQuery = "  ROUTER  ").filteredProviders.map { it.slug })
        assertEquals(listOf("openrouter"), state.copy(providerQuery = "路由").filteredProviders.map { it.slug })
        assertEquals(listOf("alpha"), state.copy(providerQuery = "ALPHA").filteredProviders.map { it.slug })
        assertTrue(state.copy(providerQuery = "missing").filteredProviders.isEmpty())
    }

    @Test
    fun keyClearsOnSuccessOrFailureAndBadgeUsesActualResponse() = withViewModel { vm, backend ->
        vm.refresh()
        advanceUntilIdle()
        val sentinel = "private-key-sentinel"
        listOf(false, true).forEach { authenticated ->
            backend.authenticated = authenticated
            val key = ProviderApiKey(sentinel)
            vm.saveProviderKey("openrouter", key)
            assertTrue(vm.state.value.saving)
            assertFalse(vm.state.value.toString().contains(sentinel))
            advanceUntilIdle()
            assertTrue(key.cleared)
            assertEquals(authenticated, vm.state.value.settings!!.providers.single().authenticated)
            assertEquals("已保存", vm.state.value.message)
            assertFalse(vm.state.value.toString().contains(sentinel))
            assertEquals(mapOf("api_key" to JsonPrimitive(sentinel)), backend.bodies.last())
        }
        backend.code = "settings_key_save_unsupported"
        val rejected = ProviderApiKey(sentinel)
        vm.saveProviderKey("openrouter", rejected)
        advanceUntilIdle()
        assertTrue(rejected.cleared)
        assertNull(vm.state.value.message)
        assertEquals("该提供商暂不支持保存密钥，请在 Mac 上配置。", vm.state.value.error)
        assertTrue(vm.state.value.settings!!.providers.single().authenticated)
        backend.code = null
        backend.transportError = IOException(sentinel)
        val failed = ProviderApiKey(sentinel)
        vm.saveProviderKey("openrouter", failed)
        advanceUntilIdle()
        assertTrue(failed.cleared)
        assertEquals("无法连接 Hermes", vm.state.value.error)
        assertFalse(vm.state.value.toString().contains(sentinel))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun keyClearsForRejectedRequestsAndCancellationBeforeCoroutineStarts() = withViewModel { vm, backend ->
        val unloaded = ProviderApiKey("test-key")
        vm.saveProviderKey("openrouter", unloaded)
        assertTrue(unloaded.cleared)
        vm.refresh()
        advanceUntilIdle()
        val unknown = ProviderApiKey("test-key")
        vm.saveProviderKey("missing", unknown)
        assertTrue(unknown.cleared)
        vm.chooseApprovalMode("off")
        val busy = ProviderApiKey("test-key")
        vm.saveProviderKey("openrouter", busy)
        assertTrue(busy.cleared)
        vm.cancelOff()
        val cancelled = ProviderApiKey("test-key")
        vm.saveProviderKey("openrouter", cancelled)
        ViewModelStore().apply { put("settings", vm); clear() }
        advanceUntilIdle()
        assertTrue(cancelled.cleared)
        assertTrue(backend.bodies.isEmpty())
    }

    @Test
    fun nonPrintableAsciiKeysAreClearedWithValidationMessageWithoutRequests() = withViewModel { vm, backend ->
        vm.refresh()
        advanceUntilIdle()
        val count = backend.requests.size
        listOf("synthetic-密钥-key", "synthetic-é-key", "synthetic-😀-key", "synthetic-\u200b-key",
            "bad key", "bad\tkey", "bad\u001fkey", "bad\u007fkey").forEach { invalid ->
            val key = ProviderApiKey(invalid)
            vm.saveProviderKey("openrouter", key)
            advanceUntilIdle()
            assertTrue(key.cleared)
            assertEquals("密钥只能包含英文字母、数字和符号，请重新从服务商后台复制。", vm.state.value.error)
            assertNull(vm.state.value.message)
            assertFalse(vm.state.value.busy)
            assertFalse(vm.state.value.toString().contains(invalid))
            assertEquals(count, backend.requests.size)
        }
        assertTrue(backend.bodies.isEmpty())
    }

    @Test
    fun overviewRefreshFailurePreservesSuccessfulSaveAndResponseBadge() = withViewModel { vm, backend ->
        vm.refresh()
        advanceUntilIdle()
        backend.failOverviewAfterSave = true
        val key = ProviderApiKey("test-key")
        vm.saveProviderKey("openrouter", key)
        advanceUntilIdle()
        assertTrue(key.cleared)
        assertTrue(vm.state.value.settings!!.providers.single().authenticated)
        assertEquals("已保存", vm.state.value.message)
        assertEquals("Mac 上的 Hermes 暂时不可用。", vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun errorCodesHaveChineseMessagesAndNeverExposePrivateDetails() {
        mapOf("confirm_required" to "此操作需要确认，请确认后重试。",
            "invalid_setting_value" to "数值超出允许范围", "setting_not_editable" to "设置不可编辑，请刷新后重试。",
            "invalid_settings_request" to "设置请求无效，请检查输入后重试。",
            "profile_not_found" to "档案不存在，可能已被移除，请刷新列表。",
            "profile_not_supported" to "该档案名与 Hermes 保留名冲突，请在 Mac 上改名后再设置。",
            "provider_not_found" to "提供商不存在，可能已被移除，请刷新列表。",
            "settings_key_save_unsupported" to "该提供商暂不支持保存密钥，请在 Mac 上配置。",
            "hermes_rejected" to "Hermes 拒绝了操作，请在 Mac 上检查配置。",
            "hermes_unavailable" to "Mac 上的 Hermes 暂时不可用。").forEach { (code, message) ->
            assertEquals(message, settingsErrorMessage(BridgeRequestException(400, code)))
        }
        assertEquals("无法连接 Hermes", settingsErrorMessage(IOException("private-key-sentinel")))
    }

    private fun withViewModel(block: suspend TestScope.(HermesSettingsViewModel, Backend) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val backend = Backend()
            val client = OkHttpClient.Builder().addInterceptor { backend.respond(it.request()) }.build()
            val vm = HermesSettingsViewModel(BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"),
                client, Json), dispatcher)
            block(vm, backend)
        } finally { Dispatchers.resetMain() }
    }

    private class Backend {
        var profiles = listOf(SettingsProfileDto("work", false), SettingsProfileDto("default", true))
        var settings = Json.decodeFromString<HermesSettingsDto>(HermesSettingsApiTest.SETTINGS)
        var code: String? = null
        var returnedValue: JsonPrimitive? = null
        var authenticated = true
        var transportError: IOException? = null
        var failOverviewAfterSave = false
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<JsonObject>()

        fun respond(request: Request): Response {
            requests += request
            transportError?.let { throw it }
            val body = if (request.body != null) Json.parseToJsonElement(
                okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject.also { bodies += it } else null
            val error = code ?: "hermes_unavailable".takeIf {
                failOverviewAfterSave && bodies.isNotEmpty() && request.method == "GET"
            }
            val payload = when {
                error != null -> """{"detail":{"code":"$error","message":"private-key-sentinel"}}"""
                request.url.encodedPath.endsWith("/profiles") -> Json.encodeToString(SettingsProfilesDto(profiles))
                request.url.encodedPath.endsWith("/key") -> {
                    settings = settings.copy(providers = settings.providers.map { it.copy(authenticated = authenticated) })
                    Json.encodeToString(SettingsProviderSavedDto("openrouter", authenticated))
                }
                request.method == "PUT" -> {
                    val key = request.url.pathSegments.last()
                    val entry = settings.editable.first { it.key == key }.copy(value = returnedValue ?: body!!.getValue("value").jsonPrimitive)
                    settings = settings.copy(editable = settings.editable.map { if (it.key == key) entry else it },
                        overview = settings.overview.map { it.copy(rows = listOf(listOf("审批超时", entry.value.content))) })
                    Json.encodeToString(entry)
                }
                else -> Json.encodeToString(settings.copy(profile = request.url.queryParameter("profile")!!))
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (error == null) 200 else 400)
                .message("Response").body(payload.toResponseBody("application/json".toMediaType())).build()
        }
    }
}
