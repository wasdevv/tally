package dev.wasdevv.tally.ingestion

import dev.wasdevv.tally.domain.ledger.EntryStatus
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId
import dev.wasdevv.tally.domain.matching.MatchingPolicy
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.parsing.cnab.Cnab400
import dev.wasdevv.tally.persistence.Database
import dev.wasdevv.tally.persistence.LedgerRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate

class BatchImporterSpec : StringSpec({
    val repository = LedgerRepository(Database.dsl)
    val importer = BatchImporter(Database.dsl, repository)

    beforeTest { Database.clean() }

    fun cnabFile(
        count: Int,
        brokenEvery: Int = 0,
    ): String {
        val details =
            (1..count).map { i ->
                val row =
                    SyntheticFiles.detail(
                        ourNumber = "%08d".format(i),
                        amountCents = 100L * i,
                        paidAt = LocalDate.of(2026, 3, 12),
                    )
                if (brokenEvery > 0 && i % brokenEvery == 0) row.replaceRange(110, 116, "999999") else row
            }
        return SyntheticFiles.file(details)
    }

    suspend fun import(
        content: String,
        filename: String = "itau-0314.ret",
        policy: MatchingPolicy = MatchingPolicy.DEFAULT,
    ) = importer.import(
        ImportRequest(
            filename = filename,
            layoutName = Cnab400.synthetic.name,
            digest = FileDigest.of(content.toByteArray()),
            lines = Cnab400.read(Cnab400.sourceLines(content)).asFlow(),
            policy = policy,
        ),
    )

    fun receivable(
        ourNumber: String,
        cents: Long,
    ) = Receivable(
        id = ReceivableId("r-$ourNumber"),
        ourNumber = ourNumber,
        amount = Cents(cents),
        dueDate = LocalDate.of(2026, 3, 12),
        payer = "Silva ME",
    )

    "importar grava uma linha de razao por linha de lancamento" {
        val outcome = import(cnabFile(10))

        outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        outcome.batch.lineCount shouldBe 10
        repository.countEntries(outcome.batch.id) shouldBe 10
    }

    "o total gravado no lote e a soma dos centavos das linhas validas" {
        val outcome = import(cnabFile(10))

        // 100 + 200 + ... + 1000
        outcome.batch.totalCents shouldBe Cents(5500)
    }

    "linha rejeitada conta na cardinalidade e nao conta na soma" {
        val outcome = import(cnabFile(10, brokenEvery = 5))

        outcome.batch.lineCount shouldBe 10
        // as linhas 5 e 10 (500 e 1000 centavos) foram rejeitadas
        outcome.batch.totalCents shouldBe Cents(4000)
        repository.entryStatuses(outcome.batch.id)[EntryStatus.REJECTED.name] shouldBe 2
    }

    "reprocessar o mesmo arquivo devolve o lote existente e nao duplica nada" {
        val content = cnabFile(20)

        val first = import(content)
        val second = import(content)

        second.shouldBeInstanceOf<ImportOutcome.AlreadyImported>()
        second.batch.id shouldBe first.batch.id
        repository.countEntries(first.batch.id) shouldBe 20
        Database.dsl.fetchCount(dev.wasdevv.tally.persistence.ImportBatches.TABLE) shouldBe 1
    }

    "arquivo com o mesmo conteudo e nome diferente e o mesmo arquivo" {
        val content = cnabFile(5)

        import(content, filename = "a.ret")
        val second = import(content, filename = "b.ret")

        second.shouldBeInstanceOf<ImportOutcome.AlreadyImported>()
        second.batch.filename shouldBe "a.ret"
    }

    // O criterio de aceite da semana 3 do brief, medido contra Postgres real.
    "N corrotinas na mesma carga escrevem M linhas uma vez so" {
        val content = cnabFile(50)
        val racers = 8

        val outcomes =
            withContext(Dispatchers.IO) {
                (1..racers).map { async { import(content) } }.awaitAll()
            }

        val imported = outcomes.filterIsInstance<ImportOutcome.Imported>()
        imported.size shouldBe 1
        outcomes.map { it.batch.id }.distinct().size shouldBe 1
        repository.countEntries(imported.single().batch.id) shouldBe 50
        Database.dsl.fetchCount(dev.wasdevv.tally.persistence.ImportBatches.TABLE) shouldBe 1
    }

    "o casamento acontece contra os recebiveis gravados" {
        repository.saveReceivables(listOf(receivable(ourNumber = "00000003", cents = 300)))

        // STRICT isola o que este exemplo mede: casamento pelo titulo. Sob a
        // politica DEFAULT a tolerancia de R$ 2,00 alcancaria as linhas
        // vizinhas, que e o assunto do exemplo seguinte.
        val outcome = import(cnabFile(5), policy = MatchingPolicy.STRICT)
        val statuses = repository.entryStatuses(outcome.batch.id)

        statuses[EntryStatus.MATCHED.name] shouldBe 1
        statuses[EntryStatus.UNMATCHED.name] shouldBe 4
    }

    // A regra que o projeto existe para defender: candidato achado so pelo VALOR
    // nunca casa sozinho. As linhas de 100 e 200 centavos caem dentro da
    // tolerancia de R$ 2,00 do recebivel de 300 -- e mesmo assim vao para a fila
    // humana, porque o titulo nao confere. Casar aqui seria palpite.
    "linha proxima no valor mas sem o titulo vai para revisao, nunca casa sozinha" {
        repository.saveReceivables(listOf(receivable(ourNumber = "00000003", cents = 300)))

        val outcome = import(cnabFile(5), policy = MatchingPolicy.DEFAULT)
        val statuses = repository.entryStatuses(outcome.batch.id)

        statuses[EntryStatus.MATCHED.name] shouldBe 1
        statuses[EntryStatus.NEEDS_REVIEW.name] shouldBe 2
        statuses[EntryStatus.UNMATCHED.name] shouldBe 2
        outcome.batch.lineCount shouldBe 5
    }

    "um recebivel nao casa com duas linhas do mesmo arquivo" {
        repository.saveReceivables(listOf(receivable(ourNumber = "00000001", cents = 100)))

        // duas linhas identicas: dois pagamentos legitimos, um recebivel so
        val content =
            SyntheticFiles.file(
                List(2) {
                    SyntheticFiles.detail(
                        ourNumber = "00000001",
                        amountCents = 100,
                        paidAt = LocalDate.of(2026, 3, 12),
                    )
                },
            )
        val outcome = import(content, policy = MatchingPolicy.STRICT)
        val statuses = repository.entryStatuses(outcome.batch.id)

        outcome.batch.lineCount shouldBe 2
        statuses[EntryStatus.MATCHED.name] shouldBe 1
        statuses[EntryStatus.UNMATCHED.name] shouldBe 1
    }

    "o razao guarda o codigo e os parametros da ocorrencia, sem texto de humano" {
        val outcome = import(cnabFile(2, brokenEvery = 1))

        val row =
            Database.dsl.fetch(
                "select occurrence_code, occurrence_params from ledger_entries " +
                    "where batch_id = ? order by line limit 1",
                outcome.batch.id,
            ).single()

        row.get(0, String::class.java) shouldBe "ROW_INVALID_DATE"
        row.get(1, String::class.java)!!.length shouldBeGreaterThan 2
    }

    "arquivo grande importa sem estourar o heap e mantem a cardinalidade" {
        val outcome = import(cnabFile(5_000))

        outcome.batch.lineCount shouldBe 5_000
        repository.countEntries(outcome.batch.id) shouldBe 5_000
    }
})
