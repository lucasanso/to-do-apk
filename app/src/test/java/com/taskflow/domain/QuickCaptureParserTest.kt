package com.taskflow.domain

import com.taskflow.TODAY
import com.taskflow.domain.engine.QuickCaptureParser
import com.taskflow.domain.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class QuickCaptureParserTest {
    @Test fun `parses all tokens`() {
        val r = QuickCaptureParser.parse("Estudar SQL amanhã 19h !alta #sql #estudo @estudos 45min", TODAY)
        assertEquals("Estudar SQL", r.title)
        assertEquals(TODAY.plusDays(1), r.dueDate)
        assertEquals(LocalTime.of(19, 0), r.dueTime)
        assertEquals(Priority.HIGH, r.priority)
        assertEquals(listOf("sql", "estudo"), r.tags)
        assertEquals("estudos", r.categoryHint)
        assertEquals(45, r.estimatedMinutes)
    }

    @Test fun `plain text stays plain`() {
        val r = QuickCaptureParser.parse("Comprar pão", TODAY)
        assertEquals("Comprar pão", r.title); assertNull(r.dueDate); assertNull(r.priority)
    }

    @Test fun `weekday resolves to next occurrence`() {
        // TODAY is Monday 2026-11-09
        assertEquals(LocalDate.of(2026, 11, 13), QuickCaptureParser.parse("Relatório sexta", TODAY).dueDate)
        assertEquals(LocalDate.of(2026, 11, 16), QuickCaptureParser.parse("Reunião seg", TODAY).dueDate)
    }

    @Test fun `time alone implies today and supports colon format`() {
        val r = QuickCaptureParser.parse("Ligar 14:30", TODAY)
        assertEquals(TODAY, r.dueDate); assertEquals(LocalTime.of(14, 30), r.dueTime)
    }

    @Test fun `numeric priority, date and hour duration`() {
        val r = QuickCaptureParser.parse("Prova !4 25/12 2hrs", TODAY)
        assertEquals(Priority.CRITICAL, r.priority)
        assertEquals(LocalDate.of(2026, 12, 25), r.dueDate)
        assertEquals(120, r.estimatedMinutes)
        assertEquals("Prova", r.title)
    }

    @Test fun `past date without year rolls to next year`() {
        assertEquals(LocalDate.of(2027, 1, 5), QuickCaptureParser.parse("x 05/01", TODAY).dueDate)
    }

    @Test fun `hour alone is a time and compound durations parse`() {
        assertEquals(LocalTime.of(19, 0), QuickCaptureParser.parse("Aula 19h", TODAY).dueTime)
        assertEquals(90, QuickCaptureParser.parse("Treino 1h30m", TODAY).estimatedMinutes)
        assertEquals(90, QuickCaptureParser.parse("Treino 1,5h", TODAY).estimatedMinutes)
    }

    @Test fun `invalid tokens are kept in title`() {
        val r = QuickCaptureParser.parse("Rodar 99:99 !xyz", TODAY)
        assertEquals("Rodar 99:99 !xyz", r.title)
    }
}
