package dev.wasdevv.tally.api.dto

import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * A fronteira. O dominio nao recebe annotation de Jackson -- estes DTOs fazem a
 * conversao, e por isso `domain` continua compilando sem framework nenhum.
 *
 * Centavos saem como INTEIRO. O motor nunca produz "R$ 1.234,56": formatar e
 * trabalho da borda, e a borda e o Rails.
 */
data class BatchSummary(
    val id: Long,
    val filename: String,
    val layoutName: String,
    val lineCount: Int,
    val totalCents: Long,
    val importedAt: OffsetDateTime,
    val counts: Map<String, Int>,
    val unreconciledCents: Long,
)

data class LedgerEntryView(
    val id: Long,
    val line: Int,
    val status: String,
    val ourNumber: String?,
    val amountCents: Long?,
    val paidAt: LocalDate?,
    val counterparty: String?,
    val matchedReceivableId: String?,
    val matchReason: String?,
    val occurrence: OccurrenceView?,
)

/**
 * Ocorrencia como codigo estavel mais parametros -- nunca frase.
 *
 * E o coracao da decisao de i18n: o Rails renderiza `occurrence.ROW_INVALID_DATE`
 * com estes parametros, e um terceiro idioma nao toca em uma linha de Kotlin.
 */
data class OccurrenceView(
    val line: Int,
    val code: String,
    val params: Map<String, String>,
)

data class ReceivableInput(
    val id: String,
    val ourNumber: String,
    val amountCents: Long,
    val dueDate: LocalDate,
    val payer: String,
)

data class ImportResponse(
    val batch: BatchSummary,
    /** true quando o arquivo ja tinha sido importado: reprocessar nao duplica. */
    val alreadyImported: Boolean,
)

/** Erro tambem e codigo, nunca prosa em ingles vazando do backend para a tela. */
data class ApiError(
    val code: String,
    val params: Map<String, String> = emptyMap(),
)
