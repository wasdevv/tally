package dev.wasdevv.tally.domain.money

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CentsSpec : StringSpec({
    "soma de centavos nao perde precisao em valores que quebram em ponto flutuante" {
        val total = (1..10).fold(Cents.ZERO) { acc, _ -> acc + Cents(10) }
        total shouldBe Cents(100)
    }

    "subtracao devolve centavos negativos em vez de saturar em zero" {
        Cents(100) - Cents(250) shouldBe Cents(-150)
    }

    "ordenacao segue o valor inteiro, inclusive entre negativos" {
        listOf(Cents(5), Cents(-300), Cents(0)).sorted() shouldBe
            listOf(Cents(-300), Cents(0), Cents(5))
    }

    "abs de valor negativo devolve o positivo" {
        Cents(-4207).abs() shouldBe Cents(4207)
    }

    // Wraparound silencioso em dinheiro e a falha que o razao nunca pode ter:
    // somar dois valores enormes daria um total NEGATIVO e a conservacao
    // "fecharia" em cima de um numero errado. Estourar e a resposta correta.
    "soma que estoura Long falha em vez de dar a volta" {
        shouldThrow<ArithmeticException> { Cents(Long.MAX_VALUE) + Cents(1) }
    }

    "subtracao que estoura Long falha em vez de dar a volta" {
        shouldThrow<ArithmeticException> { Cents(Long.MIN_VALUE) - Cents(1) }
    }

    // kotlin.math.abs(Long.MIN_VALUE) devolve Long.MIN_VALUE -- negativo.
    "abs de Long.MIN_VALUE falha em vez de devolver um negativo" {
        shouldThrow<ArithmeticException> { Cents(Long.MIN_VALUE).abs() }
    }
})
