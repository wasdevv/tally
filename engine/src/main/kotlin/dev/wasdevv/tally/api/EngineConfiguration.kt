package dev.wasdevv.tally.api

import com.fasterxml.jackson.databind.ObjectMapper
import dev.wasdevv.tally.api.security.HmacFilter
import dev.wasdevv.tally.api.security.HmacVerifier
import dev.wasdevv.tally.ingestion.BatchImporter
import dev.wasdevv.tally.persistence.LedgerRepository
import dev.wasdevv.tally.persistence.ReviewRepository
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration
import javax.sql.DataSource

@Configuration
class EngineConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun dslContext(dataSource: DataSource): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

    @Bean
    fun ledgerRepository(dsl: DSLContext) = LedgerRepository(dsl)

    @Bean
    fun reviewRepository(dsl: DSLContext) = ReviewRepository(dsl)

    @Bean
    fun batchImporter(
        dsl: DSLContext,
        repository: LedgerRepository,
    ) = BatchImporter(dsl, repository)

    /**
     * Smoke check de boot: fora de dev e test, subir sem segredo forte e recusado.
     *
     * E o mesmo invariante que vale para dinheiro, aplicado a chave: ausente nao
     * pode virar aceitavel. Um default no repositorio e um segredo que todo mundo
     * que leu o repositorio conhece.
     */
    @Bean
    fun hmacVerifier(
        @Value("\${tally.hmac-secret:}") secret: String,
        @Value("\${tally.clock-skew-seconds:300}") skewSeconds: Long,
        environment: Environment,
    ): HmacVerifier {
        val relaxed = environment.activeProfiles.any { it in setOf("dev", "test") }
        val effective =
            when {
                secret.isNotBlank() -> secret
                relaxed -> DEV_SECRET
                else ->
                    error(
                        "TALLY_HMAC_SECRET nao esta definido. O motor nao sobe sem segredo " +
                            "fora dos perfis dev/test -- ver .env.example.",
                    )
            }
        return HmacVerifier(effective, Duration.ofSeconds(skewSeconds))
    }

    @Bean
    fun hmacFilter(
        verifier: HmacVerifier,
        mapper: ObjectMapper,
        @Value("\${tally.max-upload-bytes:33554432}") maxUploadBytes: Long,
    ): FilterRegistrationBean<HmacFilter> =
        // O teto do filtro folga sobre o do upload: o corpo multipart carrega
        // cabecalho e fronteira alem do arquivo, e recusar por 200 bytes de
        // envelope daria um erro que ninguem consegue explicar.
        FilterRegistrationBean(HmacFilter(verifier, mapper, maxUploadBytes + MULTIPART_ENVELOPE_SLACK)).apply {
            addUrlPatterns("/api/*")
            order = FILTER_ORDER
        }

    private companion object {
        /** So vale sob perfil dev/test, e o proprio nome diz o que e. */
        const val DEV_SECRET = "dev-only-insecure-secret-do-not-ship!!"
        const val FILTER_ORDER = 1
        const val MULTIPART_ENVELOPE_SLACK = 64L * 1024
    }
}
