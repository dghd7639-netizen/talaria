package app.hermes.mobile.management.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.hermes.mobile.data.BotGroupMemberDto
import app.hermes.mobile.data.BotGroupMessageDto
import kotlinx.coroutines.flow.collect
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GroupsDialog(viewModel: GroupsViewModel, phoneViewModel: PhoneRoomsViewModel, onDismiss: () -> Unit) {
    var phone by remember { mutableStateOf(true) }
    val desktopState by viewModel.state.collectAsState()
    val phoneState by phoneViewModel.state.collectAsState()
    DisposableEffect(viewModel, phoneViewModel) {
        onDispose { viewModel.close(); phoneViewModel.close() }
    }
    val dismiss = { viewModel.close(); phoneViewModel.close(); onDismiss() }
    Dialog(onDismissRequest = {
        when {
            phone && (phoneState.room != null || phoneState.creating) -> phoneViewModel.back()
            !phone && desktopState.room != null -> viewModel.back()
            else -> dismiss()
        }
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = if (phone) 0 else 1) {
                    Tab(selected = phone, onClick = { phone = true }, text = { Text("手机群聊") })
                    Tab(selected = !phone, onClick = { phone = false }, text = { Text("桌面端群聊（只读）") })
                }
                if (phone) PhoneRoomsContent(phoneViewModel, dismiss) else DesktopGroupsContent(viewModel, dismiss)
            }
        }
    }
}

@Composable
private fun DesktopGroupsContent(viewModel: GroupsViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(viewModel, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            viewModel.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        viewModel.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); viewModel.setForeground(false) }
    }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val dismiss = { viewModel.close(); onDismiss() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(state.room?.name ?: "群聊", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("刷新") }
            if (state.room != null) TextButton(onClick = viewModel::back) { Text("返回列表") }
            TextButton(onClick = dismiss) { Text("关闭") }
        }
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
            Text("只读：群聊由 Mac 上的 Hermes 桌面端运行，在手机上不能发言", Modifier.padding(12.dp))
        }
        if (state.formatWarning) Text("桌面端数据格式有更新，部分内容可能无法显示",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.unstable) Text("连接不稳定", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        val room = state.room
        if (room == null) {
            if (state.empty) Text("还没有群聊；请在 Mac 上的 Hermes 桌面端「机器人 → 群聊」创建")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.rooms) { item ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = !item.room_id.isNullOrBlank()) {
                        viewModel.openRoom(item)
                    }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name ?: "未命名群聊", style = MaterialTheme.typography.titleMedium)
                        MemberChips(item.members)
                        Text("${item.message_count} 条消息 · ${groupTimestamp(item.last_at)}", style = MaterialTheme.typography.bodySmall)
                        groupOmittedNotice(item.omitted)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider()
                }
            }
        } else {
            MemberChips(room.members)
            state.omittedNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (room.total > room.messages.size) Text("当前显示最近 ${room.messages.size} 条已同步消息（共 ${room.total} 条）",
                style = MaterialTheme.typography.bodySmall)
            val list = rememberLazyListState()
            var followNewest by remember(room.room_id) { mutableStateOf(true) }
            var autoScrolling by remember(room.room_id) { mutableStateOf(false) }
            LaunchedEffect(list, room.room_id) {
                snapshotFlow { Triple(list.isScrollInProgress, list.canScrollForward, autoScrolling) }
                    .collect { (scrolling, canForward, automatic) ->
                        if (scrolling && !automatic) followNewest = !canForward
                    }
            }
            LaunchedEffect(room.room_id, room.messages) {
                if (followNewest && !list.isScrollInProgress && room.messages.isNotEmpty()) {
                    autoScrolling = true
                    try { list.scrollToItem(room.messages.lastIndex) }
                    finally { autoScrolling = false }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (room.messages.isEmpty() && !state.loading) item { Text("暂无已同步消息") }
                // Old desktop messages may have empty or duplicate IDs; position is the safe key.
                items(room.messages) { message -> GroupMessage(message) }
            }
        }
    }
}

internal fun groupMemberLabel(member: BotGroupMemberDto): String {
    val name = member.name?.takeIf { it.isNotBlank() } ?: member.handle?.takeIf { it.isNotBlank() } ?: "未知成员"
    return if (member.local) name else "$name（非本机）"
}

@Composable
private fun MemberChips(members: List<BotGroupMemberDto>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(members) { member ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(groupMemberLabel(member), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

internal fun groupAuthorLabel(message: BotGroupMessageDto): String = when (message.from_kind) {
    "user" -> "我"
    "member" -> message.from_name?.takeIf { it.isNotBlank() } ?: "未知成员"
    else -> message.from_name?.takeIf { it.isNotBlank() } ?: "其他"
}

@Composable
private fun GroupMessage(message: BotGroupMessageDto) {
    val user = message.from_kind == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text("${groupAuthorLabel(message)} · ${groupTimestamp(message.at)}", style = MaterialTheme.typography.labelSmall)
        Surface(shape = MaterialTheme.shapes.medium, color = if (user)
            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.padding(12.dp)) {
                Text(message.text.orEmpty())
                if (message.truncated) Text("(已截断)", style = MaterialTheme.typography.bodySmall)
                if (message.has_attachments) Text("[含附件，手机上暂不显示]", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun groupTimestamp(seconds: Double?): String = runCatching {
    require(seconds != null && seconds.isFinite() && seconds > 0)
    Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}.getOrDefault("时间未知")
