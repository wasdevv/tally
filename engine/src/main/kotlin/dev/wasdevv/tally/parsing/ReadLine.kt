package dev.wasdevv.tally.parsing

import dev.wasdevv.tally.domain.ledger.ParsedLine

/** O destino de UMA linha fisica lida do arquivo. Nenhuma linha sai sem um. */
sealed interface ReadLine {
    val line: Int

    data class Ledger(val parsed: ParsedLine) : ReadLine {
        override val line: Int get() = parsed.line
    }

    /** Header, trailer, cabecalho: contabilizada, sem lancamento. */
    data class Structural(override val line: Int) : ReadLine
}
