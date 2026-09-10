package dev.wasdevv.tally.support

import java.time.LocalDate

/**
 * Gerador de arquivo CNAB 400 sintetico. Nenhum dado real, nem anonimizado --
 * este arquivo E a especificacao do layout que o motor aceita.
 *
 * O layout segue as posicoes do brief secao 6 (nosso numero 63..70, status
 * 109..110, data 111..116, valor 127..139). A unica divergencia deliberada e
 * bankCode: o brief o coloca em 1..3, que engole a posicao 1 do tipo de
 * registro; aqui ele fica em 2..4. Registrado em docs/DECISIONS.md.
 */
object SyntheticCnab400 {
    const val RECORD_LENGTH = 400

    private const val BANK_CODE = "341"
    private const val SETTLED = "06"

    fun detail(
        ourNumber: String = "00012938",
        amountCents: Long = 120400,
        paidAt: LocalDate = LocalDate.of(2026, 3, 12),
        counterparty: String = "Silva ME",
    ): String {
        val row = CharArray(RECORD_LENGTH) { ' ' }
        row.put(1, "1")
        row.put(2, BANK_CODE)
        row.put(63, ourNumber.padStart(8, '0').take(8))
        row.put(109, SETTLED)
        row.put(111, "%02d%02d%02d".format(paidAt.year % 100, paidAt.monthValue, paidAt.dayOfMonth))
        row.put(127, amountCents.toString().padStart(13, '0').takeLast(13))
        row.put(325, counterparty.padEnd(30, ' ').take(30))
        return String(row)
    }

    fun header(): String = String(CharArray(RECORD_LENGTH) { ' ' }.also { it.put(1, "0") })

    fun trailer(): String = String(CharArray(RECORD_LENGTH) { ' ' }.also { it.put(1, "9") })

    /** Header, os lancamentos, as linhas cruas extras e o trailer -- nessa ordem. */
    fun file(
        vararg details: String,
        extraRaw: List<String> = emptyList(),
    ): String = (listOf(header()) + details + extraRaw + listOf(trailer())).joinToString("\n")

    /** `at` e 1-based, como a especificacao. */
    private fun CharArray.put(
        at: Int,
        value: String,
    ) {
        value.forEachIndexed { i, c -> this[at - 1 + i] = c }
    }
}
