package dev.wasdevv.tally.domain.matching

import dev.wasdevv.tally.domain.ledger.Destination
import dev.wasdevv.tally.domain.ledger.Entry
import dev.wasdevv.tally.domain.ledger.Match
import dev.wasdevv.tally.domain.ledger.Occurrence
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReconciliationResult
import dev.wasdevv.tally.domain.ledger.Review

/**
 * Le o arquivo inteiro e devolve o razao: quatro destinos, particao exclusiva e
 * completa das linhas fisicas.
 *
 * E a forma em lote de `Reconciliation`, que e a mesma regra com estado. As
 * linhas sao processadas na ordem fisica do arquivo -- a unica que o operador
 * consegue auditar contra o papel. A ordem dos RECEBIVEIS nao pode importar, e
 * nao importa: o desempate mora em `Matcher.stable()`.
 */
object Reconciler {
    fun run(
        lines: List<ParsedLine>,
        receivables: List<Receivable>,
        policy: MatchingPolicy = MatchingPolicy.DEFAULT,
    ): ReconciliationResult {
        val reconciliation = Reconciliation(receivables, policy)

        val matched = mutableListOf<Match>()
        val needsReview = mutableListOf<Review>()
        val unmatched = mutableListOf<Entry>()
        val rejected = mutableListOf<Occurrence>()

        for (parsed in lines) {
            when (val destination = reconciliation.accept(parsed)) {
                is Destination.Matched ->
                    matched += Match(destination.entry, destination.receivable, destination.reason)
                is Destination.NeedsReview ->
                    needsReview += Review(destination.entry, destination.candidates, destination.reason)
                is Destination.Unmatched -> unmatched += destination.entry
                is Destination.Rejected -> rejected += destination.occurrence
            }
        }

        return ReconciliationResult(matched, needsReview, unmatched, rejected)
    }
}
