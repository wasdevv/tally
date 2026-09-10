package dev.wasdevv.tally.support

import dev.wasdevv.tally.domain.ledger.Entry
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId
import dev.wasdevv.tally.domain.money.Cents
import java.time.LocalDate

private val DAY: LocalDate = LocalDate.of(2026, 3, 14)

fun entry(
    line: Int = 1,
    ourNumber: String = "N1",
    amount: Cents = Cents(100),
    paidAt: LocalDate = DAY,
    counterparty: String = "Silva ME",
) = Entry(line, ourNumber, amount, paidAt, counterparty)

fun receivable(
    id: String = "r1",
    ourNumber: String = "N1",
    amount: Cents = Cents(100),
    dueDate: LocalDate = DAY,
    payer: String = "Silva ME",
) = Receivable(ReceivableId(id), ourNumber, amount, dueDate, payer)
