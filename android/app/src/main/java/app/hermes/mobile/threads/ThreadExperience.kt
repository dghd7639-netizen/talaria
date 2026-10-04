package app.hermes.mobile.threads

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermes.mobile.attachments.ATTACHMENT_MIME_TYPES
import app.hermes.mobile.attachments.AttachmentStatus
import app.hermes.mobile.attachments.readAttachment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.LinearProgressIndicator
import app.hermes.mobile.management.tools.ToolsDialog
import app.hermes.mobile.management.tools.ToolsViewModel
import app.hermes.mobile.management.tools.ToolsViewModelFactory
import app.hermes.mobile.management.settings.HermesSettingsDialog
import app.hermes.mobile.management.settings.HermesSettingsViewModel
import app.hermes.mobile.management.settings.HermesSettingsViewModelFactory
import app.hermes.mobile.management.skills.SkillsDialog
import app.hermes.mobile.management.skills.SkillsViewModel
import app.hermes.mobile.management.skills.SkillsViewModelFactory
import app.hermes.mobile.management.groups.GroupsDialog
import app.hermes.mobile.management.groups.GroupsViewModel
import app.hermes.mobile.management.groups.GroupsViewModelFactory
import app.hermes.mobile.management.groups.PhoneRoomsViewModel
import app.hermes.mobile.management.groups.PhoneRoomsViewModelFactory
import app.hermes.mobile.management.audit.AuditDialog
import app.hermes.mobile.management.audit.AuditViewModel
import app.hermes.mobile.management.audit.AuditViewModelFactory
import app.hermes.mobile.management.cron.CronDialog
import app.hermes.mobile.management.cron.CronViewModel
import app.hermes.mobile.management.cron.CronViewModelFactory
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.BridgeEventSocket
import app.hermes.mobile.HermesMobileApp
import app.hermes.mobile.design.ThemeMode
import app.hermes.mobile.design.HermesColors
import app.hermes.mobile.design.LocalHermesDark
import app.hermes.mobile.pairing.DeviceConnection
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Search
import java.security.MessageDigest
import java.time.ZonedDateTime
import kotlin.math.roundToInt

