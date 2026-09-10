# frozen_string_literal: true

require "rails_helper"

# O contrato entre console e motor, contra o motor DE VERDADE.
#
# As outras suites usam um duble na borda, e duble so prova que o duble concorda
# com o codigo. O que quebra em producao e a divergencia entre o que o motor
# devolve e o que a tela espera -- nome de campo, tipo, forma da ocorrencia. So
# um motor de pe pega isso.
#
# Roda no job `contract` do CI. Fora dele, exporte TALLY_ENGINE_URL e
# TALLY_HMAC_SECRET apontando para um motor no ar.
RSpec.describe "Contrato com o motor", :contract do
  before(:all) do
    skip "TALLY_ENGINE_URL nao definido: este spec exige um motor de pe" if ENV["TALLY_ENGINE_URL"].blank?
  end

  let(:client) { EngineClient.new }

  # Arquivo unico por execucao: o motor e idempotente por digest, entao reusar
  # o mesmo conteudo devolveria o lote da execucao anterior e o spec mediria
  # historia, nao comportamento.
  let(:run_id) { SecureRandom.hex(4) }

  def detail(our_number:, cents:, date: "260312")
    row = (" " * 400).dup
    row[0] = "1"
    row[1, 3] = "341"
    row[62, 8] = our_number
    row[108, 2] = "06"
    row[110, 6] = date
    row[126, 13] = cents.to_s.rjust(13, "0")
    row[324, 30] = "Sacado #{our_number}".ljust(30)
    row
  end

  def return_file(rows) = ([ ("0" + " " * 399) ] + rows + [ ("9" + " " * 399) ]).join("\n")

  it "importa e devolve um lote com os campos que a tela consome" do
    rows = (1..4).map { |i| detail(our_number: "#{run_id[0, 4]}#{format('%04d', i)}", cents: 100 * i) }

    result = client.import(io: StringIO.new(return_file(rows)), filename: "contract-#{run_id}.ret")
    batch = result.fetch("batch")

    # Se qualquer uma destas chaves mudar de nome no motor, a tela quebra --
    # e este spec quebra antes.
    expect(batch.keys).to include(
      "id", "filename", "lineCount", "totalCents", "importedAt", "counts", "unreconciledCents"
    )
    expect(batch["lineCount"]).to eq(4)
    expect(batch["counts"].keys).to match_array(%w[MATCHED NEEDS_REVIEW UNMATCHED REJECTED])
  end

  it "centavos chegam como inteiro, nunca formatados" do
    rows = [ detail(our_number: "#{run_id[0, 4]}9001", cents: 120_400) ]

    result = client.import(io: StringIO.new(return_file(rows)), filename: "cents-#{run_id}.ret")

    expect(result.dig("batch", "totalCents")).to be_a(Integer)
    expect(result.dig("batch", "totalCents")).to eq(120_400)
  end

  # A ponta do contrato onde mora a decisao de i18n: o motor manda codigo e
  # parametros, e o console e quem tem os idiomas.
  it "ocorrencia chega como codigo e parametros, sem texto de humano" do
    rows = [ detail(our_number: "#{run_id[0, 4]}9002", cents: 500, date: "999999") ]

    result = client.import(io: StringIO.new(return_file(rows)), filename: "occ-#{run_id}.ret")
    entries = client.entries(result.dig("batch", "id"))
    occurrence = entries.first.fetch("occurrence")

    expect(occurrence["code"]).to eq("ROW_INVALID_DATE")
    expect(occurrence["params"]).to include("raw" => "999999")

    # "Sem texto de humano" e uma afirmacao sobre a FORMA, nao sobre as letras:
    # o codigo e um identificador de maquina, e conter a palavra "INVALID" nao o
    # torna uma frase. O que se exige e que a carga seja so identificador mais
    # dado cru -- nada de artigo, pontuacao ou espaco no codigo, e nenhuma chave
    # alem destas tres.
    expect(occurrence.keys).to match_array(%w[line code params])
    expect(occurrence["code"]).to match(/\A[A-Z][A-Z_]*\z/)
  end

  # A traducao existe para TODO codigo que o motor sabe emitir -- provado contra
  # o enum em spec/i18n. Aqui a checagem e a outra ponta: o codigo que o motor
  # emitiu de fato tem par.
  it "todo codigo que o motor emite tem traducao nos dois idiomas" do
    rows = [ detail(our_number: "#{run_id[0, 4]}9003", cents: 500, date: "999999") ]

    result = client.import(io: StringIO.new(return_file(rows)), filename: "i18n-#{run_id}.ret")
    code = client.entries(result.dig("batch", "id")).first.dig("occurrence", "code")

    I18n.available_locales.each do |locale|
      expect(I18n.exists?("occurrence.#{code}", locale)).to be(true), "faltou occurrence.#{code} em #{locale}"
    end
  end

  # Este exemplo existe por causa de um bug real, achado rodando e nao lendo: o
  # cliente assinava o caminho COM a query string e o motor assinava
  # `requestURI`, que no servlet nao inclui a query. Toda chamada filtrada dava
  # 401 -- e nenhuma suite via, porque so as chamadas SEM filtro eram assinadas
  # de verdade. A query agora entra no material assinado dos dois lados.
  it "chamada com filtro na query e aceita, e a query esta assinada" do
    rows = [ detail(our_number: "#{run_id[0, 4]}9005", cents: 300, date: "999999") ]
    result = client.import(io: StringIO.new(return_file(rows)), filename: "filtro-#{run_id}.ret")
    batch_id = result.dig("batch", "id")

    filtered = client.entries(batch_id, status: "REJECTED")

    expect(filtered.size).to eq(1)
    expect(filtered.first["status"]).to eq("REJECTED")
    expect(client.entries(batch_id, status: "MATCHED")).to be_empty
  end

  it "reprocessar o mesmo arquivo nao duplica o lote" do
    content = return_file([ detail(our_number: "#{run_id[0, 4]}9004", cents: 700) ])

    first = client.import(io: StringIO.new(content), filename: "idem-#{run_id}.ret")
    second = client.import(io: StringIO.new(content), filename: "outro-nome-#{run_id}.ret")

    expect(first["alreadyImported"]).to be(false)
    expect(second["alreadyImported"]).to be(true)
    expect(second.dig("batch", "id")).to eq(first.dig("batch", "id"))
  end

  it "arquivo recusado devolve codigo que o console sabe traduzir" do
    zip = "PK\x03\x04resto".b

    expect { client.import(io: StringIO.new(zip), filename: "bomba-#{run_id}.ret") }
      .to raise_error(EngineClient::Error) { |error|
        expect(error.code).to eq("FILE_TYPE_NOT_ACCEPTED")
        expect(I18n.exists?("error.#{error.code}", :en)).to be(true)
        expect(I18n.exists?("error.#{error.code}", :"pt-BR")).to be(true)
      }
  end

  it "assinatura errada e recusada pelo motor" do
    impostor = EngineClient.new(secret: "0000000000000000000000000000000000")

    expect { impostor.batches }.to raise_error(EngineClient::Error) { |error|
      expect(error.code).to eq("UNAUTHORIZED")
    }
  end
end
