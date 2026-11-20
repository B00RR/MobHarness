package com.example.data.db

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShippedDatabaseUpgradeTest {
    @Test fun productionBuilderOpensTheShippedSchemaWithoutLosingUserData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("shipped-1.2.0-fixture.db")
        check(!file.exists())
        file.parentFile!!.mkdirs()
        // Frozen from the exported v1.2.0 schema; contains only synthetic records.
        GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/upgrade/mobharn-v120.db.gz"))).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val room = MobHarnDatabase.open(context, file.name)
        try {
            val db = room.openHelper.writableDatabase
            fun scalar(sql: String) = db.query(sql).use { cursor ->
                check(cursor.moveToFirst()); cursor.getString(0)
            }
            assertEquals("ok", scalar("PRAGMA integrity_check"))
            assertEquals("Memoria sintetica di collaudo", scalar("SELECT content FROM chat_messages WHERE sessionId=1"))
            assertEquals("Rispondi Ambra.", scalar("SELECT instructionsMd FROM agent_skills WHERE enabled=1"))
            assertEquals("/test/modello.litertlm", scalar("SELECT filePath FROM model_profiles WHERE id=1"))
            assertEquals("testo vecchio", scalar("SELECT previousContent FROM workspace_snapshots WHERE id=1"))
        } finally { room.close() }
    }
}
