package app.hermes.mobile.management.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.data.BotGroupDetailDto
import app.hermes.mobile.data.BotGroupRoomDto
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
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

data class GroupsState(
    val rooms: List<BotGroupRoomDto> = emptyList(),
    val room: BotGroupDetailDto? = null,
    val loaded: Boolean = false,
    val listFormatWarning: Boolean = false,
    val loading: Boolean = false,
    val unstable: Boolean = false,
    val error: String? = null,
) {
    val empty: Boolean get() = loaded && rooms.isEmpty() && !loading && error == null
    val formatWarning: Boolean get() = room?.format_warning ?: listFormatWarning
    val omittedNotice: String? get() = groupOmittedNotice(room?.omitted ?: 0)
}

class GroupsViewModel(private val api: BridgeApi, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val mutableState = MutableStateFlow(GroupsState())
    val state = mutableState.asStateFlow()
    private val requests = Mutex()
    private var refreshJob: Job? = null
    private var foreground = false
    private var generation = 0

    fun refresh() {
        if (!state.value.loading) startRefresh(immediate = true)
    }

    fun openRoom(room: BotGroupRoomDto) {
        val id = room.room_id?.takeIf { it.isNotBlank() } ?: return
        back()
        mutableState.value = state.value.copy(room = BotGroupDetailDto(
            room_id = id, name = room.name, members = room.members, omitted = room.omitted,
            revision = room.revision, format_warning = state.value.listFormatWarning,
        ))
        refresh()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (state.value.room == null) return
        if (!value) cancelRefresh()
        else if (refreshJob?.isActive != true) startRefresh(immediate = false)
    }

    // One job owns both manual reads and polling, so requests cannot queue behind a timer.
    private fun startRefresh(immediate: Boolean) {
        cancelRefresh()
        val current = generation
        val id = state.value.room?.room_id
        mutableState.value = state.value.copy(loading = immediate)
        refreshJob = viewModelScope.launch {
            try {
                if (immediate) load(id)
                while (isActive && id != null && foreground) {
                    delay(if (state.value.unstable) 30_000L else 10_000L)
                    load(id)
                }
            } finally {
                if (current == generation) mutableState.value = state.value.copy(loading = false)
            }
        }
    }

    private suspend fun <T> request(block: () -> T): T = requests.withLock { withContext(io) { block() } }

    private suspend fun load(id: String?) {
        mutableState.value = state.value.copy(loading = true, error = null)
        try {
            if (id == null) {
                val page = request { api.botGroups() }
                currentCoroutineContext().ensureActive()
                mutableState.value = state.value.copy(rooms = page.rooms, loaded = true,
                    listFormatWarning = page.format_warning, unstable = false)
            } else {
                val detail = request { api.botGroupRoom(id) }
                currentCoroutineContext().ensureActive()
                mutableState.value = state.value.copy(room = detail.copy(room_id = id,
                    messages = detail.messages.sortedBy { it.at }), unstable = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            mutableState.value = state.value.copy(unstable = id != null, error = groupErrorMessage(error))
        }
        mutableState.value = state.value.copy(loading = false)
    }

    private fun cancelRefresh() {
        generation++
        refreshJob?.cancel()
        refreshJob = null
        mutableState.value = state.value.copy(loading = false)
    }

    fun back() {
        cancelRefresh()
        mutableState.value = state.value.copy(room = null, unstable = false, error = null)
    }

    fun close() { foreground = false; back() }
}

internal fun groupOmittedNotice(omitted: Long): String? =
    if (omitted > 0) "更早的 $omitted 条消息未同步" else null

internal fun groupErrorMessage(error: Exception): String = when ((error as? BridgeRequestException)?.code) {
    "invalid_bot_group_request" -> "群聊请求无效，请返回列表刷新后重试。"
    "bot_group_not_found" -> "群聊不存在或已被删除，请返回列表刷新。"
    "hermes_unavailable" -> "Mac 上的 Hermes 桌面端群聊暂时不可用，请检查后重试。"
    else -> if (error is IllegalArgumentException) "群聊请求无效，请返回列表刷新后重试。" else userMessage(error)
}

class GroupsViewModelFactory(private val api: BridgeApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GroupsViewModel(api) as T
}
