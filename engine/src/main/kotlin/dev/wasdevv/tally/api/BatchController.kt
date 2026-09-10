package dev.wasdevv.tally.api

import dev.wasdevv.tally.api.dto.ApiError
import dev.wasdevv.tally.api.dto.BatchSummary
import dev.wasdevv.tally.api.dto.ImportResponse
import dev.wasdevv.tally.api.dto.LedgerEntryView
import dev.wasdevv.tally.api.dto.ReviewDecision
import dev.wasdevv.tally.api.security.HmacFilter
import dev.wasdevv.tally.api.security.UploadGuard
import dev.wasdevv.tally.ingestion.BatchImporter
import dev.wasdevv.tally.ingestion.FileDigest
import dev.wasdevv.tally.ingestion.ImportOutcome
import dev.wasdevv.tally.ingestion.ImportRequest
import dev.wasdevv.tally.parsing.cnab.Cnab400
import dev.wasdevv.tally.persistence.ReviewRepository
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest

@RestController
@RequestMapping("/api/batches")
class BatchController(
    private val importer: BatchImporter,
    private val reviews: ReviewRepository,
    private val views: LedgerViews,
    @Value("\${tally.max-upload-bytes:33554432}") private val maxUploadBytes: Long,
) {
    @PostMapping
    fun import(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("filename", required = false) filename: String?,
        @RequestHeader(HmacFilter.CONTENT_DIGEST_HEADER, required = false) declaredDigest: String?,
    ): ResponseEntity<Any> {
        val bytes = file.bytes
        val digest = FileDigest.of(bytes)

        // Fecha a corrente que o filtro comecou: o digest esta DENTRO da
        // assinatura, e aqui ele e conferido contra os bytes que realmente
        // chegaram. Sem esta linha a assinatura cobriria uma promessa sobre o
        // arquivo em vez do arquivo.
        val mismatch =
            declaredDigest != null &&
                !MessageDigest.isEqual(declaredDigest.toByteArray(), digest.toByteArray())
        if (mismatch) return ResponseEntity.badRequest().body(ApiError("CONTENT_DIGEST_MISMATCH"))

        // Os bytes antes da extensao: o content-type e o que o cliente AFIRMA.
        when (val verdict = UploadGuard.inspect(bytes, maxUploadBytes)) {
            is UploadGuard.Result.Rejected ->
                return ResponseEntity.badRequest().body(ApiError(verdict.code))
            UploadGuard.Result.Accepted -> Unit
        }

        val name = filename ?: file.originalFilename ?: "upload.ret"
        val content = bytes.toString(Charsets.ISO_8859_1)

        val outcome =
            runBlocking {
                importer.import(
                    ImportRequest(
                        filename = name,
                        layoutName = Cnab400.synthetic.name,
                        digest = digest,
                        lines = Cnab400.read(Cnab400.sourceLines(content)).asFlow(),
                    ),
                )
            }

        return ResponseEntity.status(if (outcome is ImportOutcome.Imported) HttpStatus.CREATED else HttpStatus.OK)
            .body(
                ImportResponse(
                    batch = views.summary(outcome.batch),
                    alreadyImported = outcome is ImportOutcome.AlreadyImported,
                ),
            )
    }

    @GetMapping
    fun list(): List<BatchSummary> = views.allBatches()

    @GetMapping("/{id}")
    fun detail(
        @PathVariable id: Long,
    ): ResponseEntity<Any> =
        views.batch(id)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError("BATCH_NOT_FOUND"))

    @GetMapping("/{id}/entries")
    fun entries(
        @PathVariable id: Long,
        @RequestParam(required = false) status: String?,
    ): List<LedgerEntryView> = views.entries(id, status)

    /**
     * A decisao humana sobre uma linha que o motor recusou decidir.
     *
     * `receivableId` nulo e "nenhum destes": a linha vai para UNMATCHED e
     * continua no razao. Nao existe descartar linha -- a conservacao deixaria de
     * fechar contra o arquivo, e o razao passaria a mentir sobre o que chegou.
     */
    @PostMapping("/{id}/entries/{line}/decision")
    fun decide(
        @PathVariable id: Long,
        @PathVariable line: Int,
        @RequestBody decision: ReviewDecision,
    ): ResponseEntity<Any> =
        when (reviews.decide(id, line, decision.receivableId)) {
            ReviewRepository.Decision.Recorded ->
                ResponseEntity.ok(mapOf("decided" to true))
            ReviewRepository.Decision.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError("ENTRY_NOT_FOUND"))
            // 409 e nao 400: a requisicao esta bem formada, o ESTADO e que mudou
            // debaixo dela. E o caso das duas abas decidindo a mesma linha.
            ReviewRepository.Decision.NotUnderReview ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError("ENTRY_NOT_UNDER_REVIEW"))
            ReviewRepository.Decision.UnknownReceivable ->
                ResponseEntity.badRequest().body(ApiError("RECEIVABLE_NOT_FOUND"))
        }
}
