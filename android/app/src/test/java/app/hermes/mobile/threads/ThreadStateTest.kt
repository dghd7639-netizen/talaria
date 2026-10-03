package app.hermes.mobile.threads

import app.hermes.mobile.attachments.AttachmentStatus
import app.hermes.mobile.attachments.PendingAttachment
import app.hermes.mobile.data.DirectoryEntry
import app.hermes.mobile.data.DirectoryListing
import app.hermes.mobile.data.BridgeRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadStateTest {
    @Test
    fun attachmentMustBeReadyBeforeSendingAndStaysWithItsStoredThread() {
        val pending = PendingAttachment("pick-1", name = "notes.txt", size = 12)
        val state = ThreadState(selectedThreadId = "stored", draft = "Inspect", attachments = mapOf("stored" to pending))
        assertFalse(state.canSend)
        val failed = state.updateAttachment("stored", pending.key) { it.copy(status = AttachmentStatus.FAILED) }
        assertFalse(failed.canSend)
        val ready = state.updateAttachment("stored", pending.key) { it.copy(status = AttachmentStatus.READY, uploadId = "u1", progress = 100) }
        assertTrue(ready.canSend)
        assertFalse(ready.copy(loading = true).canSend)
        assertFalse(ready.copy(running = true).canSend)
        assertNull(ready.select(null).pendingAttachment)
        assertNull(ready.select("other").pendingAttachment)
        assertEquals(ready.pendingAttachment, ready.select("other").select("stored").pendingAttachment)
        assertNull(ready.clearAttachment("stored", pending.key).pendingAttachment)
        assertTrue(ready.without("stored").attachments.isEmpty())
    }

    @Test
    fun removedOrRetriedAttachmentIgnoresStaleProgressAndCompletion() {
        val current = PendingAttachment("retry")
        val state = ThreadState(selectedThreadId = "stored", attachments = mapOf("stored" to current))
        assertEquals(state, state.updateAttachment("stored", "old") { it.copy(progress = 100) })
        assertEquals(state, state.clearAttachment("stored", "old"))
        val removed = state.clearAttachment("stored", "retry")
        assertEquals(removed, removed.updateAttachment("stored", "retry") { it.copy(status = AttachmentStatus.READY) })
    }

    @Test
    fun attachedReferenceSurvivesSendFailureAndIsIncludedOnlyOncePerAttempt() {
        val attached = PendingAttachment("pick", status = AttachmentStatus.READY, uploadId = "u1", attached = true, refText = "@file/ref")
        val state = ThreadState(selectedThreadId = "stored", draft = "Inspect", attachments = mapOf("stored" to attached))
        val failure = state.copy(error = "无法连接 Hermes")
        assertTrue(failure.canSend)
        assertTrue(failure.pendingAttachment!!.attached)
        assertEquals("Inspect\n\n@file/ref", failure.pendingAttachment!!.messageText(failure.draft))
        assertEquals("Inspect\n\n@file/ref", failure.pendingAttachment!!.messageText(failure.draft))
        assertEquals("Inspect", attached.copy(refText = null).messageText("Inspect"))
        assertEquals("Inspect", attached.copy(refText = "").messageText("Inspect"))
    }

    @Test
    fun attachmentErrorsHaveActionableChineseMessages() {
        assertEquals("附件不能超过 50 MiB。", userMessage(BridgeRequestException(413, "upload_too_large")))
        assertEquals("附件分块过大，请重新上传。", userMessage(BridgeRequestException(413, "chunk_too_large")))
        assertEquals("附件校验失败，文件可能已变化，请重新上传。", userMessage(BridgeRequestException(409, "checksum_mismatch")))
        assertEquals("附件已过期或不存在，请重新上传。", userMessage(BridgeRequestException(404, "upload_not_found")))
        assertEquals("不支持这种文件类型，请选择图片、PDF 或文本文件。", userMessage(BridgeRequestException(415, "unsupported_media_type")))
    }

    @Test
    fun newTaskDirectoryIsChosenExplicitlyAndCanReturnToDefault() {
        val initial = ThreadState()
        assertNull(initial.newTaskCwd)
        assertNull(initial.directoryPicker)
        val browsing = initial.copy(directoryPicker = DirectoryPickerState(path = "/Users/mac/project"))
        assertNull(browsing.newTaskCwd)
        val chosen = browsing.chooseDirectory("/Users/mac/project")
        assertEquals("/Users/mac/project", chosen.newTaskCwd)
        assertNull(chosen.directoryPicker)
        assertNull(chosen.chooseDirectory(null).newTaskCwd)
        assertEquals("/Users/mac/project", chosen.updateDraft("task").newTaskCwd)
    }

    @Test
    fun selectingATaskOrStartingAnotherClearsTheDirectoryAndPicker() {
        val state = ThreadState(
            newTaskCwd = "/Users/mac/project",
            directoryPicker = DirectoryPickerState(path = "/Users/mac/other"),
        )
        listOf(state.select("task-1"), state.select(null)).forEach {
            assertNull(it.newTaskCwd)
            assertNull(it.directoryPicker)
        }
        val existing = state.select("task-1")
        assertEquals(existing, existing.chooseDirectory("/Users/mac/project"))
    }

    @Test
    fun pickerResolvesHomeFromChildrenButNeverGuessesAnEmptyHome() {
        val listing = DirectoryListing(listOf(DirectoryEntry("/Users/mac/project", "project")), null)
        assertEquals("/Users/mac", DirectoryPickerState(listing = listing, loading = false).currentPath)
        assertNull(DirectoryPickerState(listing = DirectoryListing(emptyList(), null), loading = false).currentPath)
        assertEquals("/Users/mac/empty", DirectoryPickerState(path = "/Users/mac/empty", listing = DirectoryListing(emptyList(), "/Users/mac"), loading = false).currentPath)
    }

    @Test
    fun directoryFailuresHaveReadableMessages() {
        assertEquals("无权访问此文件夹，请选择 Mac 用户主目录内的文件夹。", userMessage(BridgeRequestException(403, "directory_not_allowed")))
        assertEquals("找不到此文件夹，它可能已被移动或删除。", userMessage(BridgeRequestException(404, "directory_not_found")))
        assertEquals("Mac 上的 Hermes 暂时不可用。", userMessage(BridgeRequestException(503, "hermes_unavailable")))
    }

    @Test
    fun replacementCredentialGetsFreshViewModelKeyWithoutExposingSecret() {
        val first = connectionViewModelKey("first-device-secret")
        val second = connectionViewModelKey("second-device-secret")

        assertTrue(first != second)
        assertTrue("first-device-secret" !in first)
    }

    @Test
    fun approvalEventMarksThreadWaitingUntilApprovalIsLoaded() {
        val state = ThreadState(selectedThreadId = "thread-1", running = true)

        val next = state.accept(
            MobileEvent(7, "thread-1", "approval.request", emptyMap()),
        )

        assertTrue(next.running)
        assertTrue(next.approvalPending)
    }

    @Test
    fun modelButtonShowsTheModelTheTaskReallyUses() {
        val free = ModelOption("upstage/solar-pro4:free", "nous", current = true)
        val astra = ModelOption("gpt-6-astra", "opencodex", aliases = listOf("custom:opencodex", "opencodex"))
        val state = ThreadState(
            threads = listOf(
                ThreadItem("task-1", "Task", "", "idle", model = "gpt-6-astra", provider = "custom:opencodex"),
                ThreadItem("task-2", "Old", "", "idle", model = "retired-model", provider = "gone"),
            ),
            modelOptions = listOf(free, astra),
        )

        assertEquals(astra, state.select("task-1").selectedModel)
        // A new task starts on the model Hermes is configured to use, not the first in the list.
        assertEquals(free, state.select(null).selectedModel)
        // A task on a model that is no longer offered still shows its real model.
        assertEquals("retired-model", state.select("task-2").selectedModel?.model)
    }

    @Test
    fun interruptedTurnWithoutTextAddsNoEmptyBubble() {
        val state = ThreadState(selectedThreadId = "thread-1", running = true)

        val next = state.accept(
            MobileEvent(9, "thread-1", "message.complete", mapOf("status" to "interrupted")),
        )

        assertTrue(next.messages.isEmpty())
        assertEquals("", next.streamingText)
    }

    @Test
    fun withdrawnApprovalClearsItsCard() {
        val approval = PendingApproval("approval-1", "thread-1", "terminal", "rm -rf build", listOf("once", "deny"), "")
        val state = ThreadState(selectedThreadId = "thread-1", approvalPending = true, approval = approval)

        val next = state.accept(
            MobileEvent(9, "thread-1", "approval.cancelled", mapOf("id" to "approval-1")),
        )

        assertFalse(next.approvalPending)
        assertNull(next.approval)
    }

    @Test
    fun withdrawingAnotherApprovalKeepsTheVisibleCard() {
        val approval = PendingApproval("approval-2", "thread-1", "terminal", "make", listOf("once", "deny"), "")
        val state = ThreadState(selectedThreadId = "thread-1", approvalPending = true, approval = approval)

        val next = state.accept(
            MobileEvent(9, "thread-1", "approval.cancelled", mapOf("id" to "approval-1")),
        )

        assertTrue(next.approvalPending)
        assertEquals(approval, next.approval)
    }

    @Test
    fun selectionRestoresPerThreadDraft() {
        var state = ThreadState().select("thread-1").updateDraft("first")
        state = state.select("thread-2").updateDraft("second")

        assertEquals("first", state.select("thread-1").draft)
        assertEquals("second", state.select("thread-2").draft)
    }

    @Test
    fun selectingRunningThreadExposesStopState() {
        val state = ThreadState(
            threads = listOf(ThreadItem("thread-1", "Task", "", "running")),
        )

        assertTrue(state.select("thread-1").running)
    }

    @Test
    fun duplicateDeltasAppendOnceAndCompletionSealsMessage() {
        var state = ThreadState(selectedThreadId = "thread-1")
        val delta = MobileEvent(7, "thread-1", "message.delta", mapOf("text" to "Hel"))
        state = state.accept(delta).accept(delta)
        state = state.accept(
            MobileEvent(8, "thread-1", "message.complete", mapOf("text" to "Hello")),
        )

        assertEquals("", state.streamingText)
        assertEquals("Hello", state.messages.last().text)
        assertTrue(state.messages.last().fromEvent)
        assertFalse(ChatMessage("history-1", "assistant", "Hello").fromEvent)
    }

    @Test
    fun titleEventUpdatesMatchingDesktopThread() {
        val state = ThreadState(
            selectedThreadId = "thread-1",
            threads = listOf(ThreadItem("thread-1", "", "", "idle")),
        ).accept(MobileEvent(9, "thread-1", "session.title", mapOf("title" to "同步标题")))

        assertEquals("同步标题", state.threads.single().title)
    }

    @Test
    fun removingSelectedThreadMovesToNextOne() {
        val state = ThreadState(
            threads = listOf(ThreadItem("a", "A", "", "idle"), ThreadItem("b", "B", "", "idle")),
            selectedThreadId = "a",
        ).without("a")

        assertEquals(listOf("b"), state.threads.map { it.id })
        assertEquals("b", state.selectedThreadId)
    }

    @Test
    fun removingLastThreadStartsNewTask() {
        val state = ThreadState(
            threads = listOf(ThreadItem("a", "A", "", "idle")),
            selectedThreadId = "a",
        ).without("a")

        assertEquals(null, state.selectedThreadId)
        assertTrue(state.isCreatingNew)
    }

    @Test
    fun removingOtherThreadKeepsSelection() {
        val state = ThreadState(
            threads = listOf(ThreadItem("a", "A", "", "idle"), ThreadItem("b", "B", "", "idle")),
            selectedThreadId = "a",
        ).without("b")

        assertEquals("a", state.selectedThreadId)
    }

    @Test
    fun renameUpdatesOnlyMatchingThread() {
        val threads = listOf(ThreadItem("a", "A", "", "idle"), ThreadItem("b", "B", "", "idle"))

        assertEquals(listOf("新名", "B"), threads.renamed("a", "新名").map { it.title })
    }
}
