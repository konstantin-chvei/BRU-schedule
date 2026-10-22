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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.TypographyRole
import com.example.bruschedule.domain.model.University
import com.example.bruschedule.domain.model.UniversitySourceType
import com.example.bruschedule.presentation.ProfileSearchUiState
import com.example.bruschedule.ui.theme.BruColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProfileBottomSheet(
    searchState: ProfileSearchUiState,
    university: University,
    onClose: () -> Unit,
    onSearch: (ScheduleProfileType, String) -> Unit,
    onAddManual: (ScheduleProfileType, String, String?) -> Unit,
    onSelectCandidate: (ProfileCandidate) -> Unit,
) {
    val hasBruCatalog = university.sourceType == UniversitySourceType.BRU_CATALOG
    val hasBsuCatalog = university.sourceType == UniversitySourceType.BSU_HTML_AND_SHEETS
    val hasProfileCatalog = hasBruCatalog || hasBsuCatalog
    var selectedType by remember(university.id) {
        mutableStateOf(if (hasBsuCatalog) ScheduleProfileType.GROUP else searchState.type)
    }
    var query by remember(university.id) { mutableStateOf(searchState.query) }
    var sourceUrl by remember(university.id) { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onClose,
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
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text("НОВЫЙ ПРОФИЛЬ", color = BruColors.cyan, fontSize = 9.sp, letterSpacing = 1.3.sp)
            Text("Подключить расписание", color = BruColors.foreground, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    hasBruCatalog -> "Ищите группу или преподавателя в каталоге rasp.bru.by. Для других вузов добавьте открытую HTML-страницу расписания напрямую. Сохранённые пары доступны офлайн."
                    hasBsuCatalog -> "Для БГУ поиск сканирует страницы факультетов, HTML-таблицы и открытые Google Sheets. Выберите группу из результатов; файлы PDF, Excel, Word и динамические расписания требуют отдельных адаптеров."
                    else -> "Вуз: ${university.name}. Каталог этого сайта пока не подключён. Вставьте прямую HTTPS-ссылку на открытую HTML-страницу с таблицей расписания; страницы, которые строятся только JavaScript, не поддерживаются."
                },
                color = BruColors.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            if (!hasBsuCatalog) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileTypeChip("Группа", selectedType == ScheduleProfileType.GROUP) { selectedType = ScheduleProfileType.GROUP }
                    ProfileTypeChip("Преподаватель", selectedType == ScheduleProfileType.TEACHER) { selectedType = ScheduleProfileType.TEACHER }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = typographyTextStyle(TypographyRole.BODY),
                singleLine = true,
                label = {
                    Text(
                        when {
                            hasBruCatalog && selectedType == ScheduleProfileType.GROUP -> "Номер группы"
                            hasBruCatalog -> "Фамилия преподавателя"
                            hasBsuCatalog && selectedType == ScheduleProfileType.GROUP -> "Код или название группы"
                            selectedType == ScheduleProfileType.GROUP -> "Название группы или профиля"
                            else -> "Название преподавателя или профиля"
                        },
                    )
                },
                placeholder = {
                    Text(
                        when {
                            hasBruCatalog && selectedType == ScheduleProfileType.GROUP -> "Например, МПР-261"
                            hasBruCatalog -> "Например, Агеева"
                            hasBsuCatalog && selectedType == ScheduleProfileType.GROUP -> "Например, 1-11 или 1 группа А"
                            selectedType == ScheduleProfileType.GROUP -> "Например, ИС-241"
                            else -> "Фамилия преподавателя"
                        },
                    )
                },
                shape = RoundedCornerShape(14.dp),
            )

            if (hasProfileCatalog) {
                Button(
                    onClick = { onSearch(selectedType, query.trim()) },
                    enabled = query.trim().length >= 2 && !searchState.loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BruColors.indigo, contentColor = BruColors.background),
                ) {
                    if (searchState.loading) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = BruColors.background)
                        Spacer(Modifier.width(9.dp))
                    }
                    Text(
                        when {
                            searchState.loading && hasBsuCatalog -> "Ищем расписания факультетов"
                            searchState.loading -> "Ищем в каталоге"
                            hasBsuCatalog -> "Искать группу БГУ"
                            else -> "Искать в каталоге"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                searchState.error?.let { error ->
                    ScheduleErrorPanel(
                        error = error,
                        onRetry = { onSearch(selectedType, query.trim()) },
                    )
                }
                searchState.candidates.take(14).forEach { candidate ->
                    ProfileCandidateRow(candidate, onClick = { onSelectCandidate(candidate) })
                }
                Text(
                    if (searchState.candidates.isNotEmpty()) "ИЛИ ДОБАВИТЬ ПО ССЫЛКЕ"
                    else "Если профиль не найден, его можно добавить вручную.",
                    color = BruColors.dim,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                )
            } else {
                Text(
                    "УКАЖИТЕ ПРЯМУЮ ССЫЛКУ НА РАСПИСАНИЕ",
                    color = BruColors.dim,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                )
            }

            OutlinedTextField(
                value = sourceUrl,
                onValueChange = { sourceUrl = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = typographyTextStyle(TypographyRole.BODY),
                singleLine = true,
                label = {
                    Text(
                        when {
                            hasBruCatalog -> "Ссылка на rasp.bru.by · необязательно"
                            hasBsuCatalog -> "Ссылка на HTML или Google Sheets · для ручного добавления"
                            else -> "Прямая ссылка на HTML-страницу · обязательно"
                        },
                    )
                },
                placeholder = {
                    Text(
                        when {
                            hasBruCatalog -> "https://rasp.bru.by/…"
                            hasBsuCatalog -> "https://mmf.bsu.by/… или docs.google.com/…"
                            else -> "https://university.by/schedule/…"
                        },
                    )
                },
                shape = RoundedCornerShape(14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Text("Отмена", color = BruColors.muted)
                }
                Button(
                    onClick = { onAddManual(selectedType, query.trim(), sourceUrl.trim().ifBlank { null }) },
                    enabled = query.trim().isNotBlank() && (hasBruCatalog || sourceUrl.trim().startsWith("https://", ignoreCase = true)),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BruColors.elevatedSurface, contentColor = BruColors.cyan),
                ) {
                    Text("Добавить вручную", fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                when {
                    hasBruCatalog -> "Для БРУ источник обновляется через каталог; прямые ссылки можно заменить позднее."
                    hasBsuCatalog -> "Выбранный профиль сохраняется локально и обновляется напрямую с сайта факультета; сервер приложения не используется."
                    else -> "Если сайт изменит разметку или требует входа, расписание может не загрузиться."
                },
                color = BruColors.dim,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun ProfileTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) BruColors.indigo.copy(alpha = 0.14f) else BruColors.elevatedSurface,
        border = BorderStroke(1.dp, if (selected) BruColors.indigo else BruColors.outline),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp), color = if (selected) BruColors.foreground else BruColors.muted, fontSize = 11.sp)
    }
}

@Composable
private fun ProfileCandidateRow(candidate: ProfileCandidate, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = BruColors.elevatedSurface,
        border = BorderStroke(1.dp, BruColors.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(candidate.name, color = BruColors.foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(candidate.subtitle.ifBlank { "rasp.bru.by" }, color = BruColors.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("＋", modifier = Modifier.padding(start = 10.dp), color = BruColors.cyan, fontSize = 18.sp)
        }
    }
}
