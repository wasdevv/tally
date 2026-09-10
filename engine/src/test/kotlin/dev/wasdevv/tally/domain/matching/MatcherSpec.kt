package dev.wasdevv.tally.domain.matching

import dev.wasdevv.tally.domain.ledger.MatchOutcome
import dev.wasdevv.tally.domain.ledger.MatchReason
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.support.entry
import dev.wasdevv.tally.support.receivable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

class MatcherSpec : StringSpec({
    val strict = MatchingPolicy.STRICT
    val lenient = MatchingPolicy(amountTolerance = Cents(50), dateToleranceDays = 2)

    "nosso numero e valor iguais casam automaticamente" {
        val r = receivable(id = "r1", ourNumber = "00012938471", amount = Cents(120400))
        val outcome =
            Matcher.match(
                entry(ourNumber = "00012938471", amount = Cents(120400)),
                listOf(r),
                strict,
            )

        outcome.shouldBeInstanceOf<MatchOutcome.Matched>()
        outcome.receivable shouldBe r
        outcome.reason shouldBe MatchReason.EXACT
    }

    "diferenca dentro da tolerancia casa e registra o motivo" {
        val r = receivable(id = "r1", ourNumber = "N1", amount = Cents(120400))
        val outcome =
            Matcher.match(
                entry(ourNumber = "N1", amount = Cents(120370)),
                listOf(r),
                lenient,
            )

        outcome.shouldBeInstanceOf<MatchOutcome.Matched>()
        outcome.reason shouldBe MatchReason.WITHIN_TOLERANCE
    }

    "tolerancia zero nao casa diferenca de um centavo" {
        val outcome =
            Matcher.match(
                entry(ourNumber = "N1", amount = Cents(120399)),
                listOf(receivable(id = "r1", ourNumber = "N1", amount = Cents(120400))),
                strict,
            )

        outcome.shouldBeInstanceOf<MatchOutcome.NeedsReview>()
        outcome.reason shouldBe MatchReason.AMOUNT_MISMATCH
    }

    "titulo encontrado mas valor fora da tolerancia vai para revisao com o candidato" {
        val r = receivable(id = "r1", ourNumber = "N1", amount = Cents(120400))
        val outcome =
            Matcher.match(
                entry(ourNumber = "N1", amount = Cents(1)),
                listOf(r),
                lenient,
            )

        outcome.shouldBeInstanceOf<MatchOutcome.NeedsReview>()
        outcome.candidates shouldContainExactly listOf(r)
        outcome.reason shouldBe MatchReason.AMOUNT_MISMATCH
    }

    // O motor nunca adivinha: casar so por valor e um palpite, nao uma conciliacao.
    "candidato encontrado so pelo valor vai para revisao em vez de casar" {
        val r = receivable(id = "r1", ourNumber = "OUTRO", amount = Cents(120400))
        val outcome =
            Matcher.match(
                entry(ourNumber = "N1", amount = Cents(120400)),
                listOf(r),
                strict,
            )

        outcome.shouldBeInstanceOf<MatchOutcome.NeedsReview>()
        outcome.reason shouldBe MatchReason.AMOUNT_ONLY
    }

    "dois candidatos equivalentes nunca elegem um vencedor" {
        val a = receivable(id = "r1", ourNumber = "X", amount = Cents(500))
        val b = receivable(id = "r2", ourNumber = "Y", amount = Cents(500))
        val outcome = Matcher.match(entry(ourNumber = "N1", amount = Cents(500)), listOf(a, b), strict)

        outcome.shouldBeInstanceOf<MatchOutcome.NeedsReview>()
        outcome.reason shouldBe MatchReason.AMBIGUOUS
        outcome.candidates shouldContainExactly listOf(a, b)
    }

    "sem nenhum candidato a linha fica sem par, nao rejeitada" {
        Matcher.match(entry(amount = Cents(999)), emptyList(), strict) shouldBe MatchOutcome.Unmatched
    }

    "a ordem dos candidatos nao altera o resultado" {
        val a = receivable(id = "r1", ourNumber = "X", amount = Cents(500))
        val b = receivable(id = "r2", ourNumber = "Y", amount = Cents(500))
        val e = entry(ourNumber = "N1", amount = Cents(500))

        Matcher.match(e, listOf(a, b), strict) shouldBe Matcher.match(e, listOf(b, a), strict)
    }

    "data fora da tolerancia impede o casamento por valor" {
        val r =
            receivable(
                id = "r1",
                ourNumber = "N1",
                amount = Cents(120400),
                dueDate = LocalDate.of(2026, 3, 1),
            )
        val outcome =
            Matcher.match(
                entry(ourNumber = "N1", amount = Cents(120390), paidAt = LocalDate.of(2026, 3, 30)),
                listOf(r),
                lenient,
            )

        outcome.shouldBeInstanceOf<MatchOutcome.NeedsReview>()
        outcome.reason shouldBe MatchReason.AMOUNT_MISMATCH
    }

    // Valor negativo (estorno) e zero sao lancamentos legitimos: nao podem
    // sumir por truthiness nem por checagem de sinal.
    "estorno negativo casa como qualquer outro valor" {
        val r = receivable(id = "r1", ourNumber = "N1", amount = Cents(-4500))
        val outcome = Matcher.match(entry(ourNumber = "N1", amount = Cents(-4500)), listOf(r), strict)

        outcome.shouldBeInstanceOf<MatchOutcome.Matched>()
        outcome.reason shouldBe MatchReason.EXACT
    }

    "lancamento de valor zero casa e nao e descartado" {
        val r = receivable(id = "r1", ourNumber = "N1", amount = Cents.ZERO)
        val outcome = Matcher.match(entry(ourNumber = "N1", amount = Cents.ZERO), listOf(r), strict)

        outcome.shouldBeInstanceOf<MatchOutcome.Matched>()
    }

    "nosso numero em branco nao casa com recebivel de nosso numero em branco" {
        val r = receivable(id = "r1", ourNumber = "", amount = Cents(700))
        val outcome = Matcher.match(entry(ourNumber = "  ", amount = Cents(700)), listOf(r), strict)

        outcome.shouldBeInstanceOf<MatchOutcome.NeedsReview>()
        outcome.reason shouldBe MatchReason.AMOUNT_ONLY
    }
})
