package app.hermes.mobile.management.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.data.AuditEventDto
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.threads.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AuditState(
    val items: List<AuditEventDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val nextBeforeId: Long? = null,
)

class AuditViewModel(
    private val api: BridgeApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AuditState())
    val state = mutableState.asStateFlow()

    fun refresh() = load(null)

    fun loadMore() {
        val cursor = state.value.nextBeforeId ?: return
        load(cursor)
    }

    private fun load(beforeId: Long?) {
        if (state.value.loading) return
        mutableState.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val page = withContext(io) { api.auditEvents(beforeId = beforeId) }
                mutableState.value = state.value.copy(
                    items = if (beforeId == null) page.items else state.value.items + page.items,
                    nextBeforeId = page.nextBeforeId,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.value.copy(error = userMessage(error))
            } finally {
                mutableState.value = state.value.copy(loading = false)
            }
        }
    }
}

class AuditViewModelFactory(private val api: BridgeApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuditViewModel(api) as T
}
