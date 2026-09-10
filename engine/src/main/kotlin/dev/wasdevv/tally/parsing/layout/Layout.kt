package dev.wasdevv.tally.parsing.layout

import dev.wasdevv.tally.parsing.types.FieldType

/**
 * Layout de largura fixa declarado como DADO.
 *
 * O erro classico com CNAB e `substring(37, 52)` espalhado por 400 linhas:
 * adicionar o sexto banco vira arqueologia. Aqui o layout e uma estrutura, e
 * a validacao acontece na CONSTRUCAO -- layout com campo fora do registro falha
 * ao carregar a aplicacao, nao ao processar o arquivo do cliente.
 *
 * As posicoes sao 1-based e inclusivas, como a especificacao FEBRABAN, para o
 * layout poder ser conferido lado a lado com o manual do banco sem aritmetica.
 */
class Layout internal constructor(
    val name: String,
    val recordLength: Int,
    val records: List<RecordSpec>,
)

class RecordSpec internal constructor(
    val discriminator: IntRange,
    val equalTo: String,
    val fields: List<FieldSpec>,
) {
    fun matches(line: String): Boolean = line.slice(discriminator) == equalTo
}

class FieldSpec internal constructor(
    val name: String,
    val at: IntRange,
    val type: FieldType<*>,
)

/** Recorta uma faixa 1-based inclusiva. Chame so depois de conferir o comprimento. */
internal fun String.slice(at: IntRange): String = substring(at.first - 1, at.last)

@DslMarker
annotation class LayoutDsl

@LayoutDsl
class LayoutBuilder internal constructor(
    private val name: String,
    private val recordLength: Int,
) {
    private val records = mutableListOf<RecordSpec>()

    fun record(
        discriminator: IntRange,
        equalTo: String,
        block: RecordBuilder.() -> Unit,
    ) {
        check(discriminator, "discriminador do registro '$equalTo'")
        require(discriminator.count() == equalTo.length) {
            "layout '$name': discriminador $discriminator tem ${discriminator.count()} posicoes " +
                "mas compara com '$equalTo', de ${equalTo.length}"
        }
        records += RecordBuilder(name, recordLength, discriminator, equalTo).apply(block).build()
    }

    internal fun build(): Layout {
        require(records.isNotEmpty()) { "layout '$name' nao declara nenhum registro" }
        return Layout(name, recordLength, records.toList())
    }

    private fun check(
        at: IntRange,
        what: String,
    ) = checkRange(name, recordLength, at, what)
}

@LayoutDsl
class RecordBuilder internal constructor(
    private val layoutName: String,
    private val recordLength: Int,
    private val discriminator: IntRange,
    private val equalTo: String,
) {
    private val fields = mutableListOf<FieldSpec>()

    fun field(
        name: String,
        at: IntRange,
        type: FieldType<*>,
    ) {
        checkRange(layoutName, recordLength, at, "campo '$name'")
        require(fields.none { it.name == name }) {
            "layout '$layoutName': campo '$name' declarado duas vezes no mesmo registro"
        }
        fields += FieldSpec(name, at, type)
    }

    internal fun build() = RecordSpec(discriminator, equalTo, fields.toList())
}

private fun checkRange(
    layoutName: String,
    recordLength: Int,
    at: IntRange,
    what: String,
) {
    require(at.first >= 1) {
        "layout '$layoutName': $what em $at comeca antes da posicao 1 " +
            "(o layout e 1-based, como a especificacao)"
    }
    require(at.last >= at.first) { "layout '$layoutName': $what tem intervalo invertido: $at" }
    require(at.last <= recordLength) {
        "layout '$layoutName': $what em $at passa do registro de $recordLength posicoes"
    }
}

fun layout(
    name: String,
    recordLength: Int,
    block: LayoutBuilder.() -> Unit,
): Layout {
    require(recordLength > 0) { "layout '$name': comprimento de registro invalido: $recordLength" }
    return LayoutBuilder(name, recordLength).apply(block).build()
}
