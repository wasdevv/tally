package dev.wasdevv.tally.parsing

import dev.wasdevv.tally.domain.ledger.ParsedLine

/**
 * O que um parser devolve. `structuralLines` guarda header/trailer/cabecalho:
 * nao sao lancamentos, mas tambem nao podem sumir -- toda linha do arquivo tem
 * que ter destino registrado, e "eu ignorei" e um destino que precisa aparecer.
 */
data class ParsedFile(
    val layoutName: String,
    val lines: List<ParsedLine>,
    val structuralLines: List<Int>,
) {
    val accountedLines: Int get() = lines.size + structuralLines.size
}
