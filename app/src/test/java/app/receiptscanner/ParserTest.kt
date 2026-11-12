package app.receiptscanner

import app.receiptscanner.ocr.OcrLines
import app.receiptscanner.ocr.TextFragment
import app.receiptscanner.parse.Item
import app.receiptscanner.parse.Receipt
import app.receiptscanner.parse.ReceiptJson
import app.receiptscanner.parse.ReceiptParseException
import app.receiptscanner.parse.RegexParser
import app.receiptscanner.parse.RowBuilder
import app.receiptscanner.parse.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParserTest {
    private val dir = File(System.getProperty("testdata.dir") ?: "../testdata")

    private fun cases(): List<Pair<String, File>> =
        dir.listFiles { f -> f.name.endsWith(".ocr.txt") }!!.sorted()
            .map { it.name.removeSuffix(".ocr.txt") to it }
            .also { assertTrue("no testdata found in $dir", it.isNotEmpty()) }

    private fun expected(name: String): Receipt = ReceiptJson.decode(File(dir, "$name.expected.json").readText())

    @Test fun regexParserMatchesExpectedJson() {
        for ((name, ocr) in cases()) {
            val exp = expected(name)
            val got = ReceiptJson.normalize(RegexParser.parse(ocr.readText()))
            assertTrue("$name store: ${got.store}", got.store.orEmpty().startsWith(exp.store!!, ignoreCase = true))
            assertEquals("$name date", exp.date, got.date)
            assertEquals("$name time", exp.time, got.time)
            assertEquals("$name total", exp.total, got.total)
            assertEquals("$name payment", exp.paymentTotal, got.paymentTotal)
            assertEquals("$name item count: ${got.items.joinToString("\n")}", exp.items.size, got.items.size)
            exp.items.zip(got.items).forEachIndexed { i, (e, g) -> assertItem("$name item $i", e, g) }
            assertTrue("$name sum must match total", got.validate().matches)
        }
    }

    @Test fun expectedJsonFilesAreSelfConsistent() {
        for ((name, _) in cases()) assertTrue("$name: items don't sum to total", expected(name).validate().matches)
    }

    private fun assertItem(msg: String, e: Item, g: Item) {
        assertEquals("$msg name", e.name, g.name)
        assertEquals("$msg qty", e.qty, g.qty, 0.0005)
        assertEquals("$msg unit", e.unitPrice ?: 0.0, g.unitPrice ?: 0.0, 0.005)
        assertEquals("$msg total", e.lineTotal, g.lineTotal, 0.005)
    }

    @Test fun mismatchIsFlagged() {
        val r = expected("mercadona-2026-10-03")
        val broken = r.copy(items = r.items.drop(1))
        assertFalse(broken.validate().matches)
        assertEquals(1.56, broken.validate().difference!!, 0.001)
    }

    @Test fun decodeAcceptsModelJsonAndNormalizes() {
        val r = ReceiptJson.decode(
            """{"store":" Lidl ","date":"03/10/2026","time":"9:05","items":[
               {"name":"  PAN   BARRA ","qty":2,"unit_price":0.45,"line_total":0.9},
               {"name":"","qty":1,"unit_price":null,"line_total":1}],
               "total":0.9,"payment_total":null,"extra":"ignored"}"""
        )
        assertEquals("Lidl", r.store)
        assertEquals("2026-10-03", r.date)
        assertEquals("09:05", r.time)
        assertEquals(listOf(Item("PAN BARRA", 2.0, 0.45, 0.9)), r.items)
        assertTrue(r.validate().matches)
    }

    @Test(expected = ReceiptParseException::class) fun decodeRejectsGarbage() { ReceiptJson.decode("not json") }

    @Test(expected = ReceiptParseException::class) fun decodeRejectsNoItems() {
        ReceiptJson.decode("""{"store":"X","items":[],"total":1.0}""")
    }

    @Test fun receiptIdIsStableAndDistinguishesReceipts() {
        val r = expected("primaprix-2026-09-19")
        val a = RowBuilder.receiptId(r, "2026-11-12T13:43:34+01:00")
        val b = RowBuilder.receiptId(r, "2026-11-13T09:00:00+01:00") // rescanned later
        assertEquals(a, b)
        assertNotEquals(a, RowBuilder.receiptId(r.copy(total = 10.81), "2026-11-12T13:43:34+01:00"))
    }

    @Test fun rowsUsePhotoDateWhenPrintedDateMissing() {
        val r = expected("primaprix-2026-09-19").copy(date = null, time = null)
        val rows = RowBuilder.rows(r, "2026-11-12T13:43:34+01:00", "Alba")
        assertEquals(1, rows.size)
        assertEquals("2026-11-12", rows[0].date)
        assertEquals("", rows[0].time)
        assertEquals("app-ocr", rows[0].source)
        assertEquals("Alba", rows[0].added_by)
        assertEquals(10.80, rows[0].receipt_total!!, 0.001)
    }

    @Test fun ocrLinesReassemblesColumnsIntoRows() {
        // ML Kit returns the name column and the price column as separate blocks.
        val frags = listOf(
            TextFragment("3 LECHE ENTERA", 10, 100, 300, 130),
            TextFragment("1 ROLLO COCINA", 10, 140, 300, 170),
            TextFragment("3,87", 600, 102, 660, 131),
            TextFragment("1,29", 450, 99, 510, 128),
            TextFragment("2,95", 600, 141, 660, 172),
        )
        assertEquals("3 LECHE ENTERA   1,29   3,87\n1 ROLLO COCINA   2,95", OcrLines.assemble(frags))
    }
}
