package app.receiptscanner.parse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.round

/** Parsed receipt. Same shape is used for Claude's JSON output, the testdata expected JSON files and the local queue. */
@Serializable
data class Receipt(
    val store: String? = null,
    /** ISO yyyy-mm-dd as printed on the receipt. */
    val date: String? = null,
    /** HH:mm as printed on the receipt. */
    val time: String? = null,
    val items: List<Item> = emptyList(),
    val total: Double? = null,
    @SerialName("payment_total") val paymentTotal: Double? = null,
)

@Serializable
data class Item(
    val name: String,
    /** Units, or kg for weighed items. */
    val qty: Double = 1.0,
    @SerialName("unit_price") val unitPrice: Double? = null,
    /** Price for this line after line discounts. Negative for discount lines. */
    @SerialName("line_total") val lineTotal: Double,
)

fun round2(v: Double): Double = round(v * 100.0) / 100.0

data class Validation(val itemsSum: Double, val total: Double?) {
    val matches: Boolean get() = total != null && abs(itemsSum - total) < 0.005
    val difference: Double? get() = total?.let { round2(it - itemsSum) }
}

fun Receipt.validate(): Validation = Validation(round2(items.sumOf { it.lineTotal }), total)
