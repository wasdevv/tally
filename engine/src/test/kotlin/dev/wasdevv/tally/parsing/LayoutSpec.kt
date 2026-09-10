package dev.wasdevv.tally.parsing

import dev.wasdevv.tally.parsing.layout.layout
import dev.wasdevv.tally.parsing.types.Text
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Layout novo e DADO, nao codigo. O preco disso e que o layout tem que falhar
 * ao CARREGAR, nao ao processar o arquivo do cliente as tres da manha.
 */
class LayoutSpec : StringSpec({

    "campo alem do comprimento do registro falha ao carregar o layout" {
        val erro =
            shouldThrow<IllegalArgumentException> {
                layout("quebrado", recordLength = 400) {
                    detail(discriminator = 1..1, equalTo = "1") {
                        field("amount", 395..405, Text)
                    }
                }
            }

        erro.message.orEmpty() shouldContain "395..405"
    }

    "intervalo invertido falha ao carregar o layout" {
        shouldThrow<IllegalArgumentException> {
            layout("quebrado", recordLength = 400) {
                detail(discriminator = 1..1, equalTo = "1") {
                    field("amount", IntRange(139, 127), Text)
                }
            }
        }
    }

    "posicao zero falha: o layout e 1-based como a especificacao FEBRABAN" {
        shouldThrow<IllegalArgumentException> {
            layout("quebrado", recordLength = 400) {
                detail(discriminator = 1..1, equalTo = "1") {
                    field("bankCode", 0..3, Text)
                }
            }
        }
    }

    "discriminador alem do registro falha ao carregar" {
        shouldThrow<IllegalArgumentException> {
            layout("quebrado", recordLength = 10) {
                detail(discriminator = 40..40, equalTo = "1") {
                    field("x", 1..2, Text)
                }
            }
        }
    }

    "nome de campo repetido no mesmo registro falha ao carregar" {
        shouldThrow<IllegalArgumentException> {
            layout("quebrado", recordLength = 400) {
                detail(discriminator = 1..1, equalTo = "1") {
                    field("amount", 1..10, Text)
                    field("amount", 11..20, Text)
                }
            }
        }
    }

    "layout valido carrega e conhece seus registros" {
        val l =
            layout("ok", recordLength = 40) {
                detail(discriminator = 1..1, equalTo = "1") {
                    field("ourNumber", 2..9, Text)
                }
                record(discriminator = 1..1, equalTo = "9")
            }

        l.name shouldBe "ok"
        l.records.size shouldBe 2
    }
})
