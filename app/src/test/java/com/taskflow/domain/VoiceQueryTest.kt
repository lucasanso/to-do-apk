package com.taskflow.domain

import com.taskflow.NOW
import com.taskflow.TODAY
import com.taskflow.domain.voice.ListKind
import com.taskflow.domain.voice.ListQuery
import com.taskflow.domain.voice.ListStatus
import com.taskflow.domain.voice.VoiceCommandParser
import com.taskflow.domain.voice.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** NOW = Monday 2026-11-09 10:00. */
class VoiceQueryTest {
    private val cats = listOf("Universidade", "Trabalho", "Engenharia de Dados", "Finanças", "Estudos", "Casa")

    private fun q(text: String): ListQuery {
        val r = VoiceCommandParser.parse(text, NOW, cats)
        assertTrue("expected Query for '$text' but was $r", r is VoiceIntent.Query)
        return (r as VoiceIntent.Query).query
    }

    private fun notQuery(text: String) {
        val r = VoiceCommandParser.parse(text, NOW, cats)
        assertFalse("'$text' should not be a query: $r", r is VoiceIntent.Query)
    }

    @Test fun `today questions`() {
        for (t in listOf("o que eu tenho hoje?", "O que tenho pra fazer hoje", "quais são minhas tarefas de hoje", "tarefas de hoje",
            "tenho alguma coisa hoje?", "me mostra as tarefas de hoje", "o que falta fazer?", "o que eu ainda não fiz hoje")) {
            val x = q(t)
            assertEquals(t, TODAY, x.from); assertEquals(t, TODAY, x.to)
            assertEquals(t, ListStatus.OPEN, x.status)
        }
        assertEquals("Hoje", q("o que eu tenho hoje?").label)
    }

    @Test fun `other days and ranges`() {
        assertEquals(TODAY.plusDays(1), q("o que tenho amanhã?").from)
        assertEquals("Amanhã", q("o que tenho amanhã?").label)
        val fri = q("tenho algo na sexta?")
        assertEquals(LocalDate.of(2026, 11, 13), fri.singleDay)
        assertEquals("Sexta, 13/11", fri.label)
        assertEquals(LocalDate.of(2026, 11, 20), q("quais tarefas do dia 20").singleDay)
        val week = q("quais as tarefas dessa semana")
        assertEquals(TODAY, week.from); assertEquals(TODAY.plusDays(6), week.to)
        val next = q("o que tenho na semana que vem?")
        assertEquals(TODAY.plusDays(7), next.from); assertEquals(TODAY.plusDays(13), next.to)
        assertEquals(LocalDate.of(2026, 11, 30), q("minha agenda deste mês").to)
        assertEquals(LocalDate.of(2026, 11, 14), q("o que tenho no fim de semana").from)
        assertEquals(TODAY.plusDays(3), q("quais tarefas dos próximos 3 dias").to)
    }

    @Test fun `open tasks without a date`() {
        val all = q("quais são minhas tarefas")
        assertNull(all.from); assertEquals(ListStatus.OPEN, all.status); assertEquals("Abertas", all.label)
        assertTrue(q("lista as tarefas sem prazo").noDate)
    }

    @Test fun `status, priority, kind, category, search`() {
        assertEquals(ListStatus.OVERDUE, q("o que está atrasado?").status)
        assertEquals(ListStatus.OVERDUE, q("quais tarefas estão vencidas").status)
        val done = q("o que eu fiz hoje?")
        assertEquals(ListStatus.DONE, done.status); assertEquals(TODAY, done.from)
        val recent = q("quais tarefas eu concluí")
        assertEquals(ListStatus.DONE, recent.status); assertEquals(TODAY.minusDays(6), recent.from)
        assertTrue(q("quais tarefas são importantes").highPriority)
        val habits = q("quais hábitos faltam hoje")
        assertEquals(ListKind.HABITS, habits.kind); assertEquals(TODAY, habits.from)
        assertEquals("Trabalho", q("quais tarefas de trabalho tenho essa semana").category)
        assertEquals("Universidade", q("o que tenho da faculdade amanhã").category)
        assertEquals("sql", q("quais tarefas sobre SQL").search)
    }

    @Test fun `what should I do now`() {
        for (t in listOf("o que devo fazer agora?", "por onde eu começo", "qual a minha prioridade", "quais são as prioridades")) {
            val x = q(t)
            assertTrue(t, x.recommend)
            assertEquals(t, "Por onde começar", x.label)
        }
    }

    @Test fun `commands are not mistaken for questions`() {
        notQuery("ver o filme amanhã às 20h")
        notQuery("lista de exercícios de cálculo para sexta")
        notQuery("agenda reunião com cliente quinta às 10h")
        notQuery("tenho que ligar pro banco")
        notQuery("já fiz exercícios")
        notQuery("apaga a tarefa estudar SQL")
        notQuery("concluí o relatório")
        notQuery("nova tarefa organizar a agenda")
    }
}
