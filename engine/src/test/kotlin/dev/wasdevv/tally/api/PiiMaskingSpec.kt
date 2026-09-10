package dev.wasdevv.tally.api

import dev.wasdevv.tally.api.security.PiiMasking
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PiiMaskingSpec : StringSpec({

    "numero de conta longo nao aparece inteiro" {
        val masked = PiiMasking.mask("conta 123456789012 do sacado")

        masked shouldNotContain "123456789012"
        masked shouldContain "9012"
    }

    "agencia com digito verificador e mascarada por inteiro" {
        // 5 digitos nao tem sufixo que sobre para mostrar sem mostrar quase tudo.
        PiiMasking.mask("ag 1234-5") shouldBe "ag *****"
    }

    "os ultimos quatro digitos ficam visiveis para o suporte conferir" {
        PiiMasking.mask("00012938471") shouldBe "*******8471"
    }

    // Numero de linha e valor curto continuam legiveis, senao o log deixa de
    // servir para depurar e todo mundo desliga a mascara.
    "numero curto continua visivel" {
        PiiMasking.mask("linha 412 rejeitada") shouldBe "linha 412 rejeitada"
    }

    "texto sem numero passa intacto" {
        PiiMasking.mask("lote importado") shouldBe "lote importado"
    }
})
