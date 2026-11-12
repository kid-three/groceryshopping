package app.receiptscanner.parse

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** One sheet row. Field names = sheet header names. */
@Serializable
data class SheetRow(
    val receipt_id: String,
    val date: String,
    val time: String,
    val photo_timestamp: String,
    val store: String,
    val item: String,
    val qty: Double,
    val unit_price: Double?,
    val line_total: Double,
    val receipt_total: Double?,
    val added_by: String,
    val source: String,
)

object RowBuilder {
    const val SOURCE = "app-ocr"

    /**
     * Stable id: same receipt scanned twice → same id → the Apps Script skips it.
     * Falls back to the photo timestamp when the printed date/time is missing.
     */
    fun receiptId(r: Receipt, photoTimestamp: String): String {
        val key = listOf(
            r.store.orEmpty().lowercase().trim(),
            r.date ?: photoTimestamp.take(10),
            r.time ?: if (r.date == null) photoTimestamp.take(16) else "",
            r.total?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: photoTimestamp,
        ).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    /** photoTimestamp: ISO-8601 with offset, e.g. 2026-10-03T18:31:02+02:00 */
    fun rows(r: Receipt, photoTimestamp: String, addedBy: String): List<SheetRow> {
        val id = receiptId(r, photoTimestamp)
        return r.items.map {
            SheetRow(
                receipt_id = id,
                date = r.date ?: photoTimestamp.take(10),
                time = r.time ?: "",
                photo_timestamp = photoTimestamp,
                store = r.store.orEmpty(),
                item = it.name,
                qty = it.qty,
                unit_price = it.unitPrice,
                line_total = it.lineTotal,
                receipt_total = r.total,
                added_by = addedBy,
                source = SOURCE,
            )
        }
    }
}
