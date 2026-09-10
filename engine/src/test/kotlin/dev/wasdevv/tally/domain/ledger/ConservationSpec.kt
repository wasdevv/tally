package dev.wasdevv.tally.domain.ledger

import dev.wasdevv.tally.domain.matching.MatchingPolicy
import dev.wasdevv.tally.domain.matching.Reconciler
import dev.wasdevv.tally.support.arbReturnFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll

/**
 * A regra central do sistema inteiro (brief secao 5.3), em quantidade E em
 * centavos. Se este arquivo ficar vermelho, nada mais no projeto importa.
 */
class ConservationSpec : StringSpec({
    val policies = listOf(MatchingPolicy.STRICT, MatchingPolicy.DEFAULT)

    "nenhuma linha entra ou sai da conciliacao" {
        checkAll(1000, arbReturnFile()) { file ->
            val result = Reconciler.run(file.lines, file.receivables, MatchingPolicy.DEFAULT)

            result.lineCount shouldBe file.lines.size
        }
    }

    "nenhum centavo entra ou sai da conciliacao" {
        checkAll(1000, arbReturnFile()) { file ->
            val result = Reconciler.run(file.lines, file.receivables, MatchingPolicy.DEFAULT)

            val reconciled =
                result.matched.map { it.entry }.sumCents() +
                    result.needsReview.map { it.entry }.sumCents() +
                    result.unmatched.sumCents()

            reconciled shouldBe
                file.lines.filterIsInstance<ParsedLine.Valid>()
                    .map { it.entry }
                    .sumCents()
        }
    }

    "cada linha fisica aparece em exatamente um destino" {
        checkAll(200, arbReturnFile()) { file ->
            val result = Reconciler.run(file.lines, file.receivables, MatchingPolicy.DEFAULT)

            val destinos =
                result.matched.map { it.entry.line } +
                    result.needsReview.map { it.entry.line } +
                    result.unmatched.map { it.line } +
                    result.rejected.map { it.line }

            destinos.sorted() shouldBe file.lines.map { it.line }.sorted()
        }
    }

    "linha rejeitada entra no razao com linha e codigo, nunca e descartada" {
        checkAll(200, arbReturnFile()) { file ->
            val rejeitadas = file.lines.filterIsInstance<ParsedLine.Rejected>()
            val result = Reconciler.run(file.lines, file.receivables, MatchingPolicy.DEFAULT)

            result.rejected.map { it.line } shouldBe rejeitadas.map { it.line }
            result.rejected.filter { it.code !in OccurrenceCode.entries }.shouldBeEmpty()
        }
    }

    "um recebivel nunca casa com duas linhas" {
        checkAll(200, arbReturnFile()) { file ->
            val result = Reconciler.run(file.lines, file.receivables, MatchingPolicy.DEFAULT)
            val usados = result.matched.map { it.receivable.id }

            usados.distinct().size shouldBe usados.size
        }
    }

    "a ordem dos recebiveis na entrada nao altera o razao" {
        checkAll(200, arbReturnFile()) { file ->
            val direto = Reconciler.run(file.lines, file.receivables, MatchingPolicy.DEFAULT)
            val invertido = Reconciler.run(file.lines, file.receivables.reversed(), MatchingPolicy.DEFAULT)

            invertido shouldBe direto
        }
    }

    "a conservacao vale sob qualquer politica de tolerancia" {
        policies.forEach { policy ->
            checkAll(200, arbReturnFile()) { file ->
                val result = Reconciler.run(file.lines, file.receivables, policy)

                result.lineCount shouldBe file.lines.size
            }
        }
    }
})
