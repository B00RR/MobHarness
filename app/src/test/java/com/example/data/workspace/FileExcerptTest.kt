package com.example.data.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileExcerptTest {
    @Test fun hugeLineIsMarkedPartialAndCannotLeakLaterRows() = runBlocking {
        val ws = WorkspaceManager(ApplicationProvider.getApplicationContext<Context>())
        ws.writeFile("fixture", "lines.txt", "x".repeat(100_000) + "\nLAST_ROW").getOrThrow()
        val out = ws.readFileLines("fixture", "lines.txt", 1, 2).getOrThrow()
        assertTrue(out.length < 75_000)
        assertTrue(out.contains("parziale", ignoreCase = true))
        assertFalse(out.contains("LAST_ROW"))
        val beyond = ws.readFileLines("fixture", "lines.txt", 2, 2).getOrThrow()
        assertFalse(beyond.contains("LAST_ROW"))
        assertTrue(beyond.contains("parziale", ignoreCase = true))
    }

    @Test fun limitsSourceBytesAndNumberOfReturnedLines() {
        val giant = ByteArray(110_000) { 65 }.inputStream()
        readExcerpt(giant, 110_000, 100, 100, "test")
        org.junit.Assert.assertEquals(65_536, 110_000 - giant.available())
        val many = "abc\n".repeat(30_000).toByteArray()
        val input = many.inputStream()
        val excerpt = readExcerpt(input, many.size.toLong(), 1, Int.MAX_VALUE, "test")
        org.junit.Assert.assertEquals(200, excerpt.lineSequence().count { " | " in it })
        assertTrue(many.size - input.available() <= 4096)
    }

    @Test fun rangePastEofNeverReturnsAnotherLine() = runBlocking {
        val ws = WorkspaceManager(ApplicationProvider.getApplicationContext<Context>())
        ws.writeFile("fixture", "tiny.txt", "prima\r\nseconda").getOrThrow()
        val selected = ws.readFileLines("fixture", "tiny.txt", 2, 2).getOrThrow()
        assertTrue(selected.contains("2 | seconda"))
        assertFalse(selected.contains("prima"))
        assertFalse(ws.readFileLines("fixture", "tiny.txt", 7, 7).getOrThrow().contains("seconda"))
    }
}
