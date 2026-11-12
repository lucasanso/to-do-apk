package com.taskflow.domain

import com.taskflow.NOW
import com.taskflow.TODAY
import com.taskflow.domain.model.Priority
import com.taskflow.domain.model.RecurrenceType
import com.taskflow.domain.voice.CompletionCandidate
import com.taskflow.domain.voice.CompletionCandidate.Kind
import com.taskflow.domain.voice.TaskMatch
import com.taskflow.domain.voice.TaskDraft
import com.taskflow.domain.voice.TaskMatcher
import com.taskflow.domain.voice.VoiceCommandParser
import com.taskflow.domain.voice.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** NOW = Monday 2026-11-09 10:00. */
class VoiceCommandParserTest {
    private val cats = listOf("Universidade", "Trabalho", "Engenharia de Dados", "Finanças", "Saúde e exercícios", "Estudos", "Casa")

    private fun create(text: String): TaskDraft {
        val r = VoiceCommandParser.parse(text, NOW, cats)
        assertTrue("expected Create for '$text' but was $r", r is VoiceIntent.Create)
        return (r as VoiceIntent.Create).draft
    }

    private fun complete(text: String): String {
        val r = VoiceCommandParser.parse(text, NOW, cats)
        assertTrue("expected Complete for '$text' but was $r", r is VoiceIntent.Complete)
        return (r as VoiceIntent.Complete).query
    }

    // ---------- create: dates and times

    @Test fun `reminder with tomorrow and afternoon hour`() {
        val d = create("Me lembra de pagar a fatura amanhã às 3 da tarde")
        assertEquals("Pagar a fatura", d.title)
        assertEquals(TODAY.plusDays(1), d.dueDate)
        assertEquals(LocalTime.of(15, 0), d.dueTime)
        assertEquals(0, d.reminderMinutesBefore)
    }

    @Test fun `number words and half hour`() {
        val d = create("reunião com o João na sexta às três e meia da tarde")
        assertEquals("Reunião com o João", d.title)
        assertEquals(LocalDate.of(2026, 11, 13), d.dueDate)
        assertEquals(LocalTime.of(15, 30), d.dueTime)
    }

    @Test fun `digital clock and compact hours`() {
        assertEquals(LocalTime.of(14, 30), create("dentista amanhã às 14:30").dueTime)
        assertEquals(LocalTime.of(19, 0), create("aula hoje 19h").dueTime)
        assertEquals(LocalTime.of(9, 15), create("call amanhã 9h15").dueTime)
    }

    @Test fun `periods without explicit hour`() {
        val d = create("ligar para a mãe hoje à noite")
        assertEquals("Ligar para a mãe", d.title)
        assertEquals(TODAY, d.dueDate); assertEquals(LocalTime.of(20, 0), d.dueTime)
        assertEquals(LocalTime.of(9, 0), create("revisar contrato amanhã de manhã").dueTime)
        assertEquals(LocalTime.of(9, 0), create("revisar contrato amanhã às 9 da manhã").dueTime)
    }

    @Test fun `noon and midnight`() {
        assertEquals(LocalTime.of(12, 0), create("almoço com a equipe amanhã ao meio-dia").dueTime)
        assertEquals(LocalTime.of(12, 30), create("almoço amanhã meio dia e meia").dueTime)
    }

    @Test fun `ambiguous small hours become afternoon`() {
        assertEquals(LocalTime.of(16, 0), create("buscar encomenda amanhã às 4").dueTime)
        assertEquals(LocalTime.of(8, 0), create("academia amanhã às 8").dueTime)
        // Today 10:00: "às 9" already passed, so it means 21:00.
        assertEquals(LocalTime.of(21, 0), create("tomar remédio hoje às 9").dueTime)
    }

    @Test fun `time without date picks today or tomorrow`() {
        assertEquals(TODAY, create("enviar email às 15h").dueDate)
        // 08:00 already passed at 10:00, so a bare "8h" means tonight; an explicit morning means tomorrow.
        val tonight = create("enviar email às 8h")
        assertEquals(TODAY, tonight.dueDate); assertEquals(LocalTime.of(20, 0), tonight.dueTime)
        val morning = create("enviar email às 8 da manhã")
        assertEquals(TODAY.plusDays(1), morning.dueDate); assertEquals(LocalTime.of(8, 0), morning.dueTime)
    }

