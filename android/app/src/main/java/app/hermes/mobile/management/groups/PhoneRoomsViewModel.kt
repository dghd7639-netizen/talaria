package app.hermes.mobile.management.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.data.CatalogItem
import app.hermes.mobile.data.PhoneApproval
import app.hermes.mobile.data.PhoneCapabilities
import app.hermes.mobile.data.PhoneEvent
import app.hermes.mobile.data.PhoneMemberInput
import app.hermes.mobile.data.PhoneRoom
import app.hermes.mobile.threads.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class PhoneRoomsState(
    val capabilities: PhoneCapabilities? = null,
    val rooms: List<PhoneRoom> = emptyList(),
    val nextOffset: Long? = null,
    val loaded: Boolean = false,
    val room: PhoneRoom? = null,
    val events: List<PhoneEvent> = emptyList(),
    val cursor: Long = 0,
    val draft: String = "",
    val draftCursor: Int = 0,
    val creating: Boolean = false,
    val profiles: List<CatalogItem> = emptyList(),
    val profilesLimited: Boolean = false,
    val name: String = "",
    val members: List<PhoneRoomMemberDraft> = emptyList(),
    val confirmingDisband: PhoneRoom? = null,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val stopping: Boolean = false,
    val resolvingRequestId: String? = null,
    val unstable: Boolean = false,
    val error: String? = null,
    val pollError: String? = null,
    val notice: String? = null,
) {
    val busy: Boolean get() = loading || sending || stopping || resolvingRequestId != null
    val approvals: List<PhoneApproval> get() = room?.driverStatus?.approvals.orEmpty()
    val empty: Boolean get() = loaded && capabilities?.available == true && rooms.isEmpty() && !loading && error == null
    val draftLength: Int get() = draft.phoneLength()
    val messageError: String? get() = phoneMessageError(draft)
    val canSend: Boolean get() = room != null && !room.disbanded && !loading && !sending &&
        draft.isNotBlank() && messageError == null
    val createError: String? get() = phoneCreateError(name, members)
    val canCreate: Boolean get() = creating && !busy && capabilities?.available != false && createError == null
    val expectedHandles: List<String> get() = expectedPhoneHandles(members)
    val visibleEvents: List<PhoneEvent> get() = events.filter {
        it.kind in setOf("message.user", "message.member") || phoneSystemLabel(it.kind) != null
    }
    val mentionHandles: List<String> get() {
        val start = phoneMentionStart(draft, draftCursor) ?: return emptyList()
        val query = draft.substring(start + 1, draftCursor)
        return (listOf("all") + room?.members.orEmpty().mapNotNull { it.handle }).distinct()
            .filter { it.startsWith(query, ignoreCase = true) }
    }
}

