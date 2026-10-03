package app.hermes.mobile.management.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.data.BridgeApi
import app.hermes.mobile.data.CronCreateRequest
import app.hermes.mobile.data.CronJobDto
import app.hermes.mobile.data.CronRunDto
import app.hermes.mobile.threads.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.LocalDate

data class CronState(
    val jobs: List<CronJobDto> = emptyList(),
    val selected: CronJobDto? = null,
    val creating: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val runs: List<CronRunDto>? = null,
    val name: String = "",
    val prompt: String = "",
    val schedule: String = "",
    val picker: SchedulePickerState? = null,
    val advancedSchedule: Boolean = false,
    val paused: Boolean = false,
) {
    val editing: Boolean get() = creating || selected != null
    val validation: String? get() = validate()

    fun validate(clock: Clock = Clock.systemDefaultZone()): String? = when {
        name.length > 200 -> "名称不能超过 200 个字符。"
        prompt.length > 32000 -> "任务内容不能超过 32000 个字符。"
        creating && prompt.isBlank() -> "请输入任务内容。"
        !advancedSchedule && picker != null && picker.validation(creating, clock) != null -> picker.validation(creating, clock)
        creating || schedule != selected?.scheduleInput ->
            scheduleInputError(schedule) ?: if (creating && isPastSchedule(schedule, clock))
                "一次性执行时间不能早于当前时间，请重新选择。" else null
        else -> null
    }

    fun updates(): JsonObject = buildJsonObject {
        selected?.let {
            if (name != it.name.orEmpty()) put("name", name)
            if (prompt != it.prompt.orEmpty()) put("prompt", prompt)
            if (normalizeScheduleInput(schedule) != normalizeScheduleInput(it.scheduleInput)) {
                put("schedule", normalizeScheduleInput(schedule))
            }
        }
    }
}

class CronViewModel(
    private val api: BridgeApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(CronState())
    val state = mutableState.asStateFlow()

    // One request at a time also prevents stale details overwriting a newer selection.
    private fun request(block: suspend () -> Unit) {
        if (state.value.loading) return
        mutableState.value = state.value.copy(loading = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.value.copy(error = userMessage(error))
            } finally {
                mutableState.value = state.value.copy(loading = false)
            }
        }
    }

    fun refresh() = request {
        val jobs = withContext(io) { api.cronJobs() }
        mutableState.value = state.value.copy(jobs = jobs)
    }

    fun select(job: CronJobDto) = request {
        val detail = withContext(io) { api.cronJob(job.id) }
        show(detail)
    }

    private fun show(job: CronJobDto) {
        mutableState.value = state.value.copy(selected = job, creating = false, runs = null,
            name = job.name.orEmpty(), prompt = job.prompt.orEmpty(), schedule = job.scheduleInput,
            picker = parseSchedulePicker(job.scheduleInput, LocalDate.now(clock)), advancedSchedule = false)
    }

    fun create() {
        if (!state.value.loading) {
            val picker = SchedulePickerState(date = LocalDate.now(clock))
            mutableState.value = CronState(jobs = state.value.jobs, creating = true,
                picker = picker, schedule = picker.toSchedule())
        }
    }

    fun back() {
        if (!state.value.loading) mutableState.value = CronState(jobs = state.value.jobs)
    }

    fun edit(name: String = state.value.name, prompt: String = state.value.prompt,
             schedule: String? = null, paused: Boolean = state.value.paused) {
        if (!state.value.loading) mutableState.value = state.value.copy(name = name, prompt = prompt,
            schedule = schedule ?: state.value.schedule, paused = paused, error = null,
            advancedSchedule = state.value.advancedSchedule || schedule != null)
    }

    fun useAdvancedSchedule() {
        if (!state.value.loading) mutableState.value = state.value.copy(advancedSchedule = true, error = null)
    }

    fun useSchedulePicker() {
        if (state.value.loading) return
        editPicker(parseSchedulePicker(state.value.schedule, LocalDate.now(clock))
            ?: SchedulePickerState(date = LocalDate.now(clock)))
    }

    fun editPicker(picker: SchedulePickerState) {
        if (state.value.loading) return
        val output = if (picker.validation() == null) picker.toSchedule() else ""
        val original = state.value.selected?.scheduleInput
        // Keep the exact saved spelling when the user returns to the same schedule.
        val unchanged = original != null && output.isNotEmpty() &&
            parseSchedulePicker(original)?.toSchedule() == output
        mutableState.value = state.value.copy(picker = picker, advancedSchedule = false,
            schedule = if (unchanged) requireNotNull(original) else output, error = null)
    }

    fun save() {
        val draft = state.value
        if (!draft.editing || draft.loading) return
        draft.validate(clock)?.let { mutableState.value = draft.copy(error = it); return }
        if (!draft.creating && draft.updates().isEmpty()) return
        request {
            val saved = withContext(io) {
                if (draft.creating) api.createCronJob(CronCreateRequest(draft.schedule.trim(),
                    name = draft.name.takeIf { it.isNotBlank() }, prompt = draft.prompt, paused = draft.paused))
                else api.updateCronJob(requireNotNull(draft.selected).id, draft.updates())
            }
            replace(saved)
            show(saved)
            mutableState.value = state.value.copy(notice = "定时任务已保存。")
        }
    }

    private fun replace(job: CronJobDto) {
        mutableState.value = state.value.copy(jobs = state.value.jobs.filterNot { it.id == job.id } + job)
    }

    fun setActive(job: CronJobDto, active: Boolean) = request {
        val updated = withContext(io) { if (active) api.resumeCronJob(job.id) else api.pauseCronJob(job.id) }
        replace(updated)
    }

    fun delete() {
        val job = state.value.selected ?: return
        request {
            val result = withContext(io) { api.deleteCronJob(job.id) }
            check(result.ok)
            mutableState.value = CronState(jobs = state.value.jobs.filterNot { it.id == job.id },
                loading = true, notice = "定时任务已删除。")
        }
    }

    fun trigger() {
        val job = state.value.selected ?: return
        request {
            val updated = withContext(io) { api.triggerCronJob(job.id) }
            if (updated.state == "completed") {
                mutableState.value = state.value.copy(jobs = state.value.jobs.filterNot { it.id == job.id })
            } else replace(updated)
            // Preserve unsaved form text. A trigger must not silently discard edits.
            mutableState.value = state.value.copy(selected = updated, runs = null,
                notice = "已执行立即运行请求。暂停任务可能已恢复，请检查当前状态。")
        }
    }

    fun loadRuns() {
        val job = state.value.selected ?: return
        request {
            val result = withContext(io) { api.cronRuns(job.id) }
            mutableState.value = state.value.copy(runs = result.runs)
        }
    }
}

class CronViewModelFactory(private val api: BridgeApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = CronViewModel(api) as T
}
