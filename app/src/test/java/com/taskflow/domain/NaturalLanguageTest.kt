package com.taskflow.domain

import com.taskflow.domain.engine.AiResponseParser
import com.taskflow.domain.engine.ExtractionPrompt
import com.taskflow.domain.engine.HeuristicTaskExtractor
import com.taskflow.domain.engine.QuickCaptureParser
import com.taskflow.domain.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Today is fixed by TestFixtures; dates below are computed from it so the tests never rot. */
class NaturalLanguageTest {
    private val today = com.taskflow.TODAY
    private fun next(d: DayOfWeek) = today.with(TemporalAdjusters.next(d))
    private fun parse(s: String) = QuickCaptureParser.parse(s, today)

    @Test fun `meeting with weekday, spoken time and subtask`() {
        val r = parse("Reunião com o João sexta-feira às 3 da tarde, levar contrato")
        assertEquals("Reunião com o João", r.title)
        assertEquals(next(DayOfWeek.FRIDAY), r.dueDate)
        assertEquals(LocalTime.of(15, 0), r.dueTime)
        assertEquals(listOf("Levar contrato"), r.subtasks)
    }

    @Test fun `filler words, day of month and urgency`() {
        val r = parse("Preciso pagar o boleto da faculdade até dia 28, urgente")
        assertEquals("pagar o boleto da faculdade", r.title)
        assertEquals(28, r.dueDate!!.dayOfMonth)
        assertTrue(!r.dueDate!!.isBefore(today))
        assertEquals(Priority.CRITICAL, r.priority)
    }

    @Test fun `relative dates`() {
        assertEquals(today.plusDays(2), parse("ligar pro banco depois de amanhã").dueDate)
        assertEquals(today.plusDays(3), parse("revisar código daqui a 3 dias").dueDate)
        assertEquals(today.plusDays(14), parse("renovar CNH em duas semanas").dueDate)
        assertEquals(next(DayOfWeek.MONDAY), parse("planejar sprint semana que vem").dueDate)
        assertEquals(next(DayOfWeek.TUESDAY), parse("dentista na próxima terça").dueDate)
    }

    @Test fun `named month and times`() {
        val r = parse("Entregar TCC 15 de dezembro às 9 e meia")
        assertEquals("Entregar TCC", r.title)
        assertEquals(12, r.dueDate!!.monthValue); assertEquals(15, r.dueDate!!.dayOfMonth)
        assertEquals(LocalTime.of(9, 30), r.dueTime)
        assertEquals(LocalTime.of(12, 0), parse("almoço com a equipe ao meio-dia").dueTime)
        assertEquals(LocalTime.of(20, 0), parse("jogo às 8 da noite").dueTime)
        assertEquals(LocalTime.of(14, 0), parse("call às 14 horas").dueTime)
    }

    @Test fun `durations in words`() {
        assertEquals(30, parse("meditar meia hora").estimatedMinutes)
        assertEquals(90, parse("academia hora e meia").estimatedMinutes)
        assertEquals(120, parse("estudar durante 2 horas").estimatedMinutes)
        assertEquals(45, parse("ler 45 minutos").estimatedMinutes)
    }

    @Test fun `ordinary words are not mistaken for dates or times`() {
        val r = parse("separar as 3 caixas da segunda prateleira")
        assertEquals("separar as 3 caixas da segunda prateleira", r.title)
        assertNull(r.dueTime); assertNull(r.dueDate)
        assertEquals("comprar arroz, feijão", parse("comprar arroz, feijão").title)
    }

    @Test fun `heuristic extractor picks action items from a transcript`() {
        val transcript = """
            Falante 1: Bom dia a todos, tudo certo?
            Falante 2: Tudo. Então, o relatório ficou ótimo.
            Falante 1: Beleza. Ana, você fica de enviar a proposta pro cliente até sexta.
            Falante 2: Combinado. E precisamos marcar a revisão do orçamento na próxima terça às 10h.
            Falante 1: O tempo ontem estava bom.
        """.trimIndent()
        val tasks = HeuristicTaskExtractor.extract(transcript, today)
        assertEquals(2, tasks.size)
        assertTrue(tasks[0].title.contains("enviar a proposta"))
        assertEquals(next(DayOfWeek.FRIDAY), tasks[0].dueDate)
        assertEquals(next(DayOfWeek.TUESDAY), tasks[1].dueDate)
        assertEquals(LocalTime.of(10, 0), tasks[1].dueTime)
    }

    @Test fun `typed list becomes one task per line`() {
        val tasks = HeuristicTaskExtractor.extract("- comprar pão\n- pagar luz amanhã\n• ligar pra mãe", today)
        assertEquals(listOf("Comprar pão", "Pagar luz", "Ligar pra mãe"), tasks.map { it.title })
        assertEquals(today.plusDays(1), tasks[1].dueDate)
    }

    @Test fun `ai response parser tolerates fences, chatter and bad fields`() {
        val d = today.plusDays(3)
        val raw = """
            Claro! Aqui estão:
            ```json
            [{"titulo":"enviar proposta","data":"$d","hora":"14:30","prioridade":"Alta","duracao_min":"45","subtarefas":["anexar PDF",""],"trecho":"Ana envia"},
             {"titulo":"x"},
             {"titulo":"Revisar orçamento","data":"1999-01-01","hora":"99:00","prioridade":null}]
            ```
        """.trimIndent()
        val tasks = AiResponseParser.parse(raw, today)!!
        assertEquals(2, tasks.size)
        with(tasks[0]) {
            assertEquals("Enviar proposta", title); assertEquals(d, dueDate); assertEquals(LocalTime.of(14, 30), dueTime)
            assertEquals(Priority.HIGH, priority); assertEquals(45, estimatedMinutes); assertEquals(listOf("anexar PDF"), subtasks)
        }
        assertNull(tasks[1].dueDate); assertNull(tasks[1].dueTime)
        assertNull(AiResponseParser.parse("Não encontrei tarefas.", today))
        assertEquals(0, AiResponseParser.parse("[]", today)!!.size)
    }

    @Test fun `long text is chunked on sentence boundaries`() {
        val sentence = "Preciso revisar o item número tal com calma. "
        val chunks = ExtractionPrompt.chunks(sentence.repeat(200), max = 500)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 500 && it.endsWith(".") })
        assertTrue(ExtractionPrompt.build("x", LocalDate.of(2026, 11, 9)).contains("2026-11-09"))
    }
}
