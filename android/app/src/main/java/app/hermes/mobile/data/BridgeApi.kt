package app.hermes.mobile.data

import app.hermes.mobile.pairing.DeviceConnection
import app.hermes.mobile.threads.ChatMessage
import app.hermes.mobile.threads.PendingApproval
import app.hermes.mobile.threads.ThreadItem
import app.hermes.mobile.threads.ModelOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.RequestBody.Companion.toRequestBody

class BridgeApi(
    private val connection: DeviceConnection,
    private val client: OkHttpClient,
    private val json: Json,
    private val onAuthenticationExpired: () -> Unit = {},
) {
    // Management payloads evolve independently of the Android client.
    private val cronJson = Json(json) { ignoreUnknownKeys = true; encodeDefaults = false }
    private val hubJson = Json(cronJson) { coerceInputValues = true }

    private val botGroupsJson = Json(cronJson) { coerceInputValues = true }

    fun botGroups(): BotGroupsDto =
        cronExecute(Request.Builder().url(url("v1", "bot-groups")), decoder = botGroupsJson)

    fun botGroupRoom(roomId: String, limit: Int = 200): BotGroupDetailDto {
        require(roomId.isNotBlank() && roomId != "." && roomId != ".." && limit in 1..500)
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/bot-groups")
            .addPathSegment(roomId).addQueryParameter("limit", limit.toString()).build()
        return cronExecute(Request.Builder().url(target), decoder = botGroupsJson)
    }

    private val phoneJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        coerceInputValues = true
    }

    private val phoneWriteClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun phoneCapabilities(): PhoneCapabilities {
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/groups").addPathSegment("capabilities").build()
        return cronExecute<PhoneCapabilitiesDto>(Request.Builder().url(target), decoder = phoneJson).toDomain()
    }

    fun phoneRooms(limit: Int = 50, offset: Long = 0): PhoneRoomPage {
        require(limit in 1..200 && offset >= 0)
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/groups")
            .addQueryParameter("include_disbanded", "false")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        return cronExecute<PhoneRoomPageDto>(Request.Builder().url(target), decoder = phoneJson).toDomain()
    }

    fun phoneRoom(roomId: String): PhoneRoom {
        require(roomId.isNotBlank() && roomId != "." && roomId != "..")
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/groups")
            .addPathSegment(roomId).build()
        return cronExecute<PhoneRoomDto>(Request.Builder().url(target), decoder = phoneJson).toDomain()
    }

    fun phoneEvents(roomId: String, sinceSeq: Long = 0, limit: Int = 50): PhoneEventPage {
        require(roomId.isNotBlank() && roomId != "." && roomId != "..")
        require(sinceSeq >= 0 && limit in 1..200)
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/groups")
            .addPathSegment(roomId).addPathSegment("events")
            .addQueryParameter("since_seq", sinceSeq.toString())
            .addQueryParameter("limit", limit.toString()).build()
        return cronExecute<PhoneEventPageDto>(Request.Builder().url(target), decoder = phoneJson).toDomain()
    }

    fun createPhoneRoom(name: String, members: List<PhoneMemberInput>): PhoneRoom {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty() && trimmedName.codePointCount(0, trimmedName.length) in 1..200)
        require(name.codePoints().noneMatch { Character.getType(it) in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt()) })
        require(members.size in 2..6)
        require(members.all { it.profile.isNotBlank() })
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/groups").build()
        val requestBody = phoneJson.encodeToString(
            PhoneCreateRoomRequest(
                name = trimmedName,
                members = members.map {
                    PhoneCreateMemberRequest(
                        profile = it.profile.trim(),
                        handle = it.handle,
                        display_name = it.displayName,
                    )
                },
            )
        ).toRequestBody(JSON_MEDIA)
        return cronExecute<PhoneRoomDto>(
            Request.Builder().url(target).post(requestBody),
            decoder = phoneJson,
            httpClient = phoneWriteClient,
        ).toDomain()
    }

    fun sendPhoneMessage(roomId: String, text: String): PhoneSendResult {
        require(roomId.isNotBlank() && roomId != "." && roomId != "..")
        require(text.isNotBlank() && text.codePointCount(0, text.length) <= 8000 && '\u0000' !in text)
        require(text.indices.all { i ->
            when {
                text[i].isHighSurrogate() -> i + 1 < text.length && text[i + 1].isLowSurrogate()
                text[i].isLowSurrogate() -> i > 0 && text[i - 1].isHighSurrogate()
                else -> true
            }
        })
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/groups")
            .addPathSegment(roomId).addPathSegment("messages").build()
        val requestBody = phoneJson.encodeToString(PhoneMessageRequest(text)).toRequestBody(JSON_MEDIA)
        return cronExecute<PhoneSendResultDto>(
            Request.Builder().url(target).post(requestBody),
            decoder = phoneJson,
            httpClient = phoneWriteClient,
        ).toDomain()
    }

    fun stopPhoneRoom(roomId: String): PhoneStopResult {
        require(roomId.isNotBlank() && roomId != "." && roomId != "..")
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/groups")
            .addPathSegment(roomId).addPathSegment("stop").build()
        return cronExecute<PhoneStopResultDto>(
            Request.Builder().url(target).post(ByteArray(0).toRequestBody(null)),
            decoder = phoneJson,
            httpClient = phoneWriteClient,
        ).toDomain()
    }

    fun disbandPhoneRoom(roomId: String): PhoneDisbandResult {
        require(roomId.isNotBlank() && roomId != "." && roomId != "..")
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/groups")
            .addPathSegment(roomId).build()
        return cronExecute<PhoneDisbandResultDto>(
            Request.Builder().url(target).delete(),
            decoder = phoneJson,
            httpClient = phoneWriteClient,
        ).toDomain()
    }

    fun resolvePhoneApproval(roomId: String, requestId: String, choice: String): PhoneApprovalResult {
        require(roomId.isNotBlank() && roomId != "." && roomId != "..")
        require(requestId.isNotBlank() && requestId != "." && requestId != "..")
        require(choice == "once" || choice == "deny")
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/groups")
            .addPathSegment(roomId).addPathSegment("approvals").addPathSegment(requestId).addPathSegment("resolve").build()
        val requestBody = phoneJson.encodeToString(PhoneApprovalRequest(choice)).toRequestBody(JSON_MEDIA)
        return cronExecute<PhoneApprovalResultDto>(
            Request.Builder().url(target).post(requestBody),
            decoder = phoneJson,
            httpClient = phoneWriteClient,
        ).toDomain()
    }

    fun toolsets(): List<ToolsetDto> = cronExecute(Request.Builder().url(url("v1", "tools", "toolsets")))

    fun setToolsetEnabled(name: String, enabled: Boolean): ToolEnabledDto = cronExecute(
        Request.Builder().url(url("v1", "tools", "toolsets", name))
            .put(cronJson.encodeToString(ToolEnabledRequest(enabled)).toRequestBody(JSON_MEDIA)),
    )

    fun mcpServers(): McpServersDto = cronExecute(Request.Builder().url(url("v1", "mcp", "servers")))

    fun setMcpEnabled(name: String, enabled: Boolean): ToolEnabledDto = cronExecute(
        Request.Builder().url(url("v1", "mcp", "servers", name, "enabled"))
            .put(cronJson.encodeToString(ToolEnabledRequest(enabled)).toRequestBody(JSON_MEDIA)),
    )

    fun testMcpServer(name: String): McpTestDto = cronExecute(
        Request.Builder().url(url("v1", "mcp", "servers", name, "test"))
            .post(ByteArray(0).toRequestBody(null)),
    )

    fun skills(): List<SkillDto> = cronExecute(Request.Builder().url(url("v1", "skills")))

    fun setSkillEnabled(name: String, enabled: Boolean): SkillEnabledDto = cronExecute(
        Request.Builder().url(url("v1", "skills", name, "enabled"))
            .put(cronJson.encodeToString(SkillEnabledRequest(enabled)).toRequestBody(JSON_MEDIA)),
    )

    fun skillContent(name: String): SkillContentDto =
        cronExecute(Request.Builder().url(url("v1", "skills", name, "content")))

    fun updateSkillContent(name: String, content: String): SkillSavedDto = cronExecute(
        Request.Builder().url(url("v1", "skills", name, "content"))
            .put(cronJson.encodeToString(SkillContentRequest(content)).toRequestBody(JSON_MEDIA)),
    )

    fun skillHubSources(): HubSourcesDto =
        cronExecute(Request.Builder().url(url("v1", "skills", "hub", "sources")), decoder = hubJson)

    fun searchSkillHub(query: String, source: String = "all", limit: Int = 20): HubSearchDto {
        val trimmed = query.trim()
        require(trimmed.length in 1..200)
        require(source.length in 1..50 && HUB_SOURCE_REGEX.matches(source))
        require(limit in 1..50)
        val target = connection.baseUrl.newBuilder()
            .addPathSegments("v1/skills/hub/search")
            .addQueryParameter("q", trimmed)
            .addQueryParameter("source", source)
            .addQueryParameter("limit", limit.toString())
            .build()
        return cronExecute(Request.Builder().url(target), decoder = hubJson)
    }

    fun previewSkillHub(identifier: String): HubPreviewDto {
        val target = connection.baseUrl.newBuilder()
            .addPathSegments("v1/skills/hub/preview")
            .addQueryParameter("identifier", identifier)
            .build()
        return cronExecute(Request.Builder().url(target), decoder = hubJson)
    }

    fun scanSkillHub(identifier: String): HubScanDto = cronExecute(
        Request.Builder().url(url("v1", "skills", "hub", "scan"))
            .post(cronJson.encodeToString(HubScanRequest(identifier)).toRequestBody(JSON_MEDIA)),
        decoder = hubJson,
    )

    fun installSkillHub(identifier: String, scanId: String, acknowledgeRisk: Boolean): HubStartedDto =
        cronExecute(
            Request.Builder().url(url("v1", "skills", "hub", "install"))
                .post(cronJson.encodeToString(HubInstallRequest(identifier, scanId, acknowledgeRisk)).toRequestBody(JSON_MEDIA)),
            decoder = hubJson,
        )

    fun skillHubAction(actionId: String): HubActionDto = cronExecute(
        Request.Builder().url(url("v1", "skills", "hub", "actions", actionId)),
        callTimeoutMillis = 15_000L,
        decoder = hubJson,
    )

    fun uninstallSkillHub(name: String): HubStartedDto = cronExecute(
        Request.Builder().url(url("v1", "skills", "hub", "uninstall"))
            .post(cronJson.encodeToString(HubUninstallRequest(name)).toRequestBody(JSON_MEDIA)),
        decoder = hubJson,
    )

    fun auditEvents(limit: Int = 50, beforeId: Long? = null): AuditEventsDto {
        require(limit in 1..100)
        require(beforeId == null || beforeId > 0)
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/audit")
            .addQueryParameter("limit", limit.toString()).apply {
                if (beforeId != null) addQueryParameter("before_id", beforeId.toString())
            }.build()
        return cronExecute(Request.Builder().url(target))
    }

    fun cronJobs(): List<CronJobDto> = cronExecute(Request.Builder().url(url("v1", "cron", "jobs")))

    fun cronJob(id: String): CronJobDto = cronExecute(Request.Builder().url(url("v1", "cron", "jobs", id)))

    fun cronRuns(id: String, limit: Int = 20): CronRunsDto {
        require(limit in 1..100)
        val target = connection.baseUrl.newBuilder().addPathSegments("v1/cron/jobs")
            .addPathSegment(id).addPathSegment("runs").addQueryParameter("limit", limit.toString()).build()
        return cronExecute(Request.Builder().url(target))
    }

    fun createCronJob(request: CronCreateRequest): CronJobDto = cronExecute(
        Request.Builder().url(url("v1", "cron", "jobs"))
            .post(cronJson.encodeToString(request).toRequestBody(JSON_MEDIA)),
    )

    fun updateCronJob(id: String, updates: JsonObject): CronJobDto {
        require(updates.isNotEmpty() && updates.keys.all { it in CRON_UPDATE_FIELDS })
        return cronExecute(Request.Builder().url(url("v1", "cron", "jobs", id))
            .put(cronJson.encodeToString(CronUpdateRequest(updates)).toRequestBody(JSON_MEDIA)))
    }

    fun pauseCronJob(id: String): CronJobDto = cronAction(id, "pause")
    fun resumeCronJob(id: String): CronJobDto = cronAction(id, "resume")
    fun triggerCronJob(id: String): CronJobDto = cronAction(id, "trigger")

    fun deleteCronJob(id: String): CronDeleteDto =
        cronExecute(Request.Builder().url(url("v1", "cron", "jobs", id)).delete())

    private fun cronAction(id: String, action: String): CronJobDto = cronExecute(
        Request.Builder().url(url("v1", "cron", "jobs", id, action)).post(ByteArray(0).toRequestBody(null)),
    )

    private inline fun <reified T> cronExecute(
        builder: Request.Builder,
        callTimeoutMillis: Long? = null,
        decoder: Json = cronJson,
        httpClient: OkHttpClient = client,
    ): T {
        val call = httpClient.newCall(builder.authorized().build())
        if (callTimeoutMillis != null) {
            call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        }
        call.execute().use { response ->
            if (response.code == 401) onAuthenticationExpired()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BridgeRequestException(response.code, errorCode(body))
            return decoder.decodeFromString(body)
        }
    }

    fun listThreads(query: String = ""): List<ThreadItem> {
        val url = connection.baseUrl.newBuilder().addPathSegments("v1/threads").apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
        }.build()
        val response = get<ThreadListDto>(url.toString())
        return response.items.map { it.toDomain() }
    }

    fun messages(threadId: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        var offset = 0
        do {
            val requestUrl = connection.baseUrl.newBuilder()
                .addPathSegments("v1/threads/$threadId/messages")
                .addQueryParameter("limit", "500")
                .addQueryParameter("offset", offset.toString())
                .build().toString()
            val page = get<MessageListDto>(requestUrl)
            messages += page.items.map { ChatMessage(it.id, it.role, it.text) }
            offset = page.nextOffset ?: -1
        } while (offset >= 0)
        return messages
    }

    fun modelOptions(): List<ModelOption> = get<ModelOptionsDto>(url("v1", "catalog", "models"))
        .items.map {
            ModelOption(
                it.model,
                it.provider,
                it.label.ifBlank { it.model },
                it.providerLabel.ifBlank { it.provider },
                it.providerAliases,
                it.current,
            )
        }

    fun catalog(section: String): CatalogResult {
        val dto = get<CatalogDto>(url("v1", "catalog", section))
        return CatalogResult(dto.items, dto.limited)
    }

    fun directories(path: String? = null): DirectoryListing {
        val url = connection.baseUrl.newBuilder().addPathSegments("v1/catalog/directories").apply {
            if (path != null) addQueryParameter("path", path)
        }.build()
        return get(url.toString())
    }

    fun create(prompt: String, option: ModelOption?, cwd: String? = null): ThreadItem =
        post<CreateThreadRequest, ThreadDto>(
            "v1/threads",
            CreateThreadRequest(prompt, option?.model, option?.provider, cwd),
        ).toDomain()

    fun send(threadId: String, text: String) {
        post<SendMessageRequest, StatusDto>(
            url("v1", "threads", threadId, "messages"),
            SendMessageRequest(text),
        )
    }

    suspend fun createUpload(threadId: String, name: String, size: Long, sha256: String, mimeType: String): UploadDto =
        executeUpload(Request.Builder().url(url("v1", "uploads"))
            .post(json.encodeToString(CreateUploadRequest(threadId, name, size, sha256, mimeType)).toRequestBody(JSON_MEDIA)))

    suspend fun uploadChunk(id: String, index: Int, bytes: ByteArray): UploadChunkDto {
        require(bytes.isNotEmpty() && bytes.size <= MAX_CHUNK_SIZE)
        return executeUpload(Request.Builder().url(url("v1", "uploads", id, "chunks", index.toString()))
            .put(bytes.toRequestBody("application/octet-stream".toMediaType())))
    }

    /** Only one bounded chunk is retained, including when a provider returns short reads. */
    suspend fun uploadChunks(id: String, input: InputStream, size: Long, maxChunkSize: Int, onProgress: (Int) -> Unit) {
        require(maxChunkSize > 0)
        val buffer = ByteArray(minOf(maxChunkSize, MAX_CHUNK_SIZE))
        var sent = 0L
        var index = 0
        while (true) {
            var count = 0
            while (count < buffer.size) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer, count, buffer.size - count)
                if (read == -1) break
                if (read == 0) continue
                count += read
            }
            if (count == 0) break
            if (sent + count > size) throw BridgeRequestException(409, "size_mismatch")
            val result = uploadChunk(id, index, if (count == buffer.size) buffer else buffer.copyOf(count))
            sent += count
            index++
            if (result.nextIndex != index || result.receivedSize != sent) {
                throw BridgeRequestException(409, "chunk_out_of_order")
            }
            onProgress(if (size == 0L) 0 else (sent * 99 / size).toInt())
        }
        if (sent != size) throw BridgeRequestException(409, "size_mismatch")
    }

    suspend fun completeUpload(id: String): UploadStatusDto = executeUpload(
        Request.Builder().url(url("v1", "uploads", id, "complete"))
            .post(ByteArray(0).toRequestBody(null)),
    )

    suspend fun attachUpload(id: String, threadId: String): AttachedUploadDto = executeUpload(
        Request.Builder().url(url("v1", "uploads", id, "attach"))
            .post(json.encodeToString(AttachUploadRequest(threadId)).toRequestBody(JSON_MEDIA)),
    )

    /** Cancelling an upload coroutine also cancels its in-flight HTTP call. */
    private suspend inline fun <reified T> executeUpload(builder: Request.Builder): T {
        val body = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(builder.authorized().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (it.code == 401) onAuthenticationExpired()
                            val value = it.body?.string().orEmpty()
                            if (!it.isSuccessful) throw BridgeRequestException(it.code, errorCode(value))
                            value
                        }
                        continuation.resume(text)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            })
        }
        return json.decodeFromString(body)
    }

    fun resume(threadId: String): String =
        post<EmptyRequest, StatusDto>(url("v1", "threads", threadId, "resume"), EmptyRequest()).status

    fun updateModel(threadId: String, option: ModelOption) {
        post<ModelRequest, kotlinx.serialization.json.JsonObject>(
            url("v1", "threads", threadId, "model"),
            ModelRequest(option.model, option.provider),
        )
    }

    fun stop(threadId: String) {
        post<EmptyRequest, StatusDto>(url("v1", "threads", threadId, "stop"), EmptyRequest())
    }

    fun rename(threadId: String, title: String) {
        send(Request.Builder().url(url("v1", "threads", threadId))
            .patch(json.encodeToString(UpdateThreadRequest(title = title)).toRequestBody(JSON_MEDIA)))
    }

    fun archive(threadId: String) {
        send(Request.Builder().url(url("v1", "threads", threadId))
            .patch(json.encodeToString(UpdateThreadRequest(archived = true)).toRequestBody(JSON_MEDIA)))
    }

    fun delete(threadId: String) {
        send(Request.Builder().url(url("v1", "threads", threadId)).delete())
    }

    fun approvals(threadId: String): List<PendingApproval> {
        val requestUrl = connection.baseUrl.newBuilder()
            .addPathSegments("v1/approvals")
            .addQueryParameter("thread_id", threadId)
            .build()
        return get<ApprovalListDto>(requestUrl.toString()).items.map { it.toDomain() }
    }

    fun resolveApproval(approvalId: String, choice: String) {
        post<ResolveApprovalRequest, StatusDto>(
            url("v1", "approvals", approvalId, "resolve"),
            ResolveApprovalRequest(choice),
        )
    }

    private inline fun <reified T> get(url: String): T = execute(
        Request.Builder().url(url).get().authorized().build(),
    )

    private inline fun <reified I, reified O> post(path: String, body: I): O = execute(
        Request.Builder()
            .url(
                if (path.startsWith("https://") || path.startsWith("http://")) {
                    path
                } else {
                    connection.baseUrl.newBuilder().addPathSegments(path).build().toString()
                },
            )
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA))
            .authorized()
            .build(),
    )

    /** For calls whose response body we do not need (e.g. 204 No Content). */
    private fun send(builder: Request.Builder) {
        client.newCall(builder.authorized().build()).execute().use { response ->
            if (response.code == 401) onAuthenticationExpired()
            if (!response.isSuccessful) throw BridgeRequestException(response.code, errorCode(response.body?.string()))
        }
    }

    private inline fun <reified T> execute(request: Request): T {
        client.newCall(request).execute().use { response ->
            if (response.code == 401) onAuthenticationExpired()
            if (!response.isSuccessful) throw BridgeRequestException(response.code, errorCode(response.body?.string()))
            return json.decodeFromString(response.body?.string() ?: error("Empty Bridge response"))
        }
    }

    /** The Bridge's stable ``detail.code`` (e.g. ``thread_busy``), if the body carries one. */
    private fun errorCode(body: String?): String? = runCatching {
        val detail = json.parseToJsonElement(body.orEmpty()).jsonObject["detail"]
        (detail as? JsonObject)?.get("code")?.jsonPrimitive?.content
    }.getOrNull()

    private fun Request.Builder.authorized(): Request.Builder =
        header("Authorization", "Bearer ${connection.deviceSecret}")

    private fun url(vararg segments: String): String = connection.baseUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build().toString()

    private companion object {
        val CRON_UPDATE_FIELDS = setOf("schedule", "name", "prompt", "deliver", "skills", "skill",
            "model", "provider", "workdir", "context_from", "enabled_toolsets", "failure_deliver")
        val HUB_SOURCE_REGEX = Regex("^[a-zA-Z0-9_-]+$")
        const val MAX_CHUNK_SIZE = 1024 * 1024
        val JSON_MEDIA = "application/json".toMediaType()
    }
}

