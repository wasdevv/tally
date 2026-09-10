package dev.wasdevv.tally.api

import dev.wasdevv.tally.TallyApplication
import dev.wasdevv.tally.api.security.HmacFilter
import dev.wasdevv.tally.api.security.HmacVerifier
import dev.wasdevv.tally.ingestion.FileDigest
import dev.wasdevv.tally.ingestion.SyntheticFiles
import dev.wasdevv.tally.persistence.Database
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.time.Duration
import java.time.Instant

/**
 * O contrato REST contra o motor de verdade: Spring de pe, PostgreSQL 16 real,
 * HMAC ligado. Nao ha mock do proprio banco nem do proprio dominio -- so a borda
 * de terceiro seria mockavel, e aqui nao ha nenhuma.
 */
class ApiSpec : StringSpec() {
    private val app =
        SpringApplication(TallyApplication::class.java).run(
            "--server.port=0",
            "--spring.datasource.url=${Database.jdbcUrl}",
            "--spring.datasource.username=${Database.username}",
            "--spring.datasource.password=${Database.password}",
            // O Flyway ja rodou pelo objeto Database; rodar de novo aqui so
            // competiria pela mesma tabela de historico.
            "--spring.flyway.enabled=false",
            "--tally.hmac-secret=$SECRET",
        )

    private val port = app.environment.getProperty("local.server.port")!!.toInt()
    private val rest = TestRestTemplate()
    private val verifier = HmacVerifier(SECRET, Duration.ofMinutes(5))

    private fun signedHeaders(
        method: String,
        path: String,
        contentDigest: String,
    ): HttpHeaders {
        val timestamp = Instant.now().epochSecond.toString()
        return HttpHeaders().apply {
            set(HmacFilter.TIMESTAMP_HEADER, timestamp)
            set(HmacFilter.SIGNATURE_HEADER, verifier.signatureFor(method, path, contentDigest, timestamp))
        }
    }

    private val emptyDigest = HmacVerifier.digestOf(ByteArray(0))

    private fun get(path: String) =
        rest.exchange(
            "http://localhost:$port$path",
            HttpMethod.GET,
            HttpEntity<Any>(signedHeaders("GET", path, emptyDigest)),
            String::class.java,
        )

