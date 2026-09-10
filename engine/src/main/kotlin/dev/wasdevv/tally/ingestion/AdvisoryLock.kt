package dev.wasdevv.tally.ingestion

import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * Lock por arquivo, no Postgres.
 *
 * `pg_advisory_xact_lock` e nao a variante de sessao: o lock morre junto com a
 * transacao, inclusive se o processo cair no meio. Lock que sobrevive ao dono
 * e como se perde a fila de importacao ate alguem reiniciar o banco.
 *
 * Tem que rodar DENTRO da transacao que faz o trabalho -- por isso recebe o
 * DSLContext da transacao, e nao o global.
 */
object AdvisoryLock {
    fun acquire(
        txn: DSLContext,
        key: Long,
    ) {
        txn.select(DSL.function("pg_advisory_xact_lock", Any::class.java, DSL.value(key))).fetch()
    }
}
