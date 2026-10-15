package com.somphie.billing.application

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.time.format.DateTimeFormatter
import java.time.ZoneId

@Service
class ReceiptPdfService(
    private val billQueryService: BillQueryService,
) {
    fun generate(authorization: String?): ByteArray =
        render(billQueryService.getCurrentReceipt(authorization))

    private fun render(receipt: SessionReceipt): ByteArray {
        val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        return ByteArrayOutputStream().use { output ->
            PDDocument().use { document ->
                val pageWriter = ReceiptPageWriter(document, regular)
                pageWriter.use {
                    pageWriter.line(receipt.restaurantName, bold, 16f)
                    pageWriter.line(receipt.receiptLegend, regular, 10f)
                    pageWriter.line("Visita: ${receipt.sessionId}")
                    pageWriter.line("Mesa: ${receipt.tableLabel}")
                    val timezone = ZoneId.of(receipt.timezone)
                    pageWriter.line("Apertura: ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(receipt.openedAt.atZone(timezone))}")
                    pageWriter.line("Cierre: ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(receipt.closedAt.atZone(timezone))}")
                    pageWriter.line("")
                    pageWriter.line("Ítems", bold)
                    receipt.lines.forEach { item ->
                        pageWriter.line(
                            "${item.quantity} x ${item.productName} — base ${money(receipt.currency, item.basePriceMinor)}; " +
                                "unit ${money(receipt.currency, item.unitPriceMinor)} c/u; " +
                                "total ${money(receipt.currency, item.lineTotalMinor)}",
                        )
                        item.options.forEach { option ->
                            val delta = if (option.priceDeltaMinor == 0L) "" else " (${money(receipt.currency, option.priceDeltaMinor)})"
                            pageWriter.line("  ${option.groupName}: ${option.optionName}$delta")
                        }
                    }
                    pageWriter.line("")
                    pageWriter.line("Ajustes", bold)
                    receipt.adjustments.forEach { adjustment ->
                        pageWriter.line(
                            "${adjustment.getValue("label")}: " +
                                money(receipt.currency, (adjustment.getValue("amountMinor") as Number).toLong()),
                        )
                    }
                    pageWriter.line("Cargos: ${money(receipt.currency, receipt.chargesMinor)}")
                    pageWriter.line("Ajustes aplicados: ${money(receipt.currency, receipt.adjustmentsMinor)}")
                    pageWriter.line("Pagado: ${money(receipt.currency, receipt.paidMinor)}")
                    pageWriter.line("Saldo: ${money(receipt.currency, receipt.balanceMinor)}")
                    pageWriter.line("Propinas (separadas del saldo): ${money(receipt.currency, receipt.tipsMinor)}")
                    pageWriter.line("")
                    pageWriter.line("Pagos", bold)
                    receipt.payments.forEach { payment ->
                        val kind = payment.getValue("kind")
                        val method = payment.getValue("method")
                        val status = payment.getValue("status")
                        val createdAt = payment.getValue("createdAt")
                        val amount = (payment.getValue("amountMinor") as Number).toLong()
                        pageWriter.line("$createdAt / $kind / $method / $status: ${money(receipt.currency, amount)}")
                        val tip = (payment.getValue("tipMinor") as Number).toLong()
                        if (tip > 0) pageWriter.line("  Propina: ${money(receipt.currency, tip)}")
                    }
                    if (receipt.closeReason == "FORCED") {
                        pageWriter.line("")
                        pageWriter.line("Cierre forzado", bold)
                        if (receipt.balanceMinor > 0) {
                            pageWriter.line("Saldo no cobrado: ${money(receipt.currency, receipt.balanceMinor)}")
                        }
                    }
                }
                document.save(output)
            }
            output.toByteArray()
        }
    }

    private fun money(currency: String, minor: Long): String {
        val absolute = BigInteger.valueOf(minor).abs()
        val whole = absolute.divide(HUNDRED)
        val cents = absolute.mod(HUNDRED).toString().padStart(2, '0')
        return "$currency ${if (minor < 0) "-" else ""}$whole.$cents"
    }

    private class ReceiptPageWriter(
        private val document: PDDocument,
        private val regular: PDFont,
    ) : AutoCloseable {
        private var page = PDPage(PDRectangle.A4)
        private var stream: PDPageContentStream
        private var y = 790f

        init {
            document.addPage(page)
            stream = PDPageContentStream(document, page)
        }

        fun line(text: String, font: PDFont = regular, size: Float = 10f) {
            wrap(text, font, size).forEach { part ->
                if (y < 48f) newPage()
                stream.beginText()
                stream.setFont(font, size)
                stream.newLineAtOffset(48f, y)
                stream.showText(part)
                stream.endText()
                y -= size + 5f
            }
        }

        override fun close() = stream.close()

        private fun newPage() {
            stream.close()
            page = PDPage(PDRectangle.A4)
            document.addPage(page)
            stream = PDPageContentStream(document, page)
            y = 790f
        }

        private fun wrap(text: String, font: PDFont, size: Float): List<String> {
            if (text.isEmpty()) return listOf("")
            val result = mutableListOf<String>()
            var line = ""
            text.split(WHITESPACE).forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (font.getStringWidth(candidate) * size / 1000f <= MAX_LINE_WIDTH || line.isEmpty()) {
                    line = candidate
                } else {
                    result += line
                    line = word
                }
            }
            if (line.isNotEmpty()) result += line
            return result
        }

        private companion object {
            val WHITESPACE = Regex("\\s+")
            const val MAX_LINE_WIDTH = 499f
        }
    }

    private companion object {
        val HUNDRED: BigInteger = BigInteger.valueOf(100)
    }
}
