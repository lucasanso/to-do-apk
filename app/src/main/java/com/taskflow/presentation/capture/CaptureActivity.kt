package com.taskflow.presentation.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.MainActivity
import com.taskflow.data.settings.AppSettings
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.presentation.theme.TaskFlowTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Entry point for text coming from other apps:
 *  - "Compartilhar" from Samsung Gravador de Voz (transcript as text or .txt), Samsung Notes, WhatsApp, e-mail…
 *  - "Selecionar texto → TaskFlow" (ACTION_PROCESS_TEXT) in any app
 *  - the "Várias tarefas" button inside TaskFlow's own quick add
 */
@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TEXT = "capture_text"
        fun intent(context: Context, text: String = "") = Intent(context, CaptureActivity::class.java).putExtra(EXTRA_TEXT, text)
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }

    @Inject lateinit var settings: SettingsRepository
    private val vm: CaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) lifecycleScope.launch {
            val (text, label) = readIncoming(intent)
            vm.start(text, label)
        }
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle(AppSettings())
            TaskFlowTheme(s.themeMode, s.dynamicColor) {
                CaptureScreen(
                    vm = vm,
                    onClose = { finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    },
                )
            }
        }
    }

    /** Returns the text plus a human label of where it came from (kept in the task description). */
    private suspend fun readIncoming(intent: Intent?): Pair<String, String?> {
        intent ?: return "" to null
        val referrerApp = referrer?.host?.let(::appLabel)
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty() to (referrerApp ?: "Texto selecionado")
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val uri = streamUri(intent)
                val fromFile = if (uri != null) readText(uri) else null
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                val body = listOfNotNull(fromFile, text).filter { it.isNotBlank() }.distinct().joinToString("\n\n")
                val label = listOfNotNull(referrerApp ?: "Compartilhado", subject ?: uri?.let(::fileName)).joinToString(" · ")
                body to label
            }
            else -> intent.getStringExtra(EXTRA_TEXT).orEmpty() to null
        }
    }

    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)

    private suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(MAX_FILE_BYTES)
                String(bytes, Charsets.UTF_8).removePrefix("﻿")
            }
        }.getOrNull()?.takeIf { text -> text.isNotBlank() && text.count { it == '\u0000' } == 0 } // skip binaries
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun fileName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun appLabel(pkg: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}
