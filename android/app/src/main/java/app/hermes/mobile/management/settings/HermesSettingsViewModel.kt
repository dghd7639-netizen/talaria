package app.hermes.mobile.management.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeRequestException
import app.hermes.mobile.data.EditableSettingDto
import app.hermes.mobile.data.HermesSettingsDto
import app.hermes.mobile.data.ProviderApiKey
import app.hermes.mobile.data.SettingsProfileDto
import app.hermes.mobile.data.SettingsProviderDto
import app.hermes.mobile.threads.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Locale

data class HermesSettingsState(
    val profiles: List<SettingsProfileDto> = emptyList(),
    val profile: String? = null,
    val settings: HermesSettingsDto? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val pendingOff: Boolean = false,
    val providerQuery: String = "",
    val error: String? = null,
    val message: String? = null,
    val contentRevision: Long = 0,
) {
    val busy: Boolean get() = loading || saving || pendingOff
    val filteredProviders: List<SettingsProviderDto> get() {
        val query = providerQuery.trim()
        return settings?.providers.orEmpty().filter {
            it.name.contains(query, ignoreCase = true) || it.slug.contains(query, ignoreCase = true)
        }.sortedWith(compareByDescending<SettingsProviderDto> { it.authenticated }
            .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.slug })
    }
}

class HermesSettingsViewModel(
    private val api: BridgeApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HermesSettingsState())
    val state = mutableState.asStateFlow()

    private fun request(saving: Boolean = false, block: suspend () -> Unit): Job? {
        if (state.value.busy) return null
        mutableState.value = state.value.copy(loading = !saving, saving = saving, error = null, message = null)
        return viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.value.copy(error = settingsErrorMessage(error))
            } finally {
                mutableState.value = state.value.copy(loading = false, saving = false)
            }
        }
    }

    fun refresh() {
        request {
            val profiles = withContext(io) { api.settingsProfiles().profiles }
            val profile = state.value.profile?.takeIf { name -> profiles.any { it.name == name } }
                ?: profiles.firstOrNull { it.isDefault }?.name ?: profiles.firstOrNull()?.name
            mutableState.value = state.value.copy(profiles = profiles, profile = profile,
                settings = state.value.settings?.takeIf { it.profile == profile }, loaded = profile == null)
            if (profile != null) loadProfile(profile)
        }
    }

    fun selectProfile(profile: String) {
        if (state.value.busy || profile == state.value.profile || state.value.profiles.none { it.name == profile }) return
        mutableState.value = state.value.copy(profile = profile, settings = null, loaded = false)
        request { loadProfile(profile) }
    }

    private suspend fun loadProfile(profile: String) {
        val settings = withContext(io) { api.settings(profile) }
        check(settings.profile == profile)
        mutableState.value = state.value.copy(settings = settings, loaded = true,
            contentRevision = state.value.contentRevision + 1)
    }

    fun setProviderQuery(query: String) {
        mutableState.value = state.value.copy(providerQuery = query)
    }

    fun chooseApprovalMode(mode: String) {
        if (state.value.busy) return
        val entry = state.value.settings?.editable?.find { it.key == "approvals.mode" } ?: return
        if (mode !in listOf("manual", "smart", "off") || entry.choices?.contains(mode) != true) return
        if (mode == "off") {
            mutableState.value = state.value.copy(pendingOff = true, error = null, message = null)
        } else saveSetting(entry.key, JsonPrimitive(mode), confirm = false)
    }

    fun cancelOff() {
        if (!state.value.saving) mutableState.value = state.value.copy(pendingOff = false)
    }

    fun confirmOff() {
        if (!state.value.pendingOff || state.value.loading || state.value.saving) return
        mutableState.value = state.value.copy(pendingOff = false)
        saveSetting("approvals.mode", JsonPrimitive("off"), confirm = true)
    }

    fun saveInteger(key: String, input: String) {
        if (state.value.busy) return
        val entries = state.value.settings?.editable ?: return
        val entry = entries.find { it.key == key } ?: return
        val error = settingValidationError(entry, input, entries)
        if (error != null) {
            mutableState.value = state.value.copy(error = error, message = null)
            return
        }
        saveSetting(key, JsonPrimitive(input.trim().toInt()), confirm = false)
    }

    private fun saveSetting(key: String, value: JsonPrimitive, confirm: Boolean) {
        val profile = state.value.profile ?: return
        request(saving = true) {
            val updated = withContext(io) { api.updateSetting(profile, key, value, confirm) }
            check(updated.key == key)
            val settings = state.value.settings ?: return@request
            mutableState.value = state.value.copy(settings = settings.copy(editable = settings.editable.map {
                if (it.key == key) updated else it
            }), message = "已保存", contentRevision = state.value.contentRevision + 1)
            refreshOverview(profile)
        }
    }

    fun saveProviderKey(slug: String, apiKey: ProviderApiKey) {
        val profile = state.value.profile
        if (profile == null || state.value.busy || state.value.settings?.providers?.none { it.slug == slug } != false) {
            apiKey.clear()
            return
        }
        if (!apiKey.printableAscii) {
            apiKey.clear()
            mutableState.value = state.value.copy(
                error = "密钥只能包含英文字母、数字和符号，请重新从服务商后台复制。", message = null)
            return
        }
        val job = request(saving = true) {
            val result = withContext(io) { api.saveProviderKey(profile, slug, apiKey) }
            check(result.slug == slug)
            val settings = state.value.settings ?: return@request
            mutableState.value = state.value.copy(settings = settings.copy(providers = settings.providers.map {
                if (it.slug == slug) it.copy(authenticated = result.authenticated) else it
            }), message = "已保存")
            refreshOverview(profile)
        }
        // Also clears if the coroutine is cancelled before its first instruction.
        job?.invokeOnCompletion { apiKey.clear() } ?: apiKey.clear()
    }

    private suspend fun refreshOverview(profile: String) {
        val refreshed = withContext(io) { api.settings(profile) }
        check(refreshed.profile == profile)
        mutableState.value = state.value.copy(settings = state.value.settings?.copy(overview = refreshed.overview))
    }
}

