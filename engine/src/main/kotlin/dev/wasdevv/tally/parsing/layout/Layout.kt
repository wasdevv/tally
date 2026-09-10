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
    /**
     * Este registro vira lancamento no razao, ou e so estrutura (header,
     * trailer, rodape de totais)?
     *
     * Mora no LAYOUT e nao no parser de proposito. Quando isto era `equalTo ==
     * "1"` dentro do parser, um banco cujo registro de detalhe usasse outro
     * discriminador exigia mudar codigo -- e a promessa da DSL e que layout novo
     * seja dado.
     */
    val emitsEntry: Boolean,
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

    /** Registro estrutural: contabilizado no arquivo, sem virar lancamento. */
    fun record(
        discriminator: IntRange,
        equalTo: String,
        block: RecordBuilder.() -> Unit = {},
    ) = add(discriminator, equalTo, emitsEntry = false, block = block)

    /** Registro de lancamento: cada um vira uma linha do razao. */
    fun detail(
        discriminator: IntRange,
        equalTo: String,
        block: RecordBuilder.() -> Unit,
    ) = add(discriminator, equalTo, emitsEntry = true, block = block)

    private fun add(
        discriminator: IntRange,
        equalTo: String,
        emitsEntry: Boolean,
        block: RecordBuilder.() -> Unit,
    ) {
        check(discriminator, "discriminador do registro '$equalTo'")
        require(discriminator.count() == equalTo.length) {
            "layout '$name': discriminador $discriminator tem ${discriminator.count()} posicoes " +
                "mas compara com '$equalTo', de ${equalTo.length}"
        }
        require(records.none { it.equalTo == equalTo }) {
            "layout '$name': discriminador '$equalTo' declarado duas vezes"
        }
        records +=
            RecordBuilder(name, recordLength, discriminator, equalTo, emitsEntry)
                .apply(block)
                .build()
    }

    internal fun build(): Layout {
        require(records.isNotEmpty()) { "layout '$name' nao declara nenhum registro" }
        // Layout que so descreve estrutura nunca produz lancamento: importaria
        // todo arquivo com sucesso e zero linha, que e o silencio mais caro
        // que este projeto existe para impedir.
        require(records.any { it.emitsEntry }) {
            "layout '$name' nao declara nenhum registro de lancamento (`detail`)"
        }
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
    private val emitsEntry: Boolean,
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

    internal fun build() = RecordSpec(discriminator, equalTo, fields.toList(), emitsEntry)
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
