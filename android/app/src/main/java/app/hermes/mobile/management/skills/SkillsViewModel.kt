package app.hermes.mobile.management.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.data.HubMetadataDto
import app.hermes.mobile.data.HubPreviewDto
import app.hermes.mobile.data.HubScanDto
import app.hermes.mobile.data.HubSourcesDto
import app.hermes.mobile.data.HubStartedDto
import app.hermes.mobile.data.SkillDto
import app.hermes.mobile.threads.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SkillsState(
    val skills: List<SkillDto> = emptyList(),
    val query: String = "",
    val discovering: Boolean = false,
    val hubQuery: String = "",
    val hubSource: String = "all",
    val hubSources: HubSourcesDto = HubSourcesDto(),
    val hubResults: List<HubMetadataDto> = emptyList(),
    val hubPreview: HubPreviewDto? = null,
    val scan: HubScanDto? = null,
    val acknowledged: Boolean = false,
    val forceAcknowledgement: Boolean = false,
    val confirmingUninstall: Boolean = false,
    val action: HubProgress? = null,
    val selected: SkillDto? = null,
    val content: String = "",
    val draft: String = "",
    val editing: Boolean = false,
    val confirmingSave: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val needsAcknowledgement: Boolean get() = forceAcknowledgement || scan?.let {
        it.trust_level !in setOf("builtin", "trusted") || it.verdict != "safe" || it.findings.isNotEmpty()
    } == true
    val canInstall: Boolean get() = !loading && scan?.let {
        it.allowed && it.scan_id.isNotBlank() && it.identifier == hubPreview?.identifier &&
            (!needsAcknowledgement || acknowledged)
    } == true
    val filteredSkills: List<SkillDto> get() = skills.filter {
        it.name.contains(query.trim(), ignoreCase = true) ||
            it.description.orEmpty().contains(query.trim(), ignoreCase = true)
    }
    val groups: Map<String, List<SkillDto>> get() = filteredSkills
        .sortedBy { it.name.lowercase() }.groupBy { it.categoryLabel }.toSortedMap()
    val validation: String? get() = when {
        draft.isBlank() -> "技能内容不能为空或只有空白。"
        '\u0000' in draft -> "技能内容不能包含 NUL（空字符）。"
        draft.toByteArray(Charsets.UTF_8).size > 200 * 1024 -> "技能内容不能超过 200 KiB（UTF-8）。"
        else -> null
    }
}

data class HubProgress(
    val label: String,
    val running: Boolean = true,
    val logTail: String = "",
    val message: String = "正在执行…",
    val succeeded: Boolean = false,
)

