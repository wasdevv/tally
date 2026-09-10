package dev.wasdevv.tally.parsing

import dev.wasdevv.tally.domain.ledger.OccurrenceCode
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.parsing.types.BrazilianDecimal
import dev.wasdevv.tally.parsing.types.DateYYMMDD
import dev.wasdevv.tally.parsing.types.FixedDecimal
import dev.wasdevv.tally.parsing.types.ParseOutcome
import dev.wasdevv.tally.parsing.types.PlainDecimal
import dev.wasdevv.tally.parsing.types.Text
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate
import java.util.Locale

class FieldTypeSpec : StringSpec({

    fun <T> ParseOutcome<T>.value(): T = (this as ParseOutcome.Ok<T>).value

    fun <T> ParseOutcome<T>.code(): OccurrenceCode = (this as ParseOutcome.Failed).code

    "texto vem sem o preenchimento do registro" {
        Text.parse("  Silva ME          ").value() shouldBe "Silva ME"
    }

    "decimal implicito de duas casas ja e o valor em centavos" {
        FixedDecimal(places = 2).parse("000000123456").value() shouldBe Cents(123456)
    }

    "decimal implicito preenchido com zeros vale zero, nao erro" {
        FixedDecimal(places = 2).parse("0000000000000").value() shouldBe Cents.ZERO
    }

    "decimal com tres casas escala para centavos quando a casa extra e zero" {
        FixedDecimal(places = 3).parse("0001234560").value() shouldBe Cents(123456)
    }

    // Truncar 1234561 milesimos para 123456 centavos perderia um decimo de
    // centavo em silencio. Em dinheiro isso e rejeicao, nao arredondamento.
    "decimal com casa extra nao nula e rejeitado em vez de arredondado" {
        FixedDecimal(places = 3).parse("0001234561").code() shouldBe
            OccurrenceCode.ROW_INVALID_AMOUNT
    }

    "decimal com caractere nao numerico vira ocorrencia" {
        FixedDecimal(places = 2).parse("00000012E456").code() shouldBe
            OccurrenceCode.ROW_INVALID_AMOUNT
    }

    "decimal em branco vira ocorrencia de campo faltando, nao zero" {
        FixedDecimal(places = 2).parse("            ").code() shouldBe
            OccurrenceCode.ROW_MISSING_FIELD
    }

    "valor maior que Long vira ocorrencia em vez de dar a volta" {
        FixedDecimal(places = 2).parse("9".repeat(25)).code() shouldBe
            OccurrenceCode.ROW_AMOUNT_OUT_OF_RANGE
    }

    "decimal brasileiro le milhar com ponto e centavo com virgula" {
        BrazilianDecimal.parse("1.234,56").value() shouldBe Cents(123456)
    }

    "decimal brasileiro negativo mantem o sinal" {
        BrazilianDecimal.parse("-1.234,56").value() shouldBe Cents(-123456)
    }

    "decimal americano le milhar com virgula e centavo com ponto" {
        PlainDecimal.parse("1,234.56").value() shouldBe Cents(123456)
    }

    // A decisao de i18n mais afiada do projeto (brief secao 9.4): o separador
    // decimal e propriedade do LAYOUT, nunca do locale de quem opera. Se
    // dependesse do locale, o mesmo arquivo importaria diferente para dois
    // analistas -- e um deles receberia dinheiro no lugar errado.
    "parsing de decimal nao depende do locale da JVM" {
        val original = Locale.getDefault()
        try {
            listOf(Locale.US, Locale.forLanguageTag("pt-BR"), Locale.GERMANY).forEach { locale ->
                Locale.setDefault(locale)
                FixedDecimal(places = 2).parse("000000123456").value() shouldBe Cents(123456)
                BrazilianDecimal.parse("1.234,56").value() shouldBe Cents(123456)
                PlainDecimal.parse("1,234.56").value() shouldBe Cents(123456)
                DateYYMMDD.parse("260314").value() shouldBe LocalDate.of(2026, 3, 14)
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    "data YYMMDD vira data do seculo corrente" {
        DateYYMMDD.parse("260314").value() shouldBe LocalDate.of(2026, 3, 14)
    }

    "data impossivel vira ocorrencia com o valor cru" {
        val outcome = DateYYMMDD.parse("000000")
        outcome.shouldBeInstanceOf<ParseOutcome.Failed>()
        outcome.code shouldBe OccurrenceCode.ROW_INVALID_DATE
        outcome.params["raw"] shouldBe "000000"
    }

    "trinta de fevereiro nao vira primeiro de marco" {
        DateYYMMDD.parse("260230").code() shouldBe OccurrenceCode.ROW_INVALID_DATE
    }
})
