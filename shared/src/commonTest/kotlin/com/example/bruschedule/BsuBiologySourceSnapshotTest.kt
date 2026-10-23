package com.example.bruschedule

import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BsuBiologySourceSnapshotTest {
    @Test
    fun attachedOfficialHtmlMapsAllGroupsAndParsesARealLesson() {
        val parser = BsuBiologyScheduleHtmlParser()
        val groups = parser.discoverGroups(ATTACHED_SOURCE_FRAGMENT)

        assertEquals(
            listOf("47", "48", "49", "50", "51", "52", "53", "54", "55", "56", "79", "57", "58", "59"),
            groups.map { it.id },
        )
        assertEquals(
            listOf("Группа 41", "Группа 42", "Группа 42A", "Группа 43", "Группа 44", "Группа 45", "Группа 46", "Группа 47", "Группа 48", "Группа 49", "Группа 50", "Группа 61", "Группа 62", "Группа 63"),
            groups.map { it.name },
        )

        val profile = ScheduleProfile(
            id = "bsu-bio-42",
            type = ScheduleProfileType.GROUP,
            name = "Группа 42",
            sourceUrl = "https://bio.bsu.by/schedule/?study_mode=%D0%94%D0%BD%D0%B5%D0%B2%D0%BD%D0%B0%D1%8F&course=3&week_date=2026-10-19",
            sourceKey = "${BsuBiologyScheduleHtmlParser.BIO_GROUP_SOURCE_KEY_PREFIX}48",
            universityId = UniversityIds.BSU,
        )
        val lesson = parser.parse(profile, ATTACHED_SOURCE_FRAGMENT, LocalDate(2026, 10, 19)).single()

        assertEquals("Физическая культура (ПЗ)", lesson.subjectName)
        assertEquals("Микитченко Надежда Владимировна", lesson.teacherName)
        assertEquals("2026-10-19", lesson.lessonDate)
        assertEquals("10:35", lesson.startTime)
        assertEquals("12:00", lesson.endTime)
        assertEquals(1, lesson.dayOfWeek)
        assertEquals(2, lesson.slotNumber)
    }

    private companion object {
        // Real markup and server IDs from the user-provided 2026-10-19 Biology faculty snapshot.
        // Unrelated cells are omitted; the group headers, ID order and selected lesson are original.
        val ATTACHED_SOURCE_FRAGMENT = """
        <html><body>
        <div id="week-info" style="color: rgb(16, 185, 129);">19.10.2026 — 25.10.2026</div>
        <table class="grid-table">
        <thead id="schedule-header">
                <tr>
                    <th class="day-header">День</th>
                    <th class="time-header">Время</th>
                    <th class="group-header"><span class="group-header-number">Группа 41</span><span class="group-header-name">Экология 6-05-0521-01</span></th><th class="group-header"><span class="group-header-number">Группа 42</span><span class="group-header-name">Биология 6-05-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 42A</span><span class="group-header-name">Биология 6-05-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 43</span><span class="group-header-name">Биология 6-05-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 44</span><span class="group-header-name">Биология 6-05-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 45</span><span class="group-header-name">Биохимия 6-05-0511-02</span></th><th class="group-header"><span class="group-header-number">Группа 46</span><span class="group-header-name">Микробиология 6-05-0511-03</span></th><th class="group-header"><span class="group-header-number">Группа 47</span><span class="group-header-name">Микробиология 6-05-0511-03</span></th><th class="group-header"><span class="group-header-number">Группа 48</span><span class="group-header-name">Биоинженерия и биоинформатика 6-05-0511-05</span></th><th class="group-header"><span class="group-header-number">Группа 49</span><span class="group-header-name">Биоинженерия и биоинформатика 6-05-0511-05</span></th><th class="group-header"><span class="group-header-number">Группа 50</span><span class="group-header-name">6-05-0511-06 Биотехнология</span></th><th class="group-header"><span class="group-header-number">Группа 61</span><span class="group-header-name">ФПБт 7-07-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 62</span><span class="group-header-name">ФПБт 7-07-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 63</span><span class="group-header-name">ФПБт 7-07-0511-01</span></th>
                </tr>
            </thead>
        <tbody id="schedule-body">
        <tr><td class="day-header" rowspan="8">
                <span class="day-name-full">Понедельник</span>
                <span class="day-name-short" aria-label="Понедельник">Пн</span>
            </td><td class="time-header" data-day="0" data-slot-id="1">
                <strong>1 пара</strong><br>
                <span class="time-range-full">09:00-10:25</span>
                <span class="time-range-mobile">09:00-<br>10:25</span>
            </td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="47" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="48" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="49" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="50" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="51" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="52" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="53" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="54" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="55" style="cursor: default;"></td><td class="cell" data-day="0" data-slot="1" data-group-id="56" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="79" style="cursor: default;"></td><td class="cell" data-day="0" data-slot="1" data-group-id="57" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="58" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="59" style="cursor: default;"></td></tr>
        <tr><td class="time-header" data-day="0" data-slot-id="2">
                <strong>2 пара</strong><br>
                <span class="time-range-full">10:35-12:00</span>
                <span class="time-range-mobile">10:35-<br>12:00</span>
            </td><td class="cell" data-day="0" data-slot="2" data-group-id="48" style="cursor: default;">
                                    <div class="lesson-card practice is-readonly-preview" data-lesson-id="12121" style="cursor: default;">
                                        
                                        <div class="lesson-content">
                                            <div class="lesson-title">Физическая культура (ПЗ)</div>
                                            <div class="lesson-teacher"><a href="https://bio.bsu.by/employees/261/?from=schedule&amp;study_mode=%D0%94%D0%BD%D0%B5%D0%B2%D0%BD%D0%B0%D1%8F&amp;course=3" class="teacher-link" onclick="event.stopPropagation()">Микитченко Надежда Владимировна</a></div>
                                            
                                            
                                            
                                        </div>
                                    </div>
                                </td></tr>
        </tbody></table>
        </body></html>        """.trimIndent()
    }
}
