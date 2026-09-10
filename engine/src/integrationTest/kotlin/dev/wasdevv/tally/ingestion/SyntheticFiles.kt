package dev.wasdevv.tally.ingestion

import java.time.LocalDate

/** O mesmo gerador sintetico da suite rapida, disponivel para a suite lenta. */
object SyntheticFiles {
    private const val RECORD_LENGTH = 400

    fun detail(
        ourNumber: String = "00012938",
        amountCents: Long = 120400,
        paidAt: LocalDate = LocalDate.of(2026, 3, 12),
        counterparty: String = "Silva ME",
    ): String {
        val row = CharArray(RECORD_LENGTH) { ' ' }
        row.put(1, "1")
        row.put(2, "341")
        row.put(63, ourNumber.padStart(8, '0').take(8))
        row.put(109, "06")
        row.put(111, "%02d%02d%02d".format(paidAt.year % 100, paidAt.monthValue, paidAt.dayOfMonth))
        row.put(127, amountCents.toString().padStart(13, '0').takeLast(13))
        row.put(325, counterparty.padEnd(30, ' ').take(30))
        return String(row)
    }

    fun file(details: List<String>): String =
        (listOf(structural("0")) + details + listOf(structural("9"))).joinToString(
            "\n",
        )

    private fun structural(kind: String) = String(CharArray(RECORD_LENGTH) { ' ' }.also { it.put(1, kind) })

    private fun CharArray.put(
        at: Int,
        value: String,
    ) {
        value.forEachIndexed { i, c -> this[at - 1 + i] = c }
    }
}
