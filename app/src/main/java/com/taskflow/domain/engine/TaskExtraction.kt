package com.taskflow.domain.engine

import com.taskflow.domain.model.Priority
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** A task found in free text (typed note, voice transcript), not yet saved. */
data class ExtractedTask(
    val title: String,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val priority: Priority? = null,
    val estimatedMinutes: Int? = null,
    val subtasks: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val categoryHint: String? = null,
    /** The sentence it came from, kept as the task description for context. */
    val source: String = "",
)

enum class ExtractionEngine(val label: String) {
    ON_DEVICE_AI("IA no aparelho (Gemini Nano)"),
    RULES("Regras locais"),
}

data class ExtractionResult(val tasks: List<ExtractedTask>, val engine: ExtractionEngine, val note: String? = null)

/**
 * Rule-based extraction: works on any phone, instantly, offline.
 * Typed lists → one task per line. Prose/transcripts → sentences that look actionable.
 */
object HeuristicTaskExtractor {
    private val actionCues = listOf(
        "preciso", "precisamos", "tenho que", "temos que", "tem que", "lembrar", "esquecer", "vou", "vamos", "fica de", "ficou de",
        "ficamos de", "combinamos de", "prazo", "entregar", "enviar", "mandar", "ligar", "marcar", "agendar", "comprar",
        "pagar", "revisar", "preparar", "verificar", "responder", "confirmar", "falar com", "levar", "reuniao", "fazer", "terminar",
        "finalizar", "comecar", "estudar", "atualizar", "resolver", "agendado", "tarefa", "pendente", "deadline", "ate ",
    )
    private val sentenceSplit = Regex("(?<=[.!?])\\s+|\\n+|;\\s*")
    private val speakerPrefix = Regex("^\\s*(?:\\[?\\d{1,2}:\\d{2}(?::\\d{2})?]?\\s*)?(?:(?:falante|speaker|participante|orador)\\s*\\d+|[\\p{L} ]{1,25})?\\s*[:\\-–]\\s+(?=\\S)", RegexOption.IGNORE_CASE)
    private val timestampOnly = Regex("^\\s*\\[?\\d{1,2}:\\d{2}(?::\\d{2})?]?\\s*")
    private val bullet = Regex("^\\s*(?:[-*•·]|\\d+[.)]|\\[ ?x? ?])\\s*", RegexOption.IGNORE_CASE)

    fun extract(text: String, today: LocalDate): List<ExtractedTask> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val looksLikeList = lines.size >= 2 &&
            lines.count { bullet.containsMatchIn(it) || it.split(Regex("\\s+")).size <= 12 } >= lines.size * 0.7
        val candidates = if (looksLikeList) lines.map { bullet.replace(it, "") }
        else text.split(sentenceSplit).map { cleanSpeaker(it.trim()) }.filter { it.isNotEmpty() && isActionable(it, today) }

        return candidates.mapNotNull { sentence ->
            val c = QuickCaptureParser.parse(sentence, today)
            // In prose, one-word leftovers ("Combinado", "Beleza") are acknowledgements, not tasks.
            if (c.title.length < 2 || (!looksLikeList && c.title.trim().split(Regex("\\s+")).size < 2)) null
            else ExtractedTask(
                title = c.title.replaceFirstChar { it.uppercase() }.take(200),
                dueDate = c.dueDate, dueTime = c.dueTime, priority = c.priority, estimatedMinutes = c.estimatedMinutes,
                subtasks = c.subtasks, tags = c.tags, categoryHint = c.categoryHint,
                source = sentence.take(500),
            )
        }.distinctBy { normalize(it.title) }
    }

    private fun cleanSpeaker(s: String): String {
        val noTs = timestampOnly.replace(s, "")
        // Only strip "Name: " when what follows is a real sentence, not "Lembrete: algo".
        val m = speakerPrefix.find(noTs) ?: return noTs
        val prefix = m.value
        return if (prefix.contains(Regex("lembrete|tarefa|todo|to-do", RegexOption.IGNORE_CASE))) noTs else noTs.removePrefix(prefix)
    }

    private fun isActionable(sentence: String, today: LocalDate): Boolean {
        val n = " ${normalize(sentence)} "
        if (sentence.trim().endsWith("?")) return false
        if (actionCues.any { n.contains(it) }) return true
        val parsed = QuickCaptureParser.parse(sentence, today)
        return parsed.dueDate != null || parsed.dueTime != null || parsed.priority != null
    }

    internal fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}

