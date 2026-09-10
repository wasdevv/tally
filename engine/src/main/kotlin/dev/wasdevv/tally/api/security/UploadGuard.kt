package dev.wasdevv.tally.api.security

/**
 * O que se aceita como arquivo de retorno, antes de qualquer parsing.
 *
 * A extensao e o content-type sao o que o CLIENTE afirma; os bytes sao o que o
 * arquivo E. Quando os dois discordam, os bytes ganham.
 */
object UploadGuard {
    /** Assinaturas que este motor recusa por serem container, nao texto. */
    private val forbiddenMagic: Map<String, ByteArray> =
        mapOf(
            // ZIP/JAR/OOXML: porta de entrada de zip-slip e de zip bomb. Este
            // motor nao extrai nada, entao o comprimido e recusado na porta em
            // vez de aberto -- a extracao que nao existe nao pode ser explorada.
            "zip" to magic("PK\u0003\u0004"),
            "gzip" to magic("\u001F\u008B"),
            "pdf" to magic("%PDF"),
            // Binario nunca e arquivo de retorno bancario.
            "elf" to magic("\u007FELF"),
        )

    private fun magic(signature: String) = signature.toByteArray(Charsets.ISO_8859_1)

    sealed interface Result {
        data object Accepted : Result

        data class Rejected(val code: String) : Result
    }

    fun inspect(
        bytes: ByteArray,
        maxBytes: Long,
    ): Result =
        when {
            bytes.isEmpty() -> Result.Rejected("FILE_EMPTY")
            bytes.size > maxBytes -> Result.Rejected("FILE_TOO_LARGE")
            magicOf(bytes) != null -> Result.Rejected("FILE_TYPE_NOT_ACCEPTED")
            // NUL no meio e o sinal mais barato de que isto nao e texto de retorno.
            bytes.any { it == ZERO } -> Result.Rejected("FILE_NOT_TEXT")
            else -> Result.Accepted
        }

    private fun magicOf(bytes: ByteArray): String? =
        forbiddenMagic.entries.firstOrNull { (_, magic) ->
            bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
        }?.key

    private const val ZERO: Byte = 0
}
