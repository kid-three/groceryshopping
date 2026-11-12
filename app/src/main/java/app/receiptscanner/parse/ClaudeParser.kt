package app.receiptscanner.parse

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import java.time.Duration

/**
 * Sends the OCR text (never the photo) to Claude and gets strict JSON back via
 * structured outputs. The response is validated by [ReceiptJson.decode].
 */
class ClaudeParser(apiKey: String, private val model: String) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(60))
        .maxRetries(1)
        .build()

    fun parse(ocrText: String): Receipt {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(8000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build()
            )
            .addUserMessage("OCR text of the receipt, row by row:\n\n$ocrText")
            .build()

        val response = client.messages().create(params)
        val stop = response.stopReason().orElse(null)
        if (stop != StopReason.END_TURN) throw ReceiptParseException("Claude stopped early: $stop")
        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return ReceiptJson.decode(text)
    }

    companion object {
        private fun nullable(type: String) = mapOf("anyOf" to listOf(mapOf("type" to type), mapOf("type" to "null")))

        private val schema: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
            .putAllAdditionalProperties(
                mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("store", "date", "time", "items", "total", "payment_total"),
                    "properties" to mapOf(
                        "store" to nullable("string"),
                        "date" to nullable("string"),
                        "time" to nullable("string"),
                        "total" to nullable("number"),
                        "payment_total" to nullable("number"),
                        "items" to mapOf(
                            "type" to "array",
                            "items" to mapOf(
                                "type" to "object",
                                "additionalProperties" to false,
                                "required" to listOf("name", "qty", "unit_price", "line_total"),
                                "properties" to mapOf(
                                    "name" to mapOf("type" to "string"),
                                    "qty" to mapOf("type" to "number"),
                                    "unit_price" to nullable("number"),
                                    "line_total" to mapOf("type" to "number"),
                                ),
                            ),
                        ),
                    ),
                ).mapValues { JsonValue.from(it.value) }
            )
            .build()

        private val SYSTEM_PROMPT = """
            You extract purchase data from OCR text of Spanish shopping receipts (es-ES, euros).
            The OCR text may contain recognition errors and columns may be slightly misaligned;
            use the arithmetic (qty × unit price = line total, items sum = TOTAL) to resolve them.

            Output fields:
            - store: store/chain name plus branch or town if printed (e.g. "Mercadona Puertollano").
            - date: receipt date as yyyy-mm-dd. Printed as dd/mm/yyyy, dd-mm-yy or yyyy/mm/dd. null if not readable.
            - time: HH:mm (24h) if printed, else null.
            - items: one entry per purchased line, in receipt order.
              - name: product name exactly as printed (keep abbreviations; join names wrapped onto two lines).
              - qty: units, or kilograms for weighed items (e.g. "0,532 kg x 4,99 €/kg" → qty 0.532, unit_price 4.99).
              - unit_price: price per unit or per kg; null if not derivable.
              - line_total: amount charged for that line.
              - Discounts/promotions printed as their own line (DTO, Promo, descuento, -0,50) become their own
                item with a negative line_total.
            - total: the amount on the TOTAL line (what the customer owes).
            - payment_total: amount paid by card/cash (TARJETA, EFECTIVO, Pinpad...) if printed, else null.

            Ignore: VAT/IVA breakdown tables, base imponible, loyalty points, "ahorro" savings notes,
            card terminal text, operation numbers, footer text. Amounts use a comma decimal separator
            in the text; output plain JSON numbers with a dot. Never invent items that are not in the text.
        """.trimIndent()
    }
}
