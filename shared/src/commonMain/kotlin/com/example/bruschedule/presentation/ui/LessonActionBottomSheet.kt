package com.example.bruschedule.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.ui.theme.BruColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber

private enum class LessonSheetPage { EDIT_LESSON, ADD_TASK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonActionBottomSheet(
    lesson: Lesson,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSaveLesson: (Lesson) -> Unit,
    onCreateTask: (Lesson, String, String, String) -> Unit,
) {
    var page by remember(lesson.id) { mutableStateOf(LessonSheetPage.EDIT_LESSON) }
    var subject by remember(lesson.id) { mutableStateOf(lesson.subjectName) }
    var teacher by remember(lesson.id) { mutableStateOf(lesson.teacherName) }
    var classroom by remember(lesson.id) { mutableStateOf(lesson.classroom) }
    var date by remember(lesson.id) { mutableStateOf(lesson.lessonDate) }
    var start by remember(lesson.id) { mutableStateOf(lesson.startTime) }
    var end by remember(lesson.id) { mutableStateOf(lesson.endTime) }
    var slot by remember(lesson.id) { mutableStateOf(lesson.slotNumber.takeIf { it > 0 }?.toString().orEmpty()) }
    var type by remember(lesson.id) { mutableStateOf(lesson.type) }
    var validationError by remember(lesson.id) { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val editedLesson = lesson.copy(
        subjectName = subject.trim(),
        teacherName = teacher.trim(),
        classroom = classroom.trim(),
        lessonDate = date.trim(),
        dayOfWeek = runCatching { LocalDate.parse(date.trim()).dayOfWeek.isoDayNumber }.getOrDefault(lesson.dayOfWeek),
        startTime = start.trim(),
        endTime = end.trim(),
        slotNumber = slot.toIntOrNull() ?: lesson.slotNumber,
        type = type,
        colorHex = LessonTypePalette.accent(type),
        isCustom = true,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BruColors.surface,
        contentColor = BruColors.foreground,
        dragHandle = { BottomSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (page == LessonSheetPage.EDIT_LESSON) "${if (isNew) "НОВАЯ" else "РУЧНАЯ ПРАВКА"} · ПАРА" else "АКАДЕМИЧЕСКАЯ ЗАДАЧА", color = BruColors.cyan, fontSize = 9.sp, letterSpacing = 1.2.sp)
            Text(
                if (page == LessonSheetPage.EDIT_LESSON) if (isNew) "Добавить пару" else "Изменить расписание" else "Новый дедлайн",
                color = BruColors.foreground,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (page == LessonSheetPage.EDIT_LESSON) {
                OutlinedTextField(subject, { subject = it }, label = { Text("Предмет") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(13.dp))
                OutlinedTextField(teacher, { teacher = it }, label = { Text("Преподаватель") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(13.dp))
                OutlinedTextField(classroom, { classroom = it }, label = { Text("Корпус / аудитория") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(13.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(date, { date = it }, label = { Text("Дата · ГГГГ-ММ-ДД") }, modifier = Modifier.weight(1.2f), singleLine = true, shape = RoundedCornerShape(13.dp))
                    OutlinedTextField(slot, { slot = it }, label = { Text("Пара №") }, modifier = Modifier.weight(0.8f), singleLine = true, shape = RoundedCornerShape(13.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(start, { start = it }, label = { Text("Начало") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(13.dp))
                    OutlinedTextField(end, { end = it }, label = { Text("Конец") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(13.dp))
                }
                LessonTypeDropdown(type, onTypeChange = { type = it })
                validationError?.let { Text(it, color = BruColors.danger, fontSize = 11.sp) }
                Text("Ручные пары не перезаписываются при синхронизации с сайтом.", color = BruColors.dim, fontSize = 10.sp, lineHeight = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Закрыть", color = BruColors.muted) }
                    Button(
                        onClick = {
                            validationError = validateLesson(editedLesson)
                            if (validationError == null) onSaveLesson(editedLesson)
                        },
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BruColors.indigo, contentColor = BruColors.background),
                    ) { Text("Сохранить правку", fontWeight = FontWeight.SemiBold) }
                }
                Button(
                    onClick = {
                        validationError = validateLesson(editedLesson)
                        if (validationError == null) page = LessonSheetPage.ADD_TASK
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BruColors.elevatedSurface, contentColor = BruColors.cyan),
                ) { Text("＋ Добавить дедлайн к этой паре") }
            } else {
                TaskEditor(
                    defaultDate = date,
                    onCancel = { page = LessonSheetPage.EDIT_LESSON },
                    onCreate = { title, description, dueDate ->
                        validationError = validateLesson(editedLesson)
                        if (validationError == null) onCreateTask(editedLesson, title, description, dueDate)
                    },
                )
                validationError?.let { Text(it, color = BruColors.danger, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun LessonTypeDropdown(type: LessonType, onTypeChange: (LessonType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ТИП ЗАНЯТИЯ", color = BruColors.dim, fontSize = 9.sp, letterSpacing = 1.sp)
        androidx.compose.foundation.layout.Box {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                color = BruColors.elevatedSurface,
                border = BorderStroke(1.dp, BruColors.outline),
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(type.label(), color = BruColors.foreground, fontSize = 12.sp)
                    Text("⌄", color = BruColors.muted)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LessonType.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.label()) },
                        onClick = { onTypeChange(candidate); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskEditor(
    defaultDate: String,
    onCancel: () -> Unit,
    onCreate: (String, String, String) -> Unit,
) {
    var title by remember(defaultDate) { mutableStateOf("") }
    var description by remember(defaultDate) { mutableStateOf("") }
    var dueDate by remember(defaultDate) { mutableStateOf(defaultDate) }
    var dueDateError by remember(defaultDate) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text("Задание будет связано с этой парой и появится в панели дедлайнов.", color = BruColors.muted, fontSize = 11.sp, lineHeight = 16.sp)
        OutlinedTextField(title, { title = it }, label = { Text("Название · например, лабораторная № 2") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(13.dp))
        OutlinedTextField(description, { description = it }, label = { Text("Комментарий") }, modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(13.dp))
        OutlinedTextField(
            value = dueDate,
            onValueChange = { dueDate = it; dueDateError = null },
            label = { Text("Срок · ГГГГ-ММ-ДД") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = dueDateError != null,
            shape = RoundedCornerShape(13.dp),
        )
        dueDateError?.let { Text(it, color = BruColors.danger, fontSize = 10.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Назад", color = BruColors.muted) }
            Button(
                onClick = {
                    dueDateError = if (runCatching { LocalDate.parse(dueDate.trim()) }.isSuccess) {
                        null
                    } else {
                        "Дата должна быть в формате ГГГГ-ММ-ДД"
                    }
                    if (dueDateError == null) onCreate(title, description, dueDate)
                },
                modifier = Modifier.weight(1.2f),
                enabled = title.isNotBlank(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BruColors.indigo, contentColor = BruColors.background),
            ) { Text("Создать задачу", fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(3.dp))
    }
}

private fun validateLesson(lesson: Lesson): String? {
    if (lesson.subjectName.isBlank()) return "Название предмета не может быть пустым"
    val start = runCatching { LocalTime.parse(lesson.startTime) }.getOrNull()
    val end = runCatching { LocalTime.parse(lesson.endTime) }.getOrNull()
    if (start == null || end == null || end <= start) return "Укажите корректное время начала и окончания в формате ЧЧ:ММ"
    if (runCatching { LocalDate.parse(lesson.lessonDate) }.isFailure) return "Дата должна быть в формате ГГГГ-ММ-ДД"
    return null
}

private fun LessonType.label(): String = when (this) {
    LessonType.LECTURE -> "Лекция"
    LessonType.LABORATORY -> "Лабораторная"
    LessonType.PRACTICE -> "Практика"
    LessonType.UNKNOWN -> "Другое / неизвестно"
}
