package dev.wasdevv.tally.ingestion

import dev.wasdevv.tally.domain.ledger.Destination
import dev.wasdevv.tally.domain.matching.MatchingPolicy
import dev.wasdevv.tally.domain.matching.Reconciliation
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.parsing.ReadLine
import dev.wasdevv.tally.persistence.BatchRow
import dev.wasdevv.tally.persistence.LedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jooq.DSLContext
import java.time.OffsetDateTime

data class ImportRequest(
    val filename: String,
    val layoutName: String,
    val digest: String,
    val lines: Flow<ReadLine>,
    val policy: MatchingPolicy = MatchingPolicy.DEFAULT,
)

sealed interface ImportOutcome {
    val batch: BatchRow

    data class Imported(override val batch: BatchRow) : ImportOutcome

    /** O arquivo ja tinha sido importado. Reprocessar e barato e nao duplica nada. */
    data class AlreadyImported(override val batch: BatchRow) : ImportOutcome
}

/**
 * Importa um arquivo em UMA transacao.
 *
 * Tres coisas acontecem nessa ordem e a ordem e o desenho:
 *
 * 1. Advisory lock derivado do digest. Dois processos com o mesmo arquivo
 *    serializam aqui, em vez de correrem ate colidirem no indice unico.
 * 2. Consulta pelo digest. Se o lote existe, devolve o que existe -- reprocessar
 *    o mesmo arquivo e idempotente por construcao, nao por sorte.
 * 3. As linhas sao gravadas ANTES da linha do lote, que so nasce no fim com a
 *    contagem ja conferida. Falha no meio nao deixa lote concluido porque nao
 *    deixa lote nenhum: a transacao inteira volta atras.
 */
class BatchImporter(
    private val dsl: DSLContext,
    private val repository: LedgerRepository,
    private val chunkSize: Int = DEFAULT_CHUNK,
) {
    suspend fun import(request: ImportRequest): ImportOutcome =
        withContext(Dispatchers.IO) {
            dsl.transactionResult { config ->
                val txn = config.dsl()
                AdvisoryLock.acquire(txn, FileDigest.lockKey(request.digest))

                val existing = repository.findBatchByDigest(request.digest, txn)
                if (existing != null) {
                    ImportOutcome.AlreadyImported(existing)
                } else {
                    ImportOutcome.Imported(write(txn, request))
                }
            }
        }

    private fun write(
        txn: DSLContext,
        request: ImportRequest,
    ): BatchRow {
        val batchId = repository.nextBatchId(txn)
        val receivables = repository.loadReceivables(txn)
        val reconciliation = Reconciliation(receivables, request.policy)

        var lineCount = 0
        var total = Cents.ZERO

        // runBlocking prende a coleta na thread que e dona da transacao JDBC --
        // conexao JDBC nao e thread-safe. O `flowOn` acima move a leitura e o
        // casamento para outra thread, e o `buffer` limita a fila entre as duas:
        // o parser corre a frente do escritor, mas so ate um teto.
        runBlocking {
            request.lines
                .map { destinationOf(it, reconciliation) }
                .flowOn(Dispatchers.Default)
                .buffer(BUFFERED_CHUNKS * chunkSize)
                .chunked(chunkSize)
                .collect { chunk ->
                    val ledger = chunk.filterNotNull()
                    repository.insertEntries(txn, batchId, ledger)
                    lineCount += ledger.size
                    total = ledger.fold(total) { acc, d -> acc + validCents(d) }
                }
        }

        // Confere contra o banco antes de deixar o lote nascer. O numero gravado
        // no lote e o que o razao tem, nao o que o importador achou que gravou.
        val persisted = repository.countEntries(batchId, txn)
        check(persisted == lineCount) {
            "conservacao quebrada ao gravar: contei $lineCount linhas e o razao tem $persisted"
        }

        val row =
            BatchRow(
                id = batchId,
                fileDigest = request.digest,
                filename = request.filename,
                layoutName = request.layoutName,
                lineCount = lineCount,
                totalCents = total,
                importedAt = OffsetDateTime.now(),
            )
        repository.insertBatch(txn, row)
        return row
    }

    /** null = linha estrutural: contabilizada pelo parser, sem linha no razao. */
    private fun destinationOf(
        read: ReadLine,
        reconciliation: Reconciliation,
    ): Destination? =
        when (read) {
            is ReadLine.Ledger -> reconciliation.accept(read.parsed)
            is ReadLine.Structural -> null
        }

    private fun validCents(destination: Destination): Cents =
        when (destination) {
            is Destination.Matched -> destination.entry.amount
            is Destination.NeedsReview -> destination.entry.amount
            is Destination.Unmatched -> destination.entry.amount
            // Rejeitada conta na cardinalidade e nao conta na soma.
            is Destination.Rejected -> Cents.ZERO
        }

    private companion object {
        const val DEFAULT_CHUNK = 500
        const val BUFFERED_CHUNKS = 2
    }
}

/** Flow nao tem `chunked` na stdlib; sao dez linhas e evita uma dependencia. */
private fun <T> Flow<T>.chunked(size: Int): Flow<List<T>> =
    flow {
        val batch = ArrayList<T>(size)
        collect {
            batch += it
            if (batch.size == size) {
                emit(ArrayList(batch))
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) emit(batch)
    }

private fun <T, R> Flow<T>.map(transform: (T) -> R): Flow<R> =
    flow {
        collect { emit(transform(it)) }
    }
