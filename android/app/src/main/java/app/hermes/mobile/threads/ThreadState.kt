package app.hermes.mobile.threads

import app.hermes.mobile.attachments.PendingAttachment
import app.hermes.mobile.data.CatalogItem
import app.hermes.mobile.data.DirectoryListing
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime

data class ThreadItem(
    val id: String,
    val title: String,
    val preview: String,
    val status: String,
    val model: String = "",
    val provider: String = "",
    val updatedAt: Double = 0.0,
)

data class ModelOption(
    val model: String,
    val provider: String,
    val label: String = model,
    val providerLabel: String = provider,
    // Other names Hermes uses for this provider, e.g. a task reports "custom:opencodex".
    val aliases: List<String> = emptyList(),
    // The model Hermes on the Mac is configured to use; new tasks start on it.
    val current: Boolean = false,
) {
    fun matches(model: String, provider: String): Boolean =
        model == this.model && (provider == this.provider || provider in aliases)
}

fun List<ThreadItem>.renamed(threadId: String, title: String): List<ThreadItem> =
    map { if (it.id == threadId) it.copy(title = title) else it }

internal enum class RecentGroup(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    THIS_WEEK("本周"),
    EARLIER("更早"),
}

internal fun groupRecentThreads(
    threads: List<ThreadItem>,
    now: ZonedDateTime,
): List<Pair<RecentGroup, List<ThreadItem>>> {
    val today = now.toLocalDate()
    val yesterday = today.minusDays(1)
    val monday = today.with(DayOfWeek.MONDAY)
    val sorted = threads.sortedByDescending { it.updatedAt }
    val grouped = sorted.groupBy { thread ->
        if (thread.updatedAt <= 0.0 || thread.updatedAt.isNaN()) {
            RecentGroup.EARLIER
        } else {
            val itemDate = Instant.ofEpochMilli((thread.updatedAt * 1000).toLong())
                .atZone(now.zone)
                .toLocalDate()
            when {
                !itemDate.isBefore(today) -> RecentGroup.TODAY
                itemDate == yesterday -> RecentGroup.YESTERDAY
                !itemDate.isBefore(monday) -> RecentGroup.THIS_WEEK
                else -> RecentGroup.EARLIER
            }
        }
    }
    return RecentGroup.entries.mapNotNull { group ->
        val items = grouped[group]
        if (!items.isNullOrEmpty()) group to items else null
    }
}

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val fromEvent: Boolean = false,
)

data class MobileEvent(
    val id: Long,
    val threadId: String,
    val type: String,
    val payload: Map<String, String>,
)

data class PendingApproval(
    val id: String,
    val threadId: String,
    val toolName: String,
    val command: String,
    val choices: List<String>,
    val reason: String,
)

