package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.LocalLearning
import com.example.agent.SystemPromptAssembler
import com.example.di.MobHarnAppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LessonStorageTest {
    @Test fun reviewedOnlyAndDisabledLessonsCannotReturn() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = MobHarnAppContainer.getInstance(ctx).repository
        val draft = requireNotNull(LocalLearning.suggest("Correzione: calendario scadenze | Verifica il mese.", emptyList()))
        val assembler = SystemPromptAssembler(repo)
        suspend fun recalled() = assembler.assemble("starter_web", emptySet(), userPrompt = "Mostrami il calendario scadenze").contains(draft.instructionsMd)
        assertFalse(recalled())
        assertTrue(repo.acceptLesson(draft, null))
        assertTrue(recalled())
        assertFalse(repo.acceptLesson(draft, null))
        val previous = requireNotNull(repo.inspectSkill(draft.slug))
        repo.setSkillEnabled(previous.id, false)
        assertNull(repo.loadSkill(draft.slug))
        assertFalse(repo.acceptLesson(draft.copy(instructionsMd = "Nuova"), previous))
        assertFalse(recalled())
        repo.deleteSkill(previous.id)
        assertFalse(java.io.File(ctx.filesDir, "mobharn_vault/skills/${draft.slug}.md").exists())
    }
}
