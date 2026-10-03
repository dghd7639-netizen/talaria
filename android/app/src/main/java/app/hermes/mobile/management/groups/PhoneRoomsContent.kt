package app.hermes.mobile.management.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.hermes.mobile.data.PhoneApproval
import app.hermes.mobile.data.PhoneEvent
import app.hermes.mobile.data.PhoneRoom
import app.hermes.mobile.design.HermesColors
import kotlinx.coroutines.flow.collect

@Composable
internal fun PhoneRoomsContent(viewModel: PhoneRoomsViewModel, onDismiss: () -> Unit) {
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
    LaunchedEffect(viewModel) { if (viewModel.state.value.room == null) viewModel.refresh() }
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.creating) "新建群聊" else state.room?.name ?: "手机群聊", Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.room == null && !state.creating) {
                TextButton(onClick = viewModel::beginCreate, enabled = !state.busy && state.capabilities?.available != false) { Text("新建") }
            } else TextButton(onClick = viewModel::back, enabled = !state.busy) { Text("返回列表") }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.unstable) Text("连接不稳定 · ${state.pollError.orEmpty()}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val room = state.room
        when {
            state.creating -> PhoneRoomCreateForm(state, viewModel)
            room != null -> PhoneRoomConversation(state, viewModel, room)
            else -> PhoneRoomList(state, viewModel)
        }
    }
    state.confirmingDisband?.let { room ->
        AlertDialog(onDismissRequest = viewModel::cancelDisband,
            title = { Text("解散群聊？") }, text = { Text("「${room.name}」将永久删除，不可恢复。") },
            confirmButton = { TextButton(onClick = viewModel::disband, enabled = !state.busy) { Text("解散群聊") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDisband) { Text("取消") } })
    }
}

@Composable
private fun ColumnScope.PhoneRoomList(state: PhoneRoomsState, viewModel: PhoneRoomsViewModel) {
    Text("手机群聊由 Mac 运行，和桌面端群聊分开保存。", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("刷新") }
    if (state.capabilities?.available == false) Text("Mac 上的 Hermes 群聊服务暂不可用，请在 Mac 上检查后刷新。")
    else if (state.capabilities?.driver == false) Text("群聊驱动暂不可用，成员可能无法回复。", style = MaterialTheme.typography.bodySmall)
    if (state.empty) Text("新建一个群聊")
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.rooms, key = { it.roomId }) { room ->
            var menuOpen by remember(room.roomId) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled = !state.busy) { viewModel.openRoom(room) }
                    .padding(vertical = 12.dp)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${room.memberCount} 位成员 · ${groupTimestamp(room.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    TextButton(onClick = { menuOpen = true }, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = "${room.name}的更多操作" }) { Text("更多") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("解散群聊") }, enabled = !state.busy,
                            onClick = { menuOpen = false; viewModel.requestDisband(room) })
                    }
                }
            }
            HorizontalDivider()
        }
        if (state.nextOffset != null) item {
            TextButton(onClick = viewModel::loadMore, enabled = !state.busy) { Text("加载更多") }
        }
    }
}

@Composable
private fun ColumnScope.PhoneRoomCreateForm(state: PhoneRoomsState, viewModel: PhoneRoomsViewModel) {
    OutlinedTextField(value = state.name, onValueChange = viewModel::editName, enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("群名称") },
        supportingText = { Text("${state.name.trim().phoneLength()}/200") })
    Text("选择本机档案（${state.members.size}/6，至少 2 位）", style = MaterialTheme.typography.titleSmall)
    if (state.profilesLimited) Text("档案列表不完整，请在 Mac 上检查。", style = MaterialTheme.typography.bodySmall)
    val handles = state.expectedHandles
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.profiles.isEmpty() && !state.loading) item {
            Text("暂无本机档案，请在 Mac 上创建档案后刷新。")
            TextButton(onClick = viewModel::refresh) { Text("刷新档案") }
        }
        items(state.profiles, key = { it.id }) { profile ->
            val index = state.members.indexOfFirst { it.profile.id == profile.id }
            val selected = index >= 0
            val enabled = !state.busy && (selected || state.members.size < 6)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { viewModel.toggleProfile(profile) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selected, onCheckedChange = { viewModel.toggleProfile(profile) }, enabled = enabled)
                    Column(Modifier.weight(1f)) {
                        Text(profile.title.ifBlank { profile.id })
                        if (profile.subtitle.isNotBlank()) Text(profile.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (selected) {
                    val member = state.members[index]
                    OutlinedTextField(value = member.handle ?: handles[index],
                        onValueChange = { viewModel.editHandle(profile.id, it) }, enabled = !state.busy,
                        label = { Text("@ 名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(if (member.handle == null) "自动生成，以创建后的房间为准" else "小写字母、数字或 . _ : -，最多 32 字符") })
                    if (member.handle != null) TextButton(onClick = { viewModel.editHandle(profile.id, null) },
                        enabled = !state.busy) { Text("使用默认 @ 名称") }
                }
                HorizontalDivider()
            }
        }
    }
    state.createError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Button(onClick = viewModel::create, enabled = state.canCreate, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.loading) "创建中" else "创建")
    }
}

