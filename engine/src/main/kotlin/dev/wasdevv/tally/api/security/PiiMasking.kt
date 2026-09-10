package dev.wasdevv.tally.api.security

/**
 * Mascara conta e agencia antes de qualquer coisa chegar ao log.
 *
 * Nao e cosmetica: log vai para agregador, agregador vai para retencao longa, e
 * numero de conta em retencao longa e vazamento com data marcada. O spec que
 * acompanha isto quebra o build se o numero completo aparecer na saida.
 *
 * A regra tem tres faixas porque mascarar tudo torna o log inutil e ninguem
 * mantem ligada uma mascara que impede depurar:
 *
 * - ate 4 digitos: passa. E numero de linha, dia, quantidade.
 * - 5 a 7 digitos: mascara inteiro. E agencia com DV, e agencia curta nao tem
 *   sufixo que sobre para mostrar sem mostrar quase tudo.
 * - 8 ou mais: mostra os quatro ultimos. E conta, e o suporte confere pelos
 *   ultimos quatro sem que o log carregue o numero.
 */
object PiiMasking {
    private const val VISIBLE_SUFFIX = 4
    private const val MASK_ENTIRELY_BELOW = 8
    private const val MIN_MASKED_LENGTH = 5

    private val digitRun = Regex("""\d[\d.\-/]*\d""")

    fun mask(text: String): String =
        digitRun.replace(text) { match ->
            val digits = match.value.filter(Char::isDigit)
            when {
                digits.length < MIN_MASKED_LENGTH -> match.value
                digits.length < MASK_ENTIRELY_BELOW -> "*".repeat(digits.length)
                else -> "*".repeat(digits.length - VISIBLE_SUFFIX) + digits.takeLast(VISIBLE_SUFFIX)
            }
        }
}
