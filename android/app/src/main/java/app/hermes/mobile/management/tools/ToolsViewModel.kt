package app.hermes.mobile.management.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.data.McpServerDto
import app.hermes.mobile.data.ToolsetDto
import app.hermes.mobile.threads.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ToolsState(
    val toolsets: List<ToolsetDto> = emptyList(),
    val servers: List<McpServerDto> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val pendingTest: String? = null,
    val testing: String? = null,
    val testResults: Map<String, String> = emptyMap(),
) {
    val busy: Boolean get() = loading || pendingTest != null
    val groups: Map<String, List<ToolsetDto>> get() = toolsets.sortedBy { it.label.ifBlank { it.name } }
        .groupBy { it.platformLabel.ifBlank { it.platform.ifBlank { "未分类" } } }.toSortedMap()
}

class ToolsViewModel(
    private val api: BridgeApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ToolsState())
    val state = mutableState.asStateFlow()
    // Kept for this ViewModel session, including dialog reopening; never persisted to disk.
    private var testConfirmed = false

    private fun request(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.value.copy(error = toolsErrorMessage(error))
            } finally {
                mutableState.value = state.value.copy(loading = false, testing = null)
            }
        }
    }

    fun refresh() = request {
        val toolsets = withContext(io) { api.toolsets() }
        val servers = withContext(io) { api.mcpServers().servers }
        mutableState.value = state.value.copy(toolsets = toolsets, servers = servers, loaded = true,
            testResults = state.value.testResults.filterKeys { name -> servers.any { it.name == name } })
    }

    fun setToolsetEnabled(toolset: ToolsetDto, enabled: Boolean) {
        if (state.value.busy) return
        val original = state.value.toolsets.find { it.name == toolset.name && it.name.isNotBlank() } ?: return
        replace(original.copy(enabled = enabled))
        request {
            try {
                val result = withContext(io) { api.setToolsetEnabled(original.name, enabled) }
                check(result.ok && result.name == original.name)
                replace(original.copy(enabled = result.enabled))
            } catch (error: Exception) {
                replace(original)
                throw error
            }
        }
    }

    fun setMcpEnabled(server: McpServerDto, enabled: Boolean) {
        if (state.value.busy) return
        val original = state.value.servers.find { it.name == server.name && it.name.isNotBlank() } ?: return
        replace(original.copy(enabled = enabled))
        request {
            try {
                val result = withContext(io) { api.setMcpEnabled(original.name, enabled) }
                check(result.ok && result.name == original.name)
                replace(original.copy(enabled = result.enabled))
            } catch (error: Exception) {
                replace(original)
                throw error
            }
        }
    }

    private fun replace(toolset: ToolsetDto) {
        mutableState.value = state.value.copy(toolsets = state.value.toolsets.map {
            if (it.name == toolset.name) toolset else it
        })
    }

    private fun replace(server: McpServerDto) {
        mutableState.value = state.value.copy(servers = state.value.servers.map {
            if (it.name == server.name) server else it
        })
    }

    fun requestTest(server: McpServerDto) {
        if (state.value.busy || server.name.isBlank() || state.value.servers.none { it.name == server.name }) return
        if (testConfirmed) test(server.name)
        else mutableState.value = state.value.copy(pendingTest = server.name, error = null)
    }

    fun cancelTest() {
        if (!state.value.loading) mutableState.value = state.value.copy(pendingTest = null)
    }

    fun confirmTest() {
        val name = state.value.pendingTest ?: return
        if (state.value.loading) return
        testConfirmed = true
        mutableState.value = state.value.copy(pendingTest = null)
        test(name)
    }

    private fun test(name: String) {
        mutableState.value = state.value.copy(testing = name, testResults = state.value.testResults - name)
        request {
            val message = try {
                val result = withContext(io) { api.testMcpServer(name) }
                check(result.ok && result.toolCount >= 0 && result.prompts >= 0 && result.resources >= 0)
                "连接成功：${result.toolCount} 个工具、${result.prompts} 个提示、${result.resources} 个资源"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                "连接失败：${toolsErrorMessage(error)}"
            }
            mutableState.value = state.value.copy(testResults = state.value.testResults + (name to message))
        }
    }
}

internal fun toolsErrorMessage(error: Exception): String = when ((error as? BridgeRequestException)?.code) {
    "toolset_not_found" -> "工具集不存在，可能已被移除，请刷新列表。"
    "mcp_not_found" -> "MCP 服务器不存在，可能已被移除，请刷新列表。"
    "invalid_toolset_request" -> "工具集请求无效，请刷新列表后重试。"
    "invalid_mcp_request" -> "MCP 请求无效，请刷新列表后重试。"
    "hermes_rejected" -> "Hermes 拒绝了操作，请在 Mac 上检查配置。"
    "hermes_unavailable" -> "Mac 上的 Hermes 暂时不可用。"
    else -> userMessage(error)
}

class ToolsViewModelFactory(private val api: BridgeApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ToolsViewModel(api) as T
}
