package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.sandbox.CodeSandboxRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CodeSandboxRunnerTest {

    @Test
    fun splitSqlStatements_honorsQuotesWithSemicolons() {
        val sql = """
            INSERT INTO items VALUES (1, 'tea; coffee');
            SELECT * FROM items WHERE note = 'alpha; beta; gamma';
        """.trimIndent()

        val statements = CodeSandboxRunner.splitSqlStatements(sql)
        assertEquals(2, statements.size)
        assertTrue(statements[0].contains("'tea; coffee'"))
        assertTrue(statements[1].contains("'alpha; beta; gamma'"))
    }

    @Test
    fun splitSqlStatements_honorsEscapedQuotesDoubleQuotesAndBackticks() {
        val sql = """
            INSERT INTO items VALUES (1, 'it''s; tea; time');
            SELECT "col;1", `col;2`, [col;3] FROM tbl;
        """.trimIndent()

        val statements = CodeSandboxRunner.splitSqlStatements(sql)
        assertEquals(2, statements.size)
        assertTrue(statements[0].contains("'it''s; tea; time'"))
        assertTrue(statements[1].contains("\"col;1\""))
    }

    @Test
    fun splitSqlStatements_honorsCommentsWithSemicolons() {
        val sql = """
            -- line comment with a ; semicolon
            SELECT 1;
            /* block comment
               with ; semicolons */
            SELECT 2;
        """.trimIndent()

        val statements = CodeSandboxRunner.splitSqlStatements(sql)
        assertEquals(2, statements.size)
    }

    @Test
    fun splitSqlStatements_honorsTriggerBodiesWithMultipleStatements() {
        val sql = """
            CREATE TABLE logs (msg TEXT);
            CREATE TRIGGER after_insert_items AFTER INSERT ON items
            BEGIN
                INSERT INTO logs VALUES ('inserted: ' || NEW.name);
                UPDATE stats SET total = total + 1;
            END;
            SELECT * FROM logs;
        """.trimIndent()

        val statements = CodeSandboxRunner.splitSqlStatements(sql)
        assertEquals(3, statements.size)
        assertTrue(statements[1].startsWith("CREATE TRIGGER"))
        assertTrue(statements[1].contains("INSERT INTO logs"))
        assertTrue(statements[1].contains("UPDATE stats"))
        assertTrue(statements[1].endsWith("END"))
    }

    @Test
    fun splitSqlStatements_honorsCaseWhenInsideTrigger() {
        val sql = """
            CREATE TRIGGER tr AFTER INSERT ON items
            BEGIN
                UPDATE stats SET cat = CASE WHEN NEW.val > 10 THEN 'high' ELSE 'low' END;
                INSERT INTO logs VALUES ('done');
            END;
            SELECT 1;
        """.trimIndent()

        val statements = CodeSandboxRunner.splitSqlStatements(sql)
        assertEquals(2, statements.size)
        assertTrue(statements[0].contains("CASE WHEN"))
    }

    @Test
    fun runSql_insertsAndReadsBackValueContainingSemicolon() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runner = CodeSandboxRunner(context)

        val script = """
            CREATE TABLE beverages (id INTEGER PRIMARY KEY, name TEXT);
            INSERT INTO beverages (id, name) VALUES (1, 'tea; coffee');
            SELECT id, name FROM beverages WHERE id = 1;
        """.trimIndent()

        val result = runner.runSql(script)
        assertTrue("Execution should succeed: ${result.output}", result.success)
        assertTrue("Output should contain inserted quoted text with semicolon", result.output.contains("tea; coffee"))
        assertTrue("Output should report rows returned", result.output.contains("(1 righe restituite)"))
    }
}
