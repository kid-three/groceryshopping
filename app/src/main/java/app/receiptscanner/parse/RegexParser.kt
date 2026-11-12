package app.receiptscanner.parse

/**
 * Offline fallback parser for Spanish supermarket receipts.
 * Input is row-ordered OCR text (see OcrLines). Best effort: the review screen
 * flags any mismatch between the item sum and the TOTAL line.
 */
object RegexParser {

    private const val AMT = """-?\d{1,4}(?:\.\d{3})*,\d{2}"""
    private val amountRe = Regex(AMT)

    // "KAIKU LECHE ENTERA   6   1,80   10,80"  (name, qty, unit, total)
    private val nameQtyUnitTotal = Regex("""^(.+?)\s+(\d{1,3}(?:,\d{1,3})?)\s+($AMT)\s+($AMT)$""")
    // "3 LECHE ENTERA   1,29   3,87"  /  "1 AGUADOY GAS P-6X500M   1,56"
    private val qtyNameUnitTotal = Regex("""^(\d{1,3})\s+(.+?)\s+(?:($AMT)\s+)?($AMT)$""")
    // "PAN DE MOLDE   1,35"
    private val nameTotal = Regex("""^(.+?)\s+($AMT)$""")
    // "0,532 kg x 4,99 €/kg   2,65"
    private val weighed = Regex("""^(\d+,\d{1,3})\s*kg\s*[x×*]?\s*($AMT)\s*/\s*kg(?:\s+($AMT))?$""", RegexOption.IGNORE_CASE)
    // "2 x 1,89   3,78"
    private val multi = Regex("""^(\d{1,3})\s*[x×*]\s*($AMT)(?:\s+($AMT))?$""", RegexOption.IGNORE_CASE)

    private val headerRe = Regex("""DESCRIPCI|ART[IÍ]CULO|\bCANT\b|\bPVP\b|P\.\s*UNIT|IMPORTE|IMP\.""", RegexOption.IGNORE_CASE)
    private val totalRe = Regex("""^TOTAL\b(?!\s*(?:ART|ITEM|UDS|UNID))""", RegexOption.IGNORE_CASE)
    private val subtotalRe = Regex("""^(?:SUB\s*TOTAL|BASE|IVA|CUOTA|ENTREGADO|CAMBIO)\b""", RegexOption.IGNORE_CASE)
    private val paymentRe = Regex("""TARJETA|EFECTIVO|PINPAD|VISA|MASTERCARD|BIZUM|CONTADO|PAGO""", RegexOption.IGNORE_CASE)
    private val discountRe = Regex("""\bDTO\b|DESCUENTO|\bPROMO|AHORRO|OFERTA|REBAJA""", RegexOption.IGNORE_CASE)

