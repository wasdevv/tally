package dev.wasdevv.tally.domain.matching

import dev.wasdevv.tally.domain.ledger.Entry
import dev.wasdevv.tally.domain.ledger.MatchOutcome
import dev.wasdevv.tally.domain.ledger.MatchReason
import dev.wasdevv.tally.domain.ledger.Receivable
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

/**
 * Funcao pura: mesma entrada, mesmo resultado, sem I/O e sem framework.
 *
 * A regra que nao se negocia: o motor so casa sozinho quando o titulo identifica
 * o lancamento. Bater apenas o valor e palpite, e palpite em conciliacao vira
 * dinheiro no lugar errado -- vai para a fila humana com o conjunto de
 * candidatos e o motivo.
 */
object Matcher {
    fun match(
        entry: Entry,
        available: List<Receivable>,
        policy: MatchingPolicy,
    ): MatchOutcome {
        val byTitle =
            if (entry.ourNumber.isBlank()) {
                emptyList()
            } else {
                available.filter { it.ourNumber.isNotBlank() && it.ourNumber == entry.ourNumber }
            }

        if (byTitle.isNotEmpty()) return matchByTitle(entry, byTitle, policy)

        val byAmount = available.filter { fits(entry, it, policy) }.stable()
        return when (byAmount.size) {
            0 -> MatchOutcome.Unmatched
            1 -> MatchOutcome.NeedsReview(byAmount, MatchReason.AMOUNT_ONLY)
            else -> MatchOutcome.NeedsReview(byAmount, MatchReason.AMBIGUOUS)
        }
    }

    private fun matchByTitle(
        entry: Entry,
        byTitle: List<Receivable>,
        policy: MatchingPolicy,
    ): MatchOutcome {
        val exact = byTitle.filter { it.amount == entry.amount && it.dueDate == entry.paidAt }.stable()
        val within = byTitle.filter { fits(entry, it, policy) }.stable()

        return when {
            exact.size == 1 -> MatchOutcome.Matched(exact.single(), MatchReason.EXACT)
            exact.size > 1 -> MatchOutcome.NeedsReview(exact, MatchReason.AMBIGUOUS)
            within.size == 1 -> MatchOutcome.Matched(within.single(), toleranceReason(entry, within.single()))
            within.size > 1 -> MatchOutcome.NeedsReview(within, MatchReason.AMBIGUOUS)
            // O titulo existe, mas o valor ou a data nao cabem: e revisao com
            // candidato, nao "sem par". O operador precisa ver o titulo.
            else -> MatchOutcome.NeedsReview(byTitle.stable(), MatchReason.AMOUNT_MISMATCH)
        }
    }

    private fun toleranceReason(
        entry: Entry,
        receivable: Receivable,
    ) = if (receivable.amount == entry.amount) MatchReason.EXACT else MatchReason.WITHIN_TOLERANCE

    private fun fits(
        entry: Entry,
        receivable: Receivable,
        policy: MatchingPolicy,
    ): Boolean {
        val days = ChronoUnit.DAYS.between(receivable.dueDate, entry.paidAt).absoluteValue
        if (days > policy.dateToleranceDays) return false
        return (entry.amount - receivable.amount).abs() <= policy.amountTolerance
    }

    /** Ordem estavel por id: embaralhar a entrada nao pode mudar o razao. */
    private fun List<Receivable>.stable() = sortedBy { it.id.value }
}
