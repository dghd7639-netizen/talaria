package app.hermes.mobile.pairing

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRepositoryTest {
    @Test
    fun rejectsNonHttpsBridge() {
        val repository = PairingRepository(OkHttpClient(), Json) {}

        assertThrows(PairingException::class.java) {
            repository.complete(
                """{"version":1,"base_url":"http://mac:8788","token":"once"}""",
                "Pixel 9",
            )
        }
    }

    @Test
    fun rejectsUntrustedCleartextHost() {
        val repository = PairingRepository(OkHttpClient(), Json) {}

        assertThrows(PairingException::class.java) {
            repository.complete(
                """{"version":1,"base_url":"http://mac:8788","token":"once"}""",
                "emulator",
            )
        }
    }

    @Test
    fun savesOnlyExchangedDeviceSecret() {
        var requestBody = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            requestBody = buffer.readUtf8()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"device_secret":"device-secret"}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()
        var saved: DeviceConnection? = null
        val repository = PairingRepository(client, Json) { saved = it }

        repository.complete(
            """{"version":1,"base_url":"https://mac-mini.tailnet.ts.net","token":"once"}""",
            "Pixel 9",
        )

        assertEquals("device-secret", saved?.deviceSecret)
        assertFalse(requestBody.contains("device-secret"))
        assertEquals(true, requestBody.contains("once"))
    }

    @Test
    fun replacementPairingUsesNormalReplaceContract() {
        var requestBody = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestBody = okio.Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"device_secret\":\"device-secret\"}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val repository = PairingRepository(
            client,
            Json,
            PairingTransportValidator {},
        ) {}

        repository.complete(
            """{"version":1,"base_url":"http://10.0.2.2:8788/","token":"once"}""",
            "emulator",
            replace = true,
        )

        assertTrue(requestBody.contains("\"replace\":true"))
    }

    @Test
    fun alreadyPairedMacIsReportedSoTheUserCanChooseToReplace() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(409)
                .message("Conflict")
                .body("""{"detail":"A device is already paired; set replace=true to replace it"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val repository = PairingRepository(client, Json) {}

        assertThrows(PairingConflictException::class.java) {
            repository.complete(
                """{"version":1,"base_url":"https://mac-mini.tailnet.ts.net","token":"once"}""",
                "Pixel 9",
            )
        }
    }
}
