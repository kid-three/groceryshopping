package app.receiptscanner

import app.receiptscanner.parse.ClaudeParser
import app.receiptscanner.parse.ReceiptJson
import app.receiptscanner.parse.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Calls the real Claude API on every testdata receipt (costs a fraction of a cent).
 * Skipped unless ANTHROPIC_API_KEY is set:
 *   ANTHROPIC_API_KEY=sk-ant-... ./gradlew :app:testDebugUnitTest --tests '*ClaudeParserLiveTest*'
 */
class ClaudeParserLiveTest {
    @Test fun claudeParsesTestdata() {
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        assumeTrue("ANTHROPIC_API_KEY not set", key.isNotBlank())
        val model = System.getenv("CLAUDE_MODEL") ?: "claude-haiku-5-5"
        val parser = ClaudeParser(key, model)
        val dir = File(System.getProperty("testdata.dir") ?: "../testdata")
        for (ocr in dir.listFiles { f -> f.name.endsWith(".ocr.txt") }!!.sorted()) {
            val name = ocr.name.removeSuffix(".ocr.txt")
            val exp = ReceiptJson.decode(File(dir, "$name.expected.json").readText())
            val got = parser.parse(ocr.readText())
            println("$name → ${ReceiptJson.encode(got)}")
            assertEquals("$name date", exp.date, got.date)
            assertEquals("$name time", exp.time, got.time)
            assertEquals("$name total", exp.total, got.total)
            assertEquals("$name item count", exp.items.size, got.items.size)
            assertTrue("$name sum matches", got.validate().matches)
        }
    }
}
