package app.hermes.mobile.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class PairingPayload(
    val version: Int,
    @SerialName("base_url") val baseUrl: String,
    val token: String,
)

data class DeviceConnection(
    val baseUrl: HttpUrl,
    val deviceSecret: String,
)

@Serializable
private data class PairingCompleteRequest(
    val token: String,
    @SerialName("device_name") val deviceName: String,
    val replace: Boolean = false,
)

@Serializable
private data class PairingCompleteResponse(
    @SerialName("device_secret") val deviceSecret: String,
)

open class PairingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The Mac already has a paired device; completing again with ``replace = true`` swaps it out. */
class PairingConflictException : PairingException("Mac 上已经配对了另一台设备")

internal fun interface PairingTransportValidator {
    fun validate(url: HttpUrl)
}

private val secureTransportValidator = PairingTransportValidator { url ->
    if (!url.isHttps) throw PairingException("Hermes 必须使用 HTTPS")
}

internal class PairingRepository(
    private val client: OkHttpClient,
    private val json: Json,
    private val transportValidator: PairingTransportValidator = secureTransportValidator,
    private val save: (DeviceConnection) -> Unit,
) {
    fun complete(rawPayload: String, deviceName: String, replace: Boolean = false): DeviceConnection {
        val payload = try {
            json.decodeFromString<PairingPayload>(rawPayload)
        } catch (error: Exception) {
            throw PairingException("配对二维码无效", error)
        }
        val baseUrl = payload.baseUrl.toHttpUrlOrNull()
            ?: throw PairingException("Hermes 地址无效")
        if (payload.version != 1) throw PairingException("不支持的配对版本")
        transportValidator.validate(baseUrl)
        if (payload.token.isBlank() || payload.token.length > 512) {
            throw PairingException("配对令牌无效")
        }
        if (deviceName.isBlank() || deviceName.length > 128) {
            throw PairingException("设备名称无效")
        }

        val requestBody = json.encodeToString(
            PairingCompleteRequest(payload.token, deviceName.trim(), replace),
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1/pairing/complete").build())
            .post(requestBody)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: Exception) {
            throw PairingException("无法连接 Hermes", error)
        }
        response.use {
            if (it.code == 409) throw PairingConflictException()
            if (!it.isSuccessful) throw PairingException("Hermes 拒绝配对")
            val body = it.body?.string() ?: throw PairingException("Hermes 返回为空")
            val secret = try {
                json.decodeFromString<PairingCompleteResponse>(body).deviceSecret
            } catch (error: Exception) {
                throw PairingException("Hermes 返回无效", error)
            }
            if (secret.isBlank() || secret.length > 512) {
                throw PairingException("设备密钥无效")
            }
            return DeviceConnection(baseUrl, secret).also(save)
        }
    }
}
