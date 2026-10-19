package com.example.bruschedule.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.AcademicTrack
import com.example.bruschedule.domain.model.AppearancePreferences
import com.example.bruschedule.domain.model.DEFAULT_DAILY_MESSAGES
import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import com.example.bruschedule.domain.model.DomainIds
import com.example.bruschedule.domain.model.DeadlineLinkType
import com.example.bruschedule.domain.model.DeadlineReminderPreferences
import com.example.bruschedule.domain.model.TaskReminderMode
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ProfileSyncState
import com.example.bruschedule.domain.model.ScheduleAppTheme
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.repository.AcademicTaskRepository
import com.example.bruschedule.domain.repository.AppearancePreferencesRepository
import com.example.bruschedule.domain.repository.DailyMessagesRepository
import com.example.bruschedule.domain.repository.DeadlineNotificationScheduler
import com.example.bruschedule.domain.repository.DeadlineReminderPreferencesRepository
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import com.example.bruschedule.domain.repository.ScheduleRepository
import com.example.bruschedule.domain.repository.ScheduleSyncRepository
import com.example.bruschedule.domain.repository.SemesterSchedulePreferencesRepository
import com.example.bruschedule.domain.usecase.DailyMessageSelector
import com.example.bruschedule.domain.usecase.DeadlineReminderPolicy
import com.example.bruschedule.domain.usecase.SemesterCoveragePolicy
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime


enum class MainDestination { SCHEDULE, DEADLINES, SETTINGS }

private const val STARTUP_REFRESH_RETRY_COOLDOWN_MILLIS = 12 * 60 * 60 * 1000L

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
    val linkLabel: String,
    val linkDetail: String? = null,
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
    val allScheduleDatesWithLessons: Set<String> = emptySet(),
    val lessons: List<LessonUiItem> = emptyList(),
    val availableLessons: List<Lesson> = emptyList(),
    val deadlines: List<DeadlineUiItem> = emptyList(),
    val deadlineReminderPreferences: DeadlineReminderPreferences = DeadlineReminderPreferences(),
    val semesterSchedulePreferences: SemesterSchedulePreferences = SemesterSchedulePreferences(),
    val dailyMessage: String = DEFAULT_DAILY_MESSAGES.first(),
    val destination: MainDestination = MainDestination.SCHEDULE,
    val syncState: ProfileSyncState? = null,
    val isRefreshing: Boolean = false,
    val hasRemoteSchedule: Boolean = false,
    val scheduleCoverageStartDate: LocalDate? = null,
    val scheduleCoverageEndDate: LocalDate? = null,
    val appearance: AppearancePreferences = AppearancePreferences(),
    val notice: String? = null,
    val profileSearch: ProfileSearchUiState = ProfileSearchUiState(),
    val nowEpochMillis: Long = 0L,
    val isLoading: Boolean = true,
)

data class DeadlineDraft(
    val title: String,
    val description: String,
    val dueDateIso: String,
    val linkType: DeadlineLinkType,
    val profileId: String?,
    val subjectName: String,
    val lessonId: String?,
    val reminderMode: TaskReminderMode,
    val reminderOffsetsMinutes: List<Int>,
    val lessonToPersist: Lesson? = null,
    val taskId: String? = null,
    val isCompleted: Boolean = false,
)

