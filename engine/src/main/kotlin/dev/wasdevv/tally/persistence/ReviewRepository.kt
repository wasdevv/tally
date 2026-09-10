package dev.wasdevv.tally.persistence

import dev.wasdevv.tally.domain.ledger.EntryStatus
import org.jooq.DSLContext
import java.time.OffsetDateTime

/**
 * A fila humana: ler os candidatos e registrar a decisao.
 *
 * Saiu de `LedgerRepository` porque sao dois trabalhos diferentes -- gravar o
 * razao durante a importacao e resolver o que ficou pendente depois. Quem
 * apontou a divisao foi o detekt, ao reclamar do tamanho da classe; a
 * reclamacao estava certa.
 */
class ReviewRepository(private val dsl: DSLContext) {
    fun candidatesFor(
        entryIds: List<Long>,
        txn: DSLContext = dsl,
    ): Map<Long, List<String>> {
        if (entryIds.isEmpty()) return emptyMap()

        return txn.select(MatchCandidates.ENTRY_ID, MatchCandidates.RECEIVABLE_ID)
            .from(MatchCandidates.TABLE)
            .where(MatchCandidates.ENTRY_ID.`in`(entryIds))
            .orderBy(MatchCandidates.ENTRY_ID, MatchCandidates.POSITION)
            .fetch()
            .groupBy({ it[MatchCandidates.ENTRY_ID] }, { it[MatchCandidates.RECEIVABLE_ID] })
    }

    /**
     * Registra a decisao humana sobre uma linha em revisao.
     *
     * `receivableId` nulo significa "nenhum destes": a linha vai para UNMATCHED
     * e continua no razao como pendencia. Nao existe "descartar" -- linha nao
     * sai do razao por decisao de operador, senao a conservacao deixaria de
     * fechar contra o arquivo.
     *
     * A transicao so vale a partir de NEEDS_REVIEW, e a condicao esta no
     * proprio UPDATE: duas abas do navegador decidindo a mesma linha nao podem
     * gravar as duas: a segunda encontra zero linhas e recebe conflito.
     */
    fun decide(
        batchId: Long,
        line: Int,
        receivableId: String?,
        txn: DSLContext = dsl,
    ): Decision {
        val entry =
            txn.select(LedgerEntries.ID, LedgerEntries.STATUS)
                .from(LedgerEntries.TABLE)
                .where(LedgerEntries.BATCH_ID.eq(batchId))
                .and(LedgerEntries.LINE.eq(line))
                .fetchOne()
                ?: return Decision.NotFound

        if (receivableId != null && !receivableExists(receivableId, txn)) {
            return Decision.UnknownReceivable
        }

        val status =
            if (receivableId == null) {
                EntryStatus.UNMATCHED.name
            } else {
                EntryStatus.MATCHED.name
            }

        val updated =
            txn.update(LedgerEntries.TABLE)
                .set(LedgerEntries.STATUS, status)
                .set(LedgerEntries.MATCHED_RECEIVABLE_ID, receivableId)
                .set(LedgerEntries.DECIDED_AT, OffsetDateTime.now())
                // O motivo ORIGINAL nao e sobrescrito: a tela perderia a informacao
                // de por que aquilo virou revisao.
                .set(LedgerEntries.DECIDED_REASON, if (receivableId == null) NONE_OF_THESE else CHOSEN)
                .where(LedgerEntries.ID.eq(entry[LedgerEntries.ID]))
                .and(LedgerEntries.STATUS.eq(EntryStatus.NEEDS_REVIEW.name))
                .execute()

        return if (updated == 1) Decision.Recorded else Decision.NotUnderReview
    }

    private fun receivableExists(
        id: String,
        txn: DSLContext,
    ) = txn.fetchExists(Receivables.TABLE, Receivables.ID.eq(id))

    sealed interface Decision {
        data object Recorded : Decision

        data object NotFound : Decision

        /** Ja decidida, ou nunca esteve em revisao. */
        data object NotUnderReview : Decision

        data object UnknownReceivable : Decision
    }

    private companion object {
        const val CHOSEN = "OPERATOR_CHOSE_CANDIDATE"
        const val NONE_OF_THESE = "OPERATOR_REJECTED_CANDIDATES"
    }
}
