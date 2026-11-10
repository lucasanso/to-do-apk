package com.taskflow.domain.voice

import java.text.Normalizer

/** Portuguese text helpers shared by the voice parser and the task matcher. */
internal object PtText {
    private val marks = Regex("\\p{M}")

    /** Lowercase, no accents, hyphens as spaces, punctuation stripped (keeps ':' and '/' inside numbers). */
    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "")

    fun normWord(w: String): String = norm(w)
        .replace('º', 'o').replace('ª', 'a')
        .trim { !it.isLetterOrDigit() && it != ':' && it != '/' }
        .let { if (it.endsWith(":") || it.endsWith("/")) it.dropLast(1) else it }

    private val units = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5,
        "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9,
    )
    private val teens = mapOf(
        "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14, "quatorze" to 14, "quinze" to 15,
        "dezesseis" to 16, "dezasseis" to 16, "dezessete" to 17, "dezassete" to 17, "dezoito" to 18,
        "dezenove" to 19, "dezanove" to 19,
    )
    private val tens = mapOf("vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50)

    /** Value of a single number word ("tres" → 3), or null. "primeiro" counts as 1 (dates). */
    fun simpleNumber(w: String): Int? = units[w] ?: teens[w] ?: tens[w] ?: if (w == "primeiro") 1 else null

    fun isTens(w: String) = w in tens
    fun isUnit(w: String) = w in units
}
