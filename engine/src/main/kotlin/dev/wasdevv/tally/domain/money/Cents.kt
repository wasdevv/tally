package dev.wasdevv.tally.domain.money

@JvmInline
value class Cents(val value: Long) {
    operator fun plus(other: Cents) = Cents(value + other.value)

    companion object {
        val ZERO = Cents(0)
    }
}
