package app.hermes.mobile.management.cron

import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.hermes.mobile.data.CronJobDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CronDialog(viewModel: CronViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var confirmation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    AlertDialog(
        onDismissRequest = { if (!state.loading) onDismiss() },
        title = { Text(if (state.creating) "新建定时任务" else if (state.editing) "定时任务详情" else "定时任务") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.notice?.let { Text(it) }
                if (state.editing) {
                    state.selected?.let { JobSummary(it) }
                    OutlinedTextField(state.name, { viewModel.edit(name = it) }, label = { Text("名称") },
                        enabled = !state.loading, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(state.prompt, { viewModel.edit(prompt = it) }, label = { Text("任务内容") },
                        enabled = !state.loading, minLines = 3, modifier = Modifier.fillMaxWidth())
                    ScheduleEditor(state, viewModel)
                    if (state.creating) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("创建后先暂停")
                        Switch(state.paused, { viewModel.edit(paused = it) }, enabled = !state.loading,
                            modifier = Modifier.semantics { contentDescription = "创建后先暂停" })
                    }
                    state.selected?.let { job ->
                        TextButton(onClick = { confirmation = "trigger" }, enabled = !state.loading && job.state != "completed") { Text("立即运行") }
                        TextButton(onClick = { viewModel.loadRuns() }, enabled = !state.loading) { Text("查看 / 刷新最近运行") }
                        state.runs?.let { runs ->
                            Text("最近运行（最多 20 条）", style = MaterialTheme.typography.titleSmall)
                            if (runs.isEmpty()) Text("暂无运行记录")
                            runs.forEach { run ->
                                HorizontalDivider()
                                Text(run.title?.takeIf { it.isNotBlank() } ?: run.id.ifBlank { "运行记录" })
                                Text("开始：${runTime(run.startedAt)}")
                                Text(if (run.isActive) "运行中" else if (run.endedAt != null) "已结束：${runTime(run.endedAt)}" else "状态未知")
                            }
                        }
                        TextButton(onClick = { confirmation = "delete" }, enabled = !state.loading) {
                            Text("删除定时任务", color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    Row {
                        TextButton(onClick = { viewModel.create() }, enabled = !state.loading) { Text("新建") }
                        TextButton(onClick = { viewModel.refresh() }, enabled = !state.loading) { Text("刷新") }
                    }
                    if (state.jobs.isEmpty() && !state.loading && state.error == null) Text("暂无定时任务，点击“新建”添加。")
                    state.jobs.forEach { job ->
                        HorizontalDivider()
                        Column(Modifier.fillMaxWidth().clickable(enabled = !state.loading && job.id.isNotBlank()) {
                            viewModel.select(job)
                        }.padding(vertical = 8.dp)) {
                            JobSummary(job)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (job.active) "已启用" else if (job.state == "completed") "已完成" else "已暂停")
                                Switch(job.active, { viewModel.setActive(job, it) },
                                    enabled = !state.loading && job.id.isNotBlank() && job.state != "completed",
                                    modifier = Modifier.semantics { contentDescription = "${job.title}：启用定时任务" })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.editing) TextButton(onClick = { viewModel.save() },
                enabled = !state.loading && (state.creating || state.updates().isNotEmpty())) { Text("保存") }
            else TextButton(onClick = onDismiss, enabled = !state.loading) { Text("关闭") }
        },
        dismissButton = {
            if (state.editing) TextButton(onClick = { viewModel.back() }, enabled = !state.loading) { Text("返回列表") }
        },
    )
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (action == "delete") "删除定时任务？" else "立即运行定时任务？") },
            text = { Text(if (action == "delete") "将删除“${state.selected?.title.orEmpty()}”，后续不再自动执行。此操作不可撤销。"
                else "将立即执行“${state.selected?.title.orEmpty()}”已保存的任务内容，可能调用工具并产生模型费用。暂停任务可能会恢复；未保存的修改不会参与本次运行。") },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                if (action == "delete") viewModel.delete() else viewModel.trigger()
            }, enabled = !state.loading) { Text(if (action == "delete") "确认删除" else "确认运行") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ScheduleEditor(state: CronState, viewModel: CronViewModel) {
    Text("执行时间", style = MaterialTheme.typography.titleSmall)
    if (state.advancedSchedule) {
        OutlinedTextField(state.schedule, { viewModel.edit(schedule = it) }, label = { Text("执行时间（高级）") },
            enabled = !state.loading, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("例如：every 30m、0 9 * * *、2026-12-01T09:00:00+08:00。未指定时区时使用 Hermes 配置时区。",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = viewModel::useSchedulePicker, enabled = !state.loading) {
            Text(if (parseSchedulePicker(state.schedule) == null) "改用滚轮（重设为每天 09:00）" else "改用滚轮")
        }
    } else {
        val picker = state.picker
        if (picker == null) {
            Text("当前计划：${state.selected?.scheduleText.orEmpty().ifBlank { "未提供" }}")
            Text("此计划无法完整表示为滚轮选项，保持原样保存其他修改。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::useSchedulePicker, enabled = !state.loading) { Text("改用滚轮（重设为每天 09:00）") }
        } else {
            ScheduleWheels(picker, !state.loading, viewModel::editPicker)
            if (state.schedule.isNotBlank()) Text("计划：${state.schedule}", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = viewModel::useAdvancedSchedule, enabled = !state.loading) { Text("高级：手写") }
    }
    Text("执行时间使用 Mac / Hermes 配置时区。手机端过去时间检查使用手机时区，请保持两端时区一致。",
        style = MaterialTheme.typography.bodySmall)
    state.validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ScheduleWheels(picker: SchedulePickerState, enabled: Boolean, onChange: (SchedulePickerState) -> Unit) {
    ScheduleType.entries.chunked(2).forEach { types ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { type ->
                FilterChip(selected = picker.type == type, onClick = { onChange(picker.copy(type = type)) },
                    label = { Text(type.label) }, enabled = enabled, modifier = Modifier.weight(1f))
            }
        }
    }
    if (picker.type == ScheduleType.ONCE) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                ScheduleWheel("年", picker.date.year, 1, 9999, enabled, wrap = false) { onChange(picker.withYear(it)) }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                ScheduleWheel("月", picker.date.monthValue, 1, 12, enabled) { onChange(picker.withMonth(it)) }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                ScheduleWheel("日", picker.date.dayOfMonth, 1, daysInMonth(picker.date.year, picker.date.monthValue), enabled) {
                    onChange(picker.copy(date = picker.date.withDayOfMonth(it)))
                }
            }
        }
    }
    if (picker.type == ScheduleType.WEEKLY) {
        cronWeekdays.chunked(3).forEach { days ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEach { (number, label) ->
                    FilterChip(selected = number in picker.weekdays, onClick = {
                        onChange(picker.copy(weekdays = if (number in picker.weekdays) picker.weekdays - number else picker.weekdays + number))
                    }, label = { Text(label) }, enabled = enabled)
                }
            }
        }
    }
    if (picker.type == ScheduleType.INTERVAL) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScheduleWheel("间隔", picker.interval, 1, 59, enabled) { onChange(picker.copy(interval = it)) }
            }
            Column {
                IntervalUnit.entries.forEach { unit ->
                    FilterChip(selected = picker.unit == unit, onClick = { onChange(picker.copy(unit = unit)) },
                        label = { Text(unit.label) }, enabled = enabled)
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScheduleWheel("时", picker.hour, 0, 23, enabled, padded = true) { onChange(picker.copy(hour = it)) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScheduleWheel("分", picker.minute, 0, 59, enabled, padded = true) { onChange(picker.copy(minute = it)) }
            }
        }
    }
}

