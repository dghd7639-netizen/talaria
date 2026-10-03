package app.hermes.mobile.management.cron

import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.CronJobDto
import app.hermes.mobile.data.CronScheduleDto
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
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import okio.Buffer

@OptIn(ExperimentalCoroutinesApi::class)
class CronViewModelTest {
    @Test
    fun wheelsAndAdvancedEditorValidateBeforeRequestsAndPreserveSavedSchedules() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val bodies = mutableListOf<String>()
            var scheduleJson = """{"kind":"interval","minutes":120}"""
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                request.body?.let { body -> bodies += Buffer().also { body.writeTo(it) }.readUtf8() }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"id":"j","name":"旧名","prompt":"内容","schedule":$scheduleJson}"""
                        .toResponseBody("application/json".toMediaType())).build()
            }.build()
            val clock = Clock.fixed(Instant.parse("2026-12-01T01:00:00Z"), ZoneId.of("Asia/Taipei"))
            val vm = CronViewModel(BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json), dispatcher, clock)
            vm.create()
            assertEquals("0 9 * * *", vm.state.value.schedule)
            assertEquals(ScheduleType.DAILY, vm.state.value.picker?.type)
            vm.edit(prompt = "内容")
            val once = SchedulePickerState(type = ScheduleType.ONCE, date = LocalDate.of(2026, 12, 1), hour = 8)
            vm.editPicker(once)
            vm.save()
            assertNotNull(vm.state.value.error)
            assertTrue(bodies.isEmpty())
            vm.editPicker(once.copy(type = ScheduleType.WEEKLY, weekdays = emptySet()))
            vm.save()
            assertEquals("请至少选择一个星期。", vm.state.value.error)
            assertTrue(bodies.isEmpty())
            vm.edit(schedule = "2026-12-01T09:00。")
            vm.save()
            assertTrue(vm.state.value.error.orEmpty().contains("半角"))
            assertTrue(bodies.isEmpty())
            vm.edit(schedule = "2026-12-01T08:59:59+08:00")
            vm.save()
            assertNotNull(vm.state.value.error)
            assertTrue(bodies.isEmpty())
            vm.edit(schedule = " every 30m ")
            vm.save()
            advanceUntilIdle()
            assertEquals("every 30m", Json.parseToJsonElement(bodies.single()).jsonObject["schedule"]?.jsonPrimitive?.content)
            bodies.clear()

            assertEquals(2, vm.state.value.picker?.interval)
            assertEquals(IntervalUnit.HOURS, vm.state.value.picker?.unit)
            assertEquals("every 120m", vm.state.value.schedule)
            vm.editPicker(requireNotNull(vm.state.value.picker).copy(interval = 3))
            assertEquals("every 3h", vm.state.value.schedule)
            vm.editPicker(requireNotNull(vm.state.value.picker).copy(interval = 2))
            assertTrue(vm.state.value.updates().isEmpty())
            vm.edit(name = "新名")
            vm.save()
            advanceUntilIdle()
            assertEquals(Json.parseToJsonElement("""{"updates":{"name":"新名"}}"""), Json.parseToJsonElement(bodies.single()))
            bodies.clear()

            scheduleJson = """{"kind":"cron","expr":"*/5 * * * *"}"""
            vm.select(CronJobDto(id = "j"))
            advanceUntilIdle()
            assertNull(vm.state.value.picker)
            assertFalse(vm.state.value.advancedSchedule)
            vm.useAdvancedSchedule()
            assertEquals("*/5 * * * *", vm.state.value.schedule)
            assertTrue(vm.state.value.updates().isEmpty())
            vm.edit(schedule = "  */5 * * * *  ")
            assertTrue(vm.state.value.updates().isEmpty())
            vm.edit(schedule = "0 9 * * １")
            vm.save()
            assertNotNull(vm.state.value.error)
            assertTrue(bodies.isEmpty())
            vm.edit(schedule = " 0 9 * * 1 ")
            vm.save()
            advanceUntilIdle()
            assertEquals(Json.parseToJsonElement("""{"updates":{"schedule":"0 9 * * 1"}}"""), Json.parseToJsonElement(bodies.single()))

            scheduleJson = """{"kind":"once","run_at":"2026-12-01T09:00:00+08:00"}"""
            vm.select(CronJobDto(id = "j"))
            advanceUntilIdle()
            assertNull(vm.state.value.picker)
            assertTrue(vm.state.value.updates().isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun dtoInputsMapToPickerWithoutChangingOriginalSpelling() {
        val date = LocalDate.of(2026, 12, 1)
        val schedules = listOf(
            CronScheduleDto(kind = "cron", expr = "0 9 * * *") to "0 9 * * *",
            CronScheduleDto(kind = "cron", expr = "15 8 * * 1,5") to "15 8 * * 1,5",
            CronScheduleDto(kind = "once", runAt = "2026-12-01T09:00:00") to "2026-12-01T09:00",
            CronScheduleDto(kind = "interval", minutes = 120.0) to "every 2h",
        )
        schedules.forEach { (schedule, expected) ->
            val job = CronJobDto(schedule = schedule)
            val picker = parseSchedulePicker(job.scheduleInput, date)
            assertEquals(expected, picker?.toSchedule())
            assertTrue(CronState(selected = job, schedule = job.scheduleInput, picker = picker).updates().isEmpty())
        }
        val spaced = CronJobDto(schedule = CronScheduleDto(kind = "cron", expr = " 0 9 * * * "))
        assertTrue(CronState(selected = spaced, schedule = spaced.scheduleInput).updates().isEmpty())
    }

    @Test
    fun editableSchedulesAndPartialUpdatesPreserveUntouchedValues() {
        val once = CronJobDto(schedule = CronScheduleDto(kind = "once", display = "once at tomorrow", runAt = "2026-12-01T09:00:00+08:00"))
        assertEquals("2026-12-01T09:00:00+08:00", once.scheduleInput)
        assertEquals("every 30m", CronJobDto(schedule = CronScheduleDto(kind = "interval", minutes = 30.0)).scheduleInput)
        val job = CronJobDto(name = "旧名", prompt = "保持", schedule = CronScheduleDto(kind = "cron", expr = "0 9 * * *"))
        val state = CronState(selected = job, name = "新名", prompt = "保持", schedule = job.scheduleInput)
        assertEquals(Json.parseToJsonElement("""{"name":"新名"}"""), state.updates())
        assertTrue(state.copy(name = "旧名").updates().isEmpty())
        assertNotNull(CronState(creating = true, schedule = "every 30m").validation)
        assertNotNull(state.copy(schedule = "").validation)
        assertFalse(job.copy(state = "paused").active)
        assertFalse(job.copy(state = "completed").active)
        assertFalse(job.copy(enabled = false).active)
    }

    @Test
    fun requestsUpdateOwnStatePreventDuplicatesAndRecoverFromErrors() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var fail = false
            val requests = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += "${request.method} ${request.url.encodedPath}"
                val body = when {
                    fail -> """{"detail":{"code":"hermes_unavailable"}}"""
                    request.method == "DELETE" -> """{"ok":true}"""
                    request.url.encodedPath.endsWith("/runs") -> """{"runs":[],"limit":20}"""
                    request.url.encodedPath.endsWith("/pause") -> """{"id":"j","enabled":false,"state":"paused"}"""
                    request.url.encodedPath.endsWith("/trigger") -> """{"id":"j","enabled":false,"state":"completed"}"""
                    request.method == "GET" && request.url.encodedPath.endsWith("/jobs") -> """[{"id":"j"}]"""
                    else -> """{"id":"j","name":"新任务","prompt":"内容","schedule":{"kind":"interval","minutes":30}}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (fail) 503 else 200).message("Response")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json)
            val vm = CronViewModel(api, dispatcher)
            vm.refresh()
            vm.refresh()
            assertTrue(vm.state.value.loading)
            advanceUntilIdle()
            assertEquals(1, requests.size)
            assertEquals("j", vm.state.value.jobs.single().id)
            vm.setActive(vm.state.value.jobs.single(), false)
            advanceUntilIdle()
            assertFalse(vm.state.value.jobs.single().active)
            vm.select(vm.state.value.jobs.single())
            advanceUntilIdle()
            vm.edit(name = "修改")
            fail = true
            vm.save()
            advanceUntilIdle()
            assertEquals("修改", vm.state.value.name)
            assertEquals("Mac 上的 Hermes 暂时不可用。", vm.state.value.error)
            assertFalse(vm.state.value.loading)
            fail = false
            vm.save()
            advanceUntilIdle()
            assertNull(vm.state.value.error)
            vm.loadRuns()
            advanceUntilIdle()
            assertEquals(emptyList<app.hermes.mobile.data.CronRunDto>(), vm.state.value.runs)
            vm.edit(prompt = "未保存内容")
            vm.trigger()
            advanceUntilIdle()
            assertEquals("未保存内容", vm.state.value.prompt)
            assertEquals("completed", vm.state.value.selected?.state)
            assertTrue(vm.state.value.jobs.isEmpty())
            vm.back()
            vm.create()
            vm.edit(name = "新任务", prompt = "内容", schedule = "every 30m", paused = true)
            vm.save()
            advanceUntilIdle()
            assertFalse(vm.state.value.creating)
            assertEquals(1, vm.state.value.jobs.size)
            vm.delete()
            advanceUntilIdle()
            assertFalse(vm.state.value.editing)
            assertTrue(vm.state.value.jobs.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
