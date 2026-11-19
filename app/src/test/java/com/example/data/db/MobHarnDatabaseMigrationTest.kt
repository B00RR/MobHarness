package com.example.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobHarnDatabaseMigrationTest {
    @Test fun upgradePreservesSavedChat() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "upgrade-test.db"
        fun open() = Room.databaseBuilder(context, MobHarnDatabase::class.java, name)
            .addMigrations(MobHarnDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        context.deleteDatabase(name)
        val db = open()
        val id = try {
            val chat = db.chatDao().insertSession(ChatSessionEntity(title = "Before upgrade"))
            db.chatDao().insertMessage(ChatMessageEntity(sessionId = chat, role = "user", content = "Keep me"))
            chat
        } finally { db.close() }
        // The only schema change since the shipped v2 database is the new compound index.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP INDEX index_chat_messages_sessionId_timestamp_id")
            it.version = 2
        }
        val upgraded = open()
        try { assertEquals("Keep me", upgraded.chatDao().getMessages(id).single().content) } finally { upgraded.close() }
    }
}
