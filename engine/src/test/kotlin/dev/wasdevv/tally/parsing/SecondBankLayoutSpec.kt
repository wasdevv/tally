package dev.wasdevv.tally.parsing

import dev.wasdevv.tally.domain.ledger.OccurrenceCode
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.parsing.cnab.Cnab400
import dev.wasdevv.tally.parsing.layout.layout
import dev.wasdevv.tally.parsing.types.DateYYMMDD
import dev.wasdevv.tally.parsing.types.FixedDecimal
import dev.wasdevv.tally.parsing.types.Text
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * A prova da promessa da DSL: "layout de banco novo e DADO, nao codigo".
 *
 * Este banco ficticio usa posicoes diferentes E um discriminador de detalhe
 * diferente ("E" em vez de "1"), que e justamente o que quebrava antes: o
 * parser tinha `equalTo != "1"` chumbado, entao um banco assim exigia mudar
 * codigo. Agora quem diz qual registro vira lancamento e o proprio layout,
 * atraves de `detail` em vez de `record`.
 *
 * O layout abaixo tem 8 linhas de declaracao. Nenhuma linha de `parsing/` mudou
 * para ele existir -- e e por isso que este spec vive aqui e nao num README.
 */
class SecondBankLayoutSpec : StringSpec({

    val outroBanco =
        layout("banco-ficticio-200", recordLength = 200) {
            record(discriminator = 1..1, equalTo = "H")
            detail(discriminator = 1..1, equalTo = "E") {
                field("ourNumber", 10..19, Text)
                field("paidAt", 30..35, DateYYMMDD)
                field("amount", 50..61, FixedDecimal(places = 2))
                field("counterparty", 80..109, Text)
            }
            record(discriminator = 1..1, equalTo = "T")
        }

    fun row(
        kind: String,
        build: CharArray.() -> Unit = {},
    ) = String(
        CharArray(200) { ' ' }.also {
            it[0] = kind[0]
            it.build()
        },
    )

    fun put(
        row: CharArray,
        at: Int,
        value: String,
    ) = value.forEachIndexed { i, c -> row[at - 1 + i] = c }

    val detalhe =
        row("E") {
            put(this, 10, "0009988776")
            put(this, 30, "260318")
            put(this, 50, "000000450099")
            put(this, 80, "Aurora SA".padEnd(30))
        }

    val arquivo = listOf(row("H"), detalhe, row("T")).joinToString("\n")

    "o layout de outro banco le o arquivo dele sem uma linha de codigo novo" {
        val parsed = Cnab400.parse(arquivo, outroBanco)
        val entry = (parsed.lines.single() as ParsedLine.Valid).entry

        entry.ourNumber shouldBe "0009988776"
        entry.amount shouldBe Cents(450_099)
        entry.paidAt shouldBe LocalDate.of(2026, 3, 18)
        entry.counterparty shouldBe "Aurora SA"
    }

    "header e trailer com discriminador proprio continuam sendo estrutura" {
        val parsed = Cnab400.parse(arquivo, outroBanco)

        parsed.lines.size shouldBe 1
        parsed.structuralLines shouldBe listOf(1, 3)
    }

    "o comprimento do registro tambem vem do layout" {
        val curta = listOf(row("H"), "E".padEnd(150), row("T")).joinToString("\n")

        val rejeitada = (Cnab400.parse(curta, outroBanco).lines.single() as ParsedLine.Rejected)

        rejeitada.occurrence.code shouldBe OccurrenceCode.ROW_TOO_SHORT
        rejeitada.occurrence.params["length"] shouldBe "150"
    }

    // Layout que so descreve estrutura importaria todo arquivo "com sucesso" e
    // zero lancamento -- o silencio mais caro que este projeto existe para
    // impedir. Falha ao carregar, nao ao processar.
    "layout sem nenhum registro de lancamento falha ao carregar" {
        val erro =
            shouldThrow<IllegalArgumentException> {
                layout("so-estrutura", recordLength = 100) {
                    record(discriminator = 1..1, equalTo = "H")
                    record(discriminator = 1..1, equalTo = "T")
                }
            }

        erro.message!!.contains("detail") shouldBe true
    }

    "discriminador repetido falha ao carregar" {
        shouldThrow<IllegalArgumentException> {
            layout("ambiguo", recordLength = 100) {
                detail(discriminator = 1..1, equalTo = "E") { field("x", 2..5, Text) }
                record(discriminator = 1..1, equalTo = "E")
            }
        }
    }
})
