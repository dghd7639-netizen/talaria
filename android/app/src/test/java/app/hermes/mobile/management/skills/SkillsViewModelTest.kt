package app.hermes.mobile.management.skills

import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.data.SkillDto
import app.hermes.mobile.pairing.DeviceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsViewModelTest {
    @Test
    fun searchMatchesNamesAndDescriptionsAndGroupsByCategory() {
        val report = SkillDto("report.v2", "总结每日内容", "productivity", true, 7, "agent")
        val other = SkillDto("other", null, null, false, 0, "bundled")
        val web = SkillDto("web", "Search online", "research", true, 1, "hub")
        val state = SkillsState(skills = listOf(web, report, other))
        assertEquals(listOf(report), state.copy(query = " REPORT.V2 ").filteredSkills)
        assertEquals(listOf(report), state.copy(query = "每日").filteredSkills)
        assertEquals(listOf(web), state.copy(query = "ONLINE").filteredSkills)
        assertTrue(state.copy(query = "missing").filteredSkills.isEmpty())
        assertEquals(3, state.copy(query = "  ").filteredSkills.size)
        assertEquals(setOf("productivity", "research", "未分类"), state.groups.keys)
        assertEquals(listOf(other), state.groups["未分类"])
        assertEquals("未分类", other.copy(category = " ").categoryLabel)
    }

    @Test
    fun loadToggleRollbackAndSaveKeepOwnStateAndPreventConcurrentRequests() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var fail = false
            val requests = mutableListOf<String>()
            val bodies = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += "${request.method} ${request.url.encodedPath}"
                bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
                val body = when {
                    fail -> """{"detail":{"code":"hermes_unavailable"}}"""
                    request.url.encodedPath == "/v1/skills" -> """[
                        {"name":"report.v2","description":"Report","category":null,
                         "enabled":true,"usage":7,"provenance":"hub"}]
                    """
                    request.url.encodedPath.endsWith("/enabled") ->
                        """{"ok":true,"name":"report.v2","enabled":false}"""
                    request.method == "GET" -> """{"name":"report.v2","content":"original"}"""
                    else -> """{"ok":true,"name":"report.v2"}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (fail) 503 else 200).message("Response")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val vm = SkillsViewModel(BridgeApi(
                DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json), dispatcher)
            fail = true
            vm.refresh()
            vm.refresh()
            assertTrue(vm.state.value.loading)
            advanceUntilIdle()
            assertEquals(1, requests.size)
            assertEquals("Mac 上的 Hermes 暂时不可用。", vm.state.value.error)
            assertFalse(vm.state.value.loading)
            fail = false
            vm.refresh()
            advanceUntilIdle()
            assertNull(vm.state.value.error)
            assertEquals(7, vm.state.value.skills.single().usage)
            vm.search("report")
            assertEquals(1, vm.state.value.filteredSkills.size)
            val original = vm.state.value.skills.single()

            fail = true
            vm.setEnabled(original, false)
            assertFalse(vm.state.value.skills.single().enabled)
            vm.setEnabled(original, true)
            vm.select(original)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(3, requests.size)
            assertTrue(vm.state.value.skills.single().enabled)
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
            assertNull(vm.state.value.selected)

            fail = false
            vm.setEnabled(original, false)
            advanceUntilIdle()
            assertFalse(vm.state.value.skills.single().enabled)
            vm.select(vm.state.value.skills.single())
            advanceUntilIdle()
            assertEquals("original", vm.state.value.content)
            assertFalse(vm.state.value.editing)
            vm.startEditing()
            val draft = "---\nname: report.v2\ndescription: Report\n---\n修改内容\n"
            vm.edit(draft)
            val beforeSave = requests.size
            vm.save() // No write before explicit confirmation.
            advanceUntilIdle()
            assertEquals(beforeSave, requests.size)
            vm.requestSave()
            assertTrue(vm.state.value.confirmingSave)
            vm.cancelSave()
            assertEquals(draft, vm.state.value.draft)
            assertFalse(vm.state.value.confirmingSave)
            vm.requestSave()
            fail = true
            vm.save()
            vm.save()
            vm.edit("must not replace the in-flight draft")
            vm.back()
            vm.cancelEditing()
            advanceUntilIdle()
            assertEquals(beforeSave + 1, requests.size)
            assertEquals(draft, vm.state.value.draft)
            assertEquals("original", vm.state.value.content)
            assertTrue(vm.state.value.editing)
            assertFalse(vm.state.value.confirmingSave)
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)

            fail = false
            vm.requestSave()
            vm.save()
            advanceUntilIdle()
            assertEquals(draft, vm.state.value.content)
            assertEquals(draft, Json.parseToJsonElement(bodies.last()).jsonObject["content"]?.jsonPrimitive?.content)
            assertFalse(vm.state.value.editing)
            assertNull(vm.state.value.error)
            assertEquals("技能内容已保存。", vm.state.value.notice)
            vm.startEditing()
            vm.edit("discard")
            vm.cancelEditing()
            assertEquals(draft, vm.state.value.draft)
            vm.back()
            assertNull(vm.state.value.selected)
            assertEquals("report", vm.state.value.query)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun localValidationRejectsInvalidDraftsWithoutRequestsAndCountsUtf8Bytes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"name":"skill","content":"original"}"""
                        .toResponseBody("application/json".toMediaType())).build()
            }.build()
            val vm = SkillsViewModel(BridgeApi(
                DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json), dispatcher)
            vm.select(SkillDto("skill", enabled = true, usage = 0, provenance = "bundled"))
            advanceUntilIdle()
            vm.startEditing()
            listOf(
                "" to "技能内容不能为空或只有空白。",
                " \n\t" to "技能内容不能为空或只有空白。",
                "a\u0000b" to "技能内容不能包含 NUL（空字符）。",
                "a".repeat(204801) to "技能内容不能超过 200 KiB（UTF-8）。",
                "中".repeat(68267) to "技能内容不能超过 200 KiB（UTF-8）。",
            ).forEach { (content, error) ->
                vm.edit(content)
                vm.requestSave()
                vm.save()
                advanceUntilIdle()
                assertEquals(error, vm.state.value.error)
                assertEquals(content, vm.state.value.draft)
                assertTrue(vm.state.value.editing)
                assertFalse(vm.state.value.confirmingSave)
                assertEquals(1, requests)
            }
            val boundary = "中".repeat(68266) + "ab"
            assertEquals(204800, boundary.toByteArray(Charsets.UTF_8).size)
            vm.edit(boundary)
            vm.requestSave()
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.confirmingSave)
            assertNull(SkillsState(draft = "a".repeat(204800)).validation)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun skillErrorsHaveChineseMessages() {
        listOf(
            Triple(404, "skill_not_found", "技能不存在，可能已被移除，请刷新列表。"),
            Triple(400, "invalid_skill_request", "技能请求无效，请检查名称和内容后重试。"),
            Triple(400, "hermes_rejected", "Hermes 拒绝了修改，请检查技能格式或写入保护。"),
            Triple(503, "hermes_unavailable", "Mac 上的 Hermes 暂时不可用。"),
        ).forEach { (status, code, message) ->
            assertEquals(message, skillErrorMessage(BridgeRequestException(status, code)))
        }
    }
}
