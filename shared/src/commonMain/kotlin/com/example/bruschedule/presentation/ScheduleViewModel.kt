package com.example.bruschedule.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.DomainIds
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ProfileSyncState
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.repository.AcademicTaskRepository
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import com.example.bruschedule.domain.repository.ScheduleRepository
import com.example.bruschedule.domain.repository.ScheduleSyncRepository
import com.example.bruschedule.domain.usecase.RefreshOutcome
import com.example.bruschedule.domain.usecase.RefreshScheduleUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime


enum class MainDestination { SCHEDULE, DEADLINES, SETTINGS }

data class LessonUiItem(
    val lesson: Lesson,
    val pendingTaskCount: Int,
    val isCurrent: Boolean,
)

data class DeadlineUiItem(
    val task: AcademicTask,
    val subjectName: String,
    val profileName: String?,
    val lessonType: LessonType?,
)

data class ProfileSearchUiState(
    val query: String = "",
    val type: ScheduleProfileType = ScheduleProfileType.GROUP,
    val loading: Boolean = false,
    val candidates: List<ProfileCandidate> = emptyList(),
    val error: String? = null,
)

data class ScheduleUiState(
    val profiles: List<ScheduleProfile> = emptyList(),
    val selectedProfile: ScheduleProfile? = null,
    val selectedDate: LocalDate = LocalDate(2026, 1, 1),
    val todayDate: LocalDate = LocalDate(2026, 1, 1),
    val weekDatesWithLessons: Set<String> = emptySet(),
    val lessons: List<LessonUiItem> = emptyList(),
    val deadlines: List<DeadlineUiItem> = emptyList(),
    val destination: MainDestination = MainDestination.SCHEDULE,
    val syncState: ProfileSyncState? = null,
    val isRefreshing: Boolean = false,
    val notice: String? = null,
    val profileSearch: ProfileSearchUiState = ProfileSearchUiState(),
    val nowEpochMillis: Long = 0L,
    val isLoading: Boolean = true,
)

