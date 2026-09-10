plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(21)
}

// A suite rapida (dominio + property) e a lenta (Postgres real) sao source sets
// separados de proposito: loop de dev abaixo de 10s ou voce para de rodar.
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    // Parser de CSV de verdade: aspas, delimitador dentro do campo e quebra de
    // linha embutida. `split(",")` erra os tres.
    implementation("org.apache.commons:commons-csv:1.12.0")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jooq:jooq:3.19.15")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    runtimeOnly("org.postgresql:postgresql:42.7.4")

    // Postgres de verdade, nao H2: H2 mente sobre indice parcial e advisory lock,
    // e o teste que passa em H2 e quebra em producao destroi a confianca na suite.
    integrationTestImplementation("org.springframework.boot:spring-boot-starter-test")
    integrationTestImplementation("org.testcontainers:postgresql:1.21.3")
    integrationTestImplementation("org.testcontainers:junit-jupiter:1.21.3")
    integrationTestImplementation("org.postgresql:postgresql:42.7.4")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("com.lemonappdev:konsist:0.17.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Testes contra PostgreSQL 16 real via Testcontainers."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    testLogging { events("failed") }
    shouldRunAfter(tasks.test)

    // O Docker daemon moderno recusa cliente com API anterior a 1.44, e o
    // docker-java que o Testcontainers embute ainda negocia 1.32 sozinho. O
    // sintoma e "Could not find a valid Docker environment", que parece falta de
    // permissao e nao e. Tem que ser propriedade de sistema: docker-java le
    // `api.version`, e a variavel de ambiente DOCKER_API_VERSION nao basta.
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.44")
}

// O detekt roda com o compilador Kotlin que ele proprio embute. O plugin do
// Spring puxa 2.0.21 para a mesma configuracao e o detekt recusa rodar com um
// compilador diferente do que foi compilado -- fixar a versao na configuracao
// dele resolve sem prender o resto do projeto.
configurations.named("detekt").configure {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.10")
    }
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
