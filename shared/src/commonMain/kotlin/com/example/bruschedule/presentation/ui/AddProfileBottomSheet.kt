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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.presentation.ProfileSearchUiState
import com.example.bruschedule.ui.theme.BruColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProfileBottomSheet(
    searchState: ProfileSearchUiState,
    onClose: () -> Unit,
    onSearch: (ScheduleProfileType, String) -> Unit,
    onAddManual: (ScheduleProfileType, String, String?) -> Unit,
    onSelectCandidate: (ProfileCandidate) -> Unit,
) {
    var selectedType by remember { mutableStateOf(searchState.type) }
    var query by remember { mutableStateOf(searchState.query) }
    var sourceUrl by remember { mutableStateOf("") }
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
                "Найдите группу или преподавателя в каталоге rasp.bru.by. У групп показан факультет — проверьте его перед добавлением. Источник обновляется автоматически, расписание хранится локально.",
                color = BruColors.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileTypeChip("Группа", selectedType == ScheduleProfileType.GROUP) { selectedType = ScheduleProfileType.GROUP }
                ProfileTypeChip("Преподаватель", selectedType == ScheduleProfileType.TEACHER) { selectedType = ScheduleProfileType.TEACHER }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (selectedType == ScheduleProfileType.GROUP) "Номер группы" else "Фамилия преподавателя") },
                placeholder = { Text(if (selectedType == ScheduleProfileType.GROUP) "Например, МПР-261" else "Например, Агеева") },
                shape = RoundedCornerShape(14.dp),
            )
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
                Text(if (searchState.loading) "Ищем в каталоге" else "Искать на сайте", fontWeight = FontWeight.SemiBold)
            }

            searchState.error?.let {
                Text(it, color = BruColors.danger, fontSize = 11.sp, lineHeight = 16.sp)
            }
            searchState.candidates.take(14).forEach { candidate ->
                ProfileCandidateRow(candidate, onClick = { onSelectCandidate(candidate) })
            }

            if (searchState.candidates.isNotEmpty()) {
                Text("ИЛИ ДОБАВИТЬ ПО ССЫЛКЕ", color = BruColors.dim, fontSize = 9.sp, letterSpacing = 1.1.sp)
            } else {
                Text("Если каталог временно недоступен — добавьте профиль вручную; ссылку можно вставить ниже.", color = BruColors.dim, fontSize = 10.sp, lineHeight = 15.sp)
            }
            OutlinedTextField(
                value = sourceUrl,
                onValueChange = { sourceUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Ссылка на страницу rasp.bru.by · необязательно") },
                placeholder = { Text("https://rasp.bru.by/…") },
                shape = RoundedCornerShape(14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Text("Отмена", color = BruColors.muted)
                }
                Button(
                    onClick = { onAddManual(selectedType, query.trim(), sourceUrl.trim().ifBlank { null }) },
                    enabled = query.trim().isNotBlank(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BruColors.elevatedSurface, contentColor = BruColors.cyan),
                ) {
                    Text("Добавить вручную", fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Подсказка: для обновления без устаревшей ссылки лучше выбирать результат из каталога.", color = BruColors.dim, fontSize = 10.sp, lineHeight = 15.sp)
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
