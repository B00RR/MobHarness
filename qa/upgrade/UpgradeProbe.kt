package com.example

import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.db.*
import com.example.data.prefs.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

// Compiled only for the disposable upgrade rehearsal, once against each APK's sources.
class UpgradeProbe {
    @Test fun preserveData() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = MobHarnDatabase.getInstance(ctx)
        val prefs = PreferencesRepository(ctx)
        val marker = "rehearsal-only"
        when (InstrumentationRegistry.getArguments().getString("phase")) {
            "seed" -> {
                assertEquals("1.2.0", BuildConfig.VERSION_NAME)
                val session = db.chatDao().insertSession(ChatSessionEntity(id = 7001, title = marker))
                db.chatDao().insertMessage(ChatMessageEntity(sessionId = session, role = "user", content = marker))
                db.skillDao().insertNewSkill(AgentSkillEntity(slug = marker, title = marker, description = "fixture", instructionsMd = "AMBRA"))
                val model = db.modelProfileDao().upsertModel(ModelProfileEntity(displayName = marker, filePath = "fixture/model.litertlm", fileSizeBytes = 0L))
                prefs.setActiveModelId(model)
                prefs.setActiveProject(marker)
            }
            "check" -> assertEquals("1.3.0", BuildConfig.VERSION_NAME)
            else -> error("Specify seed/check on an empty CI emulator")
        }
        assertEquals(marker, db.chatDao().getMessages(7001L).single().content)
        assertEquals("AMBRA", db.skillDao().getSkillBySlug(marker)?.instructionsMd)
        val saved = prefs.preferences.first()
        assertEquals(marker, saved.activeProjectName)
        assertEquals("fixture/model.litertlm", db.modelProfileDao().getModelById(saved.activeModelId)?.filePath)
    }
}