class SkillsViewModel(
    private val api: BridgeApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SkillsState())
    val state = mutableState.asStateFlow()

    // Set the gate before launching, so repeated taps cannot queue concurrent requests.
    private fun request(block: suspend () -> Unit) {
        if (state.value.loading) return
        mutableState.value = state.value.copy(loading = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.value.copy(error = skillErrorMessage(error))
            } finally {
                mutableState.value = state.value.copy(loading = false)
            }
        }
    }

    fun refresh() = request {
        val skills = withContext(io) { api.skills() }
        mutableState.value = state.value.copy(skills = skills)
    }

    fun search(query: String) {
        mutableState.value = state.value.copy(query = query)
    }

    fun select(skill: SkillDto) = request {
        val detail = withContext(io) { api.skillContent(skill.name) }
        check(detail.name == skill.name)
        mutableState.value = state.value.copy(selected = skill, content = detail.content,
            draft = detail.content, editing = false, confirmingSave = false)
    }

    fun back() {
        if (!state.value.loading && !state.value.editing) {
            mutableState.value = state.value.copy(selected = null, content = "", draft = "",
                error = null, notice = null, confirmingSave = false, hubPreview = null,
                scan = null, acknowledged = false, forceAcknowledgement = false,
                confirmingUninstall = false, action = null)
        }
    }

    fun showInstalled() {
        if (!state.value.loading && !state.value.editing) {
            back()
            mutableState.value = state.value.copy(discovering = false)
        }
    }

    fun showHub() {
        if (state.value.loading || state.value.editing) return
        back()
        mutableState.value = state.value.copy(discovering = true)
        request {
            val sources = withContext(io) { api.skillHubSources() }
            mutableState.value = state.value.copy(hubSources = sources)
        }
    }

    fun editHubQuery(query: String) {
        if (!state.value.loading) mutableState.value = state.value.copy(hubQuery = query.take(200), hubResults = emptyList())
    }

    fun chooseHubSource(source: String) {
        if (!state.value.loading) mutableState.value = state.value.copy(hubSource = source, hubResults = emptyList())
    }

    fun searchHub() = request {
        val current = state.value
        if (current.hubQuery.isBlank()) {
            val sources = withContext(io) { api.skillHubSources() }
            mutableState.value = state.value.copy(hubSources = sources, hubResults = emptyList())
        } else {
            val results = withContext(io) { api.searchSkillHub(current.hubQuery.trim(), current.hubSource) }
            mutableState.value = state.value.copy(hubResults = results.results)
        }
    }

    fun previewHub(skill: HubMetadataDto) = request {
        mutableState.value = state.value.copy(hubPreview = null, scan = null, acknowledged = false,
            forceAcknowledgement = false, action = null)
        val preview = withContext(io) { api.previewSkillHub(skill.identifier) }
        check(preview.identifier == skill.identifier)
        mutableState.value = state.value.copy(hubPreview = preview)
    }

    fun scanHub() {
        val preview = state.value.hubPreview ?: return
        request {
            mutableState.value = state.value.copy(scan = null, acknowledged = false, forceAcknowledgement = false)
            val scan = withContext(io) { api.scanSkillHub(preview.identifier) }
            check(scan.identifier == preview.identifier && (!scan.allowed || scan.scan_id.isNotBlank()))
            mutableState.value = state.value.copy(scan = scan)
        }
    }

    fun acknowledgeRisk(acknowledged: Boolean) {
        if (!state.value.loading) mutableState.value = state.value.copy(acknowledged = acknowledged)
    }

    fun installHub() {
        val current = state.value
        if (!current.canInstall) return
        val scan = current.scan ?: return
        request {
            val started = try {
                withContext(io) { api.installSkillHub(scan.identifier, scan.scan_id,
                    current.needsAcknowledgement && current.acknowledged) }
            } catch (error: Exception) {
                // Only this refusal is guaranteed to preserve the receipt for a retry.
                if ((error as? BridgeRequestException)?.code == "risk_not_acknowledged") {
                    mutableState.value = state.value.copy(forceAcknowledgement = true, acknowledged = false)
                } else {
                    mutableState.value = state.value.copy(scan = null, acknowledged = false,
                        forceAcknowledgement = false)
                }
                throw error
            }
            mutableState.value = state.value.copy(scan = null, acknowledged = false)
            pollHubAction(started, "安装")
        }
    }

    fun requestUninstall() {
        val current = state.value
        if (!current.loading && !current.editing && current.selected?.provenance == "hub") {
            mutableState.value = current.copy(confirmingUninstall = true)
        }
    }

    fun cancelUninstall() {
        if (!state.value.loading) mutableState.value = state.value.copy(confirmingUninstall = false)
    }

    fun uninstallHub() {
        val current = state.value
        val selected = current.selected ?: return
        if (current.loading || current.editing || !current.confirmingUninstall || selected.provenance != "hub") return
        request {
            mutableState.value = state.value.copy(confirmingUninstall = false)
            val started = withContext(io) { api.uninstallSkillHub(selected.name) }
            pollHubAction(started, "卸载")
        }
    }

    private suspend fun pollHubAction(started: HubStartedDto, label: String) {
        check(started.action_id.isNotBlank() && started.status == "started")
        mutableState.value = state.value.copy(action = HubProgress(label))
        try {
            val completed = withTimeoutOrNull(300_000L) {
                while (true) {
                    val result = withContext(io) { api.skillHubAction(started.action_id) }
                    mutableState.value = state.value.copy(action = HubProgress(label,
                        running = result.running, logTail = result.log_tail,
                        message = if (result.running) "正在${label}…" else if (result.exit_code == 0) "${label}成功。"
                            else "${label}失败（退出码：${result.exit_code ?: "未知"}）。",
                        succeeded = !result.running && result.exit_code == 0))
                    if (!result.running) break
                    delay(1_500L)
                }
                true
            } ?: false
            if (!completed) {
                mutableState.value = state.value.copy(action = state.value.action?.copy(running = false,
                    message = "等待${label}结果超时，已停止查询；Mac 上的操作可能仍在运行，请稍后刷新技能列表。"))
            } else if (state.value.action?.succeeded == true) {
                val skills = withContext(io) { api.skills() }
                mutableState.value = state.value.copy(skills = skills, selected = null, content = "", draft = "")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = state.value.copy(action = state.value.action?.let {
                it.copy(running = false, message = if (it.succeeded) "${label}成功，但刷新技能列表失败，请返回后刷新。"
                    else "无法查询${label}结果；Mac 上的操作可能仍在运行。")
            })
            throw error
        }
    }

    fun setEnabled(skill: SkillDto, enabled: Boolean) {
        if (state.value.loading) return
        val original = state.value.skills.find { it.name == skill.name } ?: return
        replace(original.copy(enabled = enabled))
        request {
            try {
                val result = withContext(io) { api.setSkillEnabled(original.name, enabled) }
                check(result.ok && result.name == original.name)
                replace(original.copy(enabled = result.enabled))
            } catch (error: Exception) {
                replace(original)
                throw error
            }
        }
    }

    private fun replace(skill: SkillDto) {
        mutableState.value = state.value.copy(skills = state.value.skills.map {
            if (it.name == skill.name) skill else it
        })
    }

    fun startEditing() {
        if (!state.value.loading && state.value.selected != null) {
            mutableState.value = state.value.copy(editing = true, draft = state.value.content,
                error = null, notice = null)
        }
    }

    fun edit(content: String) {
        if (!state.value.loading && state.value.editing && !state.value.confirmingSave) {
            mutableState.value = state.value.copy(draft = content, error = null)
        }
    }

    fun cancelEditing() {
        if (!state.value.loading) mutableState.value = state.value.copy(editing = false,
            confirmingSave = false, draft = state.value.content, error = null)
    }

    fun requestSave() {
        val current = state.value
        if (current.loading || !current.editing) return
        mutableState.value = current.copy(error = current.validation,
            confirmingSave = current.validation == null)
    }

    fun cancelSave() {
        if (!state.value.loading) mutableState.value = state.value.copy(confirmingSave = false)
    }

    fun save() {
        val current = state.value
        val skill = current.selected ?: return
        if (current.loading || !current.editing || !current.confirmingSave) return
        mutableState.value = current.copy(confirmingSave = false)
        current.validation?.let {
            mutableState.value = state.value.copy(error = it)
            return
        }
        request {
            val result = withContext(io) { api.updateSkillContent(skill.name, current.draft) }
            check(result.ok && result.name == skill.name)
            mutableState.value = state.value.copy(content = current.draft, editing = false,
                notice = "技能内容已保存。")
        }
    }
}

