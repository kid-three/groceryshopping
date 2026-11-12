package app.receiptscanner.parse

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class ReceiptParseException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

object ReceiptJson {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    /** Parses and validates model/test JSON into a clean [Receipt]. Throws [ReceiptParseException]. */
    fun decode(text: String): Receipt {
        val raw = try {
            json.decodeFromString(Receipt.serializer(), text.trim().removeSurrounding("```json", "```").trim())
        } catch (e: SerializationException) {
            throw ReceiptParseException("Invalid receipt JSON: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ReceiptParseException("Invalid receipt JSON: ${e.message}", e)
        }
        return normalize(raw)
    }

    fun encode(r: Receipt): String = json.encodeToString(Receipt.serializer(), r)

    fun normalize(r: Receipt): Receipt {
        val items = r.items
            .filter { it.name.isNotBlank() && it.lineTotal.isFinite() }
            .map {
                it.copy(
                    name = it.name.trim().replace(Regex("\\s+"), " "),
                    qty = if (it.qty.isFinite() && it.qty != 0.0) it.qty else 1.0,
                    unitPrice = it.unitPrice?.takeIf { p -> p.isFinite() }?.let(::round2),
                    lineTotal = round2(it.lineTotal),
                )
            }
        if (items.isEmpty()) throw ReceiptParseException("Receipt has no items")
        return r.copy(
            store = r.store?.trim()?.takeIf { it.isNotEmpty() },
            date = r.date?.let(::normalizeDate),
            time = r.time?.let(::normalizeTime),
            items = items,
            total = r.total?.takeIf { it.isFinite() }?.let(::round2),
            paymentTotal = r.paymentTotal?.takeIf { it.isFinite() }?.let(::round2),
        )
    }

    private val isoDate = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    /** Accepts yyyy-mm-dd, yyyy/mm/dd, dd/mm/yyyy, dd-mm-yy. Returns ISO or null. */
    fun normalizeDate(s: String): String? {
        val t = s.trim()
        isoDate.matchEntire(t)?.let { return validDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        return DateTimeExtract.date(t)
    }

    fun normalizeTime(s: String): String? = DateTimeExtract.time(s)

    internal fun validDate(y: String, m: String, d: String): String? {
        val yi = y.toInt(); val mi = m.toInt(); val di = d.toInt()
        if (yi !in 2000..2099 || mi !in 1..12 || di !in 1..31) return null
        return "%04d-%02d-%02d".format(yi, mi, di)
    }
}

internal object DateTimeExtract {
    private val ymd = Regex("""(20\d{2})[/.-](\d{1,2})[/.-](\d{1,2})""")
    private val dmy = Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{4}|\d{2})(?!\d)""")
    private val hm = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?(?!\d)""")

    fun date(text: String): String? {
        ymd.find(text)?.let { m ->
            ReceiptJson.validDate(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { return it }
        }
        for (m in dmy.findAll(text)) {
            val y = m.groupValues[3].let { if (it.length == 2) "20$it" else it }
            ReceiptJson.validDate(y, m.groupValues[2], m.groupValues[1])?.let { return it }
        }
        return null
    }

    fun time(text: String): String? =
        hm.find(text)?.let { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) }
}
