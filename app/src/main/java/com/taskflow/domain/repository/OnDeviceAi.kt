package com.taskflow.domain.repository

import com.taskflow.domain.voice.CompletionCandidate
import com.taskflow.domain.voice.ModelReading
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

enum class OnDeviceAiStatus(val label: String) {
    AVAILABLE("Ativo"),
    DOWNLOADABLE("Disponível para baixar"),
    DOWNLOADING("Baixando…"),
    UNAVAILABLE("Não suportado neste aparelho"),
}

sealed interface AiDownload {
    data class Progress(val fraction: Float?) : AiDownload
    data object Done : AiDownload
    data class Failed(val message: String) : AiDownload
}

/** Port to the device's on-device language model (Gemini Nano through AICore on the Galaxy S26). */
interface OnDeviceAi {
    suspend fun status(): OnDeviceAiStatus
    suspend fun modelName(): String?

    /** Returns null when the model is unavailable, times out or answers something unusable. */
    suspend fun read(text: String, today: LocalDate, categories: List<String>, candidates: List<CompletionCandidate>): ModelReading?

    fun download(): Flow<AiDownload>
}
