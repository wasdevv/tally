package dev.wasdevv.tally.parsing.types

import dev.wasdevv.tally.domain.ledger.OccurrenceCode
import dev.wasdevv.tally.domain.money.Cents
import java.math.BigInteger
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * O resultado de ler UM campo: ou o valor tipado, ou uma ocorrencia com codigo
 * estavel e parametros. Nunca um valor "provavel" e nunca zero por omissao --
 * campo ilegivel que vira zero e como o dinheiro some sem ninguem ver.
 */
sealed interface ParseOutcome<out T> {
    data class Ok<T>(val value: T) : ParseOutcome<T>

    data class Failed(
        val code: OccurrenceCode,
        val params: Map<String, String> = emptyMap(),
    ) : ParseOutcome<Nothing>
}

fun interface FieldType<out T> {
    fun parse(raw: String): ParseOutcome<T>
}

/** Texto do registro, sem o preenchimento. */
object Text : FieldType<String> {
    override fun parse(raw: String): ParseOutcome<String> = ParseOutcome.Ok(raw.trim())
}

/**
 * Decimal de casas implicitas, o formato do CNAB: "000000123456" com duas casas
 * ja E o valor em centavos, sem separador nenhum no arquivo.
 *
 * Nao le Locale.getDefault(): o separador decimal e propriedade do layout, nao
 * de quem opera (brief secao 9.4).
 */
class FixedDecimal(private val places: Int) : FieldType<Cents> {
    init {
        require(places >= 0) { "casas decimais negativas nao existem: $places" }
    }

    override fun parse(raw: String): ParseOutcome<Cents> {
        val digits = raw.trim()
        if (digits.isEmpty()) return ParseOutcome.Failed(OccurrenceCode.ROW_MISSING_FIELD)
        if (!digits.all { it.isDigit() }) {
            return ParseOutcome.Failed(OccurrenceCode.ROW_INVALID_AMOUNT, mapOf("raw" to raw))
        }
        return scaleToCents(BigInteger(digits), places, raw)
    }
}

/** "1.234,56" -- ponto de milhar, virgula decimal. */
object BrazilianDecimal : FieldType<Cents> by SeparatedDecimal(grouping = '.', decimal = ',')

/** "1,234.56" -- virgula de milhar, ponto decimal. */
object PlainDecimal : FieldType<Cents> by SeparatedDecimal(grouping = ',', decimal = '.')

/**
 * Decimal com separadores explicitos declarados pelo layout. Os separadores sao
 * parametro, nunca herdados do locale da JVM ou da interface.
 */
class SeparatedDecimal(
    private val grouping: Char,
    private val decimal: Char,
) : FieldType<Cents> {
    override fun parse(raw: String): ParseOutcome<Cents> {
        val text = raw.trim()
        if (text.isEmpty()) return ParseOutcome.Failed(OccurrenceCode.ROW_MISSING_FIELD)

        val negative = text.startsWith('-')
        val body = text.removePrefix("-").removePrefix("+").replace(grouping.toString(), "")
        val parts = body.split(decimal)
        val invalid =
            parts.size > CENTS_SCALE ||
                parts.any { it.isEmpty() || !it.all(Char::isDigit) } ||
                (parts.size == 2 && parts[1].length > CENTS_SCALE)
        if (invalid) return ParseOutcome.Failed(OccurrenceCode.ROW_INVALID_AMOUNT, mapOf("raw" to raw))

        val whole = BigInteger(parts[0])
        val fraction = parts.getOrNull(1)?.padEnd(CENTS_SCALE, '0') ?: "00"
        val total = whole * HUNDRED + BigInteger(fraction)
        return scaleToCents(if (negative) total.negate() else total, places = 2, raw = raw)
    }
}

/** Data YYMMDD do CNAB, interpretada no seculo 2000. */
object DateYYMMDD : FieldType<LocalDate> {
    override fun parse(raw: String): ParseOutcome<LocalDate> {
        val text = raw.trim()
        if (text.isEmpty()) return ParseOutcome.Failed(OccurrenceCode.ROW_MISSING_FIELD)
        if (text.length != YYMMDD_LENGTH || !text.all(Char::isDigit)) {
            return ParseOutcome.Failed(OccurrenceCode.ROW_INVALID_DATE, mapOf("raw" to raw))
        }

        val (yy, mm, dd) = text.chunked(YYMMDD_PART).map(String::toInt)
        return runCatching { LocalDate.of(CENTURY + yy, mm, dd) }.fold(
            onSuccess = { ParseOutcome.Ok(it) },
            // LocalDate.of recusa 30/02 em vez de deslizar para 01/03. E o que
            // queremos: data impossivel e ocorrencia, nao data vizinha.
            onFailure = { ParseOutcome.Failed(OccurrenceCode.ROW_INVALID_DATE, mapOf("raw" to raw)) },
        )
    }
}

/** Data ISO yyyy-MM-dd, o formato dos CSV que este motor aceita. */
object DateIso : FieldType<LocalDate> {
    override fun parse(raw: String): ParseOutcome<LocalDate> {
        val text = raw.trim()
        if (text.isEmpty()) return ParseOutcome.Failed(OccurrenceCode.ROW_MISSING_FIELD)
        return try {
            ParseOutcome.Ok(LocalDate.parse(text))
        } catch (_: DateTimeParseException) {
            ParseOutcome.Failed(OccurrenceCode.ROW_INVALID_DATE, mapOf("raw" to raw))
        }
    }
}

/** Codigo do banco mapeado para um valor do dominio; desconhecido e ocorrencia. */
class Code<T : Any>(private val map: Map<String, T>) : FieldType<T> {
    override fun parse(raw: String): ParseOutcome<T> {
        val key = raw.trim()
        val mapped =
            map[key]
                ?: return ParseOutcome.Failed(OccurrenceCode.ROW_UNKNOWN_RECORD_TYPE, mapOf("raw" to raw))
        return ParseOutcome.Ok(mapped)
    }
}

private const val CENTS_SCALE = 2
private const val CENTURY = 2000
private const val YYMMDD_LENGTH = 6
private const val YYMMDD_PART = 2
private const val CENTS_PER_UNIT = 100L
private val HUNDRED: BigInteger = BigInteger.valueOf(CENTS_PER_UNIT)

private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)

/**
 * BigInteger no caminho de leitura de proposito: o arquivo pode trazer mais
 * digitos do que cabe em Long, e `toLong()` daria a volta em silencio. Aqui o
 * excesso vira ocorrencia com codigo proprio.
 */
private fun scaleToCents(
    digits: BigInteger,
    places: Int,
    raw: String,
): ParseOutcome<Cents> {
    val cents =
        when {
            places == CENTS_SCALE -> digits
            places < CENTS_SCALE -> digits * BigInteger.TEN.pow(CENTS_SCALE - places)
            else -> {
                val divisor = BigInteger.TEN.pow(places - CENTS_SCALE)
                val (quotient, remainder) = digits.divideAndRemainder(divisor)
                // Truncar 1234561 milesimos para 123456 centavos perderia um decimo
                // de centavo calado. Em dinheiro isso e rejeicao, nao arredondamento.
                if (remainder.signum() != 0) {
                    return ParseOutcome.Failed(OccurrenceCode.ROW_INVALID_AMOUNT, mapOf("raw" to raw))
                }
                quotient
            }
        }

    if (cents < LONG_MIN || cents > LONG_MAX) {
        return ParseOutcome.Failed(OccurrenceCode.ROW_AMOUNT_OUT_OF_RANGE, mapOf("raw" to raw.trim()))
    }
    return ParseOutcome.Ok(Cents(cents.toLong()))
}
