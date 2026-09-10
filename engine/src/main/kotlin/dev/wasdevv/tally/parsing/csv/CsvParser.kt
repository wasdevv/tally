package dev.wasdevv.tally.parsing.csv

import dev.wasdevv.tally.domain.ledger.Entry
import dev.wasdevv.tally.domain.ledger.Occurrence
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.parsing.ParsedFile
import dev.wasdevv.tally.parsing.types.BrazilianDecimal
import dev.wasdevv.tally.parsing.types.DateIso
import dev.wasdevv.tally.parsing.types.FieldType
import dev.wasdevv.tally.parsing.types.ParseOutcome
import dev.wasdevv.tally.parsing.types.PlainDecimal
import org.apache.commons.csv.CSVFormat
import java.io.StringReader
import java.time.LocalDate

/**
 * O separador decimal e o delimitador sao propriedade do LAYOUT, nunca do locale
 * de quem opera (brief secao 9.4). Um analista com a interface em ingles
 * importando CSV brasileiro precisa que "1.234,56" vire 123456 centavos.
 */
data class CsvLayout(
    val name: String,
    val delimiter: Char,
    val amount: FieldType<Cents>,
    val date: FieldType<LocalDate> = DateIso,
    val ourNumberColumn: String = "our_number",
    val amountColumn: String = "amount",
    val paidAtColumn: String = "paid_at",
    val counterpartyColumn: String = "counterparty",
) {
    internal val requiredColumns
        get() = listOf(ourNumberColumn, amountColumn, paidAtColumn, counterpartyColumn)

    companion object {
        val BRAZILIAN = CsvLayout("csv-br", delimiter = ';', amount = BrazilianDecimal)
        val AMERICAN = CsvLayout("csv-us", delimiter = ',', amount = PlainDecimal)
    }
}

object CsvParser {
    fun parse(
        content: String,
        layout: CsvLayout,
    ): ParsedFile {
        val format =
            CSVFormat.Builder.create(CSVFormat.DEFAULT)
                .setDelimiter(layout.delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .build()

        return format.parse(StringReader(content.removePrefix("\uFEFF"))).use { parser ->
            val header = parser.headerNames
            // Coluna obrigatoria ausente e defeito do ARQUIVO, nao de uma linha:
            // rejeitar linha a linha produziria N ocorrencias identicas e
            // esconderia que o operador mandou o arquivo errado.
            val missing = layout.requiredColumns.filterNot { it in header }
            require(missing.isEmpty()) {
                "csv '${layout.name}': arquivo sem a(s) coluna(s) obrigatoria(s) " +
                    "${missing.joinToString()}; cabecalho lido: ${header.joinToString()}"
            }

            val lines =
                parser.records.map { record ->
                    // +1 pelo cabecalho. Com quebra de linha dentro de aspas isto e o
                    // ordinal do registro, nao o numero fisico -- ver docs/DECISIONS.md.
                    readRecord(record.toMap(), record.recordNumber.toInt() + 1, layout)
                }

            ParsedFile(layout.name, lines, structuralLines = listOf(1))
        }
    }

    private fun readRecord(
        values: Map<String, String>,
        line: Int,
        layout: CsvLayout,
    ): ParsedLine {
        val ourNumber = values[layout.ourNumberColumn].orEmpty().trim()
        val counterparty = values[layout.counterpartyColumn].orEmpty().trim()

        val amount = layout.amount.parse(values[layout.amountColumn].orEmpty())
        if (amount is ParseOutcome.Failed) {
            return rejected(line, amount, layout.amountColumn)
        }
        val paidAt = layout.date.parse(values[layout.paidAtColumn].orEmpty())
        if (paidAt is ParseOutcome.Failed) {
            return rejected(line, paidAt, layout.paidAtColumn)
        }

        return ParsedLine.Valid(
            Entry(
                line = line,
                ourNumber = ourNumber,
                amount = (amount as ParseOutcome.Ok).value,
                paidAt = (paidAt as ParseOutcome.Ok).value,
                counterparty = counterparty,
            ),
        )
    }

    private fun rejected(
        line: Int,
        failure: ParseOutcome.Failed,
        column: String,
    ) = ParsedLine.Rejected(
        Occurrence(line, failure.code, failure.params + ("field" to column)),
    )
}