    init {
        afterSpec { app.close() }
        beforeTest { Database.clean() }

        "chamada sem assinatura e recusada com codigo, nao com frase" {
            val response = rest.getForEntity("http://localhost:$port/api/batches", String::class.java)

            response.statusCode shouldBe HttpStatus.UNAUTHORIZED
            response.body!! shouldContain "UNAUTHORIZED"
            response.body!! shouldContain "missing_signature"
        }

        "chamada com assinatura errada e recusada" {
            val headers =
                HttpHeaders().apply {
                    set(HmacFilter.TIMESTAMP_HEADER, Instant.now().epochSecond.toString())
                    set(HmacFilter.SIGNATURE_HEADER, "00".repeat(32))
                }

            val response =
                rest.exchange(
                    "http://localhost:$port/api/batches",
                    HttpMethod.GET,
                    HttpEntity<Any>(headers),
                    String::class.java,
                )

            response.statusCode shouldBe HttpStatus.UNAUTHORIZED
            response.body!! shouldContain "bad_signature"
        }

        "chamada assinada passa" {
            get("/api/batches").statusCode shouldBe HttpStatus.OK
        }

        "a API nunca emite texto para humano: ocorrencia sai como codigo e parametros" {
            val content =
                SyntheticFiles.file(
                    listOf(SyntheticFiles.detail().replaceRange(110, 116, "999999")),
                )
            val created = upload(content, "itau.ret")
            created.statusCode shouldBe HttpStatus.CREATED

            val id = Regex(""""id":(\d+)""").find(created.body!!)!!.groupValues[1]
            val entries = get("/api/batches/$id/entries").body!!

            entries shouldContain "ROW_INVALID_DATE"
            // Nenhuma frase em ingles ou portugues vazando do backend para a tela.
            entries shouldNotContain "invalid"
            entries shouldNotContain "Invalid"
            entries shouldNotContain "data invalida"
        }

        "centavos saem como inteiro, nunca formatados" {
            upload(SyntheticFiles.file(listOf(SyntheticFiles.detail(amountCents = 120400))), "a.ret")

            val body = get("/api/batches").body!!

            body shouldContain "\"totalCents\":120400"
            body shouldNotContain "R$"
            body shouldNotContain "1.204,00"
        }

        "reprocessar o mesmo arquivo devolve 200 e marca alreadyImported" {
            val content = SyntheticFiles.file(listOf(SyntheticFiles.detail()))

            upload(content, "a.ret").statusCode shouldBe HttpStatus.CREATED
            val second = upload(content, "a.ret")

            second.statusCode shouldBe HttpStatus.OK
            second.body!! shouldContain "\"alreadyImported\":true"
        }

        "zip disfarcado de retorno e recusado pelos bytes" {
            val zip = String(byteArrayOf(0x50, 0x4B, 0x03, 0x04), Charsets.ISO_8859_1) + "resto"

            val response = upload(zip, "retorno.ret")

            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            response.body!! shouldContain "FILE_TYPE_NOT_ACCEPTED"
        }

        "arquivo vazio e recusado em vez de importar zero linha com sucesso" {
            upload("", "vazio.ret").body!! shouldContain "FILE_EMPTY"
        }

        // A prova de que a assinatura cobre o ARQUIVO, nao so o envelope: o
        // atacante assina o digest de um arquivo e manda outro.
        "arquivo trocado depois de assinar e recusado" {
            val outro = SyntheticFiles.file(listOf(SyntheticFiles.detail(amountCents = 999)))
            val response =
                upload(
                    SyntheticFiles.file(listOf(SyntheticFiles.detail())),
                    "a.ret",
                    digestOverride = FileDigest.of(outro.toByteArray(Charsets.ISO_8859_1)),
                )

            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            response.body!! shouldContain "CONTENT_DIGEST_MISMATCH"
        }

        "upload sem o header de digest e recusado antes de chegar ao controller" {
            val boundary = "b"
            val body =
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
                    "filename=\"a.ret\"\r\n\r\nx\r\n--$boundary--\r\n"
            val headers =
                signedHeaders("POST", "/api/batches", emptyDigest).apply {
                    contentType = MediaType.parseMediaType("multipart/form-data; boundary=$boundary")
                }

            val response =
                rest.postForEntity(
                    "http://localhost:$port/api/batches",
                    HttpEntity(body.toByteArray(Charsets.ISO_8859_1), headers),
                    String::class.java,
                )

            response.statusCode shouldBe HttpStatus.UNAUTHORIZED
            response.body!! shouldContain "missing_content_digest"
        }

        "lote inexistente devolve codigo, nao stack trace" {
            val response = get("/api/batches/999999")

            response.statusCode shouldBe HttpStatus.NOT_FOUND
            response.body!! shouldContain "BATCH_NOT_FOUND"
        }
    }

    /**
     * O digest do ARQUIVO vai no header e entra no material assinado; o
     * controller o confere contra os bytes recebidos. E o que amarra o conteudo
     * a assinatura sem o filtro precisar drenar o multipart -- o container
     * precisa daquele stream intacto para montar as partes.
     */
    private fun upload(
        content: String,
        filename: String,
        digestOverride: String? = null,
    ): ResponseEntity<String> {
        val path = "/api/batches"
        val boundary = "tallyBoundary8c7a16aedbcc"
        val fileBytes = content.toByteArray(Charsets.ISO_8859_1)
        val body =
            (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n" +
                    content +
                    "\r\n--$boundary--\r\n"
            ).toByteArray(Charsets.ISO_8859_1)

        val declared = digestOverride ?: FileDigest.of(fileBytes)
        val headers =
            signedHeaders("POST", path, declared).apply {
                contentType = MediaType.parseMediaType("multipart/form-data; boundary=$boundary")
                set(HmacFilter.CONTENT_DIGEST_HEADER, declared)
            }

        return rest.postForEntity("http://localhost:$port$path", HttpEntity(body, headers), String::class.java)
    }

    companion object {
        const val SECRET = "0123456789abcdef0123456789abcdef"
    }
}
