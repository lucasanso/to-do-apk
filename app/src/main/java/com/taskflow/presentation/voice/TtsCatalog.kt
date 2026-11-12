package com.taskflow.presentation.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Reads the installed text-to-speech engines and the voices of one engine. */
class TtsCatalog(private val context: Context) {

    data class Snapshot(
        val engines: List<EngineOption>,
        val defaultEngine: String?,
        /** Engine whose voices are listed (the requested one, or the default when it is gone). */
        val engine: String?,
        val voices: List<VoiceInfo>,
        val ok: Boolean,
    )

    suspend fun load(engine: String?, timeoutMs: Long = 10_000): Snapshot = withContext(Dispatchers.Main) {
        var tts: TextToSpeech? = null
        try {
            val status = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val listener = TextToSpeech.OnInitListener { s -> if (cont.isActive) cont.resume(s) }
                    tts = if (engine != null) TextToSpeech(context.applicationContext, listener, engine)
                    else TextToSpeech(context.applicationContext, listener)
                }
            }
            val t = tts
            val engines = runCatching { t?.engines.orEmpty().map { EngineOption(it.name, it.label) } }.getOrDefault(emptyList())
            val default = runCatching { t?.defaultEngine }.getOrNull()
            if (t == null || status != TextToSpeech.SUCCESS) return@withContext Snapshot(engines, default, engine, emptyList(), false)
            val voices = runCatching {
                t.voices.orEmpty().map { v ->
                    VoiceInfo(
                        name = v.name,
                        language = v.locale.language,
                        country = v.locale.country,
                        quality = v.quality,
                        networkRequired = v.isNetworkConnectionRequired,
                        notInstalled = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in v.features.orEmpty(),
                        features = v.features.orEmpty(),
                    )
                }
            }.getOrDefault(emptyList())
            val inUse = engine?.takeIf { e -> engines.isEmpty() || engines.any { it.packageName == e } } ?: default
            Snapshot(engines, default, inUse, voices, true)
        } finally {
            runCatching { tts?.shutdown() }
        }
    }
}