@Serializable
private data class ThreadListDto(val items: List<ThreadDto>)

@Serializable
private data class ThreadDto(
    val id: String,
    val title: String,
    val preview: String = "",
    val status: String = "idle",
    val model: String = "",
    val provider: String = "",
    val updated_at: Double? = null,
) {
    fun toDomain() = ThreadItem(id, title, preview, status, model, provider, updated_at ?: 0.0)
}

@Serializable
private data class MessageListDto(
    val items: List<MessageDto>,
    @SerialName("next_offset") val nextOffset: Int? = null,
)

@Serializable
private data class MessageDto(val id: String, val role: String, val text: String)

@Serializable
private data class CreateThreadRequest(
    val prompt: String,
    val model: String? = null,
    val provider: String? = null,
    val cwd: String? = null,
)

class BridgeRequestException(val status: Int, val code: String?) :
    Exception("Bridge request failed: $status ${code.orEmpty()}".trim())

@Serializable private data class ModelOptionsDto(val items: List<ModelOptionDto>)
@Serializable private data class ModelOptionDto(
    val model: String,
    val provider: String,
    val label: String = "",
    @SerialName("provider_label") val providerLabel: String = "",
    @SerialName("provider_aliases") val providerAliases: List<String> = emptyList(),
    val current: Boolean = false,
)
@Serializable data class CatalogItem(val id: String, val title: String, val subtitle: String = "", val enabled: Boolean? = null)
data class CatalogResult(val items: List<CatalogItem>, val limited: Boolean)
@Serializable private data class CatalogDto(val items: List<CatalogItem>, val limited: Boolean = false)
@Serializable private data class ModelRequest(val model: String, val provider: String)