internal fun skillErrorMessage(error: Exception): String = when ((error as? BridgeRequestException)?.code) {
    "scan_required" -> "扫描凭证已失效，请重新进行安全扫描。"
    "scan_expired" -> "扫描结果已过期，请重新进行安全扫描。"
    "scan_mismatch" -> "扫描结果与当前技能或设备不匹配，请重新扫描。"
    "scan_blocked" -> "已被安全扫描拦截，无法安装，请重新扫描查看结果。"
    "risk_not_acknowledged" -> "请阅读扫描结果并勾选风险确认后重试安装。"
    "skill_not_hub" -> "只能卸载社区安装的技能；该技能可能已被移除，请刷新列表。"
    "hub_action_not_found" -> "找不到此操作，可能已过期或 Bridge 已重启，请刷新技能列表。"
    "skill_not_found" -> "技能不存在，可能已被移除，请刷新列表。"
    "invalid_skill_request" -> "技能请求无效，请检查名称和内容后重试。"
    "hermes_rejected" -> "Hermes 拒绝了修改，请检查技能格式或写入保护。"
    "hermes_unavailable" -> "Mac 上的 Hermes 暂时不可用。"
    else -> userMessage(error)
}

class SkillsViewModelFactory(private val api: BridgeApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SkillsViewModel(api) as T
}
