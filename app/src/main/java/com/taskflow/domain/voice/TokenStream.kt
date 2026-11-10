package com.taskflow.domain.voice

/**
 * The sentence as normalized tokens that keep a link to the original words.
 *
 * Patterns run over [joined] (lowercase, no accents, number words turned into digits);
 * matched tokens are marked consumed, and the title is rebuilt from the original words
 * of the tokens nobody consumed. Consumed tokens become "#" in [joined] so later patterns
 * cannot match them twice.
 */
internal class TokenStream(text: String) {
    data class Tok(val norm: String, val from: Int, val to: Int)

    val words: List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val toks: List<Tok>
    private val consumed: BooleanArray

    init {
        val raw = mutableListOf<Tok>()
        words.forEachIndexed { i, w ->
            PtText.normWord(w).replace('-', ' ').replace(',', ' ').split(' ').filter { it.isNotEmpty() }
                .forEach { raw += Tok(it, i, i) }
        }
        toks = mergeNumbers(raw)
        consumed = BooleanArray(toks.size)
    }

    /** Joined text and the char offset where each token starts in it. */
    private fun build(): Pair<String, IntArray> {
        val sb = StringBuilder()
        val starts = IntArray(toks.size)
        toks.forEachIndexed { i, t ->
            if (i > 0) sb.append(' ')
            starts[i] = sb.length
            sb.append(if (consumed[i]) "#" else t.norm)
        }
        return sb.toString() to starts
    }

    val joined: String get() = build().first

    /** Finds the first match of [regex] among unconsumed tokens, consumes it and returns its groups. */
    fun take(regex: Regex): MatchResult? {
        val (text, starts) = build()
        val m = regex.find(text) ?: return null
        consume(m.range.first, m.range.last + 1, starts)
        return m
    }

    /** Like [take] but returns every non-overlapping match. */
    fun takeAll(regex: Regex): List<MatchResult> {
        val out = mutableListOf<MatchResult>()
        while (true) out += take(regex) ?: break
        return out
    }

    private fun consume(start: Int, end: Int, starts: IntArray) {
        toks.indices.forEach { i ->
            val s = starts[i]
            val e = s + (if (consumed[i]) 1 else toks[i].norm.length)
            if (s >= start && e <= end) consumed[i] = true
        }
    }

    /** Original words of tokens that were not consumed, in order. */
    fun remainingWords(): List<String> {
        val keep = BooleanArray(words.size)
        toks.forEachIndexed { i, t -> if (!consumed[i]) for (w in t.from..t.to) keep[w] = true }
        // A word survives only if none of its tokens were consumed (e.g. "segunda-feira").
        toks.forEachIndexed { i, t -> if (consumed[i]) for (w in t.from..t.to) keep[w] = false }
        return words.filterIndexed { i, _ -> keep[i] }
    }

    fun remainingNorm(): List<String> = toks.filterIndexed { i, _ -> !consumed[i] }.map { it.norm }

    companion object {
        private val unitWords = setOf("hora", "horas", "minuto", "minutos", "semana", "semanas", "dia", "dias", "mes", "meses")

        /** "vinte e cinco" → "25", "tres" → "3", "uma hora" → "1 hora". Bare "um/uma" stay (articles). */
        private fun mergeNumbers(raw: List<Tok>): List<Tok> {
            val out = mutableListOf<Tok>()
            var i = 0
            while (i < raw.size) {
                val t = raw[i]
                val v = PtText.simpleNumber(t.norm)
                if (v != null && PtText.isTens(t.norm) && i + 2 < raw.size && raw[i + 1].norm == "e" && PtText.isUnit(raw[i + 2].norm)) {
                    out += Tok((v + PtText.simpleNumber(raw[i + 2].norm)!!).toString(), t.from, raw[i + 2].to); i += 3; continue
                }
                val isArticle = t.norm == "um" || t.norm == "uma" || t.norm == "primeiro"
                val next = raw.getOrNull(i + 1)?.norm
                val prev = out.lastOrNull()?.norm
                val numeric = v != null && (!isArticle ||
                    (t.norm != "primeiro" && (next in unitWords || prev == "as" || prev == "a" || next == "e" || next == "da")) ||
                    (t.norm == "primeiro" && next == "de"))
                out += if (numeric) Tok(v.toString(), t.from, t.to) else t
                i++
            }
            return out
        }
    }
}