/** Prompt for the on-device model. Short and strict: small models follow terse JSON specs best. */
object ExtractionPrompt {
    /** Gemini Nano has a small context window; long transcripts are processed in chunks of about this many chars. */
    const val CHUNK_CHARS = 2500

    fun build(text: String, today: LocalDate): String {
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR"))
        return """
Você extrai tarefas acionáveis de textos em português (anotações ou transcrições de reuniões/aulas).
Hoje é $weekday, $today. Converta datas relativas ("amanhã", "sexta", "semana que vem") para AAAA-MM-DD.
Responda SOMENTE com um array JSON, sem texto extra. Cada item:
{"titulo": string curta começando com verbo, "data": "AAAA-MM-DD" ou null, "hora": "HH:MM" ou null,
 "prioridade": "baixa"|"media"|"alta"|"critica" ou null, "duracao_min": número ou null,
 "subtarefas": [strings], "trecho": frase original}
Ignore conversa sem ação. Não invente datas. Se não houver tarefas, responda [].

Texto:
$text
""".trim()
    }

    /** Splits on paragraph/sentence boundaries so no sentence is cut in half. */
    fun chunks(text: String, max: Int = CHUNK_CHARS): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (piece in text.split(Regex("(?<=[.!?\\n])"))) {
            if (sb.length + piece.length > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            if (piece.length > max) piece.chunked(max).forEach { out += it } else sb.append(piece)
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** Parses model output defensively: code fences, chatter around the JSON, bad dates, wrong types. */
object AiResponseParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Returns null when the response contains no usable JSON array (caller falls back to rules). */
    fun parse(response: String, today: LocalDate): List<ExtractedTask>? {
        val start = response.indexOf('[')
        val end = response.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val array = runCatching { json.parseToJsonElement(response.substring(start, end + 1)) as? JsonArray }.getOrNull() ?: return null
        return array.mapNotNull { (it as? JsonObject)?.let { o -> toTask(o, today) } }
    }

    private fun toTask(o: JsonObject, today: LocalDate): ExtractedTask? {
        val title = o.str("titulo", "title")?.trim()?.trim('.')?.takeIf { it.length >= 2 } ?: return null
        val date = o.str("data", "date")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            // A model sometimes "hallucinates" years; keep only plausible deadlines.
            ?.takeIf { !it.isBefore(today.minusDays(1)) && it.isBefore(today.plusYears(2)) }
        val time = o.str("hora", "time")?.let { t ->
            Regex("^(\\d{1,2}):(\\d{2})").find(t)?.let { m -> runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull() }
        }
        val priority = when (o.str("prioridade", "priority")?.let { HeuristicTaskExtractor.normalize(it) }) {
            "baixa", "low" -> Priority.LOW
            "media", "medium" -> Priority.MEDIUM
            "alta", "high" -> Priority.HIGH
            "critica", "urgente", "critical" -> Priority.CRITICAL
            else -> null
        }
        val minutes = (o["duracao_min"] ?: o["duration"])?.let { (it as? JsonPrimitive)?.intOrNull ?: it.asString()?.filter(Char::isDigit)?.toIntOrNull() }
            ?.takeIf { it in 1..24 * 60 }
        val subtasks = ((o["subtarefas"] ?: o["subtasks"]) as? JsonArray)?.mapNotNull { it.asString()?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
        return ExtractedTask(
            title = title.replaceFirstChar { it.uppercase() }.take(200),
            dueDate = date, dueTime = time, priority = priority, estimatedMinutes = minutes,
            subtasks = subtasks.take(20), source = o.str("trecho", "source")?.take(500).orEmpty(),
        )
    }

    private fun JsonObject.str(vararg keys: String): String? = keys.firstNotNullOfOrNull { this[it]?.asString() }?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonElement.asString(): String? = when (this) { is JsonNull -> null; is JsonPrimitive -> contentOrNull; else -> null }
}