    @Test fun `weekdays, day of month and explicit dates`() {
        assertEquals(LocalDate.of(2026, 11, 16), create("entregar relatório segunda-feira").dueDate)
        assertEquals(LocalDate.of(2026, 11, 11), create("pagar boleto na quarta").dueDate)
        assertEquals(LocalDate.of(2026, 11, 20), create("prova de cálculo dia 20").dueDate)
        assertEquals(LocalDate.of(2026, 12, 5), create("prova dia 5").dueDate)
        assertEquals(LocalDate.of(2026, 12, 15), create("viagem 15 de dezembro").dueDate)
        assertEquals(LocalDate.of(2027, 1, 1), create("ano novo primeiro de janeiro").dueDate)
        assertEquals(LocalDate.of(2026, 12, 24), create("ceia 24/12").dueDate)
        assertEquals(LocalDate.of(2026, 11, 11), create("pagar luz depois de amanhã").dueDate)
    }

    @Test fun `relative dates and times`() {
        assertEquals(TODAY.plusDays(3), create("renovar CNH daqui a 3 dias").dueDate)
        assertEquals(TODAY.plusWeeks(2), create("revisão em duas semanas").dueDate)
        assertEquals(LocalDate.of(2026, 11, 16), create("planejar sprint semana que vem").dueDate)
        assertEquals(LocalDate.of(2026, 12, 1), create("pagar IPVA mês que vem").dueDate)
        assertEquals(LocalDate.of(2026, 11, 14), create("lavar o carro no fim de semana").dueDate)
        val d = create("tirar o bolo do forno daqui a 40 minutos")
        assertEquals(TODAY, d.dueDate); assertEquals(LocalTime.of(10, 40), d.dueTime)
        assertEquals(LocalTime.of(11, 30), create("ligar de volta daqui a uma hora e meia").dueTime)
    }

    @Test fun `deadline preposition is removed from title`() {
        val d = create("terminar o TCC até sexta")
        assertEquals("Terminar o TCC", d.title)
        assertEquals(LocalDate.of(2026, 11, 13), d.dueDate)
    }

    // ---------- create: duration, priority, category, recurrence, reminder

    @Test fun `durations`() {
        assertEquals(60, create("estudar SQL amanhã às 19h por uma hora").estimatedMinutes)
        assertEquals(90, create("treino durante 1 hora e meia").estimatedMinutes)
        assertEquals(30, create("meditar meia hora").estimatedMinutes)
        assertEquals(45, create("revisar PR 45 minutos").estimatedMinutes)
        val d = create("estudar inglês 2 horas")
        assertEquals(120, d.estimatedMinutes); assertNull(d.dueTime)
    }

    @Test fun `priority words`() {
        assertEquals(Priority.CRITICAL, create("corrigir bug em produção urgente").priority)
        assertEquals(Priority.HIGH, create("enviar proposta amanhã é importante").priority)
        assertEquals(Priority.LOW, create("organizar fotos sem pressa").priority)
        assertEquals("Corrigir bug em produção", create("corrigir bug em produção urgente").title)
    }

    @Test fun `explicit category`() {
        val d = create("criar dashboard de vendas na categoria engenharia de dados amanhã")
        assertEquals("Engenharia de Dados", d.categoryHint)
        assertEquals("Criar dashboard de vendas", d.title)
        assertEquals("Finanças", create("pagar aluguel categoria finanças").categoryHint)
    }

