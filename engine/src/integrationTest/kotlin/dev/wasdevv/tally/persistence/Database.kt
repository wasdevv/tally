package dev.wasdevv.tally.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * PostgreSQL 16 de verdade, uma vez por execucao.
 *
 * Nao e H2: H2 mente sobre indice parcial, sobre `jsonb`, sobre advisory lock e
 * sobre constraint deferivel -- os quatro recursos de que este esquema depende.
 * Teste que passa em H2 e quebra em producao destroi a confianca na suite
 * inteira, nao so naquele teste.
 */
object Database {
    private val container =
        PostgreSQLContainer("postgres:16-alpine").apply {
            withReuse(false)
            start()
        }

    val jdbcUrl: String get() = container.jdbcUrl
    val username: String get() = container.username
    val password: String get() = container.password

    val dataSource: DataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = 8
            },
        )

    val dsl: DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    /** Limpa entre exemplos sem re-migrar: TRUNCATE e mais rapido e mais honesto. */
    fun clean() {
        dsl.execute("truncate table ledger_entries, import_batches, receivables restart identity cascade")
    }
}
