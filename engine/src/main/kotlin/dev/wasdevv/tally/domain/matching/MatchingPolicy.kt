package dev.wasdevv.tally.domain.matching

import dev.wasdevv.tally.domain.money.Cents

/**
 * A tolerancia e explicita e injetada, nunca constante escondida no matcher.
 *
 * O brief pede "casamento por tolerancia" sem fixar a dimensao; aqui ela tem
 * duas, valor e data, porque as duas divergem por motivos reais e diferentes:
 * tarifa bancaria mexe no valor, compensacao mexe na data. Ver
 * docs/DECISIONS.md.
 */
data class MatchingPolicy(
    val amountTolerance: Cents = Cents.ZERO,
    val dateToleranceDays: Long = 0,
) {
    init {
        require(amountTolerance >= Cents.ZERO) {
            "tolerancia de valor negativa nao tem significado: $amountTolerance"
        }
        require(dateToleranceDays >= 0) {
            "tolerancia de data negativa nao tem significado: $dateToleranceDays"
        }
    }

    companion object {
        /** Nada alem do centavo exato e do dia exato. */
        val STRICT = MatchingPolicy()

        /**
         * Padrao operacional: tarifa de ate R$ 2,00 e tres dias de compensacao.
         * Numero de politica, nao de dominio -- muda por configuracao.
         */
        val DEFAULT = MatchingPolicy(amountTolerance = Cents(200), dateToleranceDays = 3)
    }
}