internal fun settingValidationError(entry: EditableSettingDto, input: String, entries: List<EditableSettingDto>): String? {
    if (entry.key !in listOf("approvals.timeout", "curator.stale_after_days", "curator.archive_after_days") ||
        entry.type != "integer" || entry.min == null || entry.max == null) return "设置不可编辑，请刷新后重试。"
    val value = input.trim().toIntOrNull() ?: return "请输入整数。"
    if (value !in entry.min..entry.max) return "数值超出允许范围"
    val stale = if (entry.key == "curator.stale_after_days") value
        else entries.find { it.key == "curator.stale_after_days" }?.value?.intOrNull
    val archive = if (entry.key == "curator.archive_after_days") value
        else entries.find { it.key == "curator.archive_after_days" }?.value?.intOrNull
    if (entry.key.startsWith("curator.")) {
        if (stale == null || archive == null) return "设置不可编辑，请刷新后重试。"
        if (archive < stale) return "归档天数不能小于闲置天数。"
    }
    return null
}

internal fun settingsErrorMessage(error: Exception): String = when ((error as? BridgeRequestException)?.code) {
    "confirm_required" -> "此操作需要确认，请确认后重试。"
    "invalid_setting_value" -> "数值超出允许范围"
    "setting_not_editable" -> "设置不可编辑，请刷新后重试。"
    "invalid_settings_request" -> "设置请求无效，请检查输入后重试。"
    "profile_not_found" -> "档案不存在，可能已被移除，请刷新列表。"
    "profile_not_supported" -> "该档案名与 Hermes 保留名冲突，请在 Mac 上改名后再设置。"
    "provider_not_found" -> "提供商不存在，可能已被移除，请刷新列表。"
    "settings_key_save_unsupported" -> "该提供商暂不支持保存密钥，请在 Mac 上配置。"
    "hermes_rejected" -> "Hermes 拒绝了操作，请在 Mac 上检查配置。"
    "hermes_unavailable" -> "Mac 上的 Hermes 暂时不可用。"
    else -> userMessage(error)
}

class HermesSettingsViewModelFactory(private val api: BridgeApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = HermesSettingsViewModel(api) as T
}
