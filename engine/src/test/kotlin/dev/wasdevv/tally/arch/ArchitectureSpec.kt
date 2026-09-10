package dev.wasdevv.tally.arch

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A fronteira do brief secao 4 como teste, nao como documento.
 *
 * `domain` nao importa framework nenhum. E isso que torna o matcher testavel sem
 * subir contexto: 3.000 casos de property testing rodam em milissegundos porque
 * nao ha container no caminho. Um documento nao quebra o build quando alguem
 * escreve `@Autowired` no Reconciler; este teste quebra.
 */
class ArchitectureSpec : StringSpec({
    val forbidden =
        listOf(
            "org.springframework",
            "org.jooq",
            "com.fasterxml.jackson",
            "org.flywaydb",
            "jakarta.",
            "javax.",
            // Corrotina e assunto de ingestion/. Dominio sincrono roda em ms.
            "kotlinx.coroutines",
        )

    "domain nao importa framework nem infraestrutura" {
        val offenders =
            Konsist.scopeFromProject()
                .files
                .filter { it.path.contains("/src/main/kotlin/") }
                .filter { it.packagee?.name.orEmpty().startsWith("dev.wasdevv.tally.domain") }
                .flatMap { file ->
                    file.imports
                        .filter { imported -> forbidden.any { imported.name.startsWith(it) } }
                        .map { "${file.name}: ${it.name}" }
                }

        offenders shouldBe emptyList()
    }

    "domain existe e foi mesmo varrido pelo teste" {
        val scanned =
            Konsist.scopeFromProject()
                .files
                .filter { it.path.contains("/src/main/kotlin/") }
                .count { it.packagee?.name.orEmpty().startsWith("dev.wasdevv.tally.domain") }

        (scanned >= 4) shouldBe true
    }
})