@Composable
private fun ColumnScope.PhoneRoomConversation(state: PhoneRoomsState, viewModel: PhoneRoomsViewModel, room: PhoneRoom) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(room.members) { member ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(member.displayName?.takeIf { it.isNotBlank() } ?: member.handle ?: "未知成员",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    val driver = room.driverStatus
    val approvals = state.approvals
    // `running` only says the room scheduler is alive (always true); `working` means a member is replying.
    if (driver?.working == true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("成员正在回复…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::stop, enabled = !state.loading && !state.stopping) {
                Text(if (state.stopping) "停止中" else "停止")
            }
        }
    } else if (driver?.blocked == true && approvals.isEmpty()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("成员回复已暂停", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::stop, enabled = !state.loading && !state.stopping) {
                Text(if (state.stopping) "停止中" else "停止")
            }
        }
    }
    Row {
        TextButton(onClick = viewModel::refresh) { Text("刷新") }
        TextButton(onClick = { viewModel.requestDisband(room) }, enabled = !state.busy) { Text("解散群聊") }
    }
    val list = rememberLazyListState()
    val events = state.visibleEvents
    var followNewest by remember(room.roomId) { mutableStateOf(true) }
    var autoScrolling by remember(room.roomId) { mutableStateOf(false) }
    LaunchedEffect(list, room.roomId) {
        snapshotFlow { Triple(list.isScrollInProgress, list.canScrollForward, autoScrolling) }
            .collect { (scrolling, canForward, automatic) -> if (scrolling && !automatic) followNewest = !canForward }
    }
    LaunchedEffect(room.roomId, events.lastOrNull()?.eventId, events.size) {
        if (followNewest && !list.isScrollInProgress && events.isNotEmpty()) {
            autoScrolling = true
            try { list.scrollToItem(events.lastIndex) } finally { autoScrolling = false }
        }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (events.isEmpty()) item { Text("暂无消息，发送一条消息开始群聊。") }
        items(events, key = { it.eventId }) { event -> PhoneRoomEvent(event, room) }
    }
    if (approvals.isNotEmpty()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            approvals.forEach { approval ->
                PhoneApprovalCard(approval, room, state.busy, viewModel::resolveApproval)
            }
        }
    }
    if (state.mentionHandles.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(state.mentionHandles, key = { it }) { handle ->
            FilterChip(selected = false, onClick = { viewModel.insertMention(handle) }, enabled = !state.sending,
                label = { Text("@$handle") })
        }
    }
    var input by remember(room.roomId) { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draftCursor))) }
    val field = if (input.text == state.draft && input.selection.end == state.draftCursor) input
        else TextFieldValue(state.draft, TextRange(state.draftCursor))
    OutlinedTextField(value = field,
        onValueChange = { input = it; viewModel.edit(it.text, it.selection.end) }, enabled = !state.sending,
        modifier = Modifier.fillMaxWidth(), label = { Text("发送到群聊，输入 @ 选择成员") }, maxLines = 5,
        isError = state.messageError != null,
        supportingText = { Text(state.messageError ?: "${state.draftLength}/8000") })
    Button(onClick = viewModel::send, enabled = state.canSend, modifier = Modifier.align(Alignment.End)) {
        Text(if (state.sending) "发送中" else "发送")
    }
}

@Composable
private fun PhoneRoomEvent(event: PhoneEvent, room: PhoneRoom) {
    if (event.kind !in setOf("message.user", "message.member")) {
        phoneSystemLabel(event.kind)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall) }
        return
    }
    val user = event.kind == "message.user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text("${phoneActorLabel(event, room)} · ${groupTimestamp(event.createdAt)}", style = MaterialTheme.typography.labelSmall)
        Surface(shape = MaterialTheme.shapes.medium, color = if (user) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.secondaryContainer) { Text(event.text, Modifier.padding(12.dp)) }
    }
}

@Composable
private fun PhoneApprovalCard(
    approval: PhoneApproval,
    room: PhoneRoom,
    disabled: Boolean,
    onResolve: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.shapes.medium,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("需要审批 · ${phoneMemberLabel(approval.memberId, room)}", fontWeight = FontWeight.SemiBold, color = HermesColors.Waiting)
        approval.toolName?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (approval.description.isNotBlank()) {
            Text(approval.description, style = MaterialTheme.typography.bodyMedium)
        }
        if (approval.command.isNotBlank()) {
            Text(
                approval.command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if ("once" in approval.choices) {
                Button(
                    enabled = !disabled,
                    onClick = { onResolve(approval.requestId, "once") },
                ) {
                    Text("允许一次")
                }
            }
            if ("deny" in approval.choices) {
                Button(
                    enabled = !disabled,
                    onClick = { onResolve(approval.requestId, "deny") },
                ) {
                    Text("拒绝")
                }
            }
        }
    }
}
