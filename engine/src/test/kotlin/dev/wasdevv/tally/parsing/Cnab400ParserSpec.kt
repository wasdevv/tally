package dev.wasdevv.tally.parsing

import dev.wasdevv.tally.domain.ledger.OccurrenceCode
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.parsing.cnab.Cnab400
import dev.wasdevv.tally.support.SyntheticCnab400
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class Cnab400ParserSpec : StringSpec({

    "registro de lancamento vira Entry com valor, data e nosso numero" {
        val file =
            SyntheticCnab400.file(
                SyntheticCnab400.detail(
                    ourNumber = "00012938",
                    amountCents = 120400,
                    paidAt = LocalDate.of(2026, 3, 12),
                    counterparty = "Silva ME",
                ),
            )

        val parsed = Cnab400.parse(file)
        val entry = (parsed.lines.single() as ParsedLine.Valid).entry

        entry.ourNumber shouldBe "00012938"
        entry.amount shouldBe Cents(120400)
        entry.paidAt shouldBe LocalDate.of(2026, 3, 12)
        entry.counterparty shouldBe "Silva ME"
        entry.line shouldBe 2
    }

    "header e trailer nao viram lancamento, mas continuam contabilizados" {
        val parsed = Cnab400.parse(SyntheticCnab400.file(SyntheticCnab400.detail()))

        parsed.lines.size shouldBe 1
        parsed.structuralLines shouldContainExactly listOf(1, 3)
        parsed.accountedLines shouldBe 3
    }

    "linha curta e rejeitada com o numero da linha e o comprimento lido" {
        val parsed =
            Cnab400.parse(
                SyntheticCnab400.file(SyntheticCnab400.detail(), extraRaw = listOf("1" + "X".repeat(20))),
            )

        val rejected = (parsed.lines.last() as ParsedLine.Rejected).occurrence
        rejected.code shouldBe OccurrenceCode.ROW_TOO_SHORT
        rejected.line shouldBe 3
        rejected.params["length"] shouldBe "21"
    }

    "tipo de registro desconhecido e rejeitado, nao ignorado em silencio" {
        val parsed =
            Cnab400.parse(
                SyntheticCnab400.file(extraRaw = listOf("7".padEnd(400, ' '))),
            )

        val rejected = (parsed.lines.single() as ParsedLine.Rejected).occurrence
        rejected.code shouldBe OccurrenceCode.ROW_UNKNOWN_RECORD_TYPE
    }

    "data invalida rejeita a linha e as demais continuam importando" {
        val parsed =
            Cnab400.parse(
                SyntheticCnab400.file(
                    SyntheticCnab400.detail(ourNumber = "00000001"),
                    SyntheticCnab400.detail(ourNumber = "00000002").replaceRange(110, 116, "000000"),
                    SyntheticCnab400.detail(ourNumber = "00000003"),
                ),
            )

        parsed.lines.count { it is ParsedLine.Valid } shouldBe 2
        val rejected = parsed.lines.filterIsInstance<ParsedLine.Rejected>().single()
        rejected.occurrence.code shouldBe OccurrenceCode.ROW_INVALID_DATE
        rejected.occurrence.line shouldBe 3
    }

    // O criterio de aceite da semana 2 do brief, medido.
    "arquivo com 10 por cento de linhas quebradas importa os 90 e reporta os 10" {
        val details =
            (1..100).map { i ->
                val row = SyntheticCnab400.detail(ourNumber = "%08d".format(i))
                if (i % 10 == 0) row.replaceRange(110, 116, "999999") else row
            }

        val parsed = Cnab400.parse(SyntheticCnab400.file(*details.toTypedArray()))

        parsed.lines.count { it is ParsedLine.Valid } shouldBe 90
        parsed.lines.count { it is ParsedLine.Rejected } shouldBe 10
        parsed.lines.size shouldBe 100
    }

    "CRLF, BOM e linha vazia final nao mudam o razao" {
        val comLf = Cnab400.parse(SyntheticCnab400.file(SyntheticCnab400.detail()))
        val comCrLf =
            Cnab400.parse(
                "" +
                    SyntheticCnab400.file(SyntheticCnab400.detail())
                        .replace("\n", "\r\n") + "\r\n",
            )

        comCrLf.lines shouldBe comLf.lines
        comCrLf.accountedLines shouldBe comLf.accountedLines
    }
})