class PhoneRoomsViewModel(private val api: BridgeApi, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val mutableState = MutableStateFlow(PhoneRoomsState())
    val state = mutableState.asStateFlow()
    // ponytail: one IO lane; use cancellable reads if slow polling delays Stop.
    private val requests = Mutex()
    private var polling: Job? = null
    private var action: Job? = null
    private var sendJob: Job? = null
    private var stopJob: Job? = null
    private var approvalJob: Job? = null
    private val resolvedRequestIds = mutableSetOf<String>()
    private var foreground = false
    private var generation = 0
    private enum class RequestKind { LOAD, SEND, STOP }

    private suspend fun <T> request(block: () -> T): T = requests.withLock {
        val result = withContext(io) { block() }
        currentCoroutineContext().ensureActive()
        result
    }

    private fun setBusy(kind: RequestKind, value: Boolean) {
        mutableState.value = when (kind) {
            RequestKind.LOAD -> state.value.copy(loading = value)
            RequestKind.SEND -> state.value.copy(sending = value)
            RequestKind.STOP -> state.value.copy(stopping = value)
        }
    }

    private fun launchRequest(kind: RequestKind = RequestKind.LOAD, block: suspend () -> Unit) {
        if (state.value.loading || when (kind) {
                RequestKind.LOAD -> state.value.busy
                RequestKind.SEND -> state.value.sending
                RequestKind.STOP -> state.value.stopping
            }) return
        val current = generation
        mutableState.value = state.value.copy(error = null, notice = null)
        setBusy(kind, true)
        val job = viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = state.value.copy(error = phoneErrorMessage(error))
            } finally { if (current == generation) setBusy(kind, false) }
        }
        when (kind) {
            RequestKind.LOAD -> action = job
            RequestKind.SEND -> sendJob = job
            RequestKind.STOP -> stopJob = job
        }
    }

    fun refresh() {
        if (state.value.room != null) { restartPolling(); return }
        if (state.value.creating) { loadProfiles(); return }
        launchRequest {
            val capabilities = request { api.phoneCapabilities() }
            mutableState.value = state.value.copy(capabilities = capabilities)
            val page = if (capabilities.available) request { api.phoneRooms() } else null
            mutableState.value = state.value.copy(rooms = page?.rooms.orEmpty().filterNot { it.disbanded },
                nextOffset = page?.nextOffset, loaded = true)
        }
    }

    fun loadMore() {
        val offset = state.value.nextOffset ?: return
        launchRequest {
            val page = request { api.phoneRooms(offset = offset) }
            mutableState.value = state.value.copy(rooms = (state.value.rooms + page.rooms)
                .filterNot { it.disbanded }.distinctBy { it.roomId }, nextOffset = page.nextOffset?.takeIf { it > offset })
        }
    }

    fun beginCreate() {
        if (state.value.busy) return
        back()
        mutableState.value = state.value.copy(creating = true, name = "", members = emptyList())
        loadProfiles()
    }

    private fun loadProfiles() = launchRequest {
        val catalog = request { api.catalog("profiles") }
        mutableState.value = state.value.copy(profiles = catalog.items.filter { it.enabled != false }
            .distinctBy { it.id }, profilesLimited = catalog.limited)
    }

    fun editName(value: String) {
        if (!state.value.busy) mutableState.value = state.value.copy(name = value, error = null)
    }

    fun toggleProfile(profile: CatalogItem) {
        if (!state.value.creating || state.value.busy || profile !in state.value.profiles) return
        val selected = state.value.members
        val members = if (selected.any { it.profile.id == profile.id }) selected.filterNot { it.profile.id == profile.id }
            else if (selected.size < 6) selected + PhoneRoomMemberDraft(profile) else return
        mutableState.value = state.value.copy(members = members, error = null)
    }

    fun editHandle(profileId: String, handle: String?) {
        if (!state.value.busy) mutableState.value = state.value.copy(members = state.value.members.map {
            if (it.profile.id == profileId) it.copy(handle = handle) else it
        }, error = null)
    }

    fun create() {
        val snapshot = state.value
        if (!snapshot.canCreate) return
        val members = snapshot.members.map {
            val title = it.profile.title.takeIf(String::isNotBlank)?.let { title ->
                title.take(title.offsetByCodePoints(0, minOf(80, title.phoneLength())))
            }
            PhoneMemberInput(it.profile.id, it.handle, title)
        }
        launchRequest {
            val room = request { api.createPhoneRoom(snapshot.name.trim(), members) }
            mutableState.value = state.value.copy(rooms = (listOf(room) + state.value.rooms).distinctBy { it.roomId },
                creating = false, name = "", members = emptyList(), room = room, events = emptyList(), cursor = 0,
                draft = "", draftCursor = 0, unstable = false, pollError = null)
            startPolling()
        }
    }

    fun openRoom(room: PhoneRoom) {
        if (state.value.busy || room.disbanded || room.roomId.isBlank()) return
        back()
        resolvedRequestIds.clear()
        mutableState.value = state.value.copy(room = room)
        startPolling()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) startPolling() else { polling?.cancel(); polling = null }
    }

    private fun restartPolling() { polling?.cancel(); polling = null; startPolling() }

    private fun startPolling() {
        val id = state.value.room?.roomId ?: return
        if (!foreground || polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive && foreground) {
                val more = poll(id)
                if (more) yield() else delay(if (state.value.unstable) 10_000L else 2_000L)
            }
        }
    }

    private suspend fun poll(id: String): Boolean {
        try {
            val room = request { api.phoneRoom(id) }
            if (room.roomId != id) throw BridgeRequestException(503, "hermes_unavailable")
            val rawApprovals = room.driverStatus?.approvals.orEmpty()
            val filteredApprovals = rawApprovals.filterNot { it.requestId in resolvedRequestIds }
            resolvedRequestIds.retainAll(rawApprovals.map { it.requestId }.toSet())
            val updatedRoom = if (room.driverStatus != null) {
                room.copy(driverStatus = room.driverStatus.copy(approvals = filteredApprovals))
            } else room
            mutableState.value = state.value.copy(room = updatedRoom)
            // Yield after five immediate pages so a busy log cannot starve send/stop.
            repeat(5) {
                val previous = state.value.cursor
                val page = request { api.phoneEvents(id, previous) }
                if (page.cursor < previous || page.latestSeq < page.cursor || page.hasMore && page.cursor <= previous)
                    throw BridgeRequestException(503, "hermes_unavailable")
                mutableState.value = state.value.copy(events = (state.value.events + page.events)
                    .sortedBy { it.seq }.distinctBy { it.eventId }, cursor = page.cursor, unstable = false, pollError = null)
                if (!page.hasMore) return false
            }
            return true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            mutableState.value = state.value.copy(unstable = true, pollError = phoneErrorMessage(error))
            return false
        }
    }

    fun edit(text: String, cursor: Int = text.length) {
        if (!state.value.sending) mutableState.value = state.value.copy(draft = text,
            draftCursor = cursor.coerceIn(0, text.length), error = null)
    }

    fun insertMention(handle: String) {
        val snapshot = state.value
        if (snapshot.sending || handle !in snapshot.mentionHandles) return
        val start = phoneMentionStart(snapshot.draft, snapshot.draftCursor) ?: return
        val suffix = snapshot.draft.substring(snapshot.draftCursor)
        val insertion = "@$handle" + if (suffix.firstOrNull()?.isWhitespace() == true) "" else " "
        edit(snapshot.draft.take(start) + insertion + suffix, start + insertion.length)
    }

    fun send() {
        val snapshot = state.value
        if (!snapshot.canSend) return
        val id = snapshot.room?.roomId ?: return
        launchRequest(RequestKind.SEND) {
            val sent = request { api.sendPhoneMessage(id, snapshot.draft) }
            if (!sent.accepted) throw BridgeRequestException(400, "hermes_rejected")
            mutableState.value = state.value.copy(draft = "", draftCursor = 0,
                notice = if (sent.driverStarted) null else "消息已接收，成员回复尚未启动。")
            restartPolling()
        }
    }

    fun stop() {
        val id = state.value.room?.roomId ?: return
        launchRequest(RequestKind.STOP) {
            val stopped = request { api.stopPhoneRoom(id) }
            mutableState.value = state.value.copy(notice = "已请求停止，取消任务数：${stopped.cancelled}。")
            restartPolling()
        }
    }

    fun resolveApproval(requestId: String, choice: String) {
        if (state.value.resolvingRequestId != null) return
        val roomId = state.value.room?.roomId ?: return
        val current = generation
        mutableState.value = state.value.copy(resolvingRequestId = requestId, error = null)
        approvalJob = viewModelScope.launch {
            try {
                val approval = state.value.room?.driverStatus?.approvals?.firstOrNull { it.requestId == requestId }
                if (approval != null && choice !in approval.choices) {
                    throw BridgeRequestException(400, "choice_not_offered")
                }
                request { api.resolvePhoneApproval(roomId, requestId, choice) }
                if (current != generation) return@launch
                resolvedRequestIds.add(requestId)
                val currentRoom = state.value.room
                if (currentRoom != null && currentRoom.driverStatus != null) {
                    val updatedApprovals = currentRoom.driverStatus.approvals.filterNot { it.requestId == requestId }
                    val updatedDriver = currentRoom.driverStatus.copy(approvals = updatedApprovals)
                    mutableState.value = state.value.copy(
                        room = currentRoom.copy(driverStatus = updatedDriver),
                        resolvingRequestId = null,
                    )
                } else {
                    mutableState.value = state.value.copy(resolvingRequestId = null)
                }
                restartPolling()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) {
                    mutableState.value = state.value.copy(resolvingRequestId = null, error = phoneErrorMessage(error))
                }
            } finally {
                if (current == generation && state.value.resolvingRequestId == requestId) {
                    mutableState.value = state.value.copy(resolvingRequestId = null)
                }
            }
        }
    }
    fun resolveApproval(approval: PhoneApproval, choice: String) = resolveApproval(approval.requestId, choice)

    fun requestDisband(room: PhoneRoom) {
        if (!state.value.busy) mutableState.value = state.value.copy(confirmingDisband = room)
    }
    fun cancelDisband() { if (!state.value.loading) mutableState.value = state.value.copy(confirmingDisband = null) }

    fun disband() {
        val room = state.value.confirmingDisband ?: return
        if (state.value.busy) return
        mutableState.value = state.value.copy(confirmingDisband = null)
        launchRequest {
            val result = request { api.disbandPhoneRoom(room.roomId) }
            if (!result.disbanded) throw BridgeRequestException(400, "hermes_rejected")
            if (state.value.room?.roomId == room.roomId) {
                polling?.cancel(); polling = null
                mutableState.value = state.value.copy(room = null, events = emptyList(), cursor = 0, draft = "",
                    draftCursor = 0, unstable = false, pollError = null)
            }
            mutableState.value = state.value.copy(rooms = state.value.rooms.filterNot { it.roomId == room.roomId },
                notice = "群聊已解散。")
        }
    }

    fun back() {
        generation++
        polling?.cancel(); polling = null
        action?.cancel(); action = null
        sendJob?.cancel(); sendJob = null
        stopJob?.cancel(); stopJob = null
        approvalJob?.cancel(); approvalJob = null
        resolvedRequestIds.clear()
        mutableState.value = state.value.copy(room = null, events = emptyList(), cursor = 0, draft = "", draftCursor = 0,
            creating = false, name = "", members = emptyList(), confirmingDisband = null, loading = false, resolvingRequestId = null,
            sending = false, stopping = false, unstable = false, pollError = null, error = null, notice = null)
    }
    fun close() { foreground = false; back() }
}

