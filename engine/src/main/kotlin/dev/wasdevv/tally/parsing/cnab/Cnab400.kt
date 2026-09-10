package dev.wasdevv.tally.parsing.cnab

import dev.wasdevv.tally.domain.ledger.Entry
import dev.wasdevv.tally.domain.ledger.Occurrence
import dev.wasdevv.tally.domain.ledger.OccurrenceCode
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.parsing.ParsedFile
import dev.wasdevv.tally.parsing.ReadLine
import dev.wasdevv.tally.parsing.layout.Layout
import dev.wasdevv.tally.parsing.layout.RecordSpec
import dev.wasdevv.tally.parsing.layout.layout
import dev.wasdevv.tally.parsing.layout.slice
import dev.wasdevv.tally.parsing.types.DateYYMMDD
import dev.wasdevv.tally.parsing.types.FixedDecimal
import dev.wasdevv.tally.parsing.types.ParseOutcome
import dev.wasdevv.tally.parsing.types.Text
import java.time.LocalDate

/**
 * Um layout CNAB 400 SINTETICO. Nao ha aqui especificacao de banco nenhum: as
 * posicoes vem do brief secao 6 e o arquivo de teste que as exercita e gerado
 * pelo repositorio. Nao afirmamos suporte generico a "cinco bancos" -- o que
 * existe e este layout e o custo de acrescentar outro, que e dado, nao codigo.
 */
object Cnab400 {
    const val RECORD_LENGTH = 400

    val synthetic: Layout =
        layout("cnab400-sintetico", recordLength = RECORD_LENGTH) {
            record(discriminator = 1..1, equalTo = HEADER) { }
            record(discriminator = 1..1, equalTo = DETAIL) {
                field("bankCode", 2..4, Text)
                field("ourNumber", 63..70, Text)
                field("status", 109..110, Text)
                field("paidAt", 111..116, DateYYMMDD)
                field("amount", 127..139, FixedDecimal(places = 2))
                field("counterparty", 325..354, Text)
            }
            record(discriminator = 1..1, equalTo = TRAILER) { }
        }

    private const val HEADER = "0"
    private const val DETAIL = "1"
    private const val TRAILER = "9"

    /**
     * A forma que streama: uma linha lida por vez, nada do arquivo retido.
     * E o que sustenta a medicao de heap limitado -- ler tudo em memoria daria o
     * mesmo resultado com um pico de heap proporcional ao tamanho do arquivo.
     */
    fun read(
        lines: Sequence<String>,
        layout: Layout = synthetic,
    ): Sequence<ReadLine> =
        lines.mapIndexed { index, raw -> (index + 1) to raw.removeSuffix("\r") }
            .map { (lineNumber, raw) ->
                when (val parsed = readLine(raw, lineNumber, layout)) {
                    null -> ReadLine.Structural(lineNumber)
                    else -> ReadLine.Ledger(parsed)
                }
            }

    /** Conveniencia para teste e arquivo pequeno; `read` e o caminho real. */
    fun parse(
        content: String,
        layout: Layout = synthetic,
    ): ParsedFile {
        val entries = mutableListOf<ParsedLine>()
        val structural = mutableListOf<Int>()

        read(sourceLines(content), layout).forEach {
            when (it) {
                is ReadLine.Ledger -> entries += it.parsed
                is ReadLine.Structural -> structural += it.line
            }
        }

        return ParsedFile(layout.name, entries, structural)
    }

    /** BOM fora, e um \n final e fim da ultima linha, nao uma linha vazia. */
    fun sourceLines(content: String): Sequence<String> =
        content.removePrefix("\uFEFF").split("\n").dropLastWhile { it.isEmpty() }.asSequence()

    /** null = linha estrutural (header/trailer): contabilizada, sem lancamento. */
    private fun readLine(
        raw: String,
        lineNumber: Int,
        layout: Layout,
    ): ParsedLine? {
        if (raw.length < layout.recordLength) {
            return rejected(lineNumber, OccurrenceCode.ROW_TOO_SHORT, mapOf("length" to "${raw.length}"))
        }
        if (raw.length > layout.recordLength) {
            return rejected(lineNumber, OccurrenceCode.ROW_TOO_LONG, mapOf("length" to "${raw.length}"))
        }

        val spec =
            layout.records.firstOrNull { it.matches(raw) }
                ?: return rejected(
                    lineNumber,
                    OccurrenceCode.ROW_UNKNOWN_RECORD_TYPE,
                    mapOf("raw" to raw.take(1)),
                )
        if (spec.equalTo != DETAIL) return null

        return toEntry(raw, lineNumber, spec)
    }

    private fun toEntry(
        raw: String,
        lineNumber: Int,
        spec: RecordSpec,
    ): ParsedLine {
        val values = mutableMapOf<String, Any>()
        for (field in spec.fields) {
            when (val outcome = field.type.parse(raw.slice(field.at))) {
                is ParseOutcome.Failed ->
                    return rejected(lineNumber, outcome.code, outcome.params + ("field" to field.name))
                is ParseOutcome.Ok<*> -> values[field.name] = outcome.value as Any
            }
        }

        return ParsedLine.Valid(
            Entry(
                line = lineNumber,
                ourNumber = values["ourNumber"] as String,
                amount = values["amount"] as Cents,
                paidAt = values["paidAt"] as LocalDate,
                counterparty = values["counterparty"] as String,
            ),
        )
    }

    private fun rejected(
        line: Int,
        code: OccurrenceCode,
        params: Map<String, String>,
    ) = ParsedLine.Rejected(Occurrence(line, code, params))
}
