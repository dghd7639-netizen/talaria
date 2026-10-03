package app.hermes.mobile.attachments

import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.UploadDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

const val MAX_ATTACHMENT_SIZE = 50L * 1024 * 1024
val ATTACHMENT_MIME_TYPES = setOf(
    "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf",
    "text/plain", "text/markdown", "text/csv", "application/json", "application/octet-stream",
)

class AttachmentValidationException(message: String) : Exception(message)

data class AttachmentSource(
    val name: String,
    val size: Long?,
    val mimeType: String,
    val open: () -> InputStream,
)

enum class AttachmentStatus { UPLOADING, READY, FAILED }

data class PendingAttachment(
    val key: String,
    val name: String = "读取附件…",
    val size: Long? = null,
    val mimeType: String = "application/octet-stream",
    val progress: Int = 0,
    val status: AttachmentStatus = AttachmentStatus.UPLOADING,
    val uploadId: String? = null,
    val attached: Boolean = false,
    val refText: String? = null,
    val error: String? = null,
) {
    val ready: Boolean get() = status == AttachmentStatus.READY && uploadId != null
    fun messageText(text: String): String = listOf(text.trim(), refText.orEmpty())
        .filter(String::isNotBlank).joinToString("\n\n")
}

class AttachmentUploader(private val api: BridgeApi) {
    suspend fun upload(
        threadId: String,
        source: AttachmentSource,
        onMetadata: (Long) -> Unit = {},
        onProgress: (Int) -> Unit,
    ): UploadDto = withContext(Dispatchers.IO) {
        if (source.mimeType !in ATTACHMENT_MIME_TYPES) throw AttachmentValidationException("不支持这种文件类型，请选择图片、PDF 或文本文件。")
        if (source.size != null && source.size > MAX_ATTACHMENT_SIZE) throw AttachmentValidationException("附件不能超过 50 MiB。")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        source.open().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > MAX_ATTACHMENT_SIZE) throw AttachmentValidationException("附件不能超过 50 MiB。")
                digest.update(buffer, 0, count)
            }
        }
        // SAF providers may omit size or report stale metadata. Hash and upload the actual bytes.
        onMetadata(size)
        currentCoroutineContext().ensureActive()
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val upload = api.createUpload(threadId, source.name, size, sha256, source.mimeType)
        source.open().use { api.uploadChunks(upload.id, it, size, upload.maxChunkSize, onProgress) }
        val completed = api.completeUpload(upload.id)
        check(completed.status == "completed")
        onProgress(100)
        upload
    }
}