data class ThreadState(
    val threads: List<ThreadItem> = emptyList(),
    val selectedThreadId: String? = null,
    val isCreatingNew: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val drafts: Map<String, String> = emptyMap(),
    val draft: String = "",
    val attachments: Map<String, PendingAttachment> = emptyMap(),
    val streamingText: String = "",
    val seenEventIds: Set<Long> = emptySet(),
    val loading: Boolean = false,
    val running: Boolean = false,
    val approvalPending: Boolean = false,
    val approval: PendingApproval? = null,
    val error: String? = null,
    val modelOptions: List<ModelOption> = emptyList(),
    val selectedModel: ModelOption? = null,
    val newTaskCwd: String? = null,
    val directoryPicker: DirectoryPickerState? = null,
    val catalogTitle: String? = null,
    val catalogItems: List<CatalogItem> = emptyList(),
    val catalogLimited: Boolean = false,
) {
    val pendingAttachment: PendingAttachment? get() = attachments[selectedThreadId]
    val canSend: Boolean get() = !loading && !running && draft.isNotBlank() &&
        (pendingAttachment == null || pendingAttachment?.ready == true)

    fun updateAttachment(threadId: String, key: String, transform: (PendingAttachment) -> PendingAttachment): ThreadState {
        val attachment = attachments[threadId] ?: return this
        if (attachment.key != key) return this
        return copy(attachments = attachments + (threadId to transform(attachment)))
    }

    fun clearAttachment(threadId: String, key: String): ThreadState =
        if (attachments[threadId]?.key == key) copy(attachments = attachments - threadId) else this

    fun chooseDirectory(path: String?): ThreadState =
        if (selectedThreadId == null) copy(newTaskCwd = path, directoryPicker = null) else this

    fun select(threadId: String?): ThreadState {
        val saved = selectedThreadId?.let { drafts + (it to draft) } ?: drafts
        val key = threadId ?: NEW_THREAD_ID
        return copy(
            selectedThreadId = threadId,
            newTaskCwd = null,
            directoryPicker = null,
            isCreatingNew = threadId == null,
            drafts = saved,
            draft = saved[key].orEmpty(),
            messages = emptyList(),
            streamingText = "",
            seenEventIds = emptySet(),
            running = threads.firstOrNull { it.id == threadId }?.status == "running",
            selectedModel = modelFor(threadId) ?: selectedModel,
            approvalPending = false,
            approval = null,
            error = null,
        )
    }

    /** What the model button shows: the task's real model, or Hermes' default for a new task. */
    fun modelFor(threadId: String?): ModelOption? {
        if (threadId == null) return modelOptions.firstOrNull { it.current } ?: modelOptions.firstOrNull()
        val thread = threads.firstOrNull { it.id == threadId } ?: return null
        if (thread.model.isBlank()) return null
        return modelOptions.firstOrNull { it.matches(thread.model, thread.provider) }
            ?: ModelOption(thread.model, thread.provider)
    }

    /** Drops a task from the list; if it was open, moves to the next one (or a new task). */
    fun without(threadId: String): ThreadState {
        val remaining = threads.filterNot { it.id == threadId }
        val cleaned = copy(threads = remaining, drafts = drafts - threadId, attachments = attachments - threadId)
        if (selectedThreadId != threadId) return cleaned
        return cleaned.copy(selectedThreadId = null).select(remaining.firstOrNull()?.id)
    }

    fun updateDraft(value: String): ThreadState {
        val key = selectedThreadId ?: NEW_THREAD_ID
        return copy(draft = value, drafts = drafts + (key to value))
    }

    fun accept(event: MobileEvent): ThreadState {
        if (event.threadId != selectedThreadId || event.id in seenEventIds) return this
        val nextSeen = seenEventIds + event.id
        return when (event.type) {
            "message.delta" -> copy(
                streamingText = streamingText + event.payload["text"].orEmpty(),
                seenEventIds = nextSeen,
                running = true,
            )
            "message.complete" -> {
                // An interrupted turn can end with no text at all; don't render an empty bubble.
                val text = event.payload["text"] ?: streamingText
                copy(
                    messages = if (text.isBlank()) messages else messages + ChatMessage(event.id.toString(), "assistant", text, fromEvent = true),
                    streamingText = "",
                    seenEventIds = nextSeen,
                )
            }
            "approval.request" -> copy(
                approvalPending = true,
                seenEventIds = nextSeen,
                running = true,
            )
            // Hermes withdrew an approval (timeout, interrupt); drop the card only if it is that one.
            "approval.cancelled" -> if (approval == null || approval.id == event.payload["id"]) {
                copy(approvalPending = false, approval = null, seenEventIds = nextSeen)
            } else {
                copy(seenEventIds = nextSeen)
            }
            "turn.complete" -> copy(running = false, seenEventIds = nextSeen)
            "turn.error" -> copy(
                running = false,
                error = event.payload["message"] ?: "Hermes 任务失败",
                seenEventIds = nextSeen,
            )
            "session.title" -> copy(
                threads = threads.map { thread ->
                    if (thread.id == event.threadId) thread.copy(title = event.payload["title"].orEmpty()) else thread
                },
                seenEventIds = nextSeen,
            )
            else -> copy(seenEventIds = nextSeen)
        }
    }

    companion object {
        const val NEW_THREAD_ID = "__new__"
    }
}

/** A null path requests the Mac home; the listing does not include its own path. */
data class DirectoryPickerState(
    val path: String? = null,
    val listing: DirectoryListing? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val currentPath: String?
        get() = path ?: listing?.items?.firstOrNull()?.path?.substringBeforeLast('/')?.ifEmpty { "/" }
}
