package dev.wasdevv.tally.api

import dev.wasdevv.tally.api.dto.ReceivableInput
import dev.wasdevv.tally.domain.ledger.Receivable
import dev.wasdevv.tally.domain.ledger.ReceivableId
import dev.wasdevv.tally.domain.money.Cents
import dev.wasdevv.tally.persistence.LedgerRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/receivables")
class ReceivableController(private val repository: LedgerRepository) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody inputs: List<ReceivableInput>,
    ): Map<String, Int> {
        repository.saveReceivables(
            inputs.map {
                Receivable(
                    id = ReceivableId(it.id),
                    ourNumber = it.ourNumber,
                    amount = Cents(it.amountCents),
                    dueDate = it.dueDate,
                    payer = it.payer,
                )
            },
        )
        return mapOf("saved" to inputs.size)
    }

    @GetMapping
    fun list(): List<ReceivableInput> =
        repository.loadReceivables().map {
            ReceivableInput(
                id = it.id.value,
                ourNumber = it.ourNumber,
                amountCents = it.amount.value,
                dueDate = it.dueDate,
                payer = it.payer,
            )
        }
}
