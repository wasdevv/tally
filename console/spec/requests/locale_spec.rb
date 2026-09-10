# frozen_string_literal: true

require "rails_helper"

RSpec.describe "Precedencia de locale", type: :request do
  before do
    allow(EngineClient).to receive(:new).and_return(FakeEngine.new(batches: []))
  end

  it "usa ingles quando nada e pedido" do
    get batches_path

    expect(response.body).to include("No batches yet")
  end

  it "o parametro da URL manda em tudo" do
    get batches_path(locale: "pt-BR")

    expect(response.body).to include("Nenhum lote ainda")
  end

  it "a escolha fica na sessao e vale na proxima pagina sem o parametro" do
    get batches_path(locale: "pt-BR")
    get batches_path

    expect(response.body).to include("Nenhum lote ainda")
  end

  it "Accept-Language decide quando nao ha parametro nem sessao" do
    get batches_path, headers: { "HTTP_ACCEPT_LANGUAGE" => "pt-BR,pt;q=0.9,en;q=0.8" }

    expect(response.body).to include("Nenhum lote ainda")
  end

  it "Accept-Language com so o idioma casa com a variante regional" do
    get batches_path, headers: { "HTTP_ACCEPT_LANGUAGE" => "pt" }

    expect(response.body).to include("Nenhum lote ainda")
  end

  it "o parametro vence a sessao, para a pessoa conseguir voltar" do
    get batches_path(locale: "pt-BR")
    get batches_path(locale: "en")

    expect(response.body).to include("No batches yet")
  end

  # Locale vindo da URL e entrada de usuario. Sem allowlist, `?locale=../../etc`
  # viraria busca de arquivo.
  it "locale fora da allowlist cai no padrao em vez de explodir" do
    get batches_path(locale: "../../etc/passwd")

    expect(response).to have_http_status(:ok)
    expect(response.body).to include("No batches yet")
  end

  it "locale desconhecido nao e gravado na sessao" do
    get batches_path(locale: "de")
    get batches_path

    expect(response.body).to include("No batches yet")
  end

  it "idioma sem suporte no Accept-Language cai no padrao" do
    get batches_path, headers: { "HTTP_ACCEPT_LANGUAGE" => "de-DE,de;q=0.9" }

    expect(response.body).to include("No batches yet")
  end

  it "marca o idioma corrente no atributo lang da pagina" do
    get batches_path(locale: "pt-BR")

    expect(response.body).to include('<html lang="pt-BR"')
  end
end