    @Test fun `recurrence`() {
        val daily = create("estudar SQL todos os dias às 20h")
        assertEquals(RecurrenceType.DAILY, daily.recurrence?.type)
        assertEquals(TODAY, daily.dueDate); assertEquals("Estudar SQL", daily.title)

        val wd = create("academia toda segunda e quarta às 7h")
        assertEquals(RecurrenceType.WEEKDAYS, wd.recurrence?.type)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), wd.recurrence?.days)
        assertEquals(LocalDate.of(2026, 11, 11), wd.dueDate) // Monday 07:00 already passed
        assertEquals("Academia", wd.title)

        assertEquals(DayOfWeek.entries.take(5).toSet(), create("daily do time em dias úteis às 9h30").recurrence?.days)
        assertEquals(RecurrenceType.MONTHLY, create("pagar cartão todo mês dia 10").recurrence?.type)
        assertEquals(LocalDate.of(2026, 11, 10), create("pagar cartão todo mês dia 10").dueDate)
        assertEquals(3, create("regar plantas a cada 3 dias").recurrence?.interval)
    }

    @Test fun `explicit reminder offset`() {
        val d = create("consulta médica sexta às 14h me avisa 1 hora antes")
        assertEquals(60, d.reminderMinutesBefore)
        assertEquals("Consulta médica", d.title)
    }

    @Test fun `create prefixes are stripped`() {
        assertEquals("Comprar pão", create("adicionar tarefa comprar pão").title)
        assertEquals("Revisar o artigo", create("preciso revisar o artigo amanhã").title)
        assertEquals("Ligar pro banco", create("tenho que ligar pro banco").title)
        assertEquals("Reunião com cliente", create("agenda reunião com cliente quinta às 10h").title)
        assertEquals("Agendar dentista", create("agendar dentista").title)
        assertEquals("Lavar a louça", create("nova tarefa lavar a louça").title)
        assertEquals("Dashboard de vendas", create("cria dashboard de vendas").title)
        assertEquals("Criar dashboard de vendas", create("criar dashboard de vendas").title)
    }

    @Test fun `marcar without done suffix is a creation`() {
        assertEquals("Consulta", create("marca consulta amanhã às 10h").title)
    }

    @Test fun `articles are not read as numbers`() {
        val d = create("comprar uma pizza e duas cervejas amanhã")
        assertEquals("Comprar uma pizza e duas cervejas", d.title)
        assertNull(d.estimatedMinutes); assertNull(d.dueTime)
    }

    @Test fun `empty or noise is unknown`() {
        assertTrue(VoiceCommandParser.parse("   ", NOW) is VoiceIntent.Unknown)
        assertTrue(VoiceCommandParser.parse("amanhã às 15h", NOW) is VoiceIntent.Unknown)
    }

    // ---------- complete

    @Test fun `completion phrases`() {
        assertEquals("Relatório de banco de dados", complete("concluí o relatório de banco de dados"))
        assertEquals("Paguei a fatura do cartão", complete("já paguei a fatura do cartão"))
        assertEquals("Pagar fatura", complete("marca pagar fatura como feita"))
        assertEquals("Revisar PR", complete("pode marcar a tarefa revisar PR como concluída"))
        assertEquals("Relatório mensal", complete("relatório mensal está pronto"))
        assertEquals("Estudar SQL", complete("terminei de estudar SQL hoje"))
        assertEquals("Estudei SQL", complete("estudei SQL hoje à noite"))
        assertEquals("Fiz exercícios", complete("fiz exercícios"))
        assertEquals("Lista de cálculo", complete("dá baixa na lista de cálculo"))
        assertEquals("Fiz a revisão", complete("registra que eu fiz a revisão"))
        assertEquals("Trocar filtro", complete("conclua trocar filtro"))
    }

    @Test fun `infinitive finishing verbs create tasks`() {
        assertEquals("Concluir trabalho de cálculo", create("concluir trabalho de cálculo até sexta").title)
    }

    // ---------- matcher

    private val candidates = listOf(
        CompletionCandidate(1, Kind.TASK, "Pagar fatura do cartão", true),
        CompletionCandidate(2, Kind.TASK, "Entregar relatório de Banco de Dados", true),
        CompletionCandidate(3, Kind.TASK, "Estudar window functions em SQL"),
        CompletionCandidate(4, Kind.HABIT, "Estudar SQL", true),
        CompletionCandidate(5, Kind.HABIT, "Exercícios", true),
        CompletionCandidate(6, Kind.TASK, "Revisar PR do time de dados"),
        CompletionCandidate(7, Kind.TASK, "Revisar contrato do apartamento"),
        CompletionCandidate(8, Kind.TASK, "Agendar consulta médica"),
    )

    private fun unique(q: String): Long {
        val m = TaskMatcher.match(q, candidates)
        assertTrue("expected unique match for '$q' but was $m", m is TaskMatch.Unique)
        return (m as TaskMatch.Unique).match.candidate.id
    }

    @Test fun `matches inflected verbs and partial titles`() {
        assertEquals(1L, unique(complete("já paguei a fatura")))
        assertEquals(2L, unique(complete("concluí o relatório de banco de dados")))
        assertEquals(2L, unique(complete("entreguei o relatório")))
        assertEquals(4L, unique(complete("estudei SQL hoje")))
        assertEquals(5L, unique(complete("fiz exercícios")))
        assertEquals(6L, unique(complete("revisei o PR do time")))
        assertEquals(8L, unique(complete("agendei a consulta")))
    }

    @Test fun `tolerates recognition typos`() {
        assertEquals(8L, unique("consuta medica"))
    }

    @Test fun `ambiguous and missing references`() {
        val m = TaskMatcher.match(complete("revisei"), candidates)
        assertTrue(m is TaskMatch.Ambiguous)
        assertEquals(setOf(6L, 7L), (m as TaskMatch.Ambiguous).options.map { it.candidate.id }.toSet())
        assertTrue(TaskMatcher.match("comprei passagem de avião", candidates) is TaskMatch.NotFound)
    }

    // ---------- delete

    private fun delete(text: String): VoiceIntent.Delete {
        val r = VoiceCommandParser.parse(text, NOW, cats)
        assertTrue("expected Delete for '$text' but was $r", r is VoiceIntent.Delete)
        return r as VoiceIntent.Delete
    }

    @Test fun `deletion phrases`() {
        assertEquals("Estudar SQL", delete("apaga a tarefa estudar SQL").query)
        assertEquals("Revisar PR", delete("exclui revisar PR").query)
        assertEquals("Dentista", delete("pode remover o dentista").query)
        assertEquals("Pagar fatura", delete("quero apagar a tarefa pagar fatura").query)
        assertEquals("Reunião com cliente", delete("cancela a reunião com cliente").query)
        assertEquals("Comprar pão", delete("tira comprar pão da lista").query)
        assertEquals("Ler artigo", delete("esquece ler artigo").query)
        assertEquals("Academia", delete("deleta o hábito academia").query)
        assertEquals("Lista de cálculo", delete("apagar a tarefa lista de cálculo").query)
    }

    @Test fun `deletion date narrows the search and leaves the name clean`() {
        val d = delete("apaga a reunião de amanhã")
        assertEquals("Reunião", d.query)
        assertEquals(TODAY.plusDays(1), d.date)
        val onlyDate = delete("apaga a tarefa de sexta")
        assertEquals("", onlyDate.query)
        assertEquals(LocalDate.of(2026, 11, 13), onlyDate.date)
    }

    @Test fun `bare infinitives and ambiguous verbs stay creations`() {
        assertEquals("Cancelar assinatura da Netflix", create("cancelar assinatura da Netflix").title)
        assertEquals("Tirar o lixo", create("tirar o lixo amanhã").title)
        assertEquals("Remover manchas do sofá", create("remover manchas do sofá").title)
    }

    @Test fun `delete without a target is unknown`() {
        assertTrue(VoiceCommandParser.parse("apaga", NOW) is VoiceIntent.Unknown)
    }

    @Test fun `matcher only-date query and done tasks`() {
        val items = listOf(
            CompletionCandidate(1, Kind.TASK, "Prova de cálculo", dueDate = LocalDate.of(2026, 11, 13)),
            CompletionCandidate(2, Kind.TASK, "Pagar luz", dueDate = TODAY),
            CompletionCandidate(3, Kind.TASK, "Prova de física", open = false),
        )
        assertEquals(1L, (TaskMatcher.match("", items, LocalDate.of(2026, 11, 13)) as TaskMatch.Unique).match.candidate.id)
        assertTrue(TaskMatcher.match("", items, TODAY.plusDays(20)) is TaskMatch.NotFound)
        // Open items edge out done ones with the same score.
        val ranked = TaskMatcher.rank("prova", items)
        assertEquals(1L, ranked.first().candidate.id)
    }
}
