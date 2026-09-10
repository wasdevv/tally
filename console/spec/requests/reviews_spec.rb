# frozen_string_literal: true

require "rails_helper"

RSpec.describe "Decisao de revisao", type: :request do
  let(:batch) do
    EnginePayloads.batch(id: 7,
                         counts: { "MATCHED" => 1, "NEEDS_REVIEW" => 1, "UNMATCHED" => 0, "REJECTED" => 0 })
  end
  let(:engine) { FakeEngine.new(batches: [ batch ], entries: { "7" => [] }) }

  before { allow(EngineClient).to receive(:new).and_return(engine) }

  it "responde em Turbo Stream removendo a linha e trocando o sumario" do
    patch batch_review_path(7, 42), headers: { "Accept" => "text/vnd.turbo-stream.html" }

    expect(response.media_type).to eq("text/vnd.turbo-stream.html")
    expect(response.body).to include('action="remove" target="entry-7-42"')
    expect(response.body).to include('target="batch-summary"')
  end

  # Sem JS o fluxo tem que continuar inteiro: o Stream e caminho mais curto,
  # nunca o unico. Um analista com JS bloqueado ainda precisa conciliar.
  it "sem Turbo, redireciona para a fila como antes" do
    patch batch_review_path(7, 42)

    expect(response).to redirect_to(batch_path(7, status: "NEEDS_REVIEW"))
    expect(engine.decisions).to eq([ [ "7", 42, nil ] ])
  end

  it "a decisao chega ao motor tambem pelo caminho do Stream" do
    patch batch_review_path(7, 42), params: { receivable_id: "r2" },
          headers: { "Accept" => "text/vnd.turbo-stream.html" }

    expect(engine.decisions).to eq([ [ "7", 42, "r2" ] ])
  end
end