@Serializable
private data class UpdateThreadRequest(val title: String? = null, val archived: Boolean? = null)

@Serializable
private data class SendMessageRequest(val text: String)

@Serializable
private data class ApprovalListDto(val items: List<ApprovalDto>)

@Serializable
private data class ApprovalDto(
    val id: String,
    @SerialName("thread_id") val threadId: String,
    @SerialName("tool_name") val toolName: String = "",
    val command: String = "",
    val choices: List<String>,
    val reason: String = "",
) {
    fun toDomain() = PendingApproval(id, threadId, toolName, command, choices, reason)
}

@Serializable
private data class ResolveApprovalRequest(val choice: String)

@Serializable
private class EmptyRequest

@Serializable
private data class StatusDto(
    val status: String,
    @SerialName("live_session_id") val liveSessionId: String? = null,
)

@Serializable
data class DirectoryEntry(val path: String, val name: String)

@Serializable
data class DirectoryListing(val items: List<DirectoryEntry>, val parent: String?)

@Serializable
private data class CreateUploadRequest(
    @SerialName("thread_id") val threadId: String,
    val name: String,
    val size: Long,
    val sha256: String,
    @SerialName("mime_type") val mimeType: String,
)

@Serializable
private data class AttachUploadRequest(@SerialName("thread_id") val threadId: String)

