package com.taskflow.data.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.taskflow.core.TimeProvider
import com.taskflow.domain.engine.AiResponseParser
import com.taskflow.domain.engine.ExtractedTask
import com.taskflow.domain.engine.ExtractionEngine
import com.taskflow.domain.engine.ExtractionPrompt
import com.taskflow.domain.engine.ExtractionResult
import com.taskflow.domain.engine.HeuristicTaskExtractor
import com.taskflow.domain.repository.AiStatus
import com.taskflow.domain.repository.TaskExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses Gemini Nano through ML Kit GenAI (AICore) — the same on-device model family behind Galaxy AI
 * on recent Galaxy phones. Nothing leaves the phone and there is no API cost.
 * Phones without AICore support (or when anything fails) get the rule-based extractor instead.
 */
@Singleton
class OnDeviceTaskExtractor @Inject constructor(private val time: TimeProvider) : TaskExtractor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var model: GenerativeModel? = null
    @Volatile private var downloading = false

    private fun client(): GenerativeModel? = model ?: runCatching { Generation.getClient() }
        .onFailure { Log.w(TAG, "GenAI client unavailable", it) }.getOrNull()?.also { model = it }

    override suspend fun aiStatus(): AiStatus {
        if (downloading) return AiStatus.DOWNLOADING
        val m = client() ?: return AiStatus.UNAVAILABLE
        return try {
            when (m.checkStatus()) {
                FeatureStatus.AVAILABLE -> AiStatus.AVAILABLE
                FeatureStatus.DOWNLOADING -> AiStatus.DOWNLOADING
                FeatureStatus.DOWNLOADABLE -> { startDownload(m); AiStatus.DOWNLOADING }
                else -> AiStatus.UNAVAILABLE
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "checkStatus failed", e); AiStatus.UNAVAILABLE
        }
    }

    /** Model download is handled by AICore; we only kick it off, never block the user on it. */
    private fun startDownload(m: GenerativeModel) {
        if (downloading) return
        downloading = true
        scope.launch {
            try {
                m.download().collect { s -> if (s is DownloadStatus.DownloadCompleted || s is DownloadStatus.DownloadFailed) downloading = false }
            } catch (e: Exception) { Log.w(TAG, "download failed", e) } finally { downloading = false }
        }
    }

    override suspend fun extract(text: String): ExtractionResult {
        val today = time.today()
        val status = aiStatus()
        if (status == AiStatus.AVAILABLE) {
            val m = client()
            if (m != null) {
                try {
                    val all = mutableListOf<ExtractedTask>()
                    mutex.withLock {
                        for (chunk in ExtractionPrompt.chunks(text)) {
                            val response = withTimeout(60_000) { m.generateContent(ExtractionPrompt.build(chunk, today)) }
                            val out = response.candidates.firstOrNull()?.text.orEmpty()
                            all += AiResponseParser.parse(out, today) ?: throw IllegalStateException("Resposta sem JSON")
                        }
                    }
                    return ExtractionResult(all.distinctBy { it.title.lowercase() }, ExtractionEngine.ON_DEVICE_AI)
                } catch (e: CancellationException) {
                    if (e is kotlinx.coroutines.TimeoutCancellationException) Log.w(TAG, "AI timed out") else throw e
                } catch (e: Exception) {
                    Log.w(TAG, "AI extraction failed, falling back to rules", e)
                }
                return ExtractionResult(HeuristicTaskExtractor.extract(text, today), ExtractionEngine.RULES,
                    "A IA do aparelho não respondeu bem; usei as regras locais.")
            }
        }
        val note = if (status == AiStatus.DOWNLOADING) "O modelo de IA está sendo baixado; enquanto isso, usei as regras locais." else null
        return ExtractionResult(HeuristicTaskExtractor.extract(text, today), ExtractionEngine.RULES, note)
    }

    private companion object { const val TAG = "TaskExtractor" }
}