@Composable
fun ThreadExperience(
    connection: DeviceConnection,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeMode: (ThemeMode) -> Unit = {},
    onAuthenticationExpired: () -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as HermesMobileApp
    val client = remember(connection) { application.createHttpClient(connection) }
    val json = remember { Json { ignoreUnknownKeys = true } }
    val api = remember(connection) { BridgeApi(connection, client, json, onAuthenticationExpired) }
    var toolsOpen by remember(connection) { mutableStateOf(false) }
    var skillsOpen by remember(connection) { mutableStateOf(false) }
    var cronOpen by remember(connection) { mutableStateOf(false) }
    var groupsOpen by remember(connection) { mutableStateOf(false) }
    var auditOpen by remember(connection) { mutableStateOf(false) }
    var hermesSettingsOpen by remember(connection) { mutableStateOf(false) }
    val factory = remember(connection) {
        ThreadViewModelFactory(
            api,
            BridgeEventSocket(connection, client, json, onAuthenticationExpired),
        )
    }
    val viewModel: ThreadViewModel = viewModel(
        key = connectionViewModelKey(connection.deviceSecret),
        factory = factory,
    )
    val cronFactory = remember(api) { CronViewModelFactory(api) }
    val cronViewModel: CronViewModel = viewModel(
        key = "cron-${connectionViewModelKey(connection.deviceSecret)}", factory = cronFactory,
    )
    if (cronOpen) CronDialog(cronViewModel) { cronOpen = false }
    val groupsFactory = remember(api) { GroupsViewModelFactory(api) }
    val groupsViewModel: GroupsViewModel = viewModel(
        key = "groups-${connectionViewModelKey(connection.deviceSecret)}", factory = groupsFactory,
    )
    val phoneRoomsFactory = remember(api) { PhoneRoomsViewModelFactory(api) }
    val phoneRoomsViewModel: PhoneRoomsViewModel = viewModel(
        key = "phone-rooms-${connectionViewModelKey(connection.deviceSecret)}", factory = phoneRoomsFactory,
    )
    if (groupsOpen) GroupsDialog(groupsViewModel, phoneRoomsViewModel) { groupsOpen = false }
    val auditFactory = remember(api) { AuditViewModelFactory(api) }
    val auditViewModel: AuditViewModel = viewModel(
        key = "audit-${connectionViewModelKey(connection.deviceSecret)}", factory = auditFactory,
    )
    if (auditOpen) AuditDialog(auditViewModel) { auditOpen = false }
    val skillsFactory = remember(api) { SkillsViewModelFactory(api) }
    val skillsViewModel: SkillsViewModel = viewModel(
        key = "skills-${connectionViewModelKey(connection.deviceSecret)}", factory = skillsFactory,
    )
    if (skillsOpen) SkillsDialog(skillsViewModel) { skillsOpen = false }
    val toolsFactory = remember(api) { ToolsViewModelFactory(api) }
    val toolsViewModel: ToolsViewModel = viewModel(
        key = "tools-${connectionViewModelKey(connection.deviceSecret)}", factory = toolsFactory,
    )
    if (toolsOpen) ToolsDialog(toolsViewModel) { toolsOpen = false }
    val settingsFactory = remember(api) { HermesSettingsViewModelFactory(api) }
    val settingsViewModel: HermesSettingsViewModel = viewModel(
        key = "settings-${connectionViewModelKey(connection.deviceSecret)}", factory = settingsFactory,
    )
    if (hermesSettingsOpen) HermesSettingsDialog(settingsViewModel) { hermesSettingsOpen = false }
    val state by viewModel.state.collectAsState()
    ThreadScreen(
        state = state,
        onNew = viewModel::newThread,
        onSearch = viewModel::refresh,
        onSelect = viewModel::select,
        onRename = viewModel::renameThread,
        onArchive = viewModel::archiveThread,
        onDelete = viewModel::deleteThread,
        onDraft = viewModel::updateDraft,
        onModel = viewModel::setModel,
        onOpenDirectories = { viewModel.browseDirectories() },
        onBrowseDirectories = viewModel::browseDirectories,
        onChooseDirectory = viewModel::chooseDirectory,
        onCloseDirectories = viewModel::closeDirectoryPicker,
        onCatalog = { section, title ->
            when (section) {
                "groups" -> groupsOpen = true
                "jobs" -> cronOpen = true
                "skills" -> skillsOpen = true
                "tools" -> toolsOpen = true
                else -> viewModel.openCatalog(section, title)
            }
        },
        onCloseCatalog = viewModel::closeCatalog,
        onPickAttachment = { threadId, uri ->
            viewModel.pickAttachment(threadId) { readAttachment(application.contentResolver, uri) }
        },
        onRetryAttachment = viewModel::retryAttachment,
        onRemoveAttachment = viewModel::removeAttachment,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onApproval = viewModel::resolveApproval,
        onDisconnect = onAuthenticationExpired,
        onAudit = { auditOpen = true },
        onHermesSettings = { hermesSettingsOpen = true },
        themeMode = themeMode,
        onThemeMode = onThemeMode,
    )
}

