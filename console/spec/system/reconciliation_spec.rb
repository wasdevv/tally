# frozen_string_literal: true

require "rails_helper"

RSpec.describe "Fluxo de conciliacao", type: :system do
  before { driven_by :rack_test }

  let(:batch) do
    EnginePayloads.batch(
      id: 7,
      unreconciled: 481_290,
      counts: { "MATCHED" => 2, "NEEDS_REVIEW" => 1, "UNMATCHED" => 0, "REJECTED" => 1 }
    )
  end

  let(:entries) do
    {
      "7" => [
        EnginePayloads.entry(line: 41, status: "MATCHED"),
        EnginePayloads.entry(line: 42, status: "NEEDS_REVIEW", match_reason: "AMBIGUOUS"),
        EnginePayloads.rejected_entry(line: 43)
      ]
    }
  end

  before do
    allow(EngineClient).to receive(:new)
      .and_return(FakeEngine.new(batches: [ batch ], entries: entries))
  end

  # O fluxo principal, inteiro, em cada idioma. O brief manda cortar formato
  # antes de cortar isto.
  it "percorre lote -> pendencias -> ocorrencia em ingles" do
    visit batches_path

    expect(page).to have_content("itau-0314.ret")
    click_link "itau-0314.ret"

    expect(page).to have_content("$4,812.90")
    expect(page).to have_content("unreconciled")

    click_link "Rejected"

    expect(page).to have_content("Line 43: invalid date in paidAt (00/00/00)")
    expect(page).to have_no_content("Silva ME")
  end

  it "percorre o mesmo fluxo em portugues" do
    visit batches_path(locale: "pt-BR")

    click_link "itau-0314.ret"

    expect(page).to have_content("R$ 4.812,90")
    expect(page).to have_content("não conciliado")

    click_link "Rejeitado"

    expect(page).to have_content("Linha 43: data inválida em paidAt (00/00/00)")
  end

  it "troca de idioma sem sair da pagina" do
    visit batch_path(7)
    expect(page).to have_content("unreconciled")

    click_link "PT-BR"

    expect(page).to have_content("não conciliado")
    expect(page).to have_current_path(batch_path(7), ignore_query: true)
  end

  # Nenhuma chave sem par chega na tela. Sem fallback ligado, uma chave faltando
  # aparece como "translation missing" -- e aqui isso reprova.
  it "nenhuma tela mostra chave faltando, em nenhum idioma" do
    I18n.available_locales.each do |locale|
      [ batches_path(locale: locale), batch_path(7, locale: locale),
       new_import_path(locale: locale), receivables_path(locale: locale) ].each do |path|
        visit path

        expect(page).to have_no_content("translation missing")
        expect(page).to have_no_content("Translation missing")
      end
    end
  end

  it "o contador de pendencia aparece com glifo ao lado do numero" do
    visit batch_path(7)

    expect(page).to have_content("◧")
    expect(page).to have_content("Review")
  end

  # Lote fechado mostra zero -- e zero de verdade, medido, nao ausencia.
  it "lote que fechou mostra zero em vez de vermelho" do
    fechado = EnginePayloads.batch(
      id: 8, filename: "fechado.ret", unreconciled: 0,
      counts: { "MATCHED" => 4, "NEEDS_REVIEW" => 0, "UNMATCHED" => 0, "REJECTED" => 0 }
    )
    allow(EngineClient).to receive(:new)
      .and_return(FakeEngine.new(batches: [ fechado ], entries: { "8" => [] }))

    visit batch_path(8)

    expect(page).to have_content("$0.00")
    # Verde de conciliado, nao vermelho de divergencia: `have_content` le texto,
    # entao a cor se afere no atributo.
    expect(page).to have_css("p[style*='var(--matched)']", text: "$0.00")
  end
end
