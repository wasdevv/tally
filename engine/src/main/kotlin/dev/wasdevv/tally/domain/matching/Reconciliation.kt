package dev.wasdevv.tally.domain.matching

import dev.wasdevv.tally.domain.ledger.Destination
import dev.wasdevv.tally.domain.ledger.MatchOutcome
import dev.wasdevv.tally.domain.ledger.ParsedLine
import dev.wasdevv.tally.domain.ledger.Receivable

/**
 * A conciliacao com estado: aceita UMA linha por vez e devolve o destino dela.
 *
 * Existe porque o arquivo streama e o conjunto de recebiveis nao. Os recebiveis
 * sao o que a empresa espera receber -- cabem em memoria; o arquivo pode ter
 * milhoes de linhas e nao cabe. Manter o estado aqui deixa o pico de heap
 * proporcional aos recebiveis, nao ao tamanho do arquivo.
 *
 * O indice por nosso numero e otimizacao MEDIDA, nao suposta: a versao anterior
 * refiltrava a lista inteira de recebiveis a cada linha, o que fazia a ingestao
 * ser O(linhas x recebiveis) e dominava o tempo total. Numeros antes e depois em
 * docs/MEASUREMENTS.md.
 *
 * A semantica NAO muda, e isso e provado e nao afirmado: quando o titulo tem
 * candidatos, `Matcher` receberia exatamente esse conjunto depois do proprio
 * filtro dele; quando nao tem, recebe todos os disponiveis. `ReconciliationSpec`
 * compara o razao indexado com o de uma implementacao ingenua de varredura
 * total, sobre arquivos gerados.
 */
class Reconciliation(
    receivables: List<Receivable>,
    private val policy: MatchingPolicy = MatchingPolicy.DEFAULT,
) {
    /** Titulo em branco nunca casa por titulo -- fica so na varredura por valor. */
    private val byTitle: MutableMap<String, MutableList<Receivable>> =
        receivables.filter { it.ourNumber.isNotBlank() }
            .groupByTo(mutableMapOf(), { it.ourNumber }, { it })

    private val remaining: MutableList<Receivable> = receivables.toMutableList()

    fun accept(parsed: ParsedLine): Destination =
        when (parsed) {
            is ParsedLine.Rejected -> Destination.Rejected(parsed.occurrence)
            is ParsedLine.Valid -> decide(parsed)
        }

    private fun decide(parsed: ParsedLine.Valid): Destination {
        val entry = parsed.entry
        val titled = if (entry.ourNumber.isBlank()) null else byTitle[entry.ourNumber]

        // Caminho comum: o titulo identifica o lancamento e a busca e O(1).
        // Caminho triste: sem titulo conhecido, varre o que sobrou por valor --
        // e o unico jeito de achar candidato quando o titulo nao ajuda.
        val candidates = titled?.takeIf { it.isNotEmpty() } ?: remaining

        return when (val outcome = Matcher.match(entry, candidates, policy)) {
            is MatchOutcome.Matched -> {
                consume(outcome.receivable)
                Destination.Matched(entry, outcome.receivable, outcome.reason)
            }
            is MatchOutcome.NeedsReview ->
                Destination.NeedsReview(entry, outcome.candidates, outcome.reason)
            MatchOutcome.Unmatched -> Destination.Unmatched(entry)
        }
    }

    /** Um recebivel casa no maximo uma vez: sai das duas estruturas. */
    private fun consume(receivable: Receivable) {
        remaining.remove(receivable)
        byTitle[receivable.ourNumber]?.let { bucket ->
            bucket.remove(receivable)
            if (bucket.isEmpty()) byTitle.remove(receivable.ourNumber)
        }
    }
}
