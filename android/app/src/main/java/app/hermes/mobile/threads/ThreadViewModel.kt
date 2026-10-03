package app.hermes.mobile.threads

import app.hermes.mobile.attachments.AttachmentSource
import app.hermes.mobile.attachments.AttachmentStatus
import app.hermes.mobile.attachments.AttachmentUploader
import app.hermes.mobile.attachments.AttachmentValidationException
import app.hermes.mobile.attachments.PendingAttachment
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeEventSocket
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.data.EventConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class ThreadViewModel(
    private val api: BridgeApi,
    eventSocket: BridgeEventSocket,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ThreadState())
    val state: StateFlow<ThreadState> = mutableState.asStateFlow()
    private val attachmentJobs = mutableMapOf<String, Job>()
    private val attachmentSources = mutableMapOf<String, () -> AttachmentSource>()
    private val refreshGeneration = AtomicLong()
    private val newThreadGeneration = AtomicLong()
    private val selectedEventGeneration = AtomicLong()
    private val requestsInFlight = AtomicInteger(0)
    private val stopInFlight = AtomicBoolean(false)
    private val socket: EventConnection = eventSocket.connect { event ->
        if (
            event.threadId == mutableState.value.selectedThreadId &&
            event.type in setOf("message.delta", "message.complete", "approval.request", "turn.complete", "turn.error")
        ) {
            selectedEventGeneration.incrementAndGet()
        }
        mutableState.update { it.accept(event) }
        // After a withdrawal, Hermes may still have the next queued approval waiting.
        if (event.type == "approval.request" || event.type == "approval.cancelled") loadApproval(event.threadId)
        // A title change or a finished turn doesn't invalidate the selected thread's messages;
        // a finished turn does move the thread up the recent list.
        if (event.type == "session.title" || event.type == "turn.complete") refresh("", reloadMessages = false)
    }

    init {
        refresh()
        loadModelOptions()
    }

    fun refresh(query: String = "") = refresh(query, reloadMessages = true)

    private fun refresh(query: String, reloadMessages: Boolean) {
        val generation = refreshGeneration.incrementAndGet()
        launchRequest {
            val threads = api.listThreads(query)
            mutableState.update {
                if (generation != refreshGeneration.get()) {
                    it
                } else {
                    val selectedId = if (it.isCreatingNew) null else it.selectedThreadId ?: threads.firstOrNull()?.id
                    val withThreads = it.copy(threads = threads)
                    withThreads.copy(
                        selectedThreadId = selectedId,
                        // Only an existing task dictates the model; a pick made for a new task stays.
                        selectedModel = selectedId?.let(withThreads::modelFor) ?: it.selectedModel,
                        running = if (it.selectedThreadId == null && !it.isCreatingNew) {
                            threads.firstOrNull { thread -> thread.id == selectedId }?.status == "running"
                        } else {
                            it.running
                        },
                    )
                }
            }
            if (reloadMessages && generation == refreshGeneration.get()) {
                mutableState.value.selectedThreadId?.let(::loadMessages)
            }
        }
    }

    fun newThread() {
        newThreadGeneration.incrementAndGet()
        mutableState.update { it.select(null) }
    }

    fun select(threadId: String) {
        mutableState.update { it.select(threadId) }
        loadMessages(threadId)
    }

    fun updateDraft(value: String) {
        mutableState.update { it.updateDraft(value) }
    }

    fun setModel(option: ModelOption) {
        val threadId = mutableState.value.selectedThreadId
        mutableState.update { it.copy(selectedModel = option) }
        if (threadId != null) launchRequest { api.updateModel(threadId, option) }
    }

    fun browseDirectories(path: String? = mutableState.value.newTaskCwd) {
        if (mutableState.value.selectedThreadId != null) return
        val request = DirectoryPickerState(path = path)
        mutableState.update { it.copy(isCreatingNew = true, directoryPicker = request) }
        viewModelScope.launch {
            val result = try {
                request.copy(listing = withContext(Dispatchers.IO) { api.directories(path) }, loading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                request.copy(loading = false, error = userMessage(error))
            }
            // Ignore a response after navigation, dismissal, or task selection.
            mutableState.update { if (it.directoryPicker === request) it.copy(directoryPicker = result) else it }
        }
    }

    fun closeDirectoryPicker() {
        mutableState.update { it.copy(directoryPicker = null) }
    }

    fun chooseDirectory(path: String?) {
        mutableState.update { it.chooseDirectory(path) }
    }

    fun openCatalog(section: String, title: String) = launchRequest {
        val result = api.catalog(section)
        mutableState.update {
            it.copy(catalogTitle = title, catalogItems = result.items, catalogLimited = result.limited)
        }
    }

    fun closeCatalog() {
        mutableState.update { it.copy(catalogTitle = null, catalogItems = emptyList(), catalogLimited = false) }
    }

    fun pickAttachment(threadId: String, read: () -> AttachmentSource) {
        val snapshot = mutableState.value
        if (snapshot.selectedThreadId != threadId || snapshot.loading || snapshot.pendingAttachment != null) return
        attachmentSources[threadId] = read
        startAttachment(threadId, read)
    }

    fun retryAttachment() {
        val snapshot = mutableState.value
        val threadId = snapshot.selectedThreadId ?: return
        if (snapshot.loading || snapshot.pendingAttachment?.status != AttachmentStatus.FAILED) return
        attachmentSources[threadId]?.let { startAttachment(threadId, it) }
    }

    private fun startAttachment(threadId: String, read: () -> AttachmentSource) {
        attachmentJobs.remove(threadId)?.cancel()
        val pending = PendingAttachment(key = UUID.randomUUID().toString())
        mutableState.update { it.copy(attachments = it.attachments + (threadId to pending), error = null) }
        attachmentJobs[threadId] = viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) { read() }
                mutableState.update { state ->
                    state.updateAttachment(threadId, pending.key) { it.copy(name = source.name, size = source.size, mimeType = source.mimeType) }
                }
                val uploaded = AttachmentUploader(api).upload(threadId, source,
                    onMetadata = { size ->
                        mutableState.update { state -> state.updateAttachment(threadId, pending.key) { it.copy(size = size) } }
                    },
                    onProgress = { progress ->
                        mutableState.update { state -> state.updateAttachment(threadId, pending.key) { it.copy(progress = progress) } }
                    },
                )
                mutableState.update { state ->
                    state.updateAttachment(threadId, pending.key) {
                        it.copy(name = uploaded.name, uploadId = uploaded.id, progress = 100, status = AttachmentStatus.READY)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("HermesMobile", "Attachment upload failed", error)
                mutableState.update { state ->
                    state.updateAttachment(threadId, pending.key) { it.copy(status = AttachmentStatus.FAILED, error = userMessage(error)) }
                }
            }
        }
    }

    fun removeAttachment() {
        val snapshot = mutableState.value
        val threadId = snapshot.selectedThreadId ?: return
        val pending = snapshot.pendingAttachment ?: return
        // Once attached, Hermes owns the next-turn attachment and exposes no detach endpoint.
        if (snapshot.loading || pending.attached) return
        attachmentJobs.remove(threadId)?.cancel()
        attachmentSources.remove(threadId)
        mutableState.update { it.clearAttachment(threadId, pending.key) }
    }

    fun send() {
        val snapshot = mutableState.value
        val text = snapshot.draft.trim()
        if (!snapshot.canSend) return
        val newGeneration = newThreadGeneration.get()
        launchRequest {
            val threadId = snapshot.selectedThreadId ?: api.create(text, snapshot.selectedModel, snapshot.newTaskCwd).id
            if (snapshot.selectedThreadId == null) {
                mutableState.update {
                    if (newThreadGeneration.get() == newGeneration) {
                        it.chooseDirectory(null)
                    } else it
                }
                val threads = api.listThreads()
                mutableState.update {
                    val sameNewThread = newThreadGeneration.get() == newGeneration
                    it.copy(
                        selectedThreadId = if (sameNewThread && it.selectedThreadId == null) threadId else it.selectedThreadId,
                        isCreatingNew = if (sameNewThread && it.selectedThreadId == null) false else it.isCreatingNew,
                        threads = threads,
                    )
                }
            } else {
                var pending = snapshot.pendingAttachment
                if (pending != null && !pending.attached) {
                    try {
                        val attached = api.attachUpload(requireNotNull(pending.uploadId), threadId)
                        check(attached.status == "attached")
                        if (!pending.mimeType.startsWith("image/") && pending.mimeType != "application/pdf" && attached.refText.isNullOrBlank()) {
                            throw AttachmentValidationException("附件引用缺失，无法发送，请重新选择文件。")
                        }
                        val attachedPending = pending.copy(attached = true, refText = attached.refText)
                        pending = attachedPending
                        mutableState.update { state -> state.updateAttachment(threadId, attachedPending.key) { attachedPending } }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // An expired or consumed ID needs a fresh upload, not another attach call.
                        if ((error as? BridgeRequestException)?.code == "upload_not_found") {
                            mutableState.update { state ->
                                state.updateAttachment(threadId, requireNotNull(snapshot.pendingAttachment).key) {
                                    it.copy(status = AttachmentStatus.FAILED, error = userMessage(error))
                                }
                            }
                        }
                        throw error
                    }
                }
                currentCoroutineContext().ensureActive()
                api.send(threadId, pending?.messageText(text) ?: text)
            }
            if (snapshot.pendingAttachment != null) withContext(Dispatchers.Main) {
                attachmentJobs.remove(threadId)
                attachmentSources.remove(threadId)
            }
            mutableState.update {
                val sameNewThread = snapshot.selectedThreadId != null || newThreadGeneration.get() == newGeneration
                val cleared = snapshot.pendingAttachment?.let { attachment -> it.clearAttachment(threadId, attachment.key) } ?: it
                cleared.copy(
                    draft = if (it.selectedThreadId == threadId) "" else it.draft,
                    drafts = if (sameNewThread) {
                        it.drafts + ((snapshot.selectedThreadId ?: ThreadState.NEW_THREAD_ID) to "")
                    } else {
                        it.drafts
                    },
                    messages = if (it.selectedThreadId == threadId) {
                        it.messages + ChatMessage(
                            "local-${System.nanoTime()}",
                            "user",
                            // Mirrors the Bridge's history note, so the bubble doesn't change on reload.
                            snapshot.pendingAttachment?.let { attachment -> "$text\n📎 ${attachment.name}" } ?: text,
                        )
                    } else {
                        it.messages
                    },
                    running = if (it.selectedThreadId == threadId) true else it.running,
                )
            }
        }
    }

    fun renameThread(threadId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        launchRequest {
            api.rename(threadId, clean)
            mutableState.update { it.copy(threads = it.threads.renamed(threadId, clean)) }
        }
    }

    fun archiveThread(threadId: String) = removeThread(threadId) { api.archive(threadId) }

    fun deleteThread(threadId: String) = removeThread(threadId) { api.delete(threadId) }

    private fun removeThread(threadId: String, call: () -> Unit) {
        launchRequest {
            call()
            val wasSelected = mutableState.value.selectedThreadId == threadId
            mutableState.update { it.without(threadId) }
            withContext(Dispatchers.Main) {
                attachmentJobs.remove(threadId)?.cancel()
                attachmentSources.remove(threadId)
            }
            if (wasSelected) mutableState.value.selectedThreadId?.let(::loadMessages)
        }
    }

    fun stop() {
        val snapshot = mutableState.value
        val threadId = snapshot.selectedThreadId ?: return
        if (!stopInFlight.compareAndSet(false, true)) return
        launchRequest {
            try {
                api.stop(threadId)
                mutableState.update {
                    if (it.selectedThreadId == threadId) it.copy(running = false) else it
                }
            } finally {
                stopInFlight.set(false)
            }
        }
    }

    fun resolveApproval(choice: String) {
        val snapshot = mutableState.value
        if (snapshot.loading) return
        val approval = snapshot.approval ?: return
        launchRequest {
            api.resolveApproval(approval.id, choice)
            mutableState.update {
                if (it.selectedThreadId == approval.threadId && it.approval?.id == approval.id) {
                    it.copy(approvalPending = false, approval = null)
                } else {
                    it
                }
            }
        }
    }

    private fun loadMessages(threadId: String) = launchRequest {
        val eventGeneration = selectedEventGeneration.get()
        val running = api.resume(threadId) == "running"
        val history = api.messages(threadId)
        mutableState.update {
            if (it.selectedThreadId == threadId) {
                // Events can finish a reply before Hermes persists it in history.
                val generationUnchanged = selectedEventGeneration.get() == eventGeneration
                it.copy(
                    messages = if (generationUnchanged) history else history + it.messages.filter { message ->
                        message.fromEvent && history.none { previous ->
                            previous.role == message.role && previous.text == message.text
                        }
                    },
                    running = if (generationUnchanged) running else it.running,
                )
            } else {
                it
            }
        }
        if (mutableState.value.selectedThreadId == threadId) loadApproval(threadId)
    }

    private fun loadModelOptions() = launchRequest {
        val catalog = api.modelOptions()
        mutableState.update { state ->
            val withOptions = state.copy(modelOptions = catalog.distinctBy { it.provider to it.model })
            withOptions.copy(selectedModel = withOptions.modelFor(state.selectedThreadId) ?: state.selectedModel)
        }
    }

    private fun loadApproval(threadId: String) = launchRequest {
        val approval = api.approvals(threadId).firstOrNull()
        mutableState.update {
            if (it.selectedThreadId == threadId) {
                it.copy(
                    approval = approval,
                    approvalPending = approval != null,
                )
            } else {
                it
            }
        }
    }

    private fun launchRequest(block: suspend () -> Unit) {
        requestsInFlight.incrementAndGet()
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("HermesMobile", "Bridge request failed", error)
                mutableState.update { it.copy(error = userMessage(error)) }
            } finally {
                val remaining = requestsInFlight.decrementAndGet()
                mutableState.update { it.copy(loading = remaining > 0) }
            }
        }
    }

    override fun onCleared() {
        socket.close()
    }
}

