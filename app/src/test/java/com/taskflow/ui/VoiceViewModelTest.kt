package com.taskflow.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskflow.FakeCategories
import com.taskflow.FakeHabitRepository
import com.taskflow.FakeHistory
import com.taskflow.FakePlan
import com.taskflow.FakeReminders
import com.taskflow.FakeTaskRepository
import com.taskflow.NOW
import com.taskflow.TODAY
import com.taskflow.core.FixedTimeProvider
import com.taskflow.data.settings.SettingsRepository
import com.taskflow.domain.model.Category
import com.taskflow.domain.repository.AiDownload
import com.taskflow.domain.repository.OnDeviceAi
import com.taskflow.domain.repository.OnDeviceAiStatus
import com.taskflow.domain.usecase.AnswerVoiceQueryUseCase
import com.taskflow.domain.usecase.DeleteHabitUseCase
import com.taskflow.domain.usecase.DeleteTaskUseCase
import com.taskflow.domain.usecase.ExecuteVoiceCommandUseCase
import com.taskflow.domain.usecase.InterpretVoiceCommandUseCase
import com.taskflow.domain.usecase.QuickAddTaskUseCase
import com.taskflow.domain.usecase.RestoreDeletedTaskUseCase
import com.taskflow.domain.usecase.SaveHabitUseCase
import com.taskflow.domain.usecase.SaveTaskUseCase
import com.taskflow.domain.usecase.SetTaskDoneUseCase
import com.taskflow.domain.usecase.TaskMutationSupport
import com.taskflow.domain.usecase.ToggleHabitUseCase
import com.taskflow.domain.model.TaskStatus
import com.taskflow.domain.voice.CompletionCandidate
import com.taskflow.domain.voice.ModelReading
import com.taskflow.presentation.voice.VoiceUi
import com.taskflow.presentation.voice.VoiceViewModel
import com.taskflow.task
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The voice ViewModel end to end with in-memory data: listing answers, spoken replies, checking items off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceViewModelTest {
    private object NoAi : OnDeviceAi {
        override suspend fun status() = OnDeviceAiStatus.UNAVAILABLE
        override suspend fun modelName(): String? = null
        override suspend fun read(text: String, today: LocalDate, categories: List<String>, candidates: List<CompletionCandidate>): ModelReading? = null
        override fun download() = emptyFlow<AiDownload>()
    }

    private val repo = FakeTaskRepository()
    private val habits = FakeHabitRepository()
    private val time = FixedTimeProvider(NOW)
    private val cats = FakeCategories(listOf(Category(1, "Trabalho", 0, "work")))
    private val reminders = FakeReminders()
    private val support = TaskMutationSupport(repo, FakeHistory(), reminders, time)
    private val plan = FakePlan()
    private val save = SaveTaskUseCase(support)
    private val setDone = SetTaskDoneUseCase(support)
    private val vm = VoiceViewModel(
        InterpretVoiceCommandUseCase(repo, habits, cats, NoAi, time),
        ExecuteVoiceCommandUseCase(save, setDone, DeleteTaskUseCase(support, plan), RestoreDeletedTaskUseCase(support, plan),
            SaveHabitUseCase(habits, reminders, time), DeleteHabitUseCase(habits, reminders), repo, habits, cats, time),
        QuickAddTaskUseCase(save, cats, time),
        AnswerVoiceQueryUseCase(repo, habits, cats, time),
        setDone, ToggleHabitUseCase(habits, time),
        repo, cats, SettingsRepository(ApplicationProvider.getApplicationContext()), time,
    )

    private fun await(cond: () -> Boolean) {
        repeat(200) { shadowOf(Looper.getMainLooper()).idle(); if (cond()) return; Thread.sleep(10) }
        throw AssertionError("condition not met; state=${vm.ui.value}")
    }

    @Test fun `spoken question gets a listing and a spoken reply, a typed one stays silent`() {
        runBlocking { repo.upsert(task(0, "Pagar fatura", due = TODAY)) }
        vm.submit("o que eu tenho hoje?", fromVoice = true)
        await { vm.ui.value is VoiceUi.Listing }
        val listing = (vm.ui.value as VoiceUi.Listing).listing
        assertEquals("Hoje você tem 1 tarefa: Pagar fatura.", listing.spoken)
        await { vm.reply.value != null }
        assertEquals(listing.spoken, vm.reply.value!!.text)

        val before = vm.reply.value
        vm.submit("o que tenho amanhã?")   // typed
        await { (vm.ui.value as? VoiceUi.Listing)?.listing?.query?.label == "Amanhã" }
        assertEquals(before, vm.reply.value)
    }

    @Test fun `checking an item in the listing completes it and refreshes`() {
        val id = runBlocking { repo.upsert(task(0, "Pagar fatura", due = TODAY)) }
        vm.submit("tarefas de hoje")
        await { vm.ui.value is VoiceUi.Listing }
        val item = (vm.ui.value as VoiceUi.Listing).listing.sections.single().items.single()
        vm.toggleListed(item)
        await { runBlocking { repo.get(id)!!.status } == TaskStatus.DONE }
        await { (vm.ui.value as VoiceUi.Listing).listing.total == 0 }
        assertEquals("Hoje você não tem nada pendente.", (vm.ui.value as VoiceUi.Listing).listing.spoken)
    }

    @Test fun `routes for opening the full list`() {
        runBlocking { repo.upsert(task(0, "X", due = TODAY.minusDays(2))) }
        vm.submit("o que está atrasado")
        await { vm.ui.value is VoiceUi.Listing }
        assertEquals("tasks?filter=OVERDUE&category=-1&search=false", vm.routeFor((vm.ui.value as VoiceUi.Listing).listing))
        vm.submit("quais hábitos faltam hoje")
        await { (vm.ui.value as? VoiceUi.Listing)?.listing?.habitsOnly == true }
        assertEquals("habits", vm.routeFor((vm.ui.value as VoiceUi.Listing).listing))
        assertNull(vm.routeFor((vm.ui.value as VoiceUi.Listing).listing.copy(habitsOnly = false, smartFilter = null)))
        assertTrue(true)
    }
}
