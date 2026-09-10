package dev.wasdevv.tally.support

import dev.wasdevv.tally.domain.ledger.Occurrence
import dev.wasdevv.tally.domain.ledger.OccurrenceCode
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId
import dev.wasdevv.tally.domain.money.Cents
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import java.time.LocalDate

/**
 * Arquivo de retorno sintetico. Nenhum dado real, nem anonimizado.
 *
 * Os valores ficam limitados a +/- 1e12 centavos com no maximo 60 linhas por
 * arquivo de proposito: 60 * 1e12 continua muito abaixo de Long.MAX_VALUE, entao
 * a propriedade mede conservacao e nao overflow. O overflow tem teste proprio e
 * explicito em CentsSpec -- misturar os dois esconderia qual dos dois quebrou.
 */
data class SyntheticFile(
    val lines: List<ParsedLine>,
    val receivables: List<Receivable>,
)

private const val MAX_CENTS = 1_000_000_000_000L

private val edgeAmounts = listOf(0L, 1L, -1L, MAX_CENTS, -MAX_CENTS)

private fun arbAmount(): Arb<Cents> =
    arbitrary {
        if (Arb.boolean().bind()) {
            Cents(Arb.element(edgeAmounts).bind())
        } else {
            Cents(Arb.long(-MAX_CENTS..MAX_CENTS).bind())
        }
    }

fun arbReturnFile(): Arb<SyntheticFile> =
    arbitrary {
        val lineCount = Arb.int(0..60).bind()
        val titles = (1..maxOf(lineCount, 1)).map { "TIT%08d".format(it) }

        val lines =
            (1..lineCount).map { line ->
                if (Arb.int(1..10).bind() == 1) {
                    // Linha invalida: entra no razao como REJECTED, com linha e codigo.
                    ParsedLine.Rejected(
                        Occurrence(
                            line = line,
                            code = Arb.element(OccurrenceCode.entries).bind(),
                            params = mapOf("raw" to "00/00/00"),
                        ),
                    )
                } else {
                    ParsedLine.Valid(
                        entry(
                            line = line,
                            // Repetir titulo de proposito: duplicata exata precisa
                            // continuar contando como duas linhas no razao.
                            ourNumber = Arb.element(titles).bind(),
                            amount = arbAmount().bind(),
                            paidAt = LocalDate.of(2026, 3, 1).plusDays(Arb.int(0..40).bind().toLong()),
                        ),
                    )
                }
            }

        val receivables =
            (1..Arb.int(0..40).bind()).map { i ->
                Receivable(
                    id = ReceivableId("r%04d".format(i)),
                    ourNumber = Arb.element(titles).bind(),
                    amount = Cents(Arb.long(-MAX_CENTS..MAX_CENTS).bind()),
                    dueDate = LocalDate.of(2026, 3, 1).plusDays(Arb.int(0..40).bind().toLong()),
                    payer = "Sacado $i",
                )
            }

        SyntheticFile(lines, receivables)
    }
