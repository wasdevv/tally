package dev.wasdevv.tally.ingestion

import dev.wasdevv.tally.domain.ledger.EntryStatus
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId
import dev.wasdevv.tally.domain.matching.MatchingPolicy
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.parsing.cnab.Cnab400
import dev.wasdevv.tally.persistence.Database
import dev.wasdevv.tally.persistence.LedgerRepository
import dev.wasdevv.tally.persistence.ReviewRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.asFlow
import java.time.LocalDate

/**
 * A fila de revisao humana: o que o motor recusou decidir sozinho.
 *
 * O que estes exemplos protegem e a conservacao ATRAVES da decisao: decidir
 * muda o destino de uma linha, nunca a cardinalidade nem a soma.
 */
class ReviewSpec : StringSpec({
    val repository = LedgerRepository(Database.dsl)
    val importer = BatchImporter(Database.dsl, repository)
    val reviews = ReviewRepository(Database.dsl)

    beforeTest { Database.clean() }

    fun receivable(
        id: String,
        ourNumber: String,
        cents: Long,
    ) = Receivable(
        id = ReceivableId(id),
        ourNumber = ourNumber,
        amount = Cents(cents),
        dueDate = LocalDate.of(2026, 3, 12),
        payer = "Sacado $id",
    )

    /** Dois recebiveis com o mesmo valor e titulo diferente: o motor nao desempata. */
    suspend fun importAmbiguous(): Long {
        repository.saveReceivables(
            listOf(
                receivable("r1", "AAAA0001", 500),
                receivable("r2", "BBBB0002", 500),
            ),
        )

        val content =
            SyntheticFiles.file(
                listOf(SyntheticFiles.detail(ourNumber = "99999999", amountCents = 500)),
            )

        return importer.import(
            ImportRequest(
                filename = "ambiguo.ret",
                layoutName = Cnab400.synthetic.name,
                digest = FileDigest.of(content.toByteArray()),
                lines = Cnab400.read(Cnab400.sourceLines(content)).asFlow(),
                policy = MatchingPolicy.STRICT,
            ),
        ).batch.id
    }

    "linha ambigua fica em revisao com os candidatos gravados" {
        val batchId = importAmbiguous()

        repository.entryStatuses(batchId)[EntryStatus.NEEDS_REVIEW.name] shouldBe 1

        val entryId =
            Database.dsl.fetchOne(
                "select id from ledger_entries where batch_id = ?",
                batchId,
            )!!.get(0, Long::class.java)

        reviews.candidatesFor(listOf(entryId))[entryId] shouldContainExactly listOf("r1", "r2")
    }

    "escolher um candidato move a linha para conciliada" {
        val batchId = importAmbiguous()

        reviews.decide(batchId, line = 2, receivableId = "r2") shouldBe
            ReviewRepository.Decision.Recorded

        val row =
            Database.dsl.fetchOne(
                "select status, matched_receivable_id, decided_at from ledger_entries where batch_id = ?",
                batchId,
            )!!

        row.get(0, String::class.java) shouldBe EntryStatus.MATCHED.name
        row.get(1, String::class.java) shouldBe "r2"
        (row.get(2) != null) shouldBe true
    }

    // "Nenhum destes" NAO apaga a linha: ela continua no razao como pendencia.
    // Sumir com ela faria a conservacao deixar de fechar contra o arquivo.
    "recusar todos os candidatos deixa a linha sem par, nao a remove" {
        val batchId = importAmbiguous()

        reviews.decide(batchId, line = 2, receivableId = null) shouldBe
            ReviewRepository.Decision.Recorded

        repository.countEntries(batchId) shouldBe 1
        repository.entryStatuses(batchId)[EntryStatus.UNMATCHED.name] shouldBe 1
    }

    "a decisao nao muda a cardinalidade nem a soma do lote" {
        val batchId = importAmbiguous()
        val antes =
            Database.dsl.fetchOne(
                "select count(*), coalesce(sum(amount_cents), 0) from ledger_entries where batch_id = ?",
                batchId,
            )!!

        reviews.decide(batchId, line = 2, receivableId = "r1")

        val depois =
            Database.dsl.fetchOne(
                "select count(*), coalesce(sum(amount_cents), 0) from ledger_entries where batch_id = ?",
                batchId,
            )!!

        depois.get(0) shouldBe antes.get(0)
        depois.get(1) shouldBe antes.get(1)
    }

    // Duas abas do navegador decidindo a mesma linha: a segunda tem que perder.
    "decidir duas vezes a mesma linha e conflito, nao sobrescrita" {
        val batchId = importAmbiguous()

        reviews.decide(batchId, line = 2, receivableId = "r1") shouldBe
            ReviewRepository.Decision.Recorded
        reviews.decide(batchId, line = 2, receivableId = "r2") shouldBe
            ReviewRepository.Decision.NotUnderReview

        Database.dsl.fetchOne(
            "select matched_receivable_id from ledger_entries where batch_id = ?", batchId,
        )!!.get(0, String::class.java) shouldBe "r1"
    }

    "decidir linha que o motor ja resolveu sozinho e recusado" {
        repository.saveReceivables(listOf(receivable("r9", "00000001", 100)))
        val content =
            SyntheticFiles.file(
                listOf(SyntheticFiles.detail(ourNumber = "00000001", amountCents = 100)),
            )
        val batchId =
            importer.import(
                ImportRequest(
                    filename = "casado.ret",
                    layoutName = Cnab400.synthetic.name,
                    digest = FileDigest.of(content.toByteArray()),
                    lines = Cnab400.read(Cnab400.sourceLines(content)).asFlow(),
                    policy = MatchingPolicy.STRICT,
                ),
            ).batch.id

        reviews.decide(batchId, line = 2, receivableId = null) shouldBe
            ReviewRepository.Decision.NotUnderReview
    }

    "recebivel inexistente e recusado antes de tocar o razao" {
        val batchId = importAmbiguous()

        reviews.decide(batchId, line = 2, receivableId = "nao-existe") shouldBe
            ReviewRepository.Decision.UnknownReceivable
        repository.entryStatuses(batchId)[EntryStatus.NEEDS_REVIEW.name] shouldBe 1
    }

    "linha inexistente devolve NotFound" {
        val batchId = importAmbiguous()

        reviews.decide(batchId, line = 999, receivableId = "r1") shouldBe
            ReviewRepository.Decision.NotFound
    }
})
