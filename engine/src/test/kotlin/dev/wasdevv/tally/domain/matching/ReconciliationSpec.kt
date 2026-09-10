package dev.wasdevv.tally.domain.matching

import dev.wasdevv.tally.domain.ledger.Destination
import dev.wasdevv.tally.domain.ledger.MatchOutcome
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId
import dev.wasdevv.tally.support.arbReturnFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll

/**
 * A rede que torna a otimizacao segura.
 *
 * `Reconciliation` indexa os recebiveis por nosso numero para nao varrer a
 * lista inteira a cada linha. Otimizacao que muda resultado e bug, entao aqui o
 * razao indexado e comparado com o de uma implementacao ingenua -- a versao
 * obvia, lenta e claramente correta -- sobre arquivos gerados.
 */
private class NaiveReconciliation(
    private val receivables: List<Receivable>,
    private val policy: MatchingPolicy,
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

class ReconciliationSpec : StringSpec({

    "o razao indexado e identico ao da varredura ingenua" {
        checkAll(500, arbReturnFile()) { file ->
            val indexed = Reconciliation(file.receivables, MatchingPolicy.DEFAULT)
            val naive = NaiveReconciliation(file.receivables, MatchingPolicy.DEFAULT)

            file.lines.forEach { line ->
                indexed.accept(line) shouldBe naive.accept(line)
            }
        }
    }

    "a equivalencia vale tambem com tolerancia zero" {
        checkAll(300, arbReturnFile()) { file ->
            val indexed = Reconciliation(file.receivables, MatchingPolicy.STRICT)
            val naive = NaiveReconciliation(file.receivables, MatchingPolicy.STRICT)

            file.lines.forEach { line ->
                indexed.accept(line) shouldBe naive.accept(line)
            }
        }
    }
})
