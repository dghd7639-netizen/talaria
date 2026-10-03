package app.hermes.mobile.management.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.hermes.mobile.design.LocalHermesDark
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AuditDialog(viewModel: AuditViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val successColor = if (LocalHermesDark.current) Color(0xFF81C784) else Color(0xFF2E7D32)
    LaunchedEffect(viewModel) { viewModel.refresh() }
    AlertDialog(
        onDismissRequest = { if (!state.loading) onDismiss() },
        title = { Text("操作记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.items.isEmpty() && !state.loading && state.error == null) {
                        item { Text("暂无操作记录") }
                    }
                    items(state.items) { event ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(auditActionLabel(event.action), style = MaterialTheme.typography.titleSmall)
                            Text("目标：${event.target ?: "无"}", style = MaterialTheme.typography.bodySmall)
                            Text("设备：${event.deviceId.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                            when (event.outcome) {
                                "success" -> Text("成功", color = successColor)
                                "failure" -> {
                                    Text("失败", color = MaterialTheme.colorScheme.error)
                                    Text("错误码：${event.detail ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                                }
                                else -> Text(event.outcome.ifBlank { "结果未知" })
                            }
                            Text(auditTimestamp(event.timestamp), style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                    if (state.nextBeforeId != null) {
                        item {
                            TextButton(onClick = viewModel::loadMore, enabled = !state.loading) { Text("加载更多") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !state.loading) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("刷新") }
        },
    )
}

internal fun auditActionLabel(action: String): String = when (action) {
    "cron.create" -> "创建定时任务"
    "cron.update" -> "修改定时任务"
    "cron.pause" -> "暂停定时任务"
    "cron.resume" -> "恢复定时任务"
    "cron.trigger" -> "立即运行定时任务"
    "cron.delete" -> "删除定时任务"
    else -> action
}

internal fun auditTimestamp(value: String, zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(zone)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}.getOrElse { value.ifBlank { "时间未知" } }
