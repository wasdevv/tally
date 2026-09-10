package dev.wasdevv.tally.api

import dev.wasdevv.tally.api.security.UploadGuard
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class UploadGuardSpec : StringSpec({
    val limit = 1_000L

    fun reject(bytes: ByteArray) =
        UploadGuard.inspect(bytes, limit)
            .shouldBeInstanceOf<UploadGuard.Result.Rejected>()

    "arquivo de texto e aceito" {
        UploadGuard.inspect("0".padEnd(400, ' ').toByteArray(), limit) shouldBe
            UploadGuard.Result.Accepted
    }

    "arquivo vazio e recusado em vez de importar zero linha com sucesso" {
        reject(ByteArray(0)).code shouldBe "FILE_EMPTY"
    }

    "arquivo maior que o limite e recusado antes de ser lido" {
        reject(ByteArray(limit.toInt() + 1) { 'a'.code.toByte() }).code shouldBe "FILE_TOO_LARGE"
    }

    // Renomear bomba.zip para retorno.ret nao muda o que os bytes sao.
    "zip disfarcado de retorno e recusado pelos bytes, nao pela extensao" {
        reject(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "resto".toByteArray()).code shouldBe
            "FILE_TYPE_NOT_ACCEPTED"
    }

    "gzip e recusado" {
        reject(byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08) + "x".toByteArray()).code shouldBe
            "FILE_TYPE_NOT_ACCEPTED"
    }

    "pdf e recusado" {
        reject("%PDF-1.7 resto".toByteArray()).code shouldBe "FILE_TYPE_NOT_ACCEPTED"
    }

    "binario com NUL no meio e recusado" {
        reject("0123".toByteArray() + byteArrayOf(0) + "456".toByteArray()).code shouldBe "FILE_NOT_TEXT"
    }
})
