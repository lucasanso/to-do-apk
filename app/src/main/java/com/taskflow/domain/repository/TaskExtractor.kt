package com.taskflow.domain.repository

import com.taskflow.domain.engine.ExtractionResult

/** Turns free text (notes, voice transcripts) into candidate tasks. Never throws; falls back to rules. */
interface TaskExtractor {
    suspend fun extract(text: String): ExtractionResult

    /** Whether on-device AI can be used right now (model present and supported by the phone). */
    suspend fun aiStatus(): AiStatus
}

enum class AiStatus(val label: String) {
    AVAILABLE("Gemini Nano pronto no aparelho"),
    DOWNLOADING("Baixando o modelo de IA do aparelho…"),
    DOWNLOADABLE("Modelo de IA disponível para download"),
    UNAVAILABLE("IA no aparelho indisponível — usando regras locais"),
}