internal fun phoneErrorMessage(error: Exception): String = if (error is IllegalArgumentException)
    userMessage(BridgeRequestException(400, "invalid_group_request")) else userMessage(error)

internal fun phoneMemberLabel(memberId: String, room: PhoneRoom): String =
    room.members.firstOrNull { it.memberId == memberId }?.let {
        it.displayName?.takeIf(String::isNotBlank) ?: it.handle?.takeIf(String::isNotBlank)
    } ?: "未知成员"

internal fun phoneActorLabel(event: PhoneEvent, room: PhoneRoom): String = when (event.kind) {
    "message.user" -> "我"
    "message.member" -> phoneMemberLabel(event.actor.id, room)
    else -> "系统"
}

// Only events worth interrupting a conversation for; per-turn bookkeeping (started/settled/activity) stays hidden.
internal fun phoneSystemLabel(kind: String): String? = when (kind) {
    "turn.failed" -> "成员回复失败，请在 Mac 上检查。"
    "member.unavailable" -> "有成员暂时不可用。"
    "turn.cancelled" -> "成员回复已取消。"
    "room.stop_requested" -> "已请求停止成员回复。"
    "turn.deferred" -> "成员回复已暂停，请在 Mac 上处理。"
    "room.disbanded" -> "群聊已解散。"
    else -> null
}

class PhoneRoomsViewModelFactory(private val api: BridgeApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = PhoneRoomsViewModel(api) as T
}
