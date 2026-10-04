package app.hermes.mobile.management.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.hermes.mobile.data.EditableSettingDto
import app.hermes.mobile.data.ProviderApiKey
import app.hermes.mobile.data.SettingsProviderDto

@Composable
fun HermesSettingsDialog(viewModel: HermesSettingsViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var profilesOpen by remember { mutableStateOf(false) }
    var editingProvider by remember(state.profile) { mutableStateOf<SettingsProviderDto?>(null) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Hermes 设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton(onClick = { profilesOpen = true }, enabled = !state.busy && state.profiles.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text("档案：${state.profile ?: "未选择"} ▾")
                    }
                    DropdownMenu(expanded = profilesOpen, onDismissRequest = { profilesOpen = false }) {
                        state.profiles.forEach { profile ->
                            DropdownMenuItem(text = { Text(profile.name + if (profile.isDefault) "（默认）" else "") },
                                enabled = !state.busy, onClick = {
                                    profilesOpen = false
                                    viewModel.selectProfile(profile.name)
                                })
                        }
                    }
                }
                if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.loaded && state.profiles.isEmpty() && state.error == null) {
                        item { Text("暂无档案") }
                    }
                    state.settings?.let { settings ->
                        item { SettingsSectionTitle("常用设置") }
                        settings.editable.find { it.key == "approvals.mode" }?.let { entry ->
                            item {
                                Column {
                                    Text("审批模式", style = MaterialTheme.typography.titleSmall)
                                    listOf("manual" to "手动审批", "smart" to "智能", "off" to "关闭").forEach { (mode, label) ->
                                        val enabled = !state.busy && entry.choices?.contains(mode) == true
                                        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.RadioButton) {
                                            viewModel.chooseApprovalMode(mode)
                                        }, verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = entry.value.content == mode, enabled = enabled,
                                                onClick = { viewModel.chooseApprovalMode(mode) })
                                            Text(label)
                                        }
                                    }
                                }
                            }
                        }
                        settings.editable.find { it.key == "approvals.timeout" }?.let { entry ->
                            item { IntegerSettingField(state, entry, "审批超时（秒）", viewModel::saveInteger) }
                        }
                        item { Text("技能整理", style = MaterialTheme.typography.titleSmall) }
                        listOf("curator.stale_after_days" to "闲置天数", "curator.archive_after_days" to "归档天数")
                            .forEach { (key, label) ->
                                settings.editable.find { it.key == key }?.let { entry ->
                                    item { IntegerSettingField(state, entry, label, viewModel::saveInteger) }
                                }
                            }
                        item {
                            HorizontalDivider()
                            SettingsSectionTitle("API 密钥")
                            OutlinedTextField(value = state.providerQuery, onValueChange = viewModel::setProviderQuery,
                                label = { Text("搜索提供商") }, singleLine = true, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth())
                        }
                        if (state.filteredProviders.isEmpty()) {
                            item { Text(if (settings.providers.isEmpty()) "暂无 API 密钥提供商" else "没有匹配的提供商") }
                        }
                        items(state.filteredProviders, key = { "provider-${it.slug}" }) { provider ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.Button) {
                                editingProvider = provider
                            }.padding(vertical = 8.dp).semantics {
                                contentDescription = "配置 ${provider.name} API 密钥"
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(provider.name, style = MaterialTheme.typography.titleSmall)
                                    Text(provider.slug, style = MaterialTheme.typography.bodySmall)
                                }
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                                    Text(if (provider.authenticated) "已配置" else "未配置",
                                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            HorizontalDivider()
                        }
                        item { SettingsSectionTitle("当前配置（只读）") }
                        settings.overview.forEach { section ->
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(section.title, style = MaterialTheme.typography.titleSmall)
                                    section.rows.forEach { row ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(row.firstOrNull().orEmpty(), Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodySmall)
                                            Text(row.drop(1).joinToString(" "), Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("关闭") } },
        dismissButton = { TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("刷新") } },
    )
    if (state.pendingOff) {
        AlertDialog(
            onDismissRequest = viewModel::cancelOff,
            title = { Text("关闭审批？") },
            text = { Text("关闭后，该档案的危险命令将不经批准直接执行。确定关闭？") },
            confirmButton = { TextButton(onClick = viewModel::confirmOff) { Text("确定关闭") } },
            dismissButton = { TextButton(onClick = viewModel::cancelOff) { Text("取消") } },
        )
    }
    editingProvider?.let { provider ->
        ProviderKeyDialog(provider, onDismiss = { editingProvider = null }, onSave = { key ->
            editingProvider = null
            viewModel.saveProviderKey(provider.slug, key)
        })
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun IntegerSettingField(state: HermesSettingsState, entry: EditableSettingDto, label: String,
    onSave: (String, String) -> Unit) {
    var input by remember(state.profile, entry.key, entry.value, state.contentRevision) { mutableStateOf(entry.value.content) }
    val error = settingValidationError(entry, input, state.settings?.editable.orEmpty())
    Column {
        OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text(label) },
            singleLine = true, enabled = !state.busy, isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(error ?: "允许范围：${entry.min}–${entry.max}")
            }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onSave(entry.key, input) }, enabled = !state.busy && error == null,
            modifier = Modifier.align(Alignment.End).semantics { contentDescription = "保存$label" }) { Text("保存") }
    }
}

@Composable
private fun ProviderKeyDialog(provider: SettingsProviderDto, onDismiss: () -> Unit, onSave: (ProviderApiKey) -> Unit) {
    // Never use rememberSaveable or a ViewModel for a secret input.
    var apiKey by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { apiKey = "" } }
    val valid = apiKey.isNotBlank() && apiKey.codePointCount(0, apiKey.length) <= 4096 &&
        apiKey.none { it == '\u0000' || it == '\r' || it == '\n' }
    val dismiss = { apiKey = ""; onDismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("${provider.name} API 密钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("密钥只会写入 Mac 上该档案的 .env，手机和 Bridge 都不会保存或显示它。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API 密钥") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val key = ProviderApiKey(apiKey)
                apiKey = ""
                onSave(key)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}
