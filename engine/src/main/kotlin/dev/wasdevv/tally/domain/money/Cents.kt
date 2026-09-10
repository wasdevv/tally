package dev.wasdevv.tally.domain.money

/**
 * Dinheiro no razao: inteiro de centavos, nunca Double, nunca BigDecimal solto.
 *
 * `value class` significa que em runtime isto e um `long` -- seguranca de tipo
 * com zero alocacao. Formatacao para humano acontece so na borda, no Rails; o
 * motor nunca produz "R$ 1.234,56".
 *
 * As operacoes usam `Math.*Exact` de proposito: em dinheiro, wraparound e pior
 * que falha. Um total que da a volta em Long vira negativo e a conservacao da
 * secao 5.3 "fecharia" em cima de um numero errado -- o unico modo de falha que
 * este projeto existe para impedir.
 */
@JvmInline
value class Cents(val value: Long) : Comparable<Cents> {
    operator fun plus(other: Cents) = Cents(Math.addExact(value, other.value))

    operator fun minus(other: Cents) = Cents(Math.subtractExact(value, other.value))

    fun abs() = Cents(Math.absExact(value))

    override fun compareTo(other: Cents) = value.compareTo(other.value)

    companion object {
        val ZERO = Cents(0)
    }
}
