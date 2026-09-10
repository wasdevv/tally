plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Parser de CSV de verdade: aspas, delimitador dentro do campo e quebra de
    // linha embutida. `split(",")` erra os tres.
    implementation("org.apache.commons:commons-csv:1.12.0")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("com.lemonappdev:konsist:0.17.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

kover {
    reports {
        // Piso de cobertura so em domain/ — e o codigo que a invariante protege.
        // api/ e infraestrutura ficam sem piso de proposito (brief secao 8.2).
        filters {
            includes { classes("dev.wasdevv.tally.domain.*") }
        }
        verify {
            rule {
                bound { minValue = 80 }
            }
        }
    }
}