@Serializable
data class UploadDto(
    val id: String,
    val name: String,
    @SerialName("expires_at") val expiresAt: Double,
    @SerialName("max_chunk_size") val maxChunkSize: Int,
)

@Serializable
data class UploadChunkDto(
    val id: String,
    @SerialName("next_index") val nextIndex: Int,
    @SerialName("received_size") val receivedSize: Long,
)

@Serializable
data class UploadStatusDto(val id: String, val status: String)

@Serializable
data class AttachedUploadDto(
    val id: String,
    @SerialName("thread_id") val threadId: String,
    @SerialName("live_session_id") val liveSessionId: String,
    val status: String,
    @SerialName("ref_text") val refText: String? = null,
)

@Serializable
data class CronJobDto(
    val id: String = "",
    val profile: String? = null,
    val name: String? = null,
    val prompt: String? = null,
    val schedule: CronScheduleDto? = null,
    @SerialName("schedule_display") val scheduleDisplay: String? = null,
    val enabled: Boolean = true,
    val state: String? = null,
    @SerialName("next_run_at") val nextRunAt: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
) {
    val active: Boolean get() = enabled && state != "paused" && state != "completed"
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: id.ifBlank { "未命名任务" }
    val scheduleText: String get() = scheduleDisplay ?: schedule?.display ?: scheduleInput
    // Display text such as "once at …" is not a valid editable schedule.
    val scheduleInput: String get() = when (schedule?.kind) {
        "cron" -> schedule?.expr.orEmpty()
        "once" -> schedule?.runAt.orEmpty()
        "interval" -> schedule?.minutes?.let { "every ${java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()}m" }.orEmpty()
        else -> ""
    }
}

