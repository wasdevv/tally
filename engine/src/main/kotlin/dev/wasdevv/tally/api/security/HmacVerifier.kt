package dev.wasdevv.tally.api.security

import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 sobre metodo, caminho, timestamp e o DIGEST do conteudo.
 *
 * Assina o digest e nao os bytes por um motivo pratico: o upload chega como
 * multipart, e o container precisa do stream intacto para montar as partes --
 * drenar o corpo no filtro para assinar deixaria o parser de multipart com um
 * corpo vazio. Com o digest no material assinado, o arquivo continua amarrado a
 * assinatura: quem troca o conteudo tem que trocar o digest, e trocar o digest
 * invalida a assinatura. Quem recebe confere o digest contra os bytes que
 * chegaram (`BatchController`), entao a corrente fecha.
 *
 * Para requisicao com corpo JSON o filtro calcula o digest ele mesmo, dos bytes
 * crus -- antes de qualquer desserializacao, porque a diferenca entre o que o
 * parser entendeu e o que o cliente mandou e onde mora o ataque.
 *
 * O timestamp entra na assinatura para que uma requisicao capturada nao possa
 * ser reenviada amanha -- fora da janela, a mesma assinatura deixa de valer.
 */
class HmacVerifier(
    private val secret: String,
    private val clockSkew: Duration,
    // Relogio injetado em vez de `now` como parametro: o teste de janela precisa
    // controlar o tempo, e um default `Instant.now()` no meio da assinatura e
    // um parametro que so existe para o teste.
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(secret.length >= MIN_SECRET_LENGTH) {
            "segredo HMAC precisa de ao menos $MIN_SECRET_LENGTH caracteres"
        }
    }

    sealed interface Result {
        data object Valid : Result

        data class Invalid(val reason: String) : Result
    }

    fun verify(
        method: String,
        path: String,
        contentDigest: String,
        timestamp: String?,
        signature: String?,
    ): Result {
        val sentAt = timestamp?.toLongOrNull()?.let(Instant::ofEpochSecond)
        val provided = signature?.hexToBytesOrNull()

        return when {
            timestamp.isNullOrBlank() || signature.isNullOrBlank() -> Result.Invalid("missing_signature")
            sentAt == null -> Result.Invalid("malformed_timestamp")
            Duration.between(sentAt, clock.instant()).abs() > clockSkew ->
                Result.Invalid("expired_timestamp")
            provided == null -> Result.Invalid("malformed_signature")
            // Comparacao de tempo constante: `==` em String sai no primeiro byte
            // diferente, e isso vaza o prefixo correto para quem cronometra.
            !MessageDigest.isEqual(sign(method, path, contentDigest, timestamp), provided) ->
                Result.Invalid("bad_signature")
            else -> Result.Valid
        }
    }

    fun signatureFor(
        method: String,
        path: String,
        contentDigest: String,
        timestamp: String,
    ): String = sign(method, path, contentDigest, timestamp).joinToString("") { "%02x".format(it) }

    private fun sign(
        method: String,
        path: String,
        contentDigest: String,
        timestamp: String,
    ): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(), ALGORITHM))
        mac.update("$timestamp\n$method\n$path\n$contentDigest".toByteArray())
        return mac.doFinal()
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0 || isEmpty()) return null
        return runCatching {
            chunked(2).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
        }.getOrNull()
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private const val MIN_SECRET_LENGTH = 32
        private const val HEX_RADIX = 16

        /** SHA-256 do corpo cru, em hex. E o que entra no material assinado. */
        fun digestOf(body: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    }
}