/** UI-facing MVVM state holder; local SQLDelight flows drive the screen before any network call. */
class ScheduleViewModel(
    private val schedules: ScheduleRepository,
    private val syncRepository: ScheduleSyncRepository,
    private val tasks: AcademicTaskRepository,
    private val profileCatalog: ScheduleProfileCatalogRepository,
    private val refreshSchedule: RefreshScheduleUseCase,
    private val appearancePreferences: AppearancePreferencesRepository,
    private val deadlineReminderPreferencesRepository: DeadlineReminderPreferencesRepository,
    private val semesterSchedulePreferencesRepository: SemesterSchedulePreferencesRepository,
    private val dailyMessagesRepository: DailyMessagesRepository,
    private val deadlineNotificationScheduler: DeadlineNotificationScheduler,
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
    private val appearance = appearancePreferences.observe().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppearancePreferences(),
    )
    private val reminderPreferences = deadlineReminderPreferencesRepository.observe().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DeadlineReminderPreferences(),
    )
    private val semesterPreferences = semesterSchedulePreferencesRepository.observe().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SemesterSchedulePreferences(),
    )
    private val dailyMessages = dailyMessagesRepository.observeMessages().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DEFAULT_DAILY_MESSAGES,
    )

    private data class ScreenPreferencesState(
        val appearance: AppearancePreferences,
        val deadlineReminders: DeadlineReminderPreferences,
        val semester: SemesterSchedulePreferences,
        val dailyMessages: List<String>,
    )

    private val screenPreferences = combine(
        appearance,
        reminderPreferences,
        semesterPreferences,
        dailyMessages,
    ) { currentAppearance, reminders, semester, messages ->
        ScreenPreferencesState(currentAppearance, reminders, semester, messages)
    }

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

    val uiState = combine(baseState, selectionState, screenPreferences) { base, selection, preferences ->
        val selectedId = selection.core.profileId ?: base.profiles.firstOrNull()?.id
        val selectedProfile = base.profiles.firstOrNull { it.id == selectedId }
        val profileLessons = if (selectedProfile == null) emptyList() else base.lessons.filter {
            it.profileId == selectedProfile.id
        }
        val now = selection.transient.now
        val nowLocal = kotlinx.datetime.Instant.fromEpochMilliseconds(now)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        val todayDate = nowLocal.date
        val coverageStartDate = selectedProfile?.let { SemesterCoveragePolicy.coverageStartDate(todayDate) }
        val coverageEndDate = selectedProfile?.let {
            SemesterCoveragePolicy.coverageEndDate(todayDate, preferences.semester)
        }
        val displayedDate = when {
            selection.core.date < todayDate -> todayDate
            coverageEndDate != null && selection.core.date > coverageEndDate -> coverageEndDate
            else -> selection.core.date
        }
        val visibleProfileLessons = profileLessons.filter { lesson ->
            val lessonDate = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull()
            lessonDate != null && lessonDate >= todayDate &&
                (coverageEndDate == null || lessonDate <= coverageEndDate)
        }
        val weekStart = displayedDate.minus(DatePeriod(days = displayedDate.dayOfWeek.isoDayNumber - 1))
        val weekEnd = weekStart.plus(DatePeriod(days = 6))
        val lessonsInWeek = visibleProfileLessons.filter {
            it.lessonDate >= weekStart.toString() && it.lessonDate <= weekEnd.toString()
        }
        val datesWithLessons = lessonsInWeek.mapTo(mutableSetOf()) { it.lessonDate }
        val lessonsForDate = visibleProfileLessons
            .filter { it.lessonDate == displayedDate.toString() }
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
                val linkedLesson = task.lessonId?.let(lessonById::get)
                val taskProfileId = task.profileId ?: linkedLesson?.profileId
                DeadlineUiItem(
                    task = task,
                    subjectName = when (task.linkType) {
                        DeadlineLinkType.NONE -> "Общий дедлайн"
                        DeadlineLinkType.SUBJECT -> linkedLesson?.subjectName
                            ?: task.subjectNameSnapshot.ifBlank { "Предмет не указан" }
                        DeadlineLinkType.LESSON -> linkedLesson?.subjectName
                            ?: task.subjectNameSnapshot.ifBlank { "Пара" }
                    },
                    profileName = taskProfileId?.let { profileById[it]?.name },
                    lessonType = linkedLesson?.type,
                    linkLabel = when (task.linkType) {
                        DeadlineLinkType.SUBJECT -> "ПРЕДМЕТ"
                        DeadlineLinkType.LESSON -> "КОНКРЕТНАЯ ПАРА"
                        DeadlineLinkType.NONE -> "БЕЗ ПРИВЯЗКИ"
                    },
                    linkDetail = linkedLesson?.takeIf { task.linkType == DeadlineLinkType.LESSON }
                        ?.let { "${it.lessonDate} · ${it.startTime}" },
                )
            }
        ScheduleUiState(
            profiles = base.profiles,
            selectedProfile = selectedProfile,
            selectedDate = displayedDate,
            todayDate = todayDate,
            weekDatesWithLessons = datesWithLessons,
            allScheduleDatesWithLessons = visibleProfileLessons.mapTo(mutableSetOf()) { it.lessonDate },
            lessons = lessonsForDate,
            availableLessons = base.lessons,
            deadlines = orderedDeadlines,
            deadlineReminderPreferences = preferences.deadlineReminders,
            semesterSchedulePreferences = preferences.semester,
            dailyMessage = DailyMessageSelector.forDate(todayDate, preferences.dailyMessages),
            destination = selection.core.destination,
            syncState = if (selectedId == selection.core.profileId) selection.syncState else null,
            isRefreshing = selection.transient.refreshing,
            hasRemoteSchedule = profileLessons.any { !it.isCustom },
            scheduleCoverageStartDate = coverageStartDate,
            scheduleCoverageEndDate = coverageEndDate,
            appearance = preferences.appearance,
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
            dailyMessagesRepository.refresh()
        }
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
            val storedProfile = schedules.observeProfiles().first().firstOrNull() ?: return@launch
            refreshIfSemesterCoverageIsMissing(storedProfile.id)
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
        viewModelScope.launch { refreshIfSemesterCoverageIsMissing(profileId) }
    }

    fun selectDate(date: LocalDate) {
        val current = uiState.value
        val start = current.scheduleCoverageStartDate ?: return
        val end = current.scheduleCoverageEndDate ?: return
        if (date in start..end) selectedDate.value = date
    }

    fun changeWeek(weeks: Int) {
        val current = uiState.value
        val firstCoveredDate = current.scheduleCoverageStartDate ?: return
        val lastCoveredDate = current.scheduleCoverageEndDate ?: return
        val targetDate = current.selectedDate.plus(DatePeriod(days = weeks * 7))
        val targetWeek = targetDate.startOfWeek()
        if (targetWeek < firstCoveredDate.startOfWeek() || targetWeek > lastCoveredDate.startOfWeek()) return
        selectedDate.value = if (targetDate > lastCoveredDate) lastCoveredDate else targetDate
    }

    fun showDestination(destination: MainDestination) {
        this.destination.value = destination
    }

    fun setTheme(theme: ScheduleAppTheme) = updateAppearance {
        appearancePreferences.setTheme(theme)
    }

    fun setLessonColor(type: LessonType, colorHex: String) = updateAppearance {
        appearancePreferences.setLessonColor(type, colorHex)
    }

    fun resetLessonColors() = updateAppearance {
        appearancePreferences.resetLessonColors()
    }

    fun setAcademicTrack(track: AcademicTrack) {
        val current = semesterPreferences.value
        if (current.track == track) return
        val endDate = when (track) {
            AcademicTrack.IFZO -> current.ifzoSessionEndDateIso
            AcademicTrack.BELARUSIAN, AcademicTrack.RUSSIAN ->
                SemesterSchedulePreferences.defaultSessionEndDate(track)
        }
        updateSemesterSchedulePreferences(current.copy(track = track, sessionEndDateIso = endDate))
    }

    fun setIfzoSessionEndDate(sessionEndDateIso: String) {
        val normalizedDate = runCatching { LocalDate.parse(sessionEndDateIso).toString() }.getOrNull()
        if (normalizedDate == null) {
            notice.value = "Введите дату последнего экзамена в формате ДД.ММ.ГГГГ"
            return
        }
        updateSemesterSchedulePreferences(
            semesterPreferences.value.copy(
                track = AcademicTrack.IFZO,
                sessionEndDateIso = normalizedDate,
                ifzoSessionEndDateIso = normalizedDate,
            ),
        )
    }

    private fun updateSemesterSchedulePreferences(preferences: SemesterSchedulePreferences) {
        viewModelScope.launch {
            try {
                semesterSchedulePreferencesRepository.update(preferences)
                val today = today()
                val end = SemesterCoveragePolicy.coverageEndDate(today, preferences)
                if (selectedDate.value < today || selectedDate.value > end) selectedDate.value = today
                selectedProfileId.value?.let(::launchRefresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = "Не удалось сохранить период семестра: ${failure.message ?: "ошибка настроек"}"
            }
        }
    }

    private fun updateAppearance(update: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                update()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = "Не удалось сохранить настройки оформления: ${failure.message ?: "ошибка настроек"}"
            }
        }
    }

    fun dismissNotice() {
        notice.value = null
    }

    fun refreshSelectedProfile() {
        val id = selectedProfileId.value ?: uiState.value.selectedProfile?.id ?: return
        launchRefresh(id)
    }

    private suspend fun refreshIfSemesterCoverageIsMissing(profileId: String) {
        try {
            val cachedLessons = withContext(Dispatchers.Default) { schedules.getLessons(profileId) }
            val today = today()
            val desiredCoverageEnd = SemesterCoveragePolicy.coverageEndDate(
                today,
                semesterSchedulePreferencesRepository.observe().first(),
            )
            val remoteDates = cachedLessons.asSequence()
                .filterNot(Lesson::isCustom)
                .mapNotNull { lesson -> runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull() }
                .toList()
            val coverageEnd = remoteDates.maxOrNull()
            val containsDatesOutsideRequestedRange = remoteDates.any { it < today || it > desiredCoverageEnd }
            if (coverageEnd != null && coverageEnd >= desiredCoverageEnd && !containsDatesOutsideRequestedRange) return

            val sync = syncRepository.observeSyncState(profileId).first()
            val lastAttempt = sync?.lastAttemptAtEpochMillis
            val now = clock.now().toEpochMilliseconds()
            if (sync?.lastError != null && lastAttempt != null && now - lastAttempt < STARTUP_REFRESH_RETRY_COOLDOWN_MILLIS) return
            launchRefresh(profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            notice.value = "Не удалось проверить период расписания: ${failure.message ?: "ошибка локальной базы"}"
        }
    }

    private fun launchRefresh(profileId: String) {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
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

    fun deleteSelectedProfile() {
        val selected = uiState.value.selectedProfile ?: return
        val nextProfileId = uiState.value.profiles.firstOrNull { it.id != selected.id }?.id
        val linkedTaskIds = uiState.value.deadlines
            .filter { it.task.profileId == selected.id }
            .map { it.task.id }
        viewModelScope.launch {
            try {
                linkedTaskIds.forEach { deadlineNotificationScheduler.cancel(it) }
                schedules.deleteProfile(selected.id)
                selectedProfileId.value = nextProfileId
                notice.value = "Расписание «${selected.name}» и связанные локальные данные удалены"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = "Не удалось удалить расписание: ${failure.message ?: "ошибка локального хранилища"}"
            }
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

    fun addTask(draft: DeadlineDraft) {
        if (draft.title.isBlank()) {
            notice.value = "Введите название задания"
            return
        }
        val dueDate = runCatching {
            val date = LocalDate.parse(draft.dueDateIso.trim())
            LocalDateTime(date, LocalTime(hour = 9, minute = 0))
                .toInstant(TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
        }.getOrElse {
            notice.value = "Дата должна быть в формате ГГГГ-ММ-ДД"
            return
        }
        if (draft.linkType == DeadlineLinkType.SUBJECT &&
            (draft.profileId.isNullOrBlank() || draft.subjectName.isBlank())
        ) {
            notice.value = "Выберите профиль и предмет"
            return
        }
        if (draft.linkType == DeadlineLinkType.LESSON && draft.lessonId.isNullOrBlank()) {
            notice.value = "Выберите свою пару"
            return
        }
        val task = AcademicTask(
            id = draft.taskId ?: DomainIds.new("task"),
            lessonId = draft.lessonId.takeIf { draft.linkType == DeadlineLinkType.LESSON },
            taskTitle = draft.title.trim(),
            description = draft.description.trim(),
            dueDate = dueDate,
            isCompleted = draft.isCompleted,
            profileId = draft.profileId.takeIf { draft.linkType != DeadlineLinkType.NONE },
            subjectNameSnapshot = draft.subjectName.trim().takeIf { draft.linkType != DeadlineLinkType.NONE }.orEmpty(),
            linkType = draft.linkType,
            reminderMode = draft.reminderMode,
            reminderOffsetsMinutes = draft.reminderOffsetsMinutes.distinct().sortedDescending(),
        )
        viewModelScope.launch {
            try {
                draft.lessonToPersist?.let { schedules.saveCustomLesson(it) }
                tasks.saveTask(task)
                val remindersReady = syncTaskReminders(task, reminderPreferences.value)
                notice.value = if (!remindersReady) {
                    "Дедлайн сохранён, но разрешение на уведомления не выдано"
                } else if (draft.taskId == null) {
                    "Дедлайн добавлен"
                } else {
                    "Изменения дедлайна сохранены"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                notice.value = "Не удалось сохранить дедлайн: ${failure.message ?: "ошибка локального хранилища"}"
            }
        }
    }

    fun setTaskCompleted(taskId: String, completed: Boolean) {
        viewModelScope.launch {
            tasks.setTaskCompleted(taskId, completed)
            val task = uiState.value.deadlines.firstOrNull { it.task.id == taskId }?.task
            if (completed) {
                deadlineNotificationScheduler.cancel(taskId)
            } else if (task != null && !syncTaskReminders(task.copy(isCompleted = false), reminderPreferences.value)) {
                notice.value = "Дедлайн открыт, но разрешение на уведомления не выдано"
            }
        }
    }

    fun setDeadlineReminderPreferences(preferences: DeadlineReminderPreferences) {
        viewModelScope.launch {
            val granted = !preferences.enabled || deadlineNotificationScheduler.requestPermission()
            val savedPreferences = if (granted) preferences else preferences.copy(enabled = false)
            deadlineReminderPreferencesRepository.update(savedPreferences)
            val currentTasks = tasks.observeTasks().first()
            if (!savedPreferences.enabled) {
                currentTasks.forEach { deadlineNotificationScheduler.cancel(it.id) }
            } else {
                currentTasks.forEach { syncTaskReminders(it, savedPreferences, requestPermission = false) }
            }
            notice.value = when {
                !granted -> "Разрешите уведомления в настройках устройства, чтобы включить напоминания"
                !savedPreferences.enabled -> "Напоминания отключены"
                else -> "Настройки напоминаний сохранены"
            }
        }
    }

    private suspend fun syncTaskReminders(
        task: AcademicTask,
        preferences: DeadlineReminderPreferences,
        requestPermission: Boolean = true,
    ): Boolean {
        deadlineNotificationScheduler.cancel(task.id)
        val offsets = DeadlineReminderPolicy.effectiveOffsets(task, preferences)
        if (offsets.isEmpty()) return true
        if (requestPermission && !deadlineNotificationScheduler.requestPermission()) {
            deadlineReminderPreferencesRepository.update(preferences.copy(enabled = false))
            return false
        }
        return deadlineNotificationScheduler.schedule(task, offsets)
    }

    private fun LocalDate.startOfWeek(): LocalDate =
        minus(DatePeriod(days = dayOfWeek.isoDayNumber - 1))

    private fun today(): LocalDate =
        clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private fun Lesson.isCurrentAt(now: kotlinx.datetime.LocalDateTime): Boolean {
        if (lessonDate != now.date.toString()) return false
        val starts = runCatching { kotlinx.datetime.LocalTime.parse(startTime) }.getOrNull() ?: return false
        val ends = runCatching { kotlinx.datetime.LocalTime.parse(endTime) }.getOrNull() ?: return false
        return now.time >= starts && now.time < ends
    }
}
