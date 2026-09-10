package dev.wasdevv.tally.measurement

import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.ingestion.BatchImporter
import dev.wasdevv.tally.ingestion.FileDigest
import dev.wasdevv.tally.ingestion.ImportRequest
import dev.wasdevv.tally.ingestion.SyntheticFiles
import dev.wasdevv.tally.parsing.cnab.Cnab400
import dev.wasdevv.tally.persistence.Database
import dev.wasdevv.tally.persistence.LedgerRepository
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate

/**
 * Vazao PONTA A PONTA, com o INSERT no caminho.
 *
 * A outra medicao (`IngestionMeasurement`) para antes do banco e mede parser
 * mais casamento. Este numero e o outro -- o que inclui rede local, transacao,
 * advisory lock e escrita -- e e ele que descreve o que o sistema faz de fato.
 * Citar o primeiro como se fosse este seria inflar a bullet.
 *
 * Roda contra PostgreSQL real via Testcontainers, fora do CI:
 *
 *   ./gradlew measurePersisted
 */
object PersistedIngestionMeasurement {
    private const val LINES = 100_000
    private const val RUNS = 5
    private const val WARMUP = 2
    private const val RECEIVABLES = 5_000
    private const val CENTS_STEP = 100L
    private const val NANOS = 1_000_000_000.0

    @JvmStatic
    fun main(args: Array<String>) {
        val repository = LedgerRepository(Database.dsl)
        val importer = BatchImporter(Database.dsl, repository)
        val file = File(args.firstOrNull() ?: "build/measurement/cnab400-100k.ret")
        generateIfAbsent(file)

        println("arquivo: ${file.length() / 1024 / 1024} MB, $LINES lancamentos")
        println("execucoes: $RUNS, descartadas as $WARMUP primeiras, mediana das restantes")
        println("PostgreSQL 16 em container, transacao unica por importacao")
        println()

        val rates = mutableListOf<Double>()

        repeat(RUNS) { run ->
            Database.clean()
            repository.saveReceivables(receivables())

            val started = System.nanoTime()
            val outcome =
                runBlocking {
                    importer.import(
                        ImportRequest(
                            // Digest diferente a cada execucao, senao a idempotencia
                            // faz a 2a rodada devolver o lote pronto e "medir" 0ms --
                            // o numero pareceria excelente e nao mediria nada.
                            filename = "medicao-$run.ret",
                            layoutName = Cnab400.synthetic.name,
                            digest = FileDigest.of("$run:${file.absolutePath}".toByteArray()),
                            lines = Cnab400.read(file.readLines().asSequence()).asFlow(),
                        ),
                    )
                }
            val elapsed = (System.nanoTime() - started) / NANOS

            check(outcome.batch.lineCount == LINES) {
                "esperava $LINES linhas gravadas, vieram ${outcome.batch.lineCount}"
            }
            if (run >= WARMUP) rates += LINES / elapsed
        }

        val median = rates.sorted()[rates.size / 2]
        println("streaming + persistencia")
        println("  %,.0f linhas/s".format(median))
        println("  %,d linhas gravadas por execucao".format(LINES))
    }

    private fun generateIfAbsent(file: File) {
        if (file.exists()) return
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.write(" ".repeat(400).replaceRange(0, 1, "0"))
            out.newLine()
            repeat(LINES) { i ->
                out.write(
                    SyntheticFiles.detail(
                        ourNumber = "%08d".format(i % 50_000),
                        amountCents = (i % 900 + 1).toLong() * CENTS_STEP,
                        paidAt = LocalDate.of(2026, 3, 12),
                    ),
                )
                out.newLine()
            }
            out.write(" ".repeat(400).replaceRange(0, 1, "9"))
            out.newLine()
        }
    }

    private fun receivables() =
        (1..RECEIVABLES).map { i ->
            Receivable(
                id = ReceivableId("r%06d".format(i)),
                ourNumber = "%08d".format(i),
                amount = Cents(i.toLong() * CENTS_STEP),
                dueDate = LocalDate.of(2026, 3, 12),
                payer = "Sacado $i",
            )
        }
}
