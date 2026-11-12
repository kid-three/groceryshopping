package app.receiptscanner.data

import android.content.Context
import app.receiptscanner.parse.Receipt
import app.receiptscanner.parse.ReceiptJson
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class QueuedReceipt(
    val receiptId: String,
    val receipt: Receipt,
    val photoTimestamp: String,
    val addedBy: String,
    val photoPath: String?,
)

/**
 * Durable outbox: one JSON file per saved receipt in app storage. A file is
 * deleted only after the Apps Script confirms the rows are in the sheet
 * (inserted or already present).
 */
class ReceiptQueue(context: Context) {
    private val dir = File(context.filesDir, "queue").apply { mkdirs() }

    fun add(q: QueuedReceipt) {
        val tmp = File(dir, "${q.receiptId}.json.tmp")
        tmp.writeText(ReceiptJson.json.encodeToString(QueuedReceipt.serializer(), q))
        tmp.renameTo(File(dir, "${q.receiptId}.json"))
    }

    fun all(): List<QueuedReceipt> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .sortedBy { it.lastModified() }
        .mapNotNull { f ->
            runCatching { ReceiptJson.json.decodeFromString(QueuedReceipt.serializer(), f.readText()) }.getOrNull()
        }

    fun remove(receiptId: String) {
        File(dir, "$receiptId.json").delete()
    }

    fun count(): Int = dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0
}