/** A sentence the user can act on, from the Bridge's stable error codes. */
internal fun userMessage(error: Throwable): String = when {
    error is AttachmentValidationException -> error.message ?: "无法读取附件，请重新选择文件。"
    error is java.io.FileNotFoundException -> "无法读取附件，请重新选择文件。"
    error is SecurityException -> "无法读取附件，请重新选择文件并允许访问。"
    else -> when ((error as? BridgeRequestException)?.code) {
        "upload_too_large" -> "附件不能超过 50 MiB。"
        "chunk_too_large" -> "附件分块过大，请重新上传。"
        "unsupported_media_type" -> "不支持这种文件类型，请选择图片、PDF 或文本文件。"
        "checksum_mismatch" -> "附件校验失败，文件可能已变化，请重新上传。"
        "upload_not_found" -> "附件已过期或不存在，请重新上传。"
        "invalid_filename" -> "文件名无效，请重命名后重新选择。"
        "invalid_thread_id", "upload_thread_mismatch" -> "附件与当前任务不匹配，请重新选择。"
        "unsafe_upload_path" -> "无法保存此附件，请重新选择文件。"
        "empty_chunk", "chunk_out_of_order", "chunk_conflict", "upload_completed" -> "附件分块上传失败，请重新上传。"
        "size_mismatch", "upload_incomplete" -> "附件不完整或大小已变化，请重新上传。"
        "upload_storage_unavailable" -> "Mac 暂时无法保存附件，请稍后重试。"
        "invalid_request" -> "附件请求无效，请重新选择文件。"
        "thread_open_elsewhere" -> "这个任务正在 Mac 上的 Hermes 桌面端打开。请先在桌面端关闭它，或者新建一个任务。"
        "thread_busy" -> "任务还在运行，请等它结束后再发送。"
        "thread_not_found" -> "找不到这个任务，它可能已被删除。"
        "directory_not_allowed" -> "无权访问此文件夹，请选择 Mac 用户主目录内的文件夹。"
        "directory_not_found" -> "找不到此文件夹，它可能已被移动或删除。"
        "cron_job_not_found" -> "找不到这个定时任务，它可能已被删除。"
        "invalid_audit_request" -> "操作记录请求无效，请刷新后重试。"
        "audit_storage_unavailable" -> "Mac 暂时无法读取操作记录，请稍后重试。"
        "invalid_cron_request" -> "定时任务设置无效，请检查执行时间和任务内容。"
        "invalid_group_request" -> "群聊设置或消息无效，请检查群名称、成员和消息内容。"
        "group_authority_conflict" -> "群聊控制权发生冲突，请刷新；仍失败时请在 Mac 上处理。"
        "group_history_expired" -> "群聊历史已过期，请返回列表重新打开。"
        "group_approval_stale" -> "这个审批已失效，可能已超时。"
        "choice_not_offered" -> "当前审批不支持该选项，请刷新后重试。"
        "hermes_rejected" -> "Hermes 拒绝了这个请求。"
        "hermes_unavailable" -> "Mac 上的 Hermes 暂时不可用。"
        else -> "无法连接 Hermes"
    }
}
