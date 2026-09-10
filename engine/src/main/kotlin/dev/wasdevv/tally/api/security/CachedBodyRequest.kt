package dev.wasdevv.tally.api.security

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Le o corpo uma vez, guarda os bytes e devolve um stream novo a cada leitura.
 *
 * Existe porque o HMAC precisa do corpo CRU antes de qualquer parsing, e o
 * `ContentCachingRequestWrapper` do Spring so guarda o que ja foi consumido --
 * drenar ali para assinar deixaria o parser de multipart com um corpo vazio, e o
 * upload chegaria vazio ao controller sem nenhum erro visivel.
 *
 * O limite existe para que ler o corpo nao vire o proprio ataque: sem teto, um
 * POST de 10 GB derruba o motor antes de a assinatura sequer ser conferida.
 */
class CachedBodyRequest(
    request: HttpServletRequest,
    maxBytes: Long,
) : HttpServletRequestWrapper(request) {
    val body: ByteArray = request.inputStream.readNBytes(maxBytes.toInt())

    /** true quando o corpo bateu no teto -- provavelmente ha mais coisa vindo. */
    val truncated: Boolean = body.size.toLong() == maxBytes

    override fun getInputStream(): ServletInputStream {
        val stream = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            override fun read() = stream.read()

            override fun isFinished() = stream.available() == 0

            override fun isReady() = true

            override fun setReadListener(listener: ReadListener) = Unit
        }
    }

    override fun getReader(): BufferedReader =
        BufferedReader(
            InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()),
        )
}
