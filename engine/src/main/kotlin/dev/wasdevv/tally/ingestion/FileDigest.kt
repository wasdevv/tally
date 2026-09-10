package dev.wasdevv.tally.ingestion

import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 do conteudo do arquivo. E a identidade do lote: reprocessar o mesmo
 * arquivo tem que encontrar o lote que ja existe, nao criar um segundo.
 */
object FileDigest {
    private const val BUFFER = 64 * 1024
    private const val HEX_RADIX = 16
    private const val BITS_PER_HEX_CHAR = 4
    private const val HEX_CHARS_IN_LONG = 16

    fun of(content: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(content))

    /** Le em blocos: o digest de um arquivo de 1 GB nao pode custar 1 GB de heap. */
    fun of(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    /**
     * Chave do advisory lock derivada dos 8 primeiros bytes do digest.
     *
     * Nao usa `hashCode()` de proposito: o hashCode de String e estavel na
     * especificacao da JVM, mas o de qualquer outro tipo nao e, e um lock cuja
     * chave muda entre versoes deixa de ser lock sem avisar. O digest e estavel
     * por definicao.
     */
    fun lockKey(digest: String): Long =
        digest.take(HEX_CHARS_IN_LONG).fold(0L) { acc, c ->
            (acc shl BITS_PER_HEX_CHAR) or c.digitToInt(HEX_RADIX).toLong()
        }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
