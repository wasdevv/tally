package dev.wasdevv.tally.parsing

import dev.wasdevv.tally.domain.ledger.OccurrenceCode
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.parsing.csv.CsvLayout
import dev.wasdevv.tally.parsing.csv.CsvParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.util.Locale

class CsvParserSpec : StringSpec({

    fun entries(parsed: ParsedFile) = parsed.lines.filterIsInstance<ParsedLine.Valid>().map { it.entry }

    "csv brasileiro le virgula decimal e ponto de milhar" {
        val csv = """
            our_number;amount;paid_at;counterparty
            00012938;1.234,56;2026-03-14;Silva ME
        """.trimIndent()

        entries(CsvParser.parse(csv, CsvLayout.BRAZILIAN)).single().amount shouldBe Cents(123456)
    }

    "csv americano le ponto decimal e virgula de milhar" {
        val csv = """
            our_number,amount,paid_at,counterparty
            00012938,"1,234.56",2026-03-14,Silva ME
        """.trimIndent()

        entries(CsvParser.parse(csv, CsvLayout.AMERICAN)).single().amount shouldBe Cents(123456)
    }

    // O mesmo dinheiro escrito nas duas convencoes tem que virar os mesmos
    // centavos. Se o parsing herdasse o locale da interface, o mesmo arquivo
    // importaria diferente para dois analistas.
    "o mesmo valor nas duas convencoes vira os mesmos centavos sob qualquer locale" {
        val original = Locale.getDefault()
        try {
            listOf(Locale.US, Locale.forLanguageTag("pt-BR"), Locale.GERMANY).forEach { locale ->
                Locale.setDefault(locale)

                val br = CsvParser.parse(
                    "our_number;amount;paid_at;counterparty\n1;1.234,56;2026-03-14;X",
                    CsvLayout.BRAZILIAN,
                )
                val us = CsvParser.parse(
                    "our_number,amount,paid_at,counterparty\n1,\"1,234.56\",2026-03-14,X",
                    CsvLayout.AMERICAN,
                )

                entries(br).single().amount shouldBe entries(us).single().amount
                entries(br).single().amount shouldBe Cents(123456)
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    // split(",") quebraria aqui. Por isso o parser e um parser de CSV de verdade.
    "delimitador dentro de aspas pertence ao campo, nao separa coluna" {
        val csv = "our_number,amount,paid_at,counterparty\n1,10.00,2026-03-14,\"Silva, Souza & Cia\""

        entries(CsvParser.parse(csv, CsvLayout.AMERICAN)).single().counterparty shouldBe
            "Silva, Souza & Cia"
    }

    "quebra de linha dentro de aspas nao vira duas linhas" {
        val csv = "our_number,amount,paid_at,counterparty\n1,10.00,2026-03-14,\"Silva\nME\""
        val parsed = CsvParser.parse(csv, CsvLayout.AMERICAN)

        parsed.lines.size shouldBe 1
        entries(parsed).single().counterparty shouldBe "Silva\nME"
    }

    "BOM no inicio nao vira parte do nome da primeira coluna" {
        val csv = "﻿our_number,amount,paid_at,counterparty\n1,10.00,2026-03-14,X"

        entries(CsvParser.parse(csv, CsvLayout.AMERICAN)).single().ourNumber shouldBe "1"
    }

    "CRLF le igual a LF" {
        val lf = "our_number,amount,paid_at,counterparty\n1,10.00,2026-03-14,X"

        CsvParser.parse(lf.replace("\n", "\r\n"), CsvLayout.AMERICAN).lines shouldBe
            CsvParser.parse(lf, CsvLayout.AMERICAN).lines
    }

    "coluna obrigatoria ausente falha o arquivo inteiro, nao linha a linha" {
        val csv = "our_number,paid_at,counterparty\n1,2026-03-14,X"

        shouldThrow<IllegalArgumentException> { CsvParser.parse(csv, CsvLayout.AMERICAN) }
    }

    "arquivo so com cabecalho importa zero linhas sem erro" {
        val parsed = CsvParser.parse("our_number,amount,paid_at,counterparty", CsvLayout.AMERICAN)

        parsed.lines.shouldBeEmpty()
        parsed.accountedLines shouldBe 1
    }

    "valor invalido rejeita a linha e as demais continuam" {
        val csv = """
            our_number,amount,paid_at,counterparty
            1,10.00,2026-03-14,A
            2,dez reais,2026-03-14,B
            3,30.00,2026-03-14,C
        """.trimIndent()

        val parsed = CsvParser.parse(csv, CsvLayout.AMERICAN)
        val rejected = parsed.lines.filterIsInstance<ParsedLine.Rejected>().single()

        entries(parsed).size shouldBe 2
        rejected.occurrence.code shouldBe OccurrenceCode.ROW_INVALID_AMOUNT
        rejected.occurrence.line shouldBe 3
    }

    "campo obrigatorio em branco vira ocorrencia, nao valor zero" {
        val csv = "our_number,amount,paid_at,counterparty\n1,,2026-03-14,A"
        val parsed = CsvParser.parse(csv, CsvLayout.AMERICAN)

        parsed.lines.filterIsInstance<ParsedLine.Rejected>().single()
            .occurrence.code shouldBe OccurrenceCode.ROW_MISSING_FIELD
    }

    "data invalida vira ocorrencia com o valor cru" {
        val csv = "our_number,amount,paid_at,counterparty\n1,10.00,2026-02-30,A"
        val parsed = CsvParser.parse(csv, CsvLayout.AMERICAN)

        val occ = parsed.lines.filterIsInstance<ParsedLine.Rejected>().single().occurrence
        occ.code shouldBe OccurrenceCode.ROW_INVALID_DATE
        occ.params["raw"] shouldBe "2026-02-30"
    }

    "estorno negativo e importado como valor negativo" {
        val csv = "our_number,amount,paid_at,counterparty\n1,-45.00,2026-03-14,A"

        entries(CsvParser.parse(csv, CsvLayout.AMERICAN)).single().amount shouldBe Cents(-4500)
    }

    "data e lida do arquivo, nao do relogio" {
        val csv = "our_number,amount,paid_at,counterparty\n1,10.00,2026-03-14,A"

        entries(CsvParser.parse(csv, CsvLayout.AMERICAN)).single().paidAt shouldBe
            LocalDate.of(2026, 3, 14)
    }
})
