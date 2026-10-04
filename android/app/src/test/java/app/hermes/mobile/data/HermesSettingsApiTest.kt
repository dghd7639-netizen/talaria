package app.hermes.mobile.data

import app.hermes.mobile.pairing.DeviceConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HermesSettingsApiTest {
    @Test
    fun realSettingsShapesParseMissingOptionalFieldsAndMaskedOverview() {
        val api = api { request -> response(request, if (request.url.encodedPath.endsWith("profiles"))
            """{"profiles":[{"name":"work","is_default":false},{"name":"default","is_default":true}]}"""
            else SETTINGS) }
        assertEquals(listOf(SettingsProfileDto("work", false), SettingsProfileDto("default", true)),
            api.settingsProfiles().profiles)
        val settings = api.settings("default")
        assertEquals("default", settings.profile)
        assertEquals(listOf(listOf("Model", "gpt-example"), listOf("API Key", "****1234")),
            settings.overview.single().rows)
        val mode = settings.editable[0]
        assertEquals(listOf("manual", "smart", "off"), mode.choices)
        assertEquals(listOf(JsonPrimitive("off")), mode.confirmValues)
        assertNull(mode.min)
        assertNull(mode.max)
        settings.editable.drop(1).forEach { assertNull(it.choices); assertTrue(it.confirmValues.isEmpty()) }
        assertEquals(JsonPrimitive(60), settings.editable[1].value)
        assertEquals(10, settings.editable[1].min)
        assertEquals(600, settings.editable[1].max)
        assertEquals(listOf(SettingsProviderDto("openrouter", "OpenRouter", false)), settings.providers)
    }

    @Test
    fun explicitNullOptionalFieldsAlsoParse() {
        val item = Json.decodeFromString<EditableSettingDto>("""{"key":"approvals.timeout","type":"integer",
            "value":60,"choices":null,"min":null,"max":null,"confirm_values":[]}""")
        assertNull(item.choices)
        assertNull(item.min)
        assertNull(item.max)
    }

    @Test
    fun requestsEncodeProfileAndSegmentsAndPreserveValueTypesAndConfirm() {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val api = api { request ->
            requests += request
            bodies += okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            response(request, when {
                request.url.encodedPath.endsWith("profiles") -> """{"profiles":[]}"""
                request.url.encodedPath.endsWith("/key") -> """{"slug":"provider/ 中文?#","authenticated":false}"""
                request.method == "PUT" -> """{"key":"approvals.timeout","type":"integer","value":80,
                    "min":10,"max":600,"confirm_values":[]}"""
                else -> SETTINGS
            })
        }
        val profile = "team & 中文+?#"
        api.settingsProfiles()
        api.settings(profile)
        assertEquals(JsonPrimitive(80), api.updateSetting(profile, "approvals.timeout", JsonPrimitive(90)).value)
        api.updateSetting(profile, "approvals.mode", JsonPrimitive("off"), confirm = true)
        api.updateSetting(profile, "approvals.mode", JsonPrimitive("smart"), confirm = false)
        val key = ProviderApiKey("test-key")
        assertFalse(api.saveProviderKey(profile, "provider/ 中文?#", key).authenticated)
        assertTrue(key.cleared)
        assertEquals(null, requests.first().url.encodedQuery)
        requests.drop(1).forEach { assertEquals(profile, it.url.queryParameter("profile")) }
        assertEquals("/v1/settings/providers/provider%2F%20%E4%B8%AD%E6%96%87%3F%23/key",
            requests.last().url.encodedPath)
        assertEquals(listOf("GET", "GET", "PUT", "PUT", "PUT", "PUT"), requests.map { it.method })
        requests.forEach { assertEquals("Bearer secret", it.header("Authorization")) }
        assertEquals(Json.parseToJsonElement("""{"value":90,"confirm":false}"""), Json.parseToJsonElement(bodies[2]))
        assertEquals(Json.parseToJsonElement("""{"value":"off","confirm":true}"""), Json.parseToJsonElement(bodies[3]))
        assertEquals(Json.parseToJsonElement("""{"value":"smart","confirm":false}"""), Json.parseToJsonElement(bodies[4]))
        assertEquals(mapOf("api_key" to JsonPrimitive("test-key")), Json.parseToJsonElement(bodies[5]).jsonObject)
    }

    @Test
    fun errorsKeepStableCodesWithoutServerMessagesAndAlwaysClearKeys() {
        val sentinel = "private-key-sentinel"
        var status = 400
        var code = "invalid_settings_request"
        val api = api { request -> response(request,
            """{"detail":{"code":"$code","message":"$sentinel"}}""", status) }
        listOf(400 to "invalid_settings_request", 400 to "profile_not_supported", 404 to "profile_not_found", 404 to "provider_not_found",
            409 to "settings_key_save_unsupported", 503 to "hermes_unavailable", 400 to sentinel).forEach { (http, stableCode) ->
            status = http; code = stableCode
            val key = ProviderApiKey(sentinel)
            assertFalse(key.toString().contains(sentinel))
            try { api.saveProviderKey("work", "openrouter", key); fail("Expected Bridge error") }
            catch (error: BridgeRequestException) {
                assertEquals(http, error.status)
                assertEquals(stableCode.takeUnless { it == sentinel }, error.code)
                assertFalse(error.message.orEmpty().contains(sentinel))
            }
            assertTrue(key.cleared)
        }
        status = 409; code = "confirm_required"
        try { api.updateSetting("default", "approvals.mode", JsonPrimitive("off")); fail("Expected confirmation") }
        catch (error: BridgeRequestException) { assertEquals("confirm_required", error.code) }
    }

    @Test
    fun keyTransportDecoderAndValidationFailuresAreSanitizedAndCleared() {
        val sentinel = "private-key-sentinel"
        listOf<(Request) -> Response>(
            { throw IOException(sentinel) },
            { response(it, sentinel) },
            { response(it, """{"slug":"openrouter","authenticated":"$sentinel"}""") },
        ).forEach { handler ->
            val key = ProviderApiKey(sentinel)
            try { api(handler).saveProviderKey("default", "openrouter", key); fail("Expected failure") }
            catch (error: IOException) {
                assertFalse(error.toString().contains(sentinel))
                assertNull(error.cause)
            }
            assertTrue(key.cleared)
        }
        var calls = 0
        val api = api { calls++; response(it, "{}") }
        listOf("", " \t", "bad\nkey", "bad\rkey", "bad\u0000key", "x".repeat(4097),
            "synthetic-密钥-key", "synthetic-é-key", "synthetic-😀-key", "bad key", "bad\tkey", "bad\u001fkey",
            "bad\u007fkey").forEach { invalid ->
            val key = ProviderApiKey(invalid)
            try { api.saveProviderKey("default", "openrouter", key); fail("Expected validation failure") }
            catch (_: IOException) { }
            assertTrue(key.cleared)
        }
        listOf(JsonPrimitive(true), JsonPrimitive(60.0)).forEach { invalid ->
            try { api.updateSetting("default", "approvals.timeout", invalid); fail("Expected validation failure") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, calls)
    }

    @Test
    fun secretWritesDoNotFollowRedirects() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/redirected")))
            server.enqueue(MockResponse().setBody("""{"slug":"openrouter","authenticated":true}"""))
            val api = BridgeApi(DeviceConnection(server.url("/"), "secret"), OkHttpClient(), Json)
            val key = ProviderApiKey("test-key")
            try { api.saveProviderKey("default", "openrouter", key); fail("Expected redirect rejection") }
            catch (error: BridgeRequestException) { assertEquals(307, error.status) }
            assertEquals(1, server.requestCount)
            assertTrue(key.cleared)
        }
    }

    private fun api(handler: (Request) -> Response): BridgeApi = BridgeApi(
        DeviceConnection("https://example.test/".toHttpUrl(), "secret"),
        OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build(), Json,
    )

    private fun response(request: Request, body: String, status: Int = 200): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Response")
            .body(body.toResponseBody("application/json".toMediaType())).build()

    companion object {
        const val SETTINGS = """{"profile":"default","overview":[{"title":"Model","rows":[["Model","gpt-example"],["API Key","****1234"]]}],
            "editable":[{"key":"approvals.mode","type":"string","choices":["manual","smart","off"],"confirm_values":["off"],"value":"manual"},
            {"key":"approvals.timeout","type":"integer","min":10,"max":600,"confirm_values":[],"value":60},
            {"key":"curator.stale_after_days","type":"integer","min":1,"max":365,"confirm_values":[],"value":14},
            {"key":"curator.archive_after_days","type":"integer","min":1,"max":365,"confirm_values":[],"value":30}],
            "providers":[{"slug":"openrouter","name":"OpenRouter","authenticated":false}]}"""
    }
}
