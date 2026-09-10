package dev.wasdevv.tally.domain.ledger

import dev.wasdevv.tally.domain.money.Cents
import java.time.LocalDate

@JvmInline
value class ReceivableId(val value: String)

/** Um recebivel esperado: o que a empresa espera receber. */
data class Receivable(
    val id: ReceivableId,
    val ourNumber: String,
    val amount: Cents,
    val dueDate: LocalDate,
    val payer: String,
)

/** Um lancamento valido lido de uma linha fisica do arquivo de retorno. */
data class Entry(
    val line: Int,
    val ourNumber: String,
    val amount: Cents,
    val paidAt: LocalDate,
    val counterparty: String,
)

/**
 * Codigo estavel de ocorrencia. E um enum, nao String, por dois motivos: o
 * conjunto fechado deixa o gate de i18n provar que todo codigo tem traducao nos
 * dois idiomas, e o codigo continua estavel para log e metrica enquanto o texto
 * muda livremente. O motor nunca emite texto para humano (brief secao 9.1).
 */
enum class OccurrenceCode {
    ROW_TOO_SHORT,
    ROW_TOO_LONG,
    ROW_INVALID_DATE,
    ROW_INVALID_AMOUNT,
    ROW_AMOUNT_OUT_OF_RANGE,
    ROW_MISSING_FIELD,
    ROW_UNKNOWN_RECORD_TYPE,
    ROW_INVALID_ENCODING,
}

/** Motivo de rejeicao: linha fisica, codigo estavel e parametros. Sem prosa. */
data class Occurrence(
    val line: Int,
    val code: OccurrenceCode,
    val params: Map<String, String> = emptyMap(),
)

/**
 * O resultado de ler uma linha fisica: ou virou lancamento, ou virou ocorrencia.
 * Sealed em vez de Entry com campos nulos de proposito -- campo nulo e o caminho
 * curto para "desconhecido virou zero", que e exatamente o bug que este projeto
 * existe para impedir.
 */
sealed interface ParsedLine {
    val line: Int

    data class Valid(val entry: Entry) : ParsedLine {
        override val line: Int get() = entry.line
    }

    data class Rejected(val occurrence: Occurrence) : ParsedLine {
        override val line: Int get() = occurrence.line
    }
}

enum class MatchReason {
    /** Nosso numero e valor batem exatamente. */
    EXACT,

    /** Nosso numero bate e a diferenca cabe na tolerancia declarada. */
    WITHIN_TOLERANCE,

    /** O titulo foi encontrado, mas valor ou data ficaram fora da tolerancia. */
    AMOUNT_MISMATCH,

    /** Um unico candidato, achado so pelo valor. Casar seria palpite. */
    AMOUNT_ONLY,

    /** Mais de um candidato equivalente. O motor nao desempata. */
    AMBIGUOUS,
}

sealed interface MatchOutcome {
    data class Matched(val receivable: Receivable, val reason: MatchReason) : MatchOutcome

    data class NeedsReview(val candidates: List<Receivable>, val reason: MatchReason) : MatchOutcome

    data object Unmatched : MatchOutcome
}

data class Match(val entry: Entry, val receivable: Receivable, val reason: MatchReason)

data class Review(val entry: Entry, val candidates: List<Receivable>, val reason: MatchReason)

/** Os quatro destinos do brief secao 5.2. Toda linha termina em exatamente um. */
enum class EntryStatus { MATCHED, NEEDS_REVIEW, UNMATCHED, REJECTED }

data class ReconciliationResult(
    val matched: List<Match>,
    val needsReview: List<Review>,
    val unmatched: List<Entry>,
    val rejected: List<Occurrence>,
) {
    val lineCount: Int
        get() = matched.size + needsReview.size + unmatched.size + rejected.size
}

fun List<Entry>.sumCents(): Cents = fold(Cents.ZERO) { acc, e -> acc + e.amount }
