package dev.wasdevv.tally.domain.matching

import dev.wasdevv.tally.domain.ledger.Entry
import dev.wasdevv.tally.domain.ledger.Match
import dev.wasdevv.tally.domain.ledger.MatchOutcome
import dev.wasdevv.tally.domain.ledger.Occurrence
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId
import dev.wasdevv.tally.domain.ledger.ReconciliationResult
import dev.wasdevv.tally.domain.ledger.Review

/**
 * Le o arquivo inteiro e devolve o razao: quatro destinos, particao exclusiva e
 * completa das linhas fisicas.
 *
 * Um recebivel casa no maximo uma vez. As linhas sao processadas na ordem
 * fisica do arquivo, que e a unica ordem que o operador consegue auditar contra
 * o papel -- e por isso `run` e determinista mesmo com a lista de recebiveis
 * embaralhada (o desempate mora em Matcher.stable()).
 */
object Reconciler {
    fun run(
        lines: List<ParsedLine>,
        receivables: List<Receivable>,
        policy: MatchingPolicy = MatchingPolicy.DEFAULT,
    ): ReconciliationResult {
        val matched = mutableListOf<Match>()
        val needsReview = mutableListOf<Review>()
        val unmatched = mutableListOf<Entry>()
        val rejected = mutableListOf<Occurrence>()
        val consumed = mutableSetOf<ReceivableId>()

        for (parsed in lines) {
            when (parsed) {
                is ParsedLine.Rejected -> rejected += parsed.occurrence
                is ParsedLine.Valid -> {
                    val available = receivables.filterNot { it.id in consumed }
                    when (val outcome = Matcher.match(parsed.entry, available, policy)) {
                        is MatchOutcome.Matched -> {
                            matched += Match(parsed.entry, outcome.receivable, outcome.reason)
                            consumed += outcome.receivable.id
                        }
                        is MatchOutcome.NeedsReview ->
                            needsReview += Review(parsed.entry, outcome.candidates, outcome.reason)
                        MatchOutcome.Unmatched -> unmatched += parsed.entry
                    }
                }
            }
        }

        return ReconciliationResult(matched, needsReview, unmatched, rejected)
    }
}