@Serializable
data class CronScheduleDto(
    val kind: String? = null,
    val display: String? = null,
    val expr: String? = null,
    val minutes: Double? = null,
    @SerialName("run_at") val runAt: String? = null,
)

@Serializable
data class CronRunDto(
    val id: String = "",
    val title: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("is_active") val isActive: Boolean = false,
)

@Serializable
data class CronRunsDto(val runs: List<CronRunDto> = emptyList(), val limit: Int = 20)

@Serializable
data class CronCreateRequest(
    val schedule: String,
    val name: String? = null,
    val prompt: String? = null,
    val deliver: String? = null,
    val skills: List<String>? = null,
    val model: String? = null,
    val provider: String? = null,
    val workdir: String? = null,
    @SerialName("context_from") val contextFrom: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("enabled_toolsets") val enabledToolsets: List<String>? = null,
    val paused: Boolean? = null,
    @SerialName("paused_reason") val pausedReason: String? = null,
)

@Serializable private data class CronUpdateRequest(val updates: JsonObject)
@Serializable data class CronDeleteDto(val ok: Boolean = false)

@Serializable
data class AuditEventDto(
    val id: Long = 0,
    val timestamp: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val action: String = "",
    val target: String? = null,
    val outcome: String = "",
    val detail: String? = null,
)

