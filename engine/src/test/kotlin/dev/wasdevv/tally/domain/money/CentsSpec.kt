package dev.wasdevv.tally.domain.money

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CentsSpec : StringSpec({
    "soma de centavos nao perde precisao em valores que quebram em ponto flutuante" {
        val total = (1..10).fold(Cents.ZERO) { acc, _ -> acc + Cents(10) }
        total shouldBe Cents(100)
    }
})
