# frozen_string_literal: true

require "rails_helper"

RSpec.describe "Lotes", type: :request do
  let(:batch) { EnginePayloads.batch(id: 7) }
  let(:entries) do
    {
      "7" => [
        EnginePayloads.entry(line: 41, status: "MATCHED"),
        EnginePayloads.entry(line: 42, status: "NEEDS_REVIEW", match_reason: "AMBIGUOUS"),
        EnginePayloads.rejected_entry(line: 43)
      ]
    }
  end

  def stub_engine(engine)
    allow(EngineClient).to receive(:new).and_return(engine)
  end

  it "abre com o numero que ainda nao fechou" do
    stub_engine(FakeEngine.new(batches: [ batch ], entries: entries))

    get batch_path(7)

    expect(response.body).to include("$4,812.90")
    expect(response.body).to include("unreconciled")
  end

  # A decisao de i18n do projeto, medida na tela: o motor mandou codigo e
  # parametros, o console produziu a frase.
  it "renderiza a ocorrencia a partir do codigo, nos dois idiomas" do
    stub_engine(FakeEngine.new(batches: [ batch ], entries: entries))

    get batch_path(7)
    expect(response.body).to include("Line 43: invalid date in paidAt (00/00/00)")

    get batch_path(7, locale: "pt-BR")
    expect(response.body).to include("Linha 43: data inválida em paidAt (00/00/00)")
  end

  it "nao vaza o codigo cru do motor para a tela" do
    stub_engine(FakeEngine.new(batches: [ batch ], entries: entries))

    get batch_path(7)

    expect(response.body).not_to include("ROW_INVALID_DATE")
  end

  it "moeda muda de forma com o idioma, o valor nao" do
    stub_engine(FakeEngine.new(batches: [ batch ], entries: entries))

    get batch_path(7)
    expect(response.body).to include("$1,204.00")

    get batch_path(7, locale: "pt-BR")
    expect(response.body).to include("R$ 1.204,00")
  end

  # Data em coluna e ISO nos dois idiomas: ordem lexicografica bate com a
  # cronologica e a coluna nao muda de largura ao trocar de idioma.
  it "data dentro da tabela fica ISO nos dois idiomas" do
    stub_engine(FakeEngine.new(batches: [ batch ], entries: entries))

    get batch_path(7)
    expect(response.body).to include("2026-03-12")

    get batch_path(7, locale: "pt-BR")
    expect(response.body).to include("2026-03-12")
    expect(response.body).not_to include("12/03/2026")
  end

  # A ponta na tela do invariante que vale no motor e no banco.
  it "linha rejeitada nao mostra valor zero, mostra ausencia" do
    stub_engine(FakeEngine.new(batches: [ batch ], entries: entries))

    get batch_path(7)

    expect(response.body).not_to include("$0.00")
    expect(response.body).to include("—")
  end

  it "estado aparece por glifo, nao so por cor" do
    stub_engine(FakeEngine.new(batches: [ batch ], entries: entries))

    get batch_path(7)

    expect(response.body).to include("■")
    expect(response.body).to include("◧")
    expect(response.body).to include("▨")
  end

  it "filtra por estado" do
    stub_engine(FakeEngine.new(batches: [ batch ], entries: entries))

    get batch_path(7, status: "REJECTED")

    expect(response.body).to include("Line 43")
    expect(response.body).not_to include(">41<")
  end

  it "lote inexistente vira mensagem acionavel, nao 500" do
    stub_engine(FakeEngine.new(batches: []))

    get batch_path(999)

    expect(response).to have_http_status(:bad_gateway)
    expect(response.body).to include("That batch does not exist")
  end

  it "motor fora do ar diz o que fazer" do
    stub_engine(FakeEngine.new(raises: EngineClient::Unavailable.new("Errno::ECONNREFUSED")))

    get batches_path

    expect(response).to have_http_status(:service_unavailable)
    expect(response.body).to include("The engine is not responding")
  end

  it "lista vazia convida a importar" do
    stub_engine(FakeEngine.new(batches: []))

    get batches_path

    expect(response.body).to include("No batches yet")
    expect(response.body).to include(new_import_path)
  end
end