@Serializable
data class AuditEventsDto(
    val items: List<AuditEventDto> = emptyList(),
    @SerialName("next_before_id") val nextBeforeId: Long? = null,
)

@Serializable
data class SkillDto(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val enabled: Boolean,
    val usage: Int = 0,
    val provenance: String = "",
) {
    val categoryLabel: String get() = category?.takeIf { it.isNotBlank() } ?: "未分类"
    val provenanceLabel: String get() = when (provenance) {
        "bundled" -> "内置"
        "agent" -> "自建"
        "hub" -> "社区安装"
        else -> "未知来源"
    }
}

@Serializable private data class SkillEnabledRequest(val enabled: Boolean)
@Serializable private data class SkillContentRequest(val content: String)
@Serializable data class SkillEnabledDto(val ok: Boolean, val name: String, val enabled: Boolean)
@Serializable data class SkillContentDto(val name: String, val content: String)
@Serializable data class SkillSavedDto(val ok: Boolean, val name: String)

@Serializable
data class ToolsetDto(
    val name: String = "",
    val label: String = "",
    val description: String = "",
    val platform: String = "",
    @SerialName("platform_label") val platformLabel: String = "",
    val enabled: Boolean,
    val available: Boolean = false,
    val configured: Boolean = false,
    val tools: List<String> = emptyList(),
)

@Serializable
data class McpServerDto(
    val name: String = "",
    val enabled: Boolean,
    val transport: String = "unknown",
    @SerialName("command_name") val commandName: String? = null,
    @SerialName("url_host") val urlHost: String? = null,
)

@Serializable data class McpServersDto(val servers: List<McpServerDto> = emptyList())
@Serializable private data class ToolEnabledRequest(val enabled: Boolean)
@Serializable data class ToolEnabledDto(val ok: Boolean = false, val name: String = "", val enabled: Boolean)
@Serializable
data class McpTestDto(
    val ok: Boolean = false,
    @SerialName("tool_count") val toolCount: Int = 0,
    val prompts: Int = 0,
    val resources: Int = 0,
)

