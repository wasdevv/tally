# frozen_string_literal: true

require "rails_helper"

RSpec.describe "Fila de revisao", type: :system do
  before { driven_by :rack_test }

  let(:batch) do
    EnginePayloads.batch(
      id: 7, unreconciled: 50_000,
      counts: { "MATCHED" => 0, "NEEDS_REVIEW" => 1, "UNMATCHED" => 0, "REJECTED" => 0 }
    )
  end

  let(:review_entry) do
    EnginePayloads.entry(
      line: 42, status: "NEEDS_REVIEW", amount_cents: 50_000, match_reason: "AMBIGUOUS",
      candidates: [
        EnginePayloads.receivable(id: "r1", our_number: "AAAA0001", cents: 50_000, payer: "Norte Ltda"),
        EnginePayloads.receivable(id: "r2", our_number: "BBBB0002", cents: 50_000, payer: "Aurora SA")
      ]
    )
  end

  let(:engine) { FakeEngine.new(batches: [ batch ], entries: { "7" => [ review_entry ] }) }

  before { allow(EngineClient).to receive(:new).and_return(engine) }

  it "mostra os candidatos que o motor encontrou, em vez de mandar procurar na mao" do
    visit batch_review_path(7, 42)

    expect(page).to have_content("Norte Ltda")
    expect(page).to have_content("Aurora SA")
    expect(page).to have_content("AAAA0001")
  end

  # O operador precisa saber POR QUE o motor nao decidiu.
  it "diz o motivo de a linha ter caido em revisao" do
    visit batch_review_path(7, 42)

    expect(page).to have_content("More than one candidate")
  end

  it "escolher um candidato manda a decisao para o motor" do
    visit batch_review_path(7, 42)

    choose "receivable_id_r2"
    click_button "Record decision"

    expect(engine.decisions).to eq([ [ "7", 42, "r2" ] ])
    expect(page).to have_content("Decision recorded.")
  end

  # "Nenhum destes" nao pode virar id vazio: o motor entende nil, e "" seria um
  # pedido por um recebivel de id vazio.
  it "recusar todos manda nil, nao string vazia" do
    visit batch_review_path(7, 42)

    choose "receivable_id_"
    click_button "Record decision"

    expect(engine.decisions).to eq([ [ "7", 42, nil ] ])
  end

  it "a tela de revisao funciona em portugues" do
    visit batch_review_path(7, 42, locale: "pt-BR")

    expect(page).to have_content("Revisar linha 42")
    expect(page).to have_content("Mais de um candidato")
    expect(page).to have_content("Nenhum destes")
    expect(page).to have_no_content("translation missing")
  end

  it "linha que ja saiu da revisao diz isso, em vez de 500" do
    allow(EngineClient).to receive(:new)
      .and_return(FakeEngine.new(batches: [ batch ], entries: { "7" => [] }))

    visit batch_review_path(7, 42)

    expect(page.status_code).to eq(404)
    expect(page).to have_content("no longer under review")
  end

  it "o razao leva para a revisao da linha pendente" do
    visit batch_path(7)

    # Escopado a tabela: "Review" tambem e o rotulo do filtro de estado no
    # topo, e clicar nele so recarregaria o razao filtrado.
    within("table") { click_link "Review" }

    expect(page).to have_content("Review line 42")
  end

  # Linha sem candidato nenhum ainda pode ser resolvida: o operador confirma que
  # nao ha par, e ela fica no razao como pendencia.
  it "linha sem candidato explica o que acontece ao deixar sem par" do
    sem_candidato = EnginePayloads.entry(line: 43, status: "NEEDS_REVIEW", match_reason: "AMOUNT_MISMATCH")
    allow(EngineClient).to receive(:new)
      .and_return(FakeEngine.new(batches: [ batch ], entries: { "7" => [ sem_candidato ] }))

    visit batch_review_path(7, 43)

    expect(page).to have_content("keeps it in the ledger as pending")
  end
end
