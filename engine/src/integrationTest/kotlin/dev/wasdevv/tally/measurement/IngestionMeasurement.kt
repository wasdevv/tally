package dev.wasdevv.tally.measurement

import dev.wasdevv.tally.domain.ledger.Destination
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId
import dev.wasdevv.tally.domain.matching.MatchingPolicy
import dev.wasdevv.tally.domain.matching.Reconciliation
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.ingestion.SyntheticFiles
import dev.wasdevv.tally.parsing.ReadLine
import dev.wasdevv.tally.parsing.cnab.Cnab400
import java.io.File
import java.time.LocalDate

/**
 * Medicao de ingestao: streaming contra ler o arquivo inteiro em memoria.
 *
 * Duas disciplinas que o resultado depende:
 *
 * 1. DESCARTE DE WARMUP. A JVM compila em runtime; os primeiros segundos medem
 *    o JIT, nao o codigo. Sem descartar, o numero e do compilador.
 * 2. CONTRASTE. "14.000 linhas/s" isolado nao diz nada. Contra a alternativa
 *    descartada, diz tudo.
 *
 * Nao roda no CI: runner compartilhado tem vizinho barulhento, e numero de
 * benchmark em CI e ruido que ninguem confia. E ritual manual, reproduzivel
 * pelo comando registrado em docs/MEASUREMENTS.md.
 */
object IngestionMeasurement {
    private const val LINES = 100_000
    private const val RUNS = 5
    private const val WARMUP = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val file = File(args.firstOrNull() ?: "build/measurement/cnab400-100k.ret")
        generateIfAbsent(file)

        val receivables = receivables()
        println("arquivo: ${file.length() / 1024 / 1024} MB, $LINES lancamentos")
        println("execucoes: $RUNS, descartadas as $WARMUP primeiras (warmup de JIT), mediana das restantes")
        println()

        report("streaming (Flow/Sequence, uma linha por vez)") { streaming(file, receivables) }
        report("arquivo inteiro em memoria") { wholeFile(file, receivables) }
    }

    private class Result(val lines: Int, val peakHeapBytes: Long)

    /** Le linha a linha: nada do arquivo fica retido. */
    private fun streaming(
        file: File,
        receivables: List<Receivable>,
    ): Result {
        val reconciliation = Reconciliation(receivables, MatchingPolicy.DEFAULT)
        var count = 0
        var peak = 0L

        file.useLines { lines ->
            Cnab400.read(lines).forEach { read ->
                if (read is ReadLine.Ledger) {
                    reconciliation.accept(read.parsed)
                    count++
                    if (count % SAMPLE_EVERY == 0) peak = maxOf(peak, usedHeap())
                }
            }
        }

        return Result(count, maxOf(peak, usedHeap()))
    }

    /** A alternativa descartada: tudo em memoria de uma vez. */
    private fun wholeFile(
        file: File,
        receivables: List<Receivable>,
    ): Result {
        val reconciliation = Reconciliation(receivables, MatchingPolicy.DEFAULT)
        val parsed = Cnab400.parse(file.readText())
        val peak = usedHeap()

        val destinations: List<Destination> = parsed.lines.map(reconciliation::accept)
        return Result(destinations.size, maxOf(peak, usedHeap()))
    }

    private fun report(
        label: String,
        block: () -> Result,
    ) {
        val timings = mutableListOf<Double>()
        var last: Result? = null

        repeat(RUNS) { run ->
            System.gc()
            Thread.sleep(GC_SETTLE_MS)
            val baseline = usedHeap()

            val started = System.nanoTime()
            val result = block()
            val elapsed = (System.nanoTime() - started) / NANOS_PER_SECOND

            last = Result(result.lines, result.peakHeapBytes - baseline)
            if (run >= WARMUP) timings += result.lines / elapsed
        }

        val median = timings.sorted()[timings.size / 2]
        println(label)
        println("  %,.0f linhas/s".format(median))
        println("  pico de heap: %,d MB".format(last!!.peakHeapBytes / BYTES_PER_MB))
        println("  linhas processadas: %,d".format(last!!.lines))
        println()
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun generateIfAbsent(file: File) {
        if (file.exists()) return

        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.write(" ".repeat(HEADER_PAD).replaceRange(0, 1, "0"))
            out.newLine()
            repeat(LINES) { i ->
                out.write(
                    SyntheticFiles.detail(
                        ourNumber = "%08d".format(i % TITLE_SPACE),
                        amountCents = (i % AMOUNT_SPACE + 1).toLong() * CENTS_STEP,
                        paidAt = LocalDate.of(2026, 3, 12),
                    ),
                )
                out.newLine()
            }
            out.write(" ".repeat(HEADER_PAD).replaceRange(0, 1, "9"))
            out.newLine()
        }
    }

    private fun receivables() =
        (1..RECEIVABLE_COUNT).map { i ->
            Receivable(
                id = ReceivableId("r%06d".format(i)),
                ourNumber = "%08d".format(i),
                amount = Cents(i.toLong() * CENTS_STEP),
                dueDate = LocalDate.of(2026, 3, 12),
                payer = "Sacado $i",
            )
        }

    private const val SAMPLE_EVERY = 5_000
    private const val GC_SETTLE_MS = 200L
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val BYTES_PER_MB = 1024 * 1024
    private const val HEADER_PAD = 400
    private const val TITLE_SPACE = 50_000
    private const val AMOUNT_SPACE = 900
    private const val CENTS_STEP = 100L
    private const val RECEIVABLE_COUNT = 5_000
}
