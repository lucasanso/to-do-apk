package com.taskflow.presentation.voice

import com.taskflow.data.settings.AppSettings

/** The reply voice chosen in Settings. Every field null = the phone's defaults. */
data class ReplyVoice(
    val engine: String? = null,
    val voice: String? = null,
    val rate: Float? = null,
    val pitch: Float? = null,
)

fun AppSettings.replyVoice() = ReplyVoice(ttsEngine, ttsVoice, ttsRate, ttsPitch)

/** A text-to-speech voice as reported by the engine (plain data, so labeling is unit-testable). */
data class VoiceInfo(
    val name: String,
    val language: String,
    val country: String,
    val quality: Int = 300,
    val networkRequired: Boolean = false,
    val notInstalled: Boolean = false,
    val features: Set<String> = emptySet(),
)

data class EngineOption(val packageName: String, val label: String)

/** A voice ready to show: friendly label, technical name kept for saving. */
data class VoiceOption(
    val name: String,
    val label: String,
    val detail: String,
    val region: String,
    val usable: Boolean,
    val online: Boolean,
)

/**
 * Turns engine voice lists into something a person can choose from.
 * Engines name voices like "pt-br-x-afs-local" (Google) or "pt-BR-SMTf00" (Samsung); here they become
 * "Voz 1", "Feminina 1"… with tags for offline / needs download / online.
 * Only Portuguese voices are listed (Brazil first). Online voices go last: they send the text to the provider.
 */
object VoiceLabels {
    private val samsungGender = Regex("""SMT([fm])\d""", RegexOption.IGNORE_CASE)

    fun isPortuguese(v: VoiceInfo) = v.language.lowercase() in setOf("pt", "por")

    fun options(voices: List<VoiceInfo>): List<VoiceOption> {
        // Google lists its default as a voice named "pt-BR-language"; the screen's "Padrão do mecanismo" already covers it.
        val pt = voices.filter(::isPortuguese).filterNot { it.name.endsWith("-language") }
        val ordered = pt.sortedWith(
            compareBy<VoiceInfo>(
                { if (it.country.equals("BR", true) || it.country.equals("BRA", true)) 0 else 1 },
                { it.networkRequired },
                { it.notInstalled },
                { base(it.name) },
                { it.name },
            )
        )
        // Same voice offered offline and online (Google "-local"/"-network") gets the same number.
        val numbers = mutableMapOf<String, Int>()
        val counters = mutableMapOf<String, Int>()
        return ordered.map { v ->
            val region = if (v.country.equals("BR", true) || v.country.equals("BRA", true)) "Português (Brasil)" else "Português (Portugal)"
            val gender = gender(v)
            val key = region + "|" + (gender ?: "") + "|" + base(v.name)
            val n = numbers.getOrPut(key) { (counters[region + (gender ?: "")] ?: 0).plus(1).also { counters[region + (gender ?: "")] = it } }
            val label = when {
                gender != null -> "${gender.replaceFirstChar { it.uppercase() }} $n"
                else -> "Voz $n"
            }
            val tags = buildList {
                when {
                    v.notInstalled -> add("precisa baixar")
                    v.networkRequired -> add("online · envia o texto ao provedor")
                    else -> add("no aparelho")
                }
                if (v.quality >= 400 && !v.notInstalled) add("alta qualidade")
            }
            VoiceOption(v.name, label, tags.joinToString(" · "), region, usable = !v.notInstalled, online = v.networkRequired)
        }
    }

    fun gender(v: VoiceInfo): String? {
        samsungGender.find(v.name)?.let { return if (it.groupValues[1].lowercase() == "f") "feminina" else "masculina" }
        val all = (v.features + v.name.lowercase()).joinToString(" ").lowercase()
        return when {
            "female" in all -> "feminina"
            Regex("""\bmale\b""").containsMatchIn(all) || all.contains("-male") -> "masculina"
            else -> null
        }
    }

    private fun base(name: String) = name.removeSuffix("-local").removeSuffix("-network")
}
