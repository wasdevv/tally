package dev.wasdevv.tally.persistence

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Isto e o que a geracao de codigo do jOOQ daria de graca e que as declaracoes
 * a mao nao dao: a garantia de que os nomes existem mesmo no esquema.
 *
 * Sem este teste, um campo escrito errado em `Tables.kt` so aparece quando a
 * consulta roda -- ou seja, em producao. Com ele, a divergencia entre a
 * migration e a declaracao quebra o build.
 */
class SchemaDriftSpec : StringSpec({

    fun columnsOf(table: String): Set<String> =
        Database.dsl.fetch(
            "select column_name from information_schema.columns " +
                "where table_schema = 'public' and table_name = ?",
            table,
        ).map { it.get(0, String::class.java) }.toSet()

    "todo campo declarado em Tables.kt existe no esquema migrado" {
        val declared =
            mapOf(
                "receivables" to Receivables.ALL,
                "import_batches" to ImportBatches.ALL,
                "ledger_entries" to LedgerEntries.ALL,
            )

        val drift =
            declared.flatMap { (table, fields) ->
                val actual = columnsOf(table)
                fields.map { it.name }.filterNot { it in actual }.map { "$table.$it" }
            }

        drift.shouldBeEmpty()
    }

    "as tabelas do razao existem, ou seja, o teste acima varreu algo" {
        listOf("receivables", "import_batches", "ledger_entries")
            .filter { columnsOf(it).isEmpty() }
            .shouldBeEmpty()
    }
})
