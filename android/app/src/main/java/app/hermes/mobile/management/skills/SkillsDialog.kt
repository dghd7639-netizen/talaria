package app.hermes.mobile.management.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SkillsDialog(viewModel: SkillsViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    AlertDialog(
        onDismissRequest = { if ((!state.loading || state.action != null) && !state.editing) onDismiss() },
        title = { Text(state.action?.let { "技能${it.label}" } ?: state.hubPreview?.name ?: state.selected?.name ?: "技能") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.notice?.let { Text(it) }
                val selected = state.selected
                if (state.action != null) {
                    HubActionContent(state)
                } else if (state.discovering) {
                    HubContent(state, viewModel)
                } else if (selected == null) {
                    SkillsTabs(state, viewModel)
                    OutlinedTextField(value = state.query, onValueChange = viewModel::search,
                        label = { Text("搜索名称或描述") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("${state.filteredSkills.size} / ${state.skills.size} 个技能",
                        style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.filteredSkills.isEmpty() && !state.loading && state.error == null) {
                            item { Text(if (state.query.isBlank()) "暂无已安装技能" else "没有匹配的技能") }
                        }
                        state.groups.forEach { (category, skills) ->
                            item(key = "category:$category") {
                                Text("$category（${skills.size}）", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            items(skills, key = { "skill:${it.name}" }) { skill ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)
                                        .clickable(enabled = !state.loading) { viewModel.select(skill) }
                                        .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(skill.name, style = MaterialTheme.typography.titleSmall)
                                        skill.description?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = MaterialTheme.shapes.small) {
                                            Text(skill.provenanceLabel, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text("使用计数：${skill.usage}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = skill.enabled,
                                        onCheckedChange = { viewModel.setEnabled(skill, it) }, enabled = !state.loading,
                                        modifier = Modifier.semantics { contentDescription = "启用技能 ${skill.name}" })
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                } else {
                    Text("SKILL.md · ${selected.provenanceLabel}", style = MaterialTheme.typography.labelLarge)
                    if (state.editing && selected.provenance in setOf("bundled", "hub")) {
                        Text("此技能的后续更新可能覆盖手动编辑的内容。",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.editing) {
                        OutlinedTextField(value = state.draft, onValueChange = viewModel::edit,
                            enabled = !state.loading, label = { Text("技能内容") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp))
                    } else {
                        SelectionContainer {
                            Text(state.content, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState()))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (state.action == null && state.selected != null && !state.discovering) {
                    if (!state.editing && state.selected?.provenance == "hub") {
                        TextButton(onClick = viewModel::requestUninstall, enabled = !state.loading) { Text("卸载") }
                    }
                    TextButton(onClick = { if (state.editing) viewModel.requestSave() else viewModel.startEditing() },
                        enabled = !state.loading) { Text(if (state.editing) "保存" else "编辑") }
                }
                if (!state.editing) {
                    TextButton(onClick = onDismiss, enabled = !state.loading || state.action != null) { Text("关闭") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                when {
                    state.editing -> viewModel.cancelEditing()
                    state.action != null || state.hubPreview != null || state.selected != null -> viewModel.back()
                    state.discovering -> viewModel.searchHub()
                    else -> viewModel.refresh()
                }
            }, enabled = !state.loading) {
                Text(when {
                    state.editing -> "取消"
                    state.action != null || state.hubPreview != null || state.selected != null -> "返回列表"
                    else -> "刷新"
                })
            }
        },
    )
    if (state.confirmingUninstall) {
        AlertDialog(
            onDismissRequest = viewModel::cancelUninstall,
            title = { Text("卸载技能？") },
            text = { Text("将从 Mac 卸载社区技能「${state.selected?.name.orEmpty()}」。") },
            confirmButton = { TextButton(onClick = viewModel::uninstallHub, enabled = !state.loading) { Text("卸载") } },
            dismissButton = { TextButton(onClick = viewModel::cancelUninstall) { Text("取消") } },
        )
    }
    if (state.confirmingSave) {
        AlertDialog(
            onDismissRequest = viewModel::cancelSave,
            title = { Text("保存技能内容？") },
            text = { Text("保存后 Hermes 会立即使用新内容") },
            confirmButton = { TextButton(onClick = viewModel::save, enabled = !state.loading) { Text("保存") } },
            dismissButton = { TextButton(onClick = viewModel::cancelSave) { Text("取消") } },
        )
    }
}

@Composable
private fun SkillsTabs(state: SkillsState, viewModel: SkillsViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !state.discovering, onClick = viewModel::showInstalled,
            enabled = !state.loading, label = { Text("已安装") })
        FilterChip(selected = state.discovering, onClick = viewModel::showHub,
            enabled = !state.loading, label = { Text("发现（Hub）") })
    }
}

private fun trustLabel(trust: String): String = when (trust) {
    "builtin" -> "内置"
    "trusted" -> "受信任"
    "community" -> "社区"
    "agent-created" -> "自建"
    else -> "未知"
}

@Composable
private fun TrustBadge(trust: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(trustLabel(trust), Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HubContent(state: SkillsState, viewModel: SkillsViewModel) {
    val preview = state.hubPreview
    if (preview == null) {
        SkillsTabs(state, viewModel)
        OutlinedTextField(value = state.hubQuery, onValueChange = viewModel::editHubQuery,
            enabled = !state.loading, label = { Text("搜索 Hub（1–200 字）") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(selected = state.hubSource == "all", onClick = { viewModel.chooseHubSource("all") },
                    enabled = !state.loading, label = { Text("全部来源") })
            }
            items(state.hubSources.sources) { source ->
                FilterChip(selected = state.hubSource == source.id,
                    onClick = { viewModel.chooseHubSource(source.id) },
                    enabled = !state.loading && source.searchable && source.available != false && source.rate_limited != true,
                    label = { Text(source.label.ifBlank { source.id } + when {
                        source.rate_limited == true -> "（限流）"
                        source.available == false -> "（不可用）"
                        else -> ""
                    }) })
            }
        }
        TextButton(onClick = viewModel::searchHub, enabled = !state.loading && state.hubQuery.isNotBlank()) { Text("搜索") }
        if (state.hubQuery.isBlank()) {
            Text("精选技能", style = MaterialTheme.typography.titleSmall)
            if (!state.hubSources.index_available) Text("Hub 索引暂不可用，可尝试按来源搜索。")
        }
        val results = if (state.hubQuery.isBlank()) state.hubSources.featured else state.hubResults
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (results.isEmpty() && !state.loading) item {
                Text(if (state.hubQuery.isBlank()) "暂无精选技能" else "输入关键词后点击搜索；搜索结果将在此显示。")
            }
            items(results) { skill ->
                Column(Modifier.fillMaxWidth().clickable(enabled = !state.loading && skill.identifier.isNotBlank()) {
                    viewModel.previewHub(skill)
                }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(skill.name, style = MaterialTheme.typography.titleSmall)
                    Text(skill.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    TrustBadge(skill.trust_level)
                    Text("来源：${skill.source}", style = MaterialTheme.typography.bodySmall)
                    if (state.skills.any { it.name == skill.name }) Text("已安装", color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(preview.description)
            TrustBadge(preview.trust_level)
            Text("来源：${preview.source}")
            Text("SKILL.md · 外部不可信文本，仅供阅读", style = MaterialTheme.typography.labelLarge)
            if (preview.truncated) Text("预览已截断，以下不是完整内容。", color = MaterialTheme.colorScheme.error)
            SelectionContainer {
                Text(preview.skill_md, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()))
            }
            HorizontalDivider()
            val scan = state.scan
            if (scan != null) {
                val verdict = when (scan.verdict) {
                    "safe" -> "安全"
                    "caution" -> "需注意"
                    "dangerous" -> "危险"
                    else -> "未知"
                }
                Text("扫描结论：$verdict", style = MaterialTheme.typography.titleSmall)
                Text("信任级别：${trustLabel(scan.trust_level)}")
                Text("有效期至：${scan.expires_at.ifBlank { "未知，请重新扫描" }}")
                scan.findings.take(50).forEach { finding ->
                    Text("${finding.severity.take(30)} · ${finding.category.take(80)}：${finding.message.take(300)}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (scan.findings.size > 50) Text("仅展示前 50 项扫描发现。")
                if (!scan.allowed) {
                    Text("已被安全扫描拦截，无法安装", color = MaterialTheme.colorScheme.error)
                } else {
                    if (state.needsAcknowledgement) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = state.acknowledged, onCheckedChange = viewModel::acknowledgeRisk,
                                enabled = !state.loading,
                                modifier = Modifier.semantics { contentDescription = "我已阅读扫描结果，了解风险" })
                            Text("我已阅读扫描结果，了解风险")
                        }
                    }
                    TextButton(onClick = viewModel::installHub, enabled = state.canInstall) { Text("安装") }
                }
            }
            TextButton(onClick = viewModel::scanHub, enabled = !state.loading) {
                Text(if (scan == null) "安全扫描" else "重新扫描")
            }
        }
    }
}

@Composable
private fun HubActionContent(state: SkillsState) {
    val action = state.action ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(action.message, color = if (!action.running && !action.succeeded) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface)
        if (action.running) Text("可以关闭此窗口；查询随当前页面生命周期结束而取消，最多等待五分钟。")
        SelectionContainer {
            Text(action.logTail.ifBlank { "暂无日志" }, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
        }
    }
}
