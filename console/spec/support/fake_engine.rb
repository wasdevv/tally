# frozen_string_literal: true

# O UNICO mock do projeto, e ele fica na borda de terceiro: o processo do motor,
# que nao sobe nas suites isoladas. Nao ha mock do proprio codigo do console.
#
# O job `contract` do CI sobe o motor de verdade e roda os mesmos fluxos contra
# ele -- este duble serve para a suite rapida, nao para provar o contrato.
class FakeEngine
  attr_reader :imported, :decisions

  def initialize(batches: [], entries: {}, receivables: [], raises: nil)
    @batches = batches
    @entries = entries
    @receivables = receivables
    @raises = raises
    @imported = []
    @decisions = []
  end

  def batches
    raise @raises if @raises

    @batches
  end

  def batch(id)
    raise @raises if @raises

    @batches.find { |b| b["id"].to_s == id.to_s } or
      raise EngineClient::Error.new("BATCH_NOT_FOUND")
  end

  def entries(batch_id, status: nil)
    rows = @entries.fetch(batch_id.to_s, [])
    status ? rows.select { |r| r["status"] == status } : rows
  end

  def receivables = @receivables

  def decide(batch_id, line, receivable_id)
    raise @raises if @raises

    @decisions << [ batch_id.to_s, line.to_i, receivable_id ]
    { "decided" => true }
  end

  def import(io:, filename:)
    raise @raises if @raises

    @imported << filename
    { "batch" => @batches.first, "alreadyImported" => false }
  end
end

# Fabricas: o formato e exatamente o que o motor devolve -- centavos INTEIROS,
# ocorrencia como {line, code, params}, nunca frase.
module EnginePayloads
  module_function

  def batch(id: 1, filename: "itau-0314.ret", line_count: 4, unreconciled: 481_290, counts: nil)
    {
      "id" => id,
      "filename" => filename,
      "layoutName" => "cnab400-sintetico",
      "lineCount" => line_count,
      "totalCents" => 1_204_00,
      "importedAt" => "2026-03-14T09:00:00Z",
      "counts" => counts || { "MATCHED" => 2, "NEEDS_REVIEW" => 1, "UNMATCHED" => 0, "REJECTED" => 1 },
      "unreconciledCents" => unreconciled
    }
  end

  def receivable(id: "r1", our_number: "00012938471", cents: 120_400, payer: "Silva ME")
    {
      "id" => id, "ourNumber" => our_number, "amountCents" => cents,
      "dueDate" => "2026-03-12", "payer" => payer
    }
  end

  def entry(line: 41, status: "MATCHED", amount_cents: 120_400, occurrence: nil,
            match_reason: "EXACT", candidates: [])
    {
      "id" => line,
      "line" => line,
      "status" => status,
      "ourNumber" => "00012938471",
      "amountCents" => amount_cents,
      "paidAt" => "2026-03-12",
      "counterparty" => "Silva ME",
      "matchedReceivableId" => (status == "MATCHED" ? "r1" : nil),
      "matchReason" => match_reason,
      "occurrence" => occurrence,
      "candidates" => candidates
    }
  end

  def rejected_entry(line: 43, code: "ROW_INVALID_DATE", params: { "field" => "paidAt", "raw" => "00/00/00" })
    {
      "id" => line,
      "line" => line,
      "status" => "REJECTED",
      "ourNumber" => nil,
      "amountCents" => nil,
      "paidAt" => nil,
      "counterparty" => nil,
      "matchedReceivableId" => nil,
      "matchReason" => nil,
      "occurrence" => { "line" => line, "code" => code, "params" => params },
      "candidates" => []
    }
  end
end
