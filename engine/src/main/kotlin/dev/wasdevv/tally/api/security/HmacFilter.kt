package dev.wasdevv.tally.api.security

import com.fasterxml.jackson.databind.ObjectMapper
import dev.wasdevv.tally.api.dto.ApiError
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Toda chamada da API passa por aqui antes de qualquer controller.
 *
 * O corpo e lido CRU e so depois desserializado: assinar o objeto ja parseado
 * assinaria o que o parser entendeu, nao o que o cliente mandou.
 */
class HmacFilter(
    private val verifier: HmacVerifier,
    private val mapper: ObjectMapper,
    private val maxBodyBytes: Long,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val isMultipart = request.contentType.orEmpty().startsWith("multipart/")
        val declaredDigest = request.getHeader(CONTENT_DIGEST_HEADER)

        // Duas rotas porque as duas restricoes sao reais: em multipart o
        // container precisa do stream intacto para montar as partes, entao o
        // filtro NAO le o corpo e o digest declarado e quem amarra o arquivo a
        // assinatura -- `BatchController` o confere contra os bytes recebidos.
        // Em corpo JSON o filtro calcula o digest ele mesmo, dos bytes crus.
        val forwarded: HttpServletRequest
        val digest: String

        if (isMultipart) {
            if (declaredDigest.isNullOrBlank()) {
                reject(response, "missing_content_digest")
                return
            }
            forwarded = request
            digest = declaredDigest
        } else {
            val cached = CachedBodyRequest(request, maxBodyBytes)
            if (cached.truncated) {
                reject(response, "body_too_large")
                return
            }
            forwarded = cached
            digest = HmacVerifier.digestOf(cached.body)
            if (declaredDigest != null && declaredDigest != digest) {
                reject(response, "content_digest_mismatch")
                return
            }
        }

        val result =
            verifier.verify(
                method = request.method,
                path = canonicalPath(request),
                contentDigest = digest,
                timestamp = request.getHeader(TIMESTAMP_HEADER),
                signature = request.getHeader(SIGNATURE_HEADER),
            )

        when (result) {
            is HmacVerifier.Result.Valid -> filterChain.doFilter(forwarded, response)
            is HmacVerifier.Result.Invalid -> reject(response, result.reason)
        }
    }

    /**
     * Caminho MAIS query string.
     *
     * `requestURI` do servlet nao inclui a query, e assinar so ele deixaria
     * `?status=` fora da assinatura -- quem interceptasse poderia troca-lo. E
     * as duas pontas tem que concordar byte a byte: o cliente assina o caminho
     * que ele monta, com a query junto.
     */
    private fun canonicalPath(request: HttpServletRequest): String =
        request.queryString?.let { "${request.requestURI}?$it" } ?: request.requestURI

    private fun reject(
        response: HttpServletResponse,
        reason: String,
    ) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(mapper.writeValueAsString(ApiError("UNAUTHORIZED", mapOf("reason" to reason))))
    }

    companion object {
        const val TIMESTAMP_HEADER = "X-Tally-Timestamp"
        const val SIGNATURE_HEADER = "X-Tally-Signature"
        const val CONTENT_DIGEST_HEADER = "X-Tally-Content-Sha256"
    }
}