/** UI-facing MVVM state holder; local SQLDelight flows drive the screen before any network call. */
class ScheduleViewModel(
    private val schedules: ScheduleRepository,
    private val syncRepository: ScheduleSyncRepository,
    private val tasks: AcademicTaskRepository,
    private val profileCatalog: ScheduleProfileCatalogRepository,
    private val refreshSchedule: RefreshScheduleUseCase,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val initialDate = today()
    private val selectedProfileId = MutableStateFlow<String?>(null)
    private val selectedDate = MutableStateFlow(initialDate)
    private val destination = MutableStateFlow(MainDestination.SCHEDULE)
    private val refreshing = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)
    private val profileSearch = MutableStateFlow(ProfileSearchUiState())
    private val nowEpochMillis = MutableStateFlow(clock.now().toEpochMilliseconds())

    private data class BaseState(
        val profiles: List<ScheduleProfile>,
        val lessons: List<Lesson>,
        val tasks: List<AcademicTask>,
    )

    private data class CoreSelection(
        val profileId: String?,
        val date: LocalDate,
        val destination: MainDestination,
    )

    private data class TransientState(
        val refreshing: Boolean,
        val notice: String?,
        val profileSearch: ProfileSearchUiState,
        val now: Long,
    )

    private data class SelectionState(
        val core: CoreSelection,
        val syncState: ProfileSyncState?,
        val transient: TransientState,
    )

    private val baseState: Flow<BaseState> = combine(
        schedules.observeProfiles(),
        schedules.observeAllLessons(),
        tasks.observeTasks(),
    ) { profiles, lessons, academicTasks ->
        BaseState(profiles, lessons, academicTasks)
    }

    private val syncState: Flow<ProfileSyncState?> = selectedProfileId.flatMapLatest { id ->
        if (id == null) flowOf(null) else syncRepository.observeSyncState(id)
    }

    private val coreSelection: Flow<CoreSelection> = combine(
        selectedProfileId,
        selectedDate,
        destination,
    ) { profileId, date, selectedDestination ->
        CoreSelection(profileId, date, selectedDestination)
    }

    private val transientState: Flow<TransientState> = combine(
        refreshing,
        notice,
        profileSearch,
        nowEpochMillis,
    ) { isRefreshing, message, searchState, now ->
        TransientState(isRefreshing, message, searchState, now)
    }

    private val selectionState: Flow<SelectionState> = combine(
        coreSelection,
        syncState,
        transientState,
    ) { core, currentSyncState, transient ->
        SelectionState(core, currentSyncState, transient)
    }

    val uiState = combine(baseState, selectionState) { base, selection ->
        val selectedId = selection.core.profileId ?: base.profiles.firstOrNull()?.id
        val selectedProfile = base.profiles.firstOrNull { it.id == selectedId }
        val now = selection.transient.now
        val nowLocal = kotlinx.datetime.Instant.fromEpochMilliseconds(now)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        val weekStart = selection.core.date.minus(DatePeriod(days = selection.core.date.dayOfWeek.isoDayNumber - 1))
        val weekEnd = weekStart.plus(DatePeriod(days = 6))
        val lessonsInWeek = if (selectedProfile == null) emptyList() else base.lessons.filter {
            it.profileId == selectedProfile.id && it.lessonDate >= weekStart.toString() && it.lessonDate <= weekEnd.toString()
        }
        val datesWithLessons = lessonsInWeek.mapTo(mutableSetOf()) { it.lessonDate }
        val lessonsForDate = if (selectedProfile == null) emptyList() else base.lessons
            .filter { it.profileId == selectedProfile.id && it.lessonDate == selection.core.date.toString() }
            .sortedWith(compareBy({ it.startTime }, { it.slotNumber }))
            .map { lesson ->
                LessonUiItem(
                    lesson = lesson,
                    pendingTaskCount = base.tasks.count { it.lessonId == lesson.id && !it.isCompleted },
                    isCurrent = lesson.isCurrentAt(nowLocal),
                )
            }
        val lessonById = base.lessons.associateBy(Lesson::id)
        val profileById = base.profiles.associateBy(ScheduleProfile::id)
        val orderedDeadlines = base.tasks
            .sortedWith(compareBy<AcademicTask>({ it.dueDate }, { it.isCompleted }, { it.taskTitle }))
            .map { task ->
                val linkedLesson = lessonById[task.lessonId]
                val taskProfileId = task.profileId ?: linkedLesson?.profileId
                DeadlineUiItem(
                    task = task,
                    subjectName = linkedLesson?.subjectName
                        ?: task.subjectNameSnapshot.ifBlank { "Предмет не указан" },
                    profileName = taskProfileId?.let { profileById[it]?.name },
                    lessonType = linkedLesson?.type,
                )
            }
        ScheduleUiState(
            profiles = base.profiles,
            selectedProfile = selectedProfile,
            selectedDate = selection.core.date,
            todayDate = nowLocal.date,
            weekDatesWithLessons = datesWithLessons,
            lessons = lessonsForDate,
            deadlines = orderedDeadlines,
            destination = selection.core.destination,
            syncState = if (selectedId == selection.core.profileId) selection.syncState else null,
            isRefreshing = selection.transient.refreshing,
            notice = selection.transient.notice,
            profileSearch = selection.transient.profileSearch,
            nowEpochMillis = now,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ScheduleUiState(selectedDate = initialDate, nowEpochMillis = nowEpochMillis.value),
    )

    init {
        viewModelScope.launch {
            schedules.observeProfiles().collect { profiles ->
                val activeId = selectedProfileId.value
                if (profiles.isEmpty()) {
                    selectedProfileId.value = null
                } else if (activeId == null || profiles.none { it.id == activeId }) {
                    selectedProfileId.value = profiles.first().id
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                nowEpochMillis.value = clock.now().toEpochMilliseconds()
                delay(30_000)
            }
        }
    }

    fun selectProfile(profileId: String) {
        selectedProfileId.value = profileId
    }

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
    }

    fun changeWeek(weeks: Int) {
        selectedDate.value = selectedDate.value.plus(DatePeriod(days = weeks * 7))
    }

    fun showDestination(destination: MainDestination) {
        this.destination.value = destination
    }

    fun dismissNotice() {
        notice.value = null
    }

    fun refreshSelectedProfile() {
        val id = selectedProfileId.value ?: uiState.value.selectedProfile?.id ?: return
        launchRefresh(id)
    }

    private fun launchRefresh(profileId: String) {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                val result = withContext(Dispatchers.Default) { refreshSchedule(profileId) }
                notice.value = when (result) {
                    is RefreshOutcome.Success -> "Обновлено: ${result.lessonCount} пар · ручных сохранено ${result.preservedCustomLessonCount}"
                    is RefreshOutcome.Failure -> "Не удалось обновить. Локальные данные сохранены. ${result.message}"
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    fun rollbackSelectedProfile() {
        val id = selectedProfileId.value ?: uiState.value.selectedProfile?.id ?: return
        viewModelScope.launch {
            val restored = withContext(Dispatchers.Default) { syncRepository.rollbackToPrevious(id) }
            notice.value = if (restored) "Восстановлена предыдущая версия расписания" else "Снимок для отката не найден"
        }
    }

    fun saveManualProfile(type: ScheduleProfileType, name: String, sourceUrl: String?) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            notice.value = "Укажите название группы или преподавателя"
            return
        }
        val url = sourceUrl?.trim()?.takeIf(String::isNotBlank)
        if (url != null && !url.startsWith("https://rasp.bru.by/")) {
            notice.value = "Можно добавить только ссылку rasp.bru.by"
            return
        }
        val id = DomainIds.new("profile")
        val profile = ScheduleProfile(
            id = id,
            type = type,
            name = cleanName,
            sourceUrl = url,
            sourceKey = cleanName,
        )
        viewModelScope.launch {
            schedules.saveProfile(profile)
            selectedProfileId.value = id
            notice.value = "Профиль «$cleanName» добавлен · загружаем расписание"
            launchRefresh(id)
        }
    }

    fun searchProfiles(type: ScheduleProfileType, query: String) {
        profileSearch.value = ProfileSearchUiState(query = query, type = type, loading = true)
        viewModelScope.launch {
            try {
                val candidates = withContext(Dispatchers.Default) { profileCatalog.search(type, query) }
                profileSearch.value = ProfileSearchUiState(query, type, candidates = candidates)
                if (candidates.isEmpty()) notice.value = "Совпадений не найдено — можно добавить профиль по ссылке вручную"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                profileSearch.value = ProfileSearchUiState(
                    query = query,
                    type = type,
                    error = failure.message ?: "Каталог сейчас недоступен",
                )
            }
        }
    }

    fun addProfileCandidate(candidate: ProfileCandidate) {
        val profile = ScheduleProfile(
            id = DomainIds.new("profile"),
            type = candidate.type,
            name = candidate.name,
            sourceUrl = candidate.sourceUrl,
            sourceKey = candidate.sourceKey,
        )
        viewModelScope.launch {
            schedules.saveProfile(profile)
            selectedProfileId.value = profile.id
            profileSearch.value = ProfileSearchUiState(type = candidate.type)
            notice.value = "Профиль «${candidate.name}» добавлен · загружаем расписание"
            launchRefresh(profile.id)
        }
    }

    fun saveCustomLesson(lesson: Lesson) {
        val custom = lesson.copy(
            isCustom = true,
            colorHex = LessonTypePalette.accent(lesson.type),
        )
        viewModelScope.launch {
            schedules.saveCustomLesson(custom)
            notice.value = "Пара сохранена локально и защищена от перезаписи сайтом"
        }
    }

    fun newLessonDraft(): Lesson? {
        val current = uiState.value
        val profile = current.selectedProfile ?: return null
        val id = DomainIds.new("custom-lesson")
        return Lesson(
            id = id,
            profileId = profile.id,
            subjectName = "",
            teacherName = "",
            classroom = "",
            startTime = "08:30",
            endTime = "10:05",
            type = LessonType.UNKNOWN,
            colorHex = LessonTypePalette.UNKNOWN,
            isCustom = true,
            version = 0,
            lessonDate = current.selectedDate.toString(),
            dayOfWeek = current.selectedDate.dayOfWeek.isoDayNumber,
            slotNumber = 0,
            sourceKey = "custom:$id",
        )
    }

    fun addTask(
        lesson: Lesson,
        title: String,
        description: String,
        dueDateIso: String,
        persistLessonOverride: Boolean = false,
    ) {
        if (title.isBlank()) {
            notice.value = "Введите название задания"
            return
        }
        val dueDate = runCatching {
            LocalDate.parse(dueDateIso.trim())
                .atStartOfDayIn(TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
        }.getOrElse {
            notice.value = "Дата должна быть в формате ГГГГ-ММ-ДД"
            return
        }
        val task = AcademicTask(
            id = DomainIds.new("task"),
            lessonId = lesson.id,
            taskTitle = title.trim(),
            description = description.trim(),
            dueDate = dueDate,
            isCompleted = false,
            profileId = lesson.profileId,
            subjectNameSnapshot = lesson.subjectName,
        )
        viewModelScope.launch {
            if (persistLessonOverride || schedules.getLesson(lesson.id) == null) {
                schedules.saveCustomLesson(lesson)
            }
            tasks.saveTask(task)
            notice.value = "Дедлайн добавлен"
        }
    }

    fun setTaskCompleted(taskId: String, completed: Boolean) {
        viewModelScope.launch { tasks.setTaskCompleted(taskId, completed) }
    }

    private fun today(): LocalDate =
        clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private fun Lesson.isCurrentAt(now: kotlinx.datetime.LocalDateTime): Boolean {
        if (lessonDate != now.date.toString()) return false
        val starts = runCatching { kotlinx.datetime.LocalTime.parse(startTime) }.getOrNull() ?: return false
        val ends = runCatching { kotlinx.datetime.LocalTime.parse(endTime) }.getOrNull() ?: return false
        return now.time >= starts && now.time < ends
    }
}
