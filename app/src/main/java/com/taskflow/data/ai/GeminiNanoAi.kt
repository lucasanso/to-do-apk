package com.taskflow.data.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.taskflow.domain.repository.AiDownload
import com.taskflow.domain.repository.OnDeviceAi
import com.taskflow.domain.repository.OnDeviceAiStatus
import com.taskflow.domain.voice.CompletionCandidate
import com.taskflow.domain.voice.ModelReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini Nano through ML Kit's Prompt API. The model lives in the system AICore service: inference and
 * model downloads happen on the device, outside this app (which has no INTERNET permission).
 * Every call is guarded: on devices without AICore this simply reports UNAVAILABLE.
 */
@Singleton
class GeminiNanoAi @Inject constructor() : OnDeviceAi {

    private val client: GenerativeModel? by lazy { runCatching { Generation.getClient() }.getOrNull() }
    private var bytesToDownload = 0L

    override suspend fun status(): OnDeviceAiStatus = guard(OnDeviceAiStatus.UNAVAILABLE, 3_000) {
        when (client?.checkStatus()) {
            FeatureStatus.AVAILABLE -> OnDeviceAiStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> OnDeviceAiStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> OnDeviceAiStatus.DOWNLOADING
            else -> OnDeviceAiStatus.UNAVAILABLE
        }
    }

    override suspend fun modelName(): String? = guard(null, 3_000) { client?.getBaseModelName() }

    override suspend fun read(text: String, today: LocalDate, categories: List<String>, candidates: List<CompletionCandidate>): ModelReading? =
        guard(null, 8_000) {
            val model = client ?: return@guard null
            if (model.checkStatus() != FeatureStatus.AVAILABLE) return@guard null
            val prompt = TextPart(NanoPrompt.user(text, today, categories, candidates))
            val request = if (model.isSystemPromptAvailable()) {
                generateContentRequest(SystemInstruction(NanoPrompt.SYSTEM), prompt) { temperature = 0f; topK = 1; maxOutputTokens = 200 }
            } else {
                generateContentRequest(TextPart(NanoPrompt.SYSTEM + "\n\n" + prompt.textString)) { temperature = 0f; topK = 1; maxOutputTokens = 200 }
            }
            val answer = model.generateContent(request).candidates.firstOrNull()?.text ?: return@guard null
            NanoPrompt.parse(answer)
        }

    override fun download(): Flow<AiDownload> {
        val model = client ?: return flow { emit(AiDownload.Failed("Gemini Nano não está disponível neste aparelho")) }
        return model.download().map { s ->
            when (s) {
                is DownloadStatus.DownloadStarted -> { bytesToDownload = s.bytesToDownload; AiDownload.Progress(0f) }
                is DownloadStatus.DownloadProgress -> AiDownload.Progress(
                    if (bytesToDownload > 0) (s.totalBytesDownloaded.toFloat() / bytesToDownload).coerceIn(0f, 1f) else null)
                is DownloadStatus.DownloadCompleted -> AiDownload.Done
                is DownloadStatus.DownloadFailed -> AiDownload.Failed(s.e.message ?: "Falha no download")
            }
        }.catch { e -> if (e is CancellationException) throw e; emit(AiDownload.Failed(e.message ?: "Falha no download")) }
    }

    private suspend fun <T> guard(fallback: T, timeoutMs: Long, block: suspend () -> T): T = try {
        withTimeoutOrNull(timeoutMs) { block() } ?: fallback
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // AICore missing, service not bound, model busy, quota… the rule-based parser takes over.
        fallback
    }
}

/** Prompt and answer format for Gemini Nano. Pure, so it is unit-tested. */
object NanoPrompt {
    const val SYSTEM = """Você interpreta comandos de voz em português do Brasil para um app de tarefas.
Responda SOMENTE com um objeto JSON, sem texto antes ou depois, neste formato:
{"intent":"create|complete|unknown","title":null,"when":null,"duration_minutes":null,"priority":null,"category":null,"target":null}
Regras:
- intent "complete" quando a pessoa diz que já fez, terminou ou concluiu algo, ou pede para marcar algo como feito.
- intent "create" quando a pessoa quer registrar, anotar ou ser lembrada de algo. Caso contrário "unknown".
- title: só para create. Título curto da atividade, sem data, hora, duração ou prioridade.
- when: copie exatamente o trecho da frase que fala de data ou horário (ex.: "amanhã às 3 da tarde"), ou null.
- duration_minutes: duração em minutos, se a frase disser quanto tempo leva; senão null.
- priority: "low", "medium", "high" ou "critical" se a frase indicar urgência ou importância; senão null.
- category: uma das categorias listadas, só se for evidente; senão null.
- target: só para complete. O número do item da lista que a pessoa concluiu, ou null se nenhum corresponder.
Nunca invente itens que não estão na lista."""

    fun user(text: String, today: LocalDate, categories: List<String>, candidates: List<CompletionCandidate>): String = buildString {
        append("Hoje: ").append(today).append('\n')
        append("Categorias: ").append(categories.joinToString(", ").ifEmpty { "(nenhuma)" }).append('\n')
        append("Itens abertos:\n")
        if (candidates.isEmpty()) append("(nenhum)\n")
        candidates.take(MAX_ITEMS).forEachIndexed { i, c ->
            append(i + 1).append(". ").append(c.title.take(80)).append(if (c.kind == CompletionCandidate.Kind.HABIT) " (hábito)" else "").append('\n')
        }
        append("Frase: \"").append(text.replace("\"", "'").take(400)).append("\"")
    }

    const val MAX_ITEMS = 40

    @Serializable
    private data class Answer(
        val intent: String? = null,
        val title: String? = null,
        @SerialName("when") val whenText: String? = null,
        @SerialName("duration_minutes") val durationMinutes: Int? = null,
        val priority: String? = null,
        val category: String? = null,
        val target: Int? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Extracts the first JSON object from the answer (models sometimes wrap it in ``` fences). */
    fun parse(answer: String): ModelReading? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val a = runCatching { json.decodeFromString(Answer.serializer(), answer.substring(start, end + 1)) }.getOrNull() ?: return null
        fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        return ModelReading(a.intent.clean(), a.title.clean(), a.whenText.clean(), a.durationMinutes, a.priority.clean(), a.category.clean(), a.target)
    }
}
