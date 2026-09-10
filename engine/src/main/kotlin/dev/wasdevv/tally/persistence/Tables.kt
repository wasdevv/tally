package dev.wasdevv.tally.persistence

import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.name
import org.jooq.impl.DSL.table
import org.jooq.impl.SQLDataType
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * As tabelas e colunas do razao, declaradas uma vez.
 *
 * Isto e jOOQ sem geracao de codigo, e a escolha e deliberada. O que a geracao
 * daria e tipo por coluna e um unico lugar onde o nome mora -- os dois estao
 * aqui, em 60 linhas, sem plugin de build e sem exigir um Postgres de pe para
 * compilar. O que ela daria a mais e a garantia de que estes nomes existem
 * mesmo no esquema; essa garantia esta em `SchemaDriftSpec`, que roda contra o
 * banco migrado e falha se qualquer campo declarado aqui nao existir la.
 *
 * ponytail: sem codegen. Migrar para jooq-codegen-gradle quando o esquema
 * passar de ~10 tabelas ou quando o custo de manter isto sincronizado aparecer.
 */
object Receivables {
    val TABLE: Table<Record> = table(name("receivables"))
    val ID: Field<String> = field(name("receivables", "id"), SQLDataType.CLOB)
    val OUR_NUMBER: Field<String> = field(name("receivables", "our_number"), SQLDataType.CLOB)
    val AMOUNT_CENTS: Field<Long> = field(name("receivables", "amount_cents"), SQLDataType.BIGINT)
    val DUE_DATE: Field<LocalDate> = field(name("receivables", "due_date"), SQLDataType.LOCALDATE)
    val PAYER: Field<String> = field(name("receivables", "payer"), SQLDataType.CLOB)

    val ALL = listOf(ID, OUR_NUMBER, AMOUNT_CENTS, DUE_DATE, PAYER)
}

object ImportBatches {
    val TABLE: Table<Record> = table(name("import_batches"))
    val ID: Field<Long> = field(name("import_batches", "id"), SQLDataType.BIGINT)
    val FILE_DIGEST: Field<String> = field(name("import_batches", "file_digest"), SQLDataType.CLOB)
    val FILENAME: Field<String> = field(name("import_batches", "filename"), SQLDataType.CLOB)
    val LAYOUT_NAME: Field<String> = field(name("import_batches", "layout_name"), SQLDataType.CLOB)
    val LINE_COUNT: Field<Int> = field(name("import_batches", "line_count"), SQLDataType.INTEGER)
    val TOTAL_CENTS: Field<Long> = field(name("import_batches", "total_cents"), SQLDataType.BIGINT)
    val IMPORTED_AT: Field<OffsetDateTime> =
        field(name("import_batches", "imported_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)

    val ALL = listOf(ID, FILE_DIGEST, FILENAME, LAYOUT_NAME, LINE_COUNT, TOTAL_CENTS, IMPORTED_AT)
}

object LedgerEntries {
    val TABLE: Table<Record> = table(name("ledger_entries"))
    val ID: Field<Long> = field(name("ledger_entries", "id"), SQLDataType.BIGINT)
    val BATCH_ID: Field<Long> = field(name("ledger_entries", "batch_id"), SQLDataType.BIGINT)
    val LINE: Field<Int> = field(name("ledger_entries", "line"), SQLDataType.INTEGER)
    val STATUS: Field<String> = field(name("ledger_entries", "status"), SQLDataType.CLOB)
    val OUR_NUMBER: Field<String> = field(name("ledger_entries", "our_number"), SQLDataType.CLOB)
    val AMOUNT_CENTS: Field<Long> = field(name("ledger_entries", "amount_cents"), SQLDataType.BIGINT)
    val PAID_AT: Field<LocalDate> = field(name("ledger_entries", "paid_at"), SQLDataType.LOCALDATE)
    val COUNTERPARTY: Field<String> = field(name("ledger_entries", "counterparty"), SQLDataType.CLOB)
    val MATCHED_RECEIVABLE_ID: Field<String> =
        field(name("ledger_entries", "matched_receivable_id"), SQLDataType.CLOB)
    val MATCH_REASON: Field<String> = field(name("ledger_entries", "match_reason"), SQLDataType.CLOB)
    val OCCURRENCE_CODE: Field<String> =
        field(name("ledger_entries", "occurrence_code"), SQLDataType.CLOB)
    val DECIDED_AT: Field<OffsetDateTime> =
        field(name("ledger_entries", "decided_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val DECIDED_REASON: Field<String> =
        field(name("ledger_entries", "decided_reason"), SQLDataType.CLOB)
    val OCCURRENCE_PARAMS: Field<JSONB> =
        field(name("ledger_entries", "occurrence_params"), SQLDataType.JSONB)

    val ALL =
        listOf(
            ID, BATCH_ID, LINE, STATUS, OUR_NUMBER, AMOUNT_CENTS, PAID_AT, COUNTERPARTY,
            MATCHED_RECEIVABLE_ID, MATCH_REASON, OCCURRENCE_CODE, OCCURRENCE_PARAMS,
            DECIDED_AT, DECIDED_REASON,
        )
}

/**
 * Os candidatos que o motor achou e nao desempatou.
 *
 * Sem eles a tela de revisao mandaria o operador procurar o titulo na mao --
 * repetindo o trabalho que o motor ja fez, cuja conclusao foi "nao sei
 * escolher". Isso e informacao, nao ausencia dela.
 */
object MatchCandidates {
    val TABLE: Table<Record> = table(name("match_candidates"))
    val ENTRY_ID: Field<Long> = field(name("match_candidates", "entry_id"), SQLDataType.BIGINT)
    val RECEIVABLE_ID: Field<String> =
        field(name("match_candidates", "receivable_id"), SQLDataType.CLOB)
    val POSITION: Field<Int> = field(name("match_candidates", "position"), SQLDataType.INTEGER)

    val ALL = listOf(ENTRY_ID, RECEIVABLE_ID, POSITION)
}
