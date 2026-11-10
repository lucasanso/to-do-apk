package com.taskflow.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.taskflow.presentation.voice.VoiceCaptureActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "Compartilhar → TaskFlow" from any app: the shared sentence is interpreted and applied. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceEntryTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun sharedTextCreatesTask() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), VoiceCaptureActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "me lembra de pagar o boleto amanhã às 10h")
        ActivityScenario.launch<VoiceCaptureActivity>(intent).use {
            rule.waitUntil(8_000) { rule.onAllNodesWithText("Tarefa criada").fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(2_000) { rule.onAllNodesWithText("Pagar o boleto").fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(2_000) { rule.onAllNodesWithText("Amanhã 10:00", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