@Composable
private fun ScheduleWheel(
    label: String, value: Int, minimum: Int, maximum: Int, enabled: Boolean,
    padded: Boolean = false, wrap: Boolean = true, onChange: (Int) -> Unit,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val formatter = remember(padded) { NumberPicker.Formatter { if (padded) it.toString().padStart(2, '0') else it.toString() } }
    Text(label, style = MaterialTheme.typography.labelMedium)
    AndroidView(
        modifier = Modifier.wrapContentWidth().height(132.dp),
        factory = { context ->
            NumberPicker(context).apply {
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // Keep vertical wheel gestures out of the surrounding dialog's scroll view.
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
            }
        },
        update = { wheel ->
            wheel.setOnValueChangedListener(null)
            // Bounds must be set before value, especially when February replaces a 31-day month.
            wheel.minValue = minimum
            wheel.maxValue = maximum
            wheel.setFormatter(formatter)
            wheel.wrapSelectorWheel = wrap
            if (wheel.value != value) wheel.value = value
            wheel.setTextColor(textColor) // API 29; this app's minSdk is 31.
            wheel.isEnabled = enabled
            wheel.alpha = if (enabled) 1f else 0.5f
            wheel.contentDescription = label
            wheel.setOnValueChangedListener { _, _, newValue -> onChange(newValue) }
        },
    )
}

@Composable
private fun JobSummary(job: CronJobDto) {
    Text(job.title, style = MaterialTheme.typography.titleSmall)
    Text("计划：${job.scheduleText.ifBlank { "未提供" }}")
    Text("下次运行：${job.nextRunAt ?: "暂无"}")
    Text("上次运行：${job.lastRunAt ?: "暂无"}")
    if (!job.profile.isNullOrBlank()) Text("配置：${job.profile}", style = MaterialTheme.typography.bodySmall)
    Text(if (job.active) "状态：已启用" else if (job.state == "completed") "状态：已完成" else "状态：已暂停")
}

private fun runTime(seconds: Double?): String = seconds?.let {
    runCatching {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli((it * 1000).toLong()))
    }.getOrDefault("未知")
} ?: "未知"
