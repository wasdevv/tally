package dev.wasdevv.tally.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.wasdevv.tally.domain.ledger.Destination
import dev.wasdevv.tally.domain.ledger.EntryStatus
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId
import dev.wasdevv.tally.domain.money.Cents
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import java.time.OffsetDateTime

data class BatchRow(
    val id: Long,
    val fileDigest: String,
    val filename: String,
    val layoutName: String,
    val lineCount: Int,
    val totalCents: Cents,
    val importedAt: OffsetDateTime,
)

/**
 * O SQL fica visivel de proposito -- e o assunto. Sem lazy loading escondendo
 * N+1 e sem ORM decidindo quando ir ao banco.
 */
class LedgerRepository(private val dsl: DSLContext) {
    fun findBatchByDigest(
        digest: String,
        txn: DSLContext = dsl,
    ): BatchRow? =
        txn.select(ImportBatches.ALL)
            .from(ImportBatches.TABLE)
            .where(ImportBatches.FILE_DIGEST.eq(digest))
            .fetchOne()
            ?.let {
                BatchRow(
                    id = it[ImportBatches.ID],
                    fileDigest = it[ImportBatches.FILE_DIGEST].trim(),
                    filename = it[ImportBatches.FILENAME],
                    layoutName = it[ImportBatches.LAYOUT_NAME],
                    lineCount = it[ImportBatches.LINE_COUNT],
                    totalCents = Cents(it[ImportBatches.TOTAL_CENTS]),
                    importedAt = it[ImportBatches.IMPORTED_AT],
                )
            }

    /** Reserva o id antes de gravar as linhas; a linha do lote vem no fim. */
    fun nextBatchId(txn: DSLContext): Long = txn.nextval(DSL.sequence(DSL.name("import_batches_id_seq"))).toLong()

    fun insertBatch(
        txn: DSLContext,
        row: BatchRow,
    ) {
        txn.insertInto(ImportBatches.TABLE)
            .set(ImportBatches.ID, row.id)
            .set(ImportBatches.FILE_DIGEST, row.fileDigest)
            .set(ImportBatches.FILENAME, row.filename)
            .set(ImportBatches.LAYOUT_NAME, row.layoutName)
            .set(ImportBatches.LINE_COUNT, row.lineCount)
            .set(ImportBatches.TOTAL_CENTS, row.totalCents.value)
            .execute()
    }

    /** Um INSERT com N linhas, nao N INSERTs: o lote e o ponto. */
    fun insertEntries(
        txn: DSLContext,
        batchId: Long,
        destinations: List<Destination>,
    ) {
        if (destinations.isEmpty()) return

        var insert =
            txn.insertInto(
                LedgerEntries.TABLE,
                LedgerEntries.BATCH_ID,
                LedgerEntries.LINE,
                LedgerEntries.STATUS,
                LedgerEntries.OUR_NUMBER,
                LedgerEntries.AMOUNT_CENTS,
                LedgerEntries.PAID_AT,
                LedgerEntries.COUNTERPARTY,
                LedgerEntries.MATCHED_RECEIVABLE_ID,
                LedgerEntries.MATCH_REASON,
                LedgerEntries.OCCURRENCE_CODE,
                LedgerEntries.OCCURRENCE_PARAMS,
            )

        destinations.forEach { destination ->
            insert =
                when (destination) {
                    is Destination.Matched ->
                        insert.values(
                            batchId, destination.entry.line, EntryStatus.MATCHED.name,
                            destination.entry.ourNumber, destination.entry.amount.value,
                            destination.entry.paidAt, destination.entry.counterparty,
                            destination.receivable.id.value, destination.reason.name, null, null,
                        )
                    is Destination.NeedsReview ->
                        insert.values(
                            batchId, destination.entry.line, EntryStatus.NEEDS_REVIEW.name,
                            destination.entry.ourNumber, destination.entry.amount.value,
                            destination.entry.paidAt, destination.entry.counterparty,
                            null, destination.reason.name, null, null,
                        )
                    is Destination.Unmatched ->
                        insert.values(
                            batchId, destination.entry.line, EntryStatus.UNMATCHED.name,
                            destination.entry.ourNumber, destination.entry.amount.value,
                            destination.entry.paidAt, destination.entry.counterparty,
                            null, null, null, null,
                        )
                    is Destination.Rejected ->
                        insert.values(
                            batchId, destination.occurrence.line, EntryStatus.REJECTED.name,
                            null, null, null, null, null, null,
                            destination.occurrence.code.name, jsonb(destination.occurrence.params),
                        )
                }
        }

        insert.execute()
    }

    fun loadReceivables(txn: DSLContext = dsl): List<Receivable> =
        txn.select(Receivables.ALL)
            .from(Receivables.TABLE)
            .orderBy(Receivables.ID)
            .fetch()
            .map {
                Receivable(
                    id = ReceivableId(it[Receivables.ID]),
                    ourNumber = it[Receivables.OUR_NUMBER],
                    amount = Cents(it[Receivables.AMOUNT_CENTS]),
                    dueDate = it[Receivables.DUE_DATE],
                    payer = it[Receivables.PAYER],
                )
            }

    fun saveReceivables(
        receivables: List<Receivable>,
        txn: DSLContext = dsl,
    ) {
        receivables.forEach { r ->
            txn.insertInto(Receivables.TABLE)
                .set(Receivables.ID, r.id.value)
                .set(Receivables.OUR_NUMBER, r.ourNumber)
                .set(Receivables.AMOUNT_CENTS, r.amount.value)
                .set(Receivables.DUE_DATE, r.dueDate)
                .set(Receivables.PAYER, r.payer)
                .onConflict(Receivables.ID)
                .doUpdate()
                .set(Receivables.OUR_NUMBER, r.ourNumber)
                .set(Receivables.AMOUNT_CENTS, r.amount.value)
                .set(Receivables.DUE_DATE, r.dueDate)
                .set(Receivables.PAYER, r.payer)
                .execute()
        }
    }

    fun countEntries(
        batchId: Long,
        txn: DSLContext = dsl,
    ): Int = txn.fetchCount(LedgerEntries.TABLE, LedgerEntries.BATCH_ID.eq(batchId))

    fun entryStatuses(
        batchId: Long,
        txn: DSLContext = dsl,
    ): Map<String, Int> =
        txn.select(LedgerEntries.STATUS, DSL.count())
            .from(LedgerEntries.TABLE)
            .where(LedgerEntries.BATCH_ID.eq(batchId))
            .groupBy(LedgerEntries.STATUS)
            .fetch()
            .associate { it.value1() to it.value2() }

    // Jackson em vez de montar JSON na mao: escape de aspas, barra invertida e
    // caractere de controle tem canto demais para uma funcao caseira acertar.
    private fun jsonb(params: Map<String, String>): JSONB = JSONB.valueOf(MAPPER.writeValueAsString(params))

    private companion object {
        val MAPPER = ObjectMapper()
    }
}