internal fun connectionViewModelKey(secret: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(secret.toByteArray())
        .joinToString(prefix = "thread-", separator = "") { "%02x".format(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadScreen(
    state: ThreadState,
    onNew: () -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onArchive: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDraft: (String) -> Unit,
    onModel: (ModelOption) -> Unit,
    onOpenDirectories: () -> Unit,
    onBrowseDirectories: (String?) -> Unit,
    onChooseDirectory: (String?) -> Unit,
    onCloseDirectories: () -> Unit,
    onCatalog: (String, String) -> Unit,
    onCloseCatalog: () -> Unit,
    onPickAttachment: (String, Uri) -> Unit,
    onRetryAttachment: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onApproval: (String) -> Unit,
    onDisconnect: () -> Unit,
    onAudit: () -> Unit,
    onHermesSettings: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.targetValue }.collect {
            if (it == DrawerValue.Open) {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            }
        }
    }
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    var disconnectConfirmationOpen by remember { mutableStateOf(false) }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ThreadDrawer(
                state = state,
                onNew = {
                    onNew()
                    scope.launch { drawerState.close() }
                },
                onSearch = onSearch,
                onSelect = {
                    onSelect(it)
                    scope.launch { drawerState.close() }
                },
                onRename = onRename,
                onArchive = onArchive,
                onDelete = onDelete,
                onCatalog = onCatalog,
                themeMode = themeMode,
                onThemeMode = onThemeMode,
                onSettings = { settingsOpen = true },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            state.threads.firstOrNull { it.id == state.selectedThreadId }?.title
                                ?: "新任务",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "打开菜单")
                        }
                    },
                )
            },
            bottomBar = {
                Composer(
                    state = state,
                    onDraft = onDraft,
                    onModel = onModel,
                    onOpenDirectories = onOpenDirectories,
                    onBrowseDirectories = onBrowseDirectories,
                    onChooseDirectory = onChooseDirectory,
                    onCloseDirectories = onCloseDirectories,
                    onPickAttachment = onPickAttachment,
                    onRetryAttachment = onRetryAttachment,
                    onRemoveAttachment = onRemoveAttachment,
                    onSend = onSend,
                    onStop = onStop,
                )
            },
        ) { padding ->
            Conversation(
                state,
                onApproval,
                Modifier.fillMaxSize().padding(padding),
            )
        }
    }
    state.catalogTitle?.let { title ->
        AlertDialog(
            onDismissRequest = onCloseCatalog,
            confirmButton = { Button(onClick = onCloseCatalog) { Text("关闭") } },
            title = { Text(title) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .height(420.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (state.catalogLimited) item { Text("当前桌面后端尚未开放此同步接口") }
                    if (!state.catalogLimited && state.catalogItems.isEmpty()) item { Text("暂无内容") }
                    items(state.catalogItems, key = { it.id }) { item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                                .padding(12.dp),
                        ) {
                            Text(item.title, fontWeight = FontWeight.SemiBold)
                            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
        )
    }
    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            confirmButton = { Button(onClick = { settingsOpen = false }) { Text("完成") } },
            title = { Text("设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("外观", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                                .clickable { onThemeMode(mode) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == themeMode, onClick = { onThemeMode(mode) })
                            Text(label)
                        }
                    }
                    OutlinedButton(
                        onClick = { settingsOpen = false; onHermesSettings() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Hermes 设置") }
                    OutlinedButton(
                        onClick = { settingsOpen = false; onAudit() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("操作记录") }
                    OutlinedButton(
                        onClick = {
                            settingsOpen = false
                            disconnectConfirmationOpen = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("断开连接", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
    if (disconnectConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { disconnectConfirmationOpen = false },
            title = { Text("断开连接？") },
            text = { Text("断开后需要重新扫码配对") },
            confirmButton = {
                Button(onClick = {
                    disconnectConfirmationOpen = false
                    onDisconnect()
                }) { Text("断开连接") }
            },
            dismissButton = {
                OutlinedButton(onClick = { disconnectConfirmationOpen = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadDrawer(
    state: ThreadState,
    onNew: () -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onArchive: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCatalog: (String, String) -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var menuThreadId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ThreadItem?>(null) }
    var deleting by remember { mutableStateOf<ThreadItem?>(null) }
    val context = LocalContext.current
    val splitStore = remember(context) { SidebarSplitStore(context) }
    var recentRatio by remember(splitStore) { mutableStateOf(splitStore.read()) }
    renaming?.let { thread ->
        var title by remember(thread.id) { mutableStateOf(thread.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名任务") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                Button(
                    enabled = title.isNotBlank(),
                    onClick = { onRename(thread.id, title); renaming = null },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
    deleting?.let { thread ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除任务？") },
            text = { Text("“${thread.title.ifBlank { "未命名任务" }}”将从 Hermes 中永久删除，无法恢复。") },
            confirmButton = {
                Button(onClick = { onDelete(thread.id); deleting = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Text("Talaria", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 4.dp))
        Text("MOBILE REMOTE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        Button(
            onClick = onNew,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 40.dp),
        ) {
            Text("新建任务")
        }
        BasicTextField(
            value = query,
            onValueChange = {
                query = it
                onSearch(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "搜索对话",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )
        BoxWithConstraints(Modifier.weight(1f)) {
            val density = LocalDensity.current
            val dividerHeight = 24.dp
            val available = (maxHeight - dividerHeight).coerceAtLeast(0.dp)
            val minimum = minOf(96.dp, available / 2)
            val recentHeight = (available * recentRatio).coerceIn(minimum, available - minimum)
            val availablePx = with(density) { available.toPx() }
            val dragState = rememberDraggableState { delta ->
                recentRatio = ratioAfterDrag(recentRatio, delta, availablePx)
            }

            Column {
                Column(Modifier.height(recentHeight)) {
                    Text("最近任务", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    val now = remember(state.threads) { ZonedDateTime.now() }
                    val recentGroups = remember(state.threads, now) { groupRecentThreads(state.threads, now) }
                    LazyColumn(Modifier.weight(1f)) {
                        recentGroups.forEach { (group, threads) ->
                            item(key = "group:${group.name}") {
                                Text(
                                    group.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 4.dp),
                                )
                            }
                            items(threads, key = { it.id }) { thread ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { onSelect(thread.id) },
                                            onLongClick = { menuThreadId = thread.id },
                                        )
                                        .background(
                                            if (thread.id == state.selectedThreadId) MaterialTheme.colorScheme.surfaceVariant
                                            else Color.Transparent,
                                        )
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.width(8.dp).height(8.dp).background(statusColor(thread.status), CircleShape))
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(thread.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (thread.preview.isNotBlank()) {
                                            Text(thread.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        DropdownMenu(
                                            expanded = menuThreadId == thread.id,
                                            onDismissRequest = { menuThreadId = null },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("重命名") },
                                                onClick = { menuThreadId = null; renaming = thread },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("归档") },
                                                onClick = { menuThreadId = null; onArchive(thread.id) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                                onClick = { menuThreadId = null; deleting = thread },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(dividerHeight)
                        .semantics { contentDescription = "调整最近任务与工作区高度；双击恢复默认" }
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { splitStore.write(recentRatio) },
                        )
                        .combinedClickable(
                            onClick = {},
                            onDoubleClick = {
                                recentRatio = DEFAULT_SIDEBAR_RATIO
                                splitStore.write(recentRatio)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    HorizontalDivider()
                }
                Column(Modifier.height(available - recentHeight)) {
                    Text("工作区", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(
                            listOf(
                                "artifacts" to "产物", "messaging" to "消息平台", "jobs" to "定时任务",
                                "profiles" to "配置档案", "skills" to "技能", "tools" to "工具",
                                "groups" to "群聊",
                            ),
                        ) { (section, label) ->
                            Text(label, modifier = Modifier.fillMaxWidth().clickable { onCatalog(section, label) }.padding(horizontal = 20.dp, vertical = 9.dp))
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onSettings() }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.width(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("设置", style = MaterialTheme.typography.labelLarge)
        }
        HorizontalDivider()
        Text("已连接 Mac mini", style = MaterialTheme.typography.bodySmall, color = HermesColors.Running, modifier = Modifier.padding(20.dp))
    }
}

@Composable
private fun Conversation(
    state: ThreadState,
    onApproval: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var lastAutoScrolledThreadId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.selectedThreadId, state.loading, state.messages.size) {
        val threadId = state.selectedThreadId
        if (shouldAutoScrollToLatest(lastAutoScrolledThreadId, threadId, state.loading, state.messages.size)) {
            listState.scrollToItem(
                latestConversationItemIndex(
                    state.messages.size,
                    state.streamingText.isNotBlank(),
                    state.running && state.streamingText.isBlank(),
                    state.approval != null,
                    state.error != null,
                ),
            )
            lastAutoScrolledThreadId = threadId
        }
    }
    val layoutInfo = listState.layoutInfo
    val total = layoutInfo.totalItemsCount.coerceAtLeast(1)
    val visibleItems = layoutInfo.visibleItemsInfo
    val visible = visibleItems.size.coerceAtLeast(1)
    val canScroll = listState.canScrollBackward || listState.canScrollForward
    val thumbFraction = (visible.toFloat() / total).coerceIn(0.08f, 1f)
    val firstItemSize = visibleItems.firstOrNull()?.size?.toFloat()?.coerceAtLeast(1f) ?: 1f
    val offsetFraction = when {
        !listState.canScrollBackward -> 0f
        !listState.canScrollForward -> 1f
        else -> ((listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset / firstItemSize) /
            (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    var draggingScrollbar by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var scrollTargetIndex by remember { mutableStateOf(0) }
    var scrollbarScrollRequest by remember { mutableStateOf(0) }
    LaunchedEffect(scrollbarScrollRequest) {
        if (scrollbarScrollRequest > 0) listState.scrollToItem(scrollTargetIndex)
    }
    BoxWithConstraints(modifier) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.loading && state.messages.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }
        items(state.messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
        if (state.streamingText.isNotBlank()) {
            item { MessageBubble(ChatMessage("stream", "assistant", state.streamingText + "▍")) }
        }
        if (state.running && state.streamingText.isBlank()) {
            item(key = "running") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HermesAvatar()
                    Text("Hermes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在运行…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        state.approval?.let { approval ->
            item { ApprovalCard(approval, state.loading, onApproval) }
        }
        state.error?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(14.dp))
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    val trackHeight = maxHeight - 16.dp
    val thumbHeight = trackHeight * thumbFraction
    val density = LocalDensity.current
    val draggableRangePx = with(density) { (trackHeight - thumbHeight).toPx() }
    if (canScroll && draggableRangePx > 0f) {
        fun targetFor(fraction: Float) {
            dragFraction = fraction.coerceIn(0f, 1f)
            scrollTargetIndex = conversationTargetIndex(dragFraction, total, visible)
            scrollbarScrollRequest += 1
        }
        Box(
            Modifier
                .height(trackHeight)
                .width(48.dp)
                .align(Alignment.CenterEnd)
                .semantics {
                    contentDescription = "拖动以滚动任务输出"
                    verticalScrollAxisRange = ScrollAxisRange(
                        value = { if (draggingScrollbar) dragFraction else offsetFraction },
                        maxValue = { 1f },
                    )
                    scrollBy { _, y ->
                        targetFor((if (draggingScrollbar) dragFraction else offsetFraction) + y / draggableRangePx)
                        true
                    }
                }
                .draggable(
                    state = rememberDraggableState { delta -> targetFor(dragFraction + delta / draggableRangePx) },
                    orientation = Orientation.Vertical,
                    onDragStarted = { targetFor(offsetFraction); draggingScrollbar = true },
                    onDragStopped = { draggingScrollbar = false },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(thumbHeight)
                    .offset(y = (trackHeight - thumbHeight) * offsetFraction)
                    .background(MaterialTheme.colorScheme.outline),
            )
        }
    }
    }
}

@Composable
private fun ApprovalCard(
    approval: PendingApproval,
    loading: Boolean,
    onApproval: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.shapes.medium,
            )
            .padding(14.dp),
    ) {
        Text("需要审批", fontWeight = FontWeight.SemiBold, color = HermesColors.Waiting)
        if (approval.toolName.isNotBlank()) Text(approval.toolName)
        if (approval.reason.isNotBlank()) Text(approval.reason)
        if (approval.command.isNotBlank()) Text(approval.command)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            approval.choices.forEach { choice ->
                Button(
                    enabled = !loading,
                    onClick = { onApproval(choice) },
                ) {
                    Text(approvalChoiceLabel(choice))
                }
            }
        }
    }
}

private fun approvalChoiceLabel(choice: String): String = when (choice) {
    "once", "approve", "allow" -> "允许一次"
    "always", "allow_always" -> "始终允许"
    "deny", "reject" -> "拒绝"
    else -> choice
}

@Composable
private fun HermesAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .background(
                if (LocalHermesDark.current) HermesColors.DarkUserBubble else HermesColors.LightUserBubble,
                CircleShape,
            )
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text("H", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == "user"
    if (user) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .background(
                        if (LocalHermesDark.current) HermesColors.DarkUserBubble else HermesColors.LightUserBubble,
                        MaterialTheme.shapes.medium,
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), MaterialTheme.shapes.medium)
                    .padding(14.dp),
            ) {
                Text("你", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(message.text)
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HermesAvatar()
                Text("Hermes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .background(
                        if (LocalHermesDark.current) HermesColors.DarkHermesBubble else HermesColors.LightSurface,
                        MaterialTheme.shapes.small,
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                    .padding(14.dp),
            ) {
                Text(message.text)
            }
        }
    }
}

@Composable
private fun Composer(
    state: ThreadState,
    onDraft: (String) -> Unit,
    onModel: (ModelOption) -> Unit,
    onOpenDirectories: () -> Unit,
    onBrowseDirectories: (String?) -> Unit,
    onChooseDirectory: (String?) -> Unit,
    onCloseDirectories: () -> Unit,
    onPickAttachment: (String, Uri) -> Unit,
    onRetryAttachment: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var pickerThreadId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val onPickerResult: (Uri?) -> Unit = { uri ->
        val threadId = pickerThreadId
        if (uri != null && threadId != null && threadId == state.selectedThreadId) onPickAttachment(threadId, uri)
        pickerThreadId = null
    }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { onPickerResult(it) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { onPickerResult(it) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val expansionStore = remember(context) { ModelProviderExpansionStore(context) }
    val storedExpansion = remember(expansionStore) { expansionStore.read() }
    var expandedProviders by remember(expansionStore) {
        mutableStateOf(initialExpandedProviders(storedExpansion, state.selectedModel?.provider))
    }
    LaunchedEffect(storedExpansion, state.selectedModel?.provider) {
        if (storedExpansion == null && expandedProviders.isEmpty()) {
            expandedProviders = initialExpandedProviders(null, state.selectedModel?.provider)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Above the nav bar, or above the keyboard when it is open (union = the larger).
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (state.selectedThreadId == null) {
            OutlinedButton(onClick = onOpenDirectories, enabled = !state.loading) {
                Text(
                    "工作目录 · ${state.newTaskCwd?.substringAfterLast('/')?.ifEmpty { "/" } ?: "默认目录"}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.directoryPicker?.let { picker ->
                DirectoryPickerDialog(picker, onBrowseDirectories, onChooseDirectory, onCloseDirectories)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (state.modelOptions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Box {
                    OutlinedButton(
                        onClick = { modelMenu = true },
                        modifier = Modifier.height(32.dp).widthIn(max = 220.dp),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text(
                            "模型 · ${state.selectedModel?.label ?: "选择"}",
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    if (modelMenu) {
                        ModelPickerDialog(
                            options = state.modelOptions,
                            selected = state.selectedModel,
                            expandedProviders = expandedProviders,
                            onToggleProvider = { provider ->
                                expandedProviders = toggleProvider(expandedProviders, provider)
                                expansionStore.write(expandedProviders)
                            },
                            onPick = { onModel(it); modelMenu = false },
                            onDismiss = { modelMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        state.pendingAttachment?.let { attachment ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val size = attachment.size?.let { android.text.format.Formatter.formatShortFileSize(context, it) } ?: "大小未知"
                            val status = when (attachment.status) {
                                AttachmentStatus.UPLOADING -> "上传中 ${attachment.progress}%"
                                AttachmentStatus.READY -> if (attachment.attached) "已附加，等待消息发送" else "已就绪"
                                AttachmentStatus.FAILED -> "上传失败"
                            }
                            Text("$size · $status", style = MaterialTheme.typography.bodySmall)
                        }
                        if (attachment.status == AttachmentStatus.FAILED) {
                            TextButton(onClick = onRetryAttachment, enabled = !state.loading) { Text("重试") }
                        }
                        IconButton(onClick = onRemoveAttachment, enabled = !state.loading && !attachment.attached) {
                            Icon(Icons.Default.Close, contentDescription = if (attachment.status == AttachmentStatus.UPLOADING) "取消上传并移除附件" else "移除附件")
                        }
                    }
                    if (attachment.status == AttachmentStatus.UPLOADING) {
                        LinearProgressIndicator(progress = { attachment.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    attachment.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = state.draft,
                onValueChange = onDraft,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "发送任务给 Hermes" },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                minLines = 1,
                maxLines = 6,
                decorationBox = { innerTextField ->
                    // Styling lives here (not on the field's modifier) so the whole pill is tappable.
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraLarge)
                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraLarge)
                            .padding(
                                start = 14.dp,
                                end = if (state.selectedThreadId != null) 6.dp else 14.dp,
                                top = 6.dp,
                                bottom = 6.dp,
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .align(Alignment.CenterVertically),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (state.draft.isEmpty()) {
                                    Text(
                                        "发送任务给 Hermes",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clearAndSetSemantics {},
                                    )
                                }
                                innerTextField()
                            }
                            if (state.selectedThreadId != null) {
                                Spacer(Modifier.width(4.dp))
                                Box {
                                    IconButton(
                                        modifier = Modifier.size(32.dp),
                                        enabled = !state.loading && state.pendingAttachment == null,
                                        onClick = { attachMenuOpen = true },
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = "添加图片或文件",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = attachMenuOpen,
                                        onDismissRequest = { attachMenuOpen = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("图片") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Image,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            },
                                            onClick = {
                                                attachMenuOpen = false
                                                pickerThreadId = state.selectedThreadId
                                                photoPicker.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                )
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("文件") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Description,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            },
                                            onClick = {
                                                attachMenuOpen = false
                                                pickerThreadId = state.selectedThreadId
                                                document.launch(ATTACHMENT_MIME_TYPES.toTypedArray())
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                enabled = state.running || state.canSend,
                onClick = if (state.running) onStop else onSend,
            ) {
                Icon(
                    if (state.running) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (state.running) "停止任务" else "发送消息",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun statusColor(status: String): Color = when (status) {
    "running", "waiting_for_approval" -> Color(0xFF0F9D58)
    "failed" -> Color(0xFFD93025)
    else -> Color(0xFF9AA0A6)
}


internal fun latestConversationItemIndex(
    messageCount: Int,
    hasStreamingText: Boolean,
    hasRunningIndicator: Boolean,
    hasApproval: Boolean,
    hasError: Boolean,
): Int = (
    messageCount +
        (if (hasStreamingText) 1 else 0) +
        (if (hasRunningIndicator) 1 else 0) +
        (if (hasApproval) 1 else 0) +
        (if (hasError) 1 else 0)
).coerceAtLeast(0)

internal fun shouldAutoScrollToLatest(
    lastScrolledThreadId: String?,
    selectedThreadId: String?,
    loading: Boolean,
    messageCount: Int,
): Boolean = selectedThreadId != null && selectedThreadId != lastScrolledThreadId && !loading && messageCount > 0

internal fun conversationTargetIndex(fraction: Float, totalItems: Int, visibleItems: Int): Int {
    val bounded = fraction.coerceIn(0f, 1f)
    return if (bounded == 1f) totalItems - 1 else
        (bounded * (totalItems - visibleItems).coerceAtLeast(0)).roundToInt()
}


/**
 * Fixed-size model picker: providers first, a tap expands one in place, and the list scrolls
 * inside the dialog. The dialog never grows or shrinks while providers open and close.
 */
@Composable
private fun ModelPickerDialog(
    options: List<ModelOption>,
    selected: ModelOption?,
    expandedProviders: Set<String>,
    onToggleProvider: (String) -> Unit,
    onPick: (ModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
        ) {
            Column {
                Text(
                    "选择模型",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    groupModelOptions(options).forEach { (provider, providerOptions) ->
                        val expanded = provider in expandedProviders
                        item(key = "provider:$provider") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleProvider(provider) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            ) {
                                Icon(
                                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.width(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    providerOptions.first().providerLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${providerOptions.size}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (expanded) {
                            items(providerOptions, key = { "model:$provider:${it.model}" }) { option ->
                                val isSelected = selected?.let { option.matches(it.model, it.provider) } == true
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(option) }
                                        .padding(start = 44.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                                ) {
                                    Text(option.label, modifier = Modifier.weight(1f))
                                    if (isSelected) Icon(Icons.Default.Check, contentDescription = "当前模型", modifier = Modifier.width(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryPickerDialog(
    picker: DirectoryPickerState,
    onBrowse: (String?) -> Unit,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
        ) {
            Column {
                Text("选择工作目录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp))
                Text(
                    picker.currentPath ?: "Mac 用户主目录",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    if (picker.loading) {
                        item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    } else if (picker.error != null) {
                        item {
                            Text(picker.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
                            TextButton(onClick = { onBrowse(picker.path) }) { Text("重试") }
                            TextButton(onClick = { onBrowse(null) }) { Text("返回主目录") }
                        }
                    } else {
                        picker.listing?.parent?.let { parent ->
                            item {
                                Text("上一级", modifier = Modifier.fillMaxWidth().clickable { onBrowse(parent) }.padding(16.dp))
                            }
                        }
                        items(picker.listing?.items.orEmpty(), key = { it.path }) { entry ->
                            Text(entry.name, modifier = Modifier.fillMaxWidth().clickable { onBrowse(entry.path) }.padding(16.dp))
                        }
                        if (picker.listing?.items.isNullOrEmpty()) {
                            item {
                                Text(
                                    if (picker.currentPath == null) "没有子文件夹，无法确定主目录路径，可使用默认目录。" else "没有子文件夹",
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Button(
                        onClick = { picker.currentPath?.let(onChoose) },
                        enabled = !picker.loading && picker.error == null && picker.currentPath != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("选择此文件夹") }
                    TextButton(onClick = { onChoose(null) }, modifier = Modifier.fillMaxWidth()) { Text("使用默认目录") }
                }
            }
        }
    }
}