    private val knownStores = listOf(
        "MERCADONA", "CARREFOUR", "LIDL", "ALDI", "ALCAMPO", "EROSKI", "CONSUM", "AHORRAMAS",
        "PRIMAPRIX", "HIPERCOR", "EL CORTE INGLES", "SUPERCOR", "BM SUPERMERCADOS", "MAS Y MAS",
        "GADIS", "FROIZ", "BONPREU", "CONDIS", "CAPRABO", "SPAR", "COVIRAN", "MAKRO", "DIA",
    ).map { it to Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE) }
    private val storeNoise = Regex("""BIENVENID|FACTURA|TICKET|GRACIAS|TEL[EÉ]FONO|^C\.?I\.?F|^N\.?I\.?F|^\d""", RegexOption.IGNORE_CASE)

    fun parse(ocrText: String): Receipt {
        val lines = ocrText.lines().map(::clean).filter { it.isNotEmpty() }

        val store = findStore(lines)
        val dateLineIdx = lines.indexOfFirst { DateTimeExtract.date(it) != null }
        val date = lines.getOrNull(dateLineIdx)?.let(DateTimeExtract::date)
        val time = lines.getOrNull(dateLineIdx)?.let(DateTimeExtract::time)

        val totalIdx = lines.indexOfFirst { totalRe.containsMatchIn(it) && amountRe.containsMatchIn(it) }
        val total = lines.getOrNull(totalIdx)?.let(::lastAmount)
        val paymentTotal = if (totalIdx >= 0) {
            lines.drop(totalIdx + 1).take(4)
                .firstOrNull { paymentRe.containsMatchIn(it) && amountRe.containsMatchIn(it) }
                ?.let(::lastAmount)
        } else null

        val headerIdx = lines.indexOfFirst { headerRe.containsMatchIn(it) }
        val start = when {
            headerIdx >= 0 -> headerIdx + 1
            dateLineIdx >= 0 -> dateLineIdx + 1
            else -> 0
        }
        val end = if (totalIdx > start) totalIdx else lines.size
        val items = parseItems(lines.subList(start, end))

        return Receipt(store, date, time, items, total, paymentTotal)
    }

    private fun parseItems(lines: List<String>): List<Item> {
        val items = mutableListOf<Item>()
        var pendingName: String? = null

        fun flushPendingAsContinuation() {
            val p = pendingName ?: return
            pendingName = null
            if (items.isNotEmpty()) {
                val last = items.removeAt(items.lastIndex)
                items += last.copy(name = "${last.name} $p")
            }
        }

        for (line in lines) {
            if (subtotalRe.containsMatchIn(line) || headerRe.containsMatchIn(line)) continue

            val w = weighed.matchEntire(line)
            if (w != null) {
                val kg = num(w.groupValues[1]); val perKg = num(w.groupValues[2])
                val lineTotal = w.groupValues[3].takeIf { it.isNotEmpty() }?.let(::num) ?: round2(kg * perKg)
                val name = pendingName ?: items.removeLastOrNull()?.name ?: "Producto a granel"
                pendingName = null
                items += Item(name, kg, perKg, lineTotal)
                continue
            }

            val mq = multi.matchEntire(line)
            if (mq != null) {
                val q = mq.groupValues[1].toDouble(); val unit = num(mq.groupValues[2])
                val lineTotal = mq.groupValues[3].takeIf { it.isNotEmpty() }?.let(::num) ?: round2(q * unit)
                val name = pendingName ?: items.removeLastOrNull()?.name ?: "Producto"
                pendingName = null
                items += Item(name, q, unit, lineTotal)
                continue
            }

            val item = matchItemLine(line)
            if (item != null) {
                flushPendingAsContinuation()
                items += item
                continue
            }

            // No amount on this line: either the name of a weighed item (amount on the
            // next line) or the wrapped tail of the previous item's name.
            if (!amountRe.containsMatchIn(line) && line.any { it.isLetter() }) {
                flushPendingAsContinuation()
                pendingName = line.replace(Regex("""^\d{1,3}\s+"""), "")
            }
        }
        flushPendingAsContinuation()
        return items
    }

    private fun matchItemLine(line: String): Item? {
        nameQtyUnitTotal.matchEntire(line)?.let { m ->
            val qty = num(m.groupValues[2]); val unit = num(m.groupValues[3]); val tot = num(m.groupValues[4])
            if (qty > 0 && kotlin.math.abs(qty * unit - tot) < 0.02) return Item(m.groupValues[1].trim(), qty, unit, tot)
        }
        qtyNameUnitTotal.matchEntire(line)?.let { m ->
            val qty = m.groupValues[1].toDouble()
            val tot = num(m.groupValues[4])
            val unit = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::num) ?: round2(tot / qty)
            if (qty > 0) return Item(m.groupValues[2].trim(), qty, unit, tot)
        }
        nameTotal.matchEntire(line)?.let { m ->
            var tot = num(m.groupValues[2])
            val name = m.groupValues[1].trim()
            if (discountRe.containsMatchIn(name) && tot > 0) tot = -tot
            if (name.any { it.isLetter() }) return Item(name, 1.0, tot, tot)
        }
        return null
    }

    private fun findStore(lines: List<String>): String? {
        val head = lines.take(8)
        for (l in head) {
            knownStores.firstOrNull { (_, re) -> re.containsMatchIn(l) }?.let { (s, _) ->
                return s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
            }
        }
        return head.firstOrNull { l -> !storeNoise.containsMatchIn(l) && l.count { it.isLetter() } >= 3 }
    }

    /** Normalizes OCR noise: euro signs, stray slashes before amounts, repeated spaces. */
    private fun clean(s: String): String = s
        .replace(' ', ' ')
        .replace(Regex("""\s*(€|EUR)(?=\s|$|/)"""), "")
        .replace(Regex("""(?<=\s)[/|](?=\d)"""), "")
        .replace(Regex("""(\d) ,(\d{2})\b"""), "$1,$2")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun lastAmount(s: String): Double? = amountRe.findAll(s).lastOrNull()?.value?.let(::num)

    private fun num(s: String): Double = s.replace(".", "").replace(',', '.').toDouble()
}
