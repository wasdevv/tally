package dev.wasdevv.tally.api

import com.fasterxml.jackson.databind.ObjectMapper
import dev.wasdevv.tally.api.dto.BatchSummary
import dev.wasdevv.tally.api.dto.LedgerEntryView
import dev.wasdevv.tally.api.dto.OccurrenceView
import dev.wasdevv.tally.api.dto.ReceivableInput
import dev.wasdevv.tally.domain.ledger.EntryStatus
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.persistence.BatchRow
import dev.wasdevv.tally.persistence.ImportBatches
import dev.wasdevv.tally.persistence.LedgerEntries
import dev.wasdevv.tally.persistence.LedgerRepository
import dev.wasdevv.tally.persistence.ReviewRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Component

/**
 * As consultas de leitura do razao, com o SQL a vista.
 *
 * A ordem padrao poe as pendencias na frente: a tela abre no trabalho, nao no
 * que ja esta feito. E ordem explicita, nao alfabetica do enum -- por acaso
 * MATCHED viria primeiro no alfabeto, que e exatamente o contrario do util.
 */
@Component
class LedgerViews(
    private val dsl: DSLContext,
    private val repository: LedgerRepository,
    private val reviews: ReviewRepository,
    private val mapper: ObjectMapper,
) {
    fun allBatches(): List<BatchSummary> =
        dsl.select(ImportBatches.ALL)
            .from(ImportBatches.TABLE)
            .orderBy(ImportBatches.IMPORTED_AT.desc())
            .fetch()
            .map { record ->
                summary(
                    BatchRow(
                        id = record[ImportBatches.ID],
                        fileDigest = record[ImportBatches.FILE_DIGEST].trim(),
                        filename = record[ImportBatches.FILENAME],
                        layoutName = record[ImportBatches.LAYOUT_NAME],
                        lineCount = record[ImportBatches.LINE_COUNT],
                        totalCents = Cents(record[ImportBatches.TOTAL_CENTS]),
                        importedAt = record[ImportBatches.IMPORTED_AT],
                    ),
                )
            }

    fun batch(id: Long): BatchSummary? = allBatches().firstOrNull { it.id == id }

    fun summary(row: BatchRow): BatchSummary {
        val counts = repository.entryStatuses(row.id)
        return BatchSummary(
            id = row.id,
            filename = row.filename,
            layoutName = row.layoutName,
            lineCount = row.lineCount,
            totalCents = row.totalCents.value,
            importedAt = row.importedAt,
            counts = EntryStatus.entries.associate { it.name to (counts[it.name] ?: 0) },
            unreconciledCents = unreconciledCents(row.id),
        )
    }

    /** O numero que a tela abre mostrando: quanto ainda nao fechou. */
    private fun unreconciledCents(batchId: Long): Long =
        dsl.select(DSL.sum(LedgerEntries.AMOUNT_CENTS))
            .from(LedgerEntries.TABLE)
            .where(LedgerEntries.BATCH_ID.eq(batchId))
            .and(LedgerEntries.STATUS.`in`(EntryStatus.NEEDS_REVIEW.name, EntryStatus.UNMATCHED.name))
            .fetchOne()
            ?.value1()
            ?.toLong()
            // Lote sem pendencia soma nada, e nada aqui e zero de verdade: nao ha
            // valor desconhecido neste caminho, so conjunto vazio.
            ?: 0L

    fun entries(
        batchId: Long,
        status: String?,
    ): List<LedgerEntryView> {
        val rows = entryRows(batchId, status)

        // Uma consulta para TODOS os candidatos, nao uma por linha em revisao:
        // um lote grande faria centenas de idas ao banco para desenhar uma tela.
        val candidates = reviews.candidatesFor(rows.map { it.id })
        if (candidates.isEmpty()) return rows.map { it.view }

        val receivables = repository.loadReceivables().associateBy { it.id.value }
        return rows.map { row ->
            row.view.copy(
                candidates =
                    candidates[row.id]
                        .orEmpty()
                        .mapNotNull { receivables[it] }
                        .map { receivable ->
                            ReceivableInput(
                                id = receivable.id.value,
                                ourNumber = receivable.ourNumber,
                                amountCents = receivable.amount.value,
                                dueDate = receivable.dueDate,
                                payer = receivable.payer,
                            )
                        },
            )
        }
    }

    private class EntryRow(val id: Long, val view: LedgerEntryView)

    private fun entryRows(
        batchId: Long,
        status: String?,
    ): List<EntryRow> {
        var condition = LedgerEntries.BATCH_ID.eq(batchId)
        if (!status.isNullOrBlank()) condition = condition.and(LedgerEntries.STATUS.eq(status))

        return dsl.select(LedgerEntries.ALL)
            .from(LedgerEntries.TABLE)
            .where(condition)
            // Pendencia primeiro, depois a linha fisica. Explicita de proposito.
            .orderBy(
                DSL.field(
                    "case status when 'NEEDS_REVIEW' then 0 when 'UNMATCHED' then 1 " +
                        "when 'REJECTED' then 2 else 3 end",
                ),
                LedgerEntries.LINE,
            )
            .fetch()
            .map { record ->
                val code = record[LedgerEntries.OCCURRENCE_CODE]
                val view =
                    LedgerEntryView(
                        id = record[LedgerEntries.ID],
                        line = record[LedgerEntries.LINE],
                        status = record[LedgerEntries.STATUS],
                        ourNumber = record[LedgerEntries.OUR_NUMBER],
                        amountCents = record[LedgerEntries.AMOUNT_CENTS],
                        paidAt = record[LedgerEntries.PAID_AT],
                        counterparty = record[LedgerEntries.COUNTERPARTY],
                        matchedReceivableId = record[LedgerEntries.MATCHED_RECEIVABLE_ID],
                        matchReason = record[LedgerEntries.MATCH_REASON],
                        occurrence =
                            code?.let {
                                OccurrenceView(
                                    line = record[LedgerEntries.LINE],
                                    code = it,
                                    params = paramsOf(record[LedgerEntries.OCCURRENCE_PARAMS]),
                                )
                            },
                        decidedAt = record[LedgerEntries.DECIDED_AT],
                    )
                EntryRow(record[LedgerEntries.ID], view)
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun paramsOf(json: JSONB?): Map<String, String> =
        json?.data()?.let { mapper.readValue(it, Map::class.java) as Map<String, String> } ?: emptyMap()
}
