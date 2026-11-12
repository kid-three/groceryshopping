package app.receiptscanner.ocr

/** A recognized text fragment with its bounding box (pixels, image coordinates). */
data class TextFragment(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = bottom - top
}

/**
 * ML Kit tends to return receipt columns (names / unit prices / totals) as separate
 * blocks. This puts fragments back into visual rows: fragments whose vertical centers
 * are within half a line height are one row, ordered left to right.
 */
object OcrLines {
    fun assemble(fragments: List<TextFragment>): String {
        if (fragments.isEmpty()) return ""
        val sorted = fragments.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<TextFragment>>()
        for (f in sorted) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowCenter = row.map { it.centerY }.average()
                val tol = maxOf(row.maxOf { it.height }, f.height) * 0.5
                if (kotlin.math.abs(f.centerY - rowCenter) <= tol) {
                    row += f
                    continue
                }
            }
            rows += mutableListOf(f)
        }
        return rows.joinToString("\n") { r -> r.sortedBy { it.left }.joinToString("   ") { it.text.trim() } }
    }
}
