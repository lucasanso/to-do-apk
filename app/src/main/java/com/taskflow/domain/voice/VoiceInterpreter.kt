package com.taskflow.domain.voice

import com.taskflow.domain.model.Priority
import java.time.LocalDateTime

/** What the on-device model (Gemini Nano) extracted from a sentence. All fields are untrusted. */
data class ModelReading(
    val intent: String?,
    val title: String? = null,
    val whenText: String? = null,
    val durationMinutes: Int? = null,
    val priority: String? = null,
    val category: String? = null,
    /** 1-based index into the candidate list given to the model. */
    val target: Int? = null,
)

enum class InterpretationSource(val label: String) { RULES("Regras locais"), ON_DEVICE_AI("Gemini Nano (no aparelho)") }

sealed interface Interpretation {
    val source: InterpretationSource

    data class Create(val draft: TaskDraft, override val source: InterpretationSource) : Interpretation
    data class Complete(val query: String, val match: TaskMatch, override val source: InterpretationSource) : Interpretation
    data class Unknown(val text: String, override val source: InterpretationSource = InterpretationSource.RULES) : Interpretation
}

/**
 * Combines the deterministic parser with an optional model reading.
 *
 * Policy: dates, times and recurrence always come from [VoiceCommandParser] (the model only points
 * at the words that express them). The model can improve intent, title, category and which task is
 * meant, but every model output is validated against the sentence and the real task list; anything
 * that does not check out falls back to the rules.
 */
object VoiceInterpreter {

    fun interpret(
        text: String,
        now: LocalDateTime,
        categories: List<String>,
        candidates: List<CompletionCandidate>,
        model: ModelReading?,
    ): Interpretation {
        val rules = VoiceCommandParser.parse(text, now, categories)
        val intent = model?.intent?.lowercase()?.trim()
        if (model == null || intent !in setOf("create", "complete")) return fromRules(rules, candidates)

        return when (intent) {
            "complete" -> when (rules) {
                is VoiceIntent.Complete -> completeWithModel(rules.query, model, candidates)
                // The model says "done" but no completion wording was found: accept only if it points
                // at a real item that the sentence actually mentions.
                else -> {
                    val target = targetOf(model, candidates)
                    if (target != null && TaskMatcher.score(text, target.title) >= MODEL_TARGET_MIN)
                        Interpretation.Complete(text, TaskMatch.Unique(ScoredCandidate(target, TaskMatcher.score(text, target.title))), InterpretationSource.ON_DEVICE_AI)
                    else fromRules(rules, candidates)
                }
            }
            else -> when (rules) {
                // Explicit completion wording ("concluí", "marca como feito") wins over the model.
                is VoiceIntent.Complete -> fromRules(rules, candidates)
                is VoiceIntent.Create -> Interpretation.Create(refine(rules.draft, text, now, categories, model), InterpretationSource.ON_DEVICE_AI)
                is VoiceIntent.Unknown -> {
                    val title = model.title?.takeIf { titleIsGrounded(it, text) }
                        ?: return Interpretation.Unknown(text, InterpretationSource.ON_DEVICE_AI)
                    Interpretation.Create(refine(TaskDraft(title), text, now, categories, model), InterpretationSource.ON_DEVICE_AI)
                }
            }
        }
    }

    private const val MODEL_TARGET_MIN = 0.25

    private fun fromRules(rules: VoiceIntent, candidates: List<CompletionCandidate>): Interpretation = when (rules) {
        is VoiceIntent.Create -> Interpretation.Create(rules.draft, InterpretationSource.RULES)
        is VoiceIntent.Complete -> Interpretation.Complete(rules.query, TaskMatcher.match(rules.query, candidates), InterpretationSource.RULES)
        is VoiceIntent.Unknown -> Interpretation.Unknown(rules.text)
    }

    private fun completeWithModel(query: String, model: ModelReading, candidates: List<CompletionCandidate>): Interpretation {
        val ruleMatch = TaskMatcher.match(query, candidates)
        val target = targetOf(model, candidates)
        if (target != null) {
            val score = TaskMatcher.score(query, target.title)
            // Trust the model's pick when it shares words with the sentence, or when the rules are unsure.
            if (score >= MODEL_TARGET_MIN || ruleMatch !is TaskMatch.Unique)
                return Interpretation.Complete(query, TaskMatch.Unique(ScoredCandidate(target, score)), InterpretationSource.ON_DEVICE_AI)
        }
        return Interpretation.Complete(query, ruleMatch, InterpretationSource.RULES)
    }

    private fun targetOf(model: ModelReading, candidates: List<CompletionCandidate>): CompletionCandidate? =
        model.target?.let { candidates.getOrNull(it - 1) }

    private fun refine(base: TaskDraft, text: String, now: LocalDateTime, categories: List<String>, model: ModelReading): TaskDraft {
        var d = base
        model.title?.trim()?.takeIf { titleIsGrounded(it, text) }?.let { d = d.copy(title = it.replaceFirstChar { c -> c.uppercase() }) }
        if (d.dueDate == null && d.dueTime == null && !model.whenText.isNullOrBlank()) {
            val w = VoiceCommandParser.parseWhen(model.whenText, now)
            d = d.copy(dueDate = w.dueDate, dueTime = w.dueTime, recurrence = d.recurrence ?: w.recurrence,
                reminderMinutesBefore = d.reminderMinutesBefore ?: w.reminderMinutesBefore)
        }
        if (d.estimatedMinutes == null) d = d.copy(estimatedMinutes = model.durationMinutes?.takeIf { it in 1..24 * 60 })
        if (d.priority == null) d = d.copy(priority = when (model.priority?.lowercase()) {
            "low" -> Priority.LOW; "high" -> Priority.HIGH; "critical" -> Priority.CRITICAL; else -> null
        })
        if (d.categoryHint == null) d = d.copy(categoryHint = model.category?.let { c ->
            categories.firstOrNull { PtText.norm(it) == PtText.norm(c.trim()) }
        })
        return d
    }

    /** At least 60% of the model's title words (or their stems) must appear in what the user said. */
    internal fun titleIsGrounded(title: String, text: String): Boolean {
        if (title.isBlank() || title.length > 120) return false
        val t = TaskMatcher.tokens(title)
        if (t.isEmpty()) return false
        val said = TaskMatcher.tokens(text)
        val found = t.count { w -> said.any { TaskMatcher.similarity(w, it) >= 0.8 } }
        return found >= t.size * 0.6
    }
}
