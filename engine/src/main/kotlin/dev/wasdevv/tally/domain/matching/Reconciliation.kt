package dev.wasdevv.tally.domain.matching

import dev.wasdevv.tally.domain.ledger.Destination
import dev.wasdevv.tally.domain.ledger.MatchOutcome
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId

/**
 * A conciliacao com estado: aceita UMA linha por vez e devolve o destino dela.
 *
 * Existe porque o arquivo streama e o conjunto de recebiveis nao. Os recebiveis
 * sao o que a empresa espera receber -- cabem em memoria; o arquivo pode ter
 * milhoes de linhas e nao cabe. Manter o estado aqui deixa o pico de heap
 * proporcional aos recebiveis, nao ao tamanho do arquivo.
 *
 * `Reconciler.run` usa esta mesma classe, entao nao existem duas implementacoes
 * da regra de casamento para divergirem com o tempo.
 */
class Reconciliation(
    private val receivables: List<Receivable>,
    private val policy: MatchingPolicy = MatchingPolicy.DEFAULT,
) {
    private val consumed = mutableSetOf<ReceivableId>()

    fun accept(parsed: ParsedLine): Destination =
        when (parsed) {
            is ParsedLine.Rejected -> Destination.Rejected(parsed.occurrence)
            is ParsedLine.Valid -> {
                val available = receivables.filterNot { it.id in consumed }
                when (val outcome = Matcher.match(parsed.entry, available, policy)) {
                    is MatchOutcome.Matched -> {
                        consumed += outcome.receivable.id
                        Destination.Matched(parsed.entry, outcome.receivable, outcome.reason)
                    }
                    is MatchOutcome.NeedsReview ->
                        Destination.NeedsReview(parsed.entry, outcome.candidates, outcome.reason)
                    MatchOutcome.Unmatched -> Destination.Unmatched(parsed.entry)
                }
            }
        }
}