@Serializable
data class HubMetadataDto(
    val name: String = "",
    val description: String = "",
    val source: String = "",
    val identifier: String = "",
    val trust_level: String = "unknown",
    val repo: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class HubPreviewDto(
    val name: String = "",
    val description: String = "",
    val source: String = "",
    val identifier: String = "",
    val trust_level: String = "unknown",
    val repo: String? = null,
    val tags: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val skill_md: String = "",
    val truncated: Boolean = false,
)

@Serializable
data class HubSourceDto(
    val id: String = "",
    val label: String = "",
    val searchable: Boolean = false,
    val available: Boolean? = null,
    val rate_limited: Boolean? = null,
)

@Serializable
data class HubSourcesDto(
    val sources: List<HubSourceDto> = emptyList(),
    val index_available: Boolean = false,
    val featured: List<HubMetadataDto> = emptyList(),
)

@Serializable
data class HubSearchDto(
    val results: List<HubMetadataDto> = emptyList(),
)

@Serializable
data class HubFindingDto(
    val severity: String = "",
    val category: String = "",
    val message: String = "",
)

@Serializable
data class HubScanDto(
    val identifier: String = "",
    val trust_level: String = "unknown",
    val verdict: String = "unknown",
    val allowed: Boolean,
    val findings: List<HubFindingDto> = emptyList(),
    val scan_id: String = "",
    val expires_at: String = "",
) {
    override fun toString(): String =
        "HubScanDto(identifier=$identifier, trust_level=$trust_level, verdict=$verdict, allowed=$allowed, findings=$findings, scan_id=[REDACTED], expires_at=$expires_at)"
}

@Serializable
data class HubStartedDto(
    val action_id: String = "",
    val status: String = "",
)

@Serializable
data class HubActionDto(
    val running: Boolean,
    val exit_code: Int? = null,
    val log_tail: String = "",
)

@Serializable
private data class HubScanRequest(val identifier: String)

@Serializable
private data class HubInstallRequest(
    val identifier: String,
    val scan_id: String,
    val acknowledge_risk: Boolean,
)

@Serializable
private data class HubUninstallRequest(val name: String)

@Serializable data class BotGroupMemberDto(
    val name: String? = null, val handle: String? = null, val local: Boolean = false,
)
@Serializable data class BotGroupRoomDto(
    val room_id: String? = null, val name: String? = null,
    val members: List<BotGroupMemberDto> = emptyList(), val message_count: Long = 0,
    val omitted: Long = 0, val last_at: Double = 0.0, val revision: Long = 0,
)
@Serializable data class BotGroupsDto(
    val rooms: List<BotGroupRoomDto> = emptyList(), val updated_at: Double = 0.0,
    val format_warning: Boolean = false,
)
@Serializable data class BotGroupMessageDto(
    val id: String? = null, val from_kind: String? = null, val from_name: String? = null,
    val text: String? = null, val at: Double = 0.0, val thread: String? = null,
    val truncated: Boolean = false, val has_attachments: Boolean = false,
)
@Serializable data class BotGroupDetailDto(
    val room_id: String? = null, val name: String? = null,
    val members: List<BotGroupMemberDto> = emptyList(), val omitted: Long = 0,
    val revision: Long = 0, val messages: List<BotGroupMessageDto> = emptyList(),
    val total: Long = 0, val format_warning: Boolean = false,
)


data class PhoneCapabilities(
    val available: Boolean = false,
    val driver: Boolean = false,
    val features: List<String> = emptyList(),
)

data class PhoneMember(
    val memberId: String? = null,
    val profile: String? = null,
    val handle: String? = null,
    val displayName: String? = null,
)

data class PhoneDriverStatus(
    val running: Boolean = false,
    val working: Boolean = false,
    val blocked: Boolean = false,
    val approvals: List<PhoneApproval> = emptyList(),
)

data class PhoneApproval(
    val memberId: String = "",
    val taskId: String = "",
    val executionGeneration: Long = 0L,
    val requestId: String = "",
    val command: String = "",
    val description: String = "",
    val toolName: String? = null,
    val choices: List<String> = emptyList(),
)

data class PhoneRoom(
    val roomId: String,
    val name: String = "",
    val memberCount: Int = 0,
    val members: List<PhoneMember> = emptyList(),
    val latestSeq: Long? = null,
    val updatedAt: Double = 0.0,
    val disbanded: Boolean = false,
    val driverStatus: PhoneDriverStatus? = null,
)

data class PhoneRoomPage(
    val rooms: List<PhoneRoom>,
    val nextOffset: Long? = null,
)

data class PhoneActor(
    val kind: String,
    val id: String,
)

data class PhoneEvent(
    val seq: Long,
    val eventId: String,
    val kind: String,
    val actor: PhoneActor,
    val text: String,
    val createdAt: Double,
)

data class PhoneEventPage(
    val events: List<PhoneEvent>,
    val cursor: Long,
    val latestSeq: Long,
    val hasMore: Boolean,
)

data class PhoneMemberInput(
    val profile: String,
    val handle: String? = null,
    val displayName: String? = null,
)

data class PhoneSendResult(
    val accepted: Boolean,
    val eventId: String,
    val driverStarted: Boolean,
)

data class PhoneStopResult(
    val cancelled: Long,
)

data class PhoneDisbandResult(
    val disbanded: Boolean,
)

data class PhoneApprovalResult(
    val status: String = "",
)

@Serializable
private data class PhoneCapabilitiesDto(
    val available: Boolean = false,
    val driver: Boolean = false,
    val features: List<String> = emptyList(),
) {
    fun toDomain(): PhoneCapabilities = PhoneCapabilities(
        available = available,
        driver = driver,
        features = features,
    )
}

@Serializable
private data class PhoneMemberDto(
    val member_id: String? = null,
    val profile: String? = null,
    val handle: String? = null,
    val display_name: String? = null,
) {
    fun toDomain(): PhoneMember = PhoneMember(
        memberId = member_id,
        profile = profile,
        handle = handle,
        displayName = display_name,
    )
}

@Serializable
private data class PhoneDriverStatusDto(
    val running: Boolean = false,
    val working: Boolean = false,
    val blocked: Boolean = false,
    val approvals: List<PhoneApprovalDto>? = null,
) {
    fun toDomain(): PhoneDriverStatus = PhoneDriverStatus(
        running = running,
        working = working,
        blocked = blocked,
        approvals = approvals?.map { it.toDomain() } ?: emptyList(),
    )
}

@Serializable
private data class PhoneApprovalDto(
    val member_id: String? = null,
    val task_id: String? = null,
    val execution_generation: Long? = null,
    val request_id: String? = null,
    val command: String? = null,
    val description: String? = null,
    val tool_name: String? = null,
    val choices: List<String>? = null,
) {
    fun toDomain(): PhoneApproval = PhoneApproval(
        memberId = member_id.orEmpty(),
        taskId = task_id.orEmpty(),
        executionGeneration = execution_generation ?: 0L,
        requestId = request_id.orEmpty(),
        command = command.orEmpty(),
        description = description.orEmpty(),
        toolName = tool_name,
        choices = choices ?: emptyList(),
    )
}

@Serializable
private data class PhoneRoomDto(
    val room_id: String? = null,
    val name: String? = null,
    val member_count: Int? = null,
    val members: List<PhoneMemberDto>? = null,
    val latest_seq: Long? = null,
    val updated_at: Double? = null,
    val disbanded: Boolean? = null,
    val driver_status: PhoneDriverStatusDto? = null,
) {
    fun toDomain(): PhoneRoom = PhoneRoom(
        roomId = room_id.orEmpty(),
        name = name.orEmpty(),
        memberCount = member_count ?: members?.size ?: 0,
        members = members?.map { it.toDomain() } ?: emptyList(),
        latestSeq = latest_seq,
        updatedAt = updated_at ?: 0.0,
        disbanded = disbanded ?: false,
        driverStatus = driver_status?.toDomain(),
    )
}

@Serializable
private data class PhoneRoomPageDto(
    val rooms: List<PhoneRoomDto>? = null,
    val next_offset: Long? = null,
) {
    fun toDomain(): PhoneRoomPage = PhoneRoomPage(
        rooms = rooms?.map { it.toDomain() } ?: emptyList(),
        nextOffset = next_offset,
    )
}

@Serializable
private data class PhoneActorDto(
    val kind: String? = null,
    val id: String? = null,
) {
    fun toDomain(): PhoneActor = PhoneActor(
        kind = kind.orEmpty(),
        id = id.orEmpty(),
    )
}

@Serializable
private data class PhoneEventDto(
    val seq: Long? = null,
    val event_id: String? = null,
    val kind: String? = null,
    val actor: PhoneActorDto? = null,
    val text: String? = null,
    val created_at: Double? = null,
) {
    fun toDomain(): PhoneEvent = PhoneEvent(
        seq = seq ?: 0L,
        eventId = event_id.orEmpty(),
        kind = kind.orEmpty(),
        actor = actor?.toDomain() ?: PhoneActor("", ""),
        text = text.orEmpty(),
        createdAt = created_at ?: 0.0,
    )
}

@Serializable
private data class PhoneEventPageDto(
    val events: List<PhoneEventDto>? = null,
    val cursor: Long? = null,
    val latest_seq: Long? = null,
    val has_more: Boolean? = null,
) {
    fun toDomain(): PhoneEventPage = PhoneEventPage(
        events = events?.map { it.toDomain() } ?: emptyList(),
        cursor = cursor ?: 0L,
        latestSeq = latest_seq ?: 0L,
        hasMore = has_more ?: false,
    )
}

@Serializable
private data class PhoneCreateMemberRequest(
    val profile: String,
    val handle: String? = null,
    val display_name: String? = null,
)

@Serializable
private data class PhoneCreateRoomRequest(
    val name: String,
    val members: List<PhoneCreateMemberRequest>,
)

@Serializable
private data class PhoneMessageRequest(
    val text: String,
)

@Serializable
private data class PhoneSendResultDto(
    val accepted: Boolean? = null,
    val event_id: String? = null,
    val driver_started: Boolean? = null,
) {
    fun toDomain(): PhoneSendResult = PhoneSendResult(
        accepted = accepted ?: false,
        eventId = event_id.orEmpty(),
        driverStarted = driver_started ?: false,
    )
}

@Serializable
private data class PhoneStopResultDto(
    val cancelled: Long? = null,
) {
    fun toDomain(): PhoneStopResult = PhoneStopResult(
        cancelled = cancelled ?: 0L,
    )
}

@Serializable
private data class PhoneDisbandResultDto(
    val disbanded: Boolean? = null,
) {
    fun toDomain(): PhoneDisbandResult = PhoneDisbandResult(
        disbanded = disbanded ?: false,
    )
}

@Serializable
private data class PhoneApprovalRequest(
    val choice: String,
)

@Serializable
private data class PhoneApprovalResultDto(
    val status: String? = null,
) {
    fun toDomain(): PhoneApprovalResult = PhoneApprovalResult(
        status = status.orEmpty(),
    )
}
