package com.taskflow.domain.voice

import kotlin.math.max
import kotlin.math.min

/**
 * Finds which task/habit a spoken phrase refers to ("já paguei a fatura" → "Pagar fatura do cartão").
 *
 * Token-level fuzzy matching tolerant to verb inflection (shared stem: paguei/pagar, estudei/estudar),
 * accents and small recognition errors. Each query word takes its best match among the title words;
 * the score mixes how much of the query was found with how much of the title was covered.
 */
object TaskMatcher {
    const val UNIQUE_MIN = 0.55
    const val AMBIGUOUS_MIN = 0.35
    const val MARGIN = 0.12

    private val STOP = setOf(
        "a", "o", "as", "os", "de", "do", "da", "dos", "das", "e", "em", "no", "na", "nos", "nas", "um", "uma", "uns", "umas",
        "para", "pra", "pro", "com", "que", "ja", "eu", "meu", "minha", "meus", "minhas", "tarefa", "habito", "atividade",
        "hoje", "ontem", "agora", "isso", "essa", "esse", "esta", "este", "aquela", "aquele", "como", "ao", "aos", "pelo",
        "pela", "por", "se", "lhe", "me", "te", "ai", "la", "mais", "tudo",
    )

    fun tokens(s: String): List<String> =
        PtText.norm(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in STOP }

    fun score(query: String, title: String): Double {
        val q = tokens(query)
        val t = tokens(title)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        val usedTitle = BooleanArray(t.size)
        var sum = 0.0
        for (qt in q) {
            var best = 0.0; var bestIdx = -1
            t.forEachIndexed { i, tt -> val sim = similarity(qt, tt); if (sim > best) { best = sim; bestIdx = i } }
            sum += best
            if (best >= 0.6 && bestIdx >= 0) usedTitle[bestIdx] = true
        }
        val queryFound = sum / q.size
        val titleCovered = usedTitle.count { it }.toDouble() / t.size
        return 0.65 * queryFound + 0.35 * titleCovered
    }

    fun rank(query: String, candidates: List<CompletionCandidate>): List<ScoredCandidate> =
        candidates.map { c -> ScoredCandidate(c, score(query, c.title) + if (c.relevantToday) 0.04 else 0.0) }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }

    fun match(query: String, candidates: List<CompletionCandidate>): TaskMatch {
        val ranked = rank(query, candidates)
        val best = ranked.firstOrNull() ?: return TaskMatch.NotFound
        val second = ranked.getOrNull(1)
        return when {
            best.score >= UNIQUE_MIN && (second == null || best.score - second.score >= MARGIN) -> TaskMatch.Unique(best)
            best.score >= AMBIGUOUS_MIN -> TaskMatch.Ambiguous(ranked.filter { it.score >= AMBIGUOUS_MIN }.take(3))
            else -> TaskMatch.NotFound
        }
    }

    /** 1.0 equal · ~0.85 same stem (≥ 3 shared leading letters covering most of the shorter word) · edit-distance ratio for typos. */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val cp = a.zip(b).takeWhile { (x, y) -> x == y }.count()
        val shorter = min(a.length, b.length)
        val stem = if (cp >= 3 && cp >= shorter * 0.6) 0.85 else 0.0
        val lev = if (shorter >= 4) 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length) else 0.0
        val typo = if (lev >= 0.75) lev * 0.9 else 0.0
        return max(stem, typo)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
