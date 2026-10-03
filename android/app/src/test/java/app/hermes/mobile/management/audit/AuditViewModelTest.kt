package app.hermes.mobile.management.audit

import app.hermes.mobile.data.BridgeApi
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
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModelTest {
    @Test
    fun initialLoadPaginationAndRefreshPreserveOrderAndPreventDuplicateRequests() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val cursors = mutableListOf<String?>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val cursor = chain.request().url.queryParameter("before_id")
                cursors += cursor
                val body = if (cursor == null) """{"items":[{"id":3},{"id":2}],"next_before_id":2}"""
                    else """{"items":[{"id":1}],"next_before_id":null}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val vm = AuditViewModel(BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json), dispatcher)
            vm.loadMore()
            assertTrue(cursors.isEmpty())
            vm.refresh()
            vm.refresh()
            assertTrue(vm.state.value.loading)
            advanceUntilIdle()
            assertEquals(listOf(3L, 2L), vm.state.value.items.map { it.id })
            assertEquals(2L, vm.state.value.nextBeforeId)
            assertFalse(vm.state.value.loading)
            vm.loadMore()
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(listOf(3L, 2L, 1L), vm.state.value.items.map { it.id })
            assertNull(vm.state.value.nextBeforeId)
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(listOf(null, "2"), cursors)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(3L, 2L), vm.state.value.items.map { it.id })
            assertEquals(listOf(null, "2", null), cursors)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedPageAndRefreshKeepItemsAndCursorAndAllowRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var fail = false
            val cursors = mutableListOf<String?>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val cursor = chain.request().url.queryParameter("before_id")
                cursors += cursor
                val body = when {
                    fail -> """{"detail":{"code":"audit_storage_unavailable"}}"""
                    cursor == null -> """{"items":[{"id":2}],"next_before_id":2}"""
                    else -> """{"items":[{"id":1}],"next_before_id":null}"""
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (fail) 503 else 200).message("Response")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val vm = AuditViewModel(BridgeApi(DeviceConnection("https://example.test/".toHttpUrl(), "secret"), client, Json), dispatcher)
            vm.refresh()
            advanceUntilIdle()
            val items = vm.state.value.items
            fail = true
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(items, vm.state.value.items)
            assertEquals(2L, vm.state.value.nextBeforeId)
            assertEquals("Mac 暂时无法读取操作记录，请稍后重试。", vm.state.value.error)
            assertFalse(vm.state.value.loading)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(items, vm.state.value.items)
            assertEquals(2L, vm.state.value.nextBeforeId)
            fail = false
            vm.loadMore()
            assertNull(vm.state.value.error)
            advanceUntilIdle()
            assertEquals(listOf(2L, 1L), vm.state.value.items.map { it.id })
            assertEquals(listOf(null, "2", null, "2"), cursors)
            assertFalse(vm.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun labelsAndOffsetTimestampsHaveReadableFallbacks() {
        assertEquals("删除定时任务", auditActionLabel("cron.delete"))
        assertEquals("future.action", auditActionLabel("future.action"))
        assertEquals("2026-09-29 16:00:00", auditTimestamp("2026-09-29T08:00:00.123456+00:00", ZoneId.of("Asia/Taipei")))
        assertEquals("2026-09-28 23:00:00", auditTimestamp("2026-09-29T01:00:00+02:00", ZoneId.of("UTC")))
        assertEquals("bad timestamp", auditTimestamp("bad timestamp"))
        assertEquals("时间未知", auditTimestamp(""))
    }
}
