package app.hermes.mobile.management.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ToolsDialog(viewModel: ToolsViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("工具集与 MCP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TabRow(selectedTabIndex = tab) {
                    listOf("工具集", "MCP").forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (tab == 0) {
                    Text("启用部分工具集时 Hermes 可能在后台安装依赖", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.loaded && !state.loading && state.error == null && state.toolsets.isEmpty()) {
                            item { Text("暂无工具集") }
                        }
                        state.groups.forEach { (platform, toolsets) ->
                            item { Text(platform, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary) }
                            items(toolsets) { toolset ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(toolset.label.ifBlank { toolset.name }, style = MaterialTheme.typography.titleSmall)
                                        if (toolset.description.isNotBlank()) {
                                            Text(toolset.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (!toolset.configured) Text("需要配置", style = MaterialTheme.typography.labelSmall)
                                        if (!toolset.available) Text("不可用", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Switch(checked = toolset.enabled,
                                        onCheckedChange = { viewModel.setToolsetEnabled(toolset, it) },
                                        enabled = !state.busy && toolset.name.isNotBlank(),
                                        modifier = Modifier.semantics {
                                            contentDescription = "启用工具集 ${toolset.label.ifBlank { toolset.name }}"
                                        })
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                } else {
                    Text("开关更改将在下一次 Hermes 会话或网关启动时生效。", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.loaded && !state.loading && state.error == null && state.servers.isEmpty()) {
                            item { Text("尚未配置 MCP 服务器；需要在 Mac 上添加") }
                        }
                        items(state.servers) { server ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(server.name, style = MaterialTheme.typography.titleSmall)
                                        Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = MaterialTheme.shapes.small) {
                                            Text(when (server.transport) {
                                                "http" -> "HTTP"
                                                "stdio" -> "本地命令"
                                                else -> "未知"
                                            }, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                        val address = when (server.transport) {
                                            "http" -> server.urlHost
                                            "stdio" -> server.commandName
                                            else -> null
                                        }
                                        address?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Switch(checked = server.enabled,
                                        onCheckedChange = { viewModel.setMcpEnabled(server, it) },
                                        enabled = !state.busy && server.name.isNotBlank(),
                                        modifier = Modifier.semantics { contentDescription = "启用 MCP 服务器 ${server.name}" })
                                }
                                TextButton(onClick = { viewModel.requestTest(server) },
                                    enabled = !state.busy && server.name.isNotBlank(),
                                    modifier = Modifier.semantics { contentDescription = "测试连接 ${server.name}" }) {
                                    if (state.testing == server.name) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text("测试中…", Modifier.padding(start = 8.dp))
                                    } else Text("测试连接")
                                }
                                state.testResults[server.name]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("关闭") } },
        dismissButton = { TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("刷新") } },
    )
    if (state.pendingTest != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelTest,
            title = { Text("测试连接？") },
            text = { Text("测试连接可能会在 Mac 上启动该服务器配置的命令") },
            confirmButton = { TextButton(onClick = viewModel::confirmTest) { Text("测试连接") } },
            dismissButton = { TextButton(onClick = viewModel::cancelTest) { Text("取消") } },
        )
    }
}
