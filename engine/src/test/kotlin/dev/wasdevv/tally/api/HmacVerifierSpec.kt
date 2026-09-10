package dev.wasdevv.tally.api

import dev.wasdevv.tally.api.security.HmacVerifier
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class HmacVerifierSpec : StringSpec({
    val secret = "0123456789abcdef0123456789abcdef"
    val now = Instant.parse("2026-03-14T12:00:00Z")
    val verifier = HmacVerifier(secret, Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC))
    val body = """{"filename":"itau-0314.ret"}""".toByteArray()
    val digest = HmacVerifier.digestOf(body)

    fun signedAt(
        instant: Instant,
        contentDigest: String = HmacVerifier.digestOf(ByteArray(0)),
        path: String = "/api/batches",
    ) = instant.epochSecond.toString() to
        verifier.signatureFor("POST", path, contentDigest, instant.epochSecond.toString())

    "assinatura correta e aceita" {
        val (ts, sig) = signedAt(now, digest)

        verifier.verify("POST", "/api/batches", digest, ts, sig) shouldBe HmacVerifier.Result.Valid
    }

    "corpo alterado depois de assinar invalida a assinatura" {
        val (ts, sig) = signedAt(now, digest)
        val adulterado = HmacVerifier.digestOf("""{"filename":"outro.ret"}""".toByteArray())

        val result = verifier.verify("POST", "/api/batches", adulterado, ts, sig)

        result.shouldBeInstanceOf<HmacVerifier.Result.Invalid>()
        result.reason shouldBe "bad_signature"
    }

    // Sem o caminho na assinatura, uma requisicao assinada para um endpoint
    // inofensivo poderia ser reapontada para um destrutivo.
    "assinatura de um caminho nao vale em outro" {
        val (ts, sig) = signedAt(now, digest, path = "/api/batches")

        val result = verifier.verify("POST", "/api/receivables", digest, ts, sig)

        result.shouldBeInstanceOf<HmacVerifier.Result.Invalid>()
        result.reason shouldBe "bad_signature"
    }

    "assinatura de um metodo nao vale em outro" {
        val (ts, sig) = signedAt(now, digest)

        verifier.verify("DELETE", "/api/batches", digest, ts, sig)
            .shouldBeInstanceOf<HmacVerifier.Result.Invalid>()
    }

    "requisicao capturada e reenviada depois da janela e recusada" {
        val ontem = now.minus(Duration.ofDays(1))
        val (ts, sig) = signedAt(ontem, digest)

        val result = verifier.verify("POST", "/api/batches", digest, ts, sig)

        result.shouldBeInstanceOf<HmacVerifier.Result.Invalid>()
        result.reason shouldBe "expired_timestamp"
    }

    "relogio adiantado alem da janela tambem e recusado" {
        val (ts, sig) = signedAt(now.plus(Duration.ofHours(1)), digest)

        verifier.verify("POST", "/api/batches", digest, ts, sig)
            .shouldBeInstanceOf<HmacVerifier.Result.Invalid>()
    }

    "assinatura ausente e recusada, nao tratada como vazia" {
        verifier.verify("POST", "/api/batches", digest, null, null)
            .shouldBeInstanceOf<HmacVerifier.Result.Invalid>()
    }

    "assinatura malformada e recusada sem explodir" {
        val result = verifier.verify("POST", "/api/batches", digest, now.epochSecond.toString(), "zzz")

        result.shouldBeInstanceOf<HmacVerifier.Result.Invalid>()
        result.reason shouldBe "malformed_signature"
    }

    "timestamp que nao e numero e recusado sem explodir" {
        val result = verifier.verify("POST", "/api/batches", digest, "ontem", "ab")

        result.shouldBeInstanceOf<HmacVerifier.Result.Invalid>()
        result.reason shouldBe "malformed_timestamp"
    }

    // Chave fraca nao pode subir: e o mesmo invariante de "desconhecido nao vira
    // zero" aplicado a segredo -- ausente nao pode virar aceitavel.
    "segredo curto demais recusa a construcao em vez de aceitar em silencio" {
        shouldThrow<IllegalArgumentException> { HmacVerifier("curto", Duration.ofMinutes(5)) }
    }
})
