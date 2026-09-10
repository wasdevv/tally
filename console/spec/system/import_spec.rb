# frozen_string_literal: true

require "rails_helper"

RSpec.describe "Importacao", type: :system do
  before { driven_by :rack_test }

  let(:engine) { FakeEngine.new(batches: [ EnginePayloads.batch(id: 7) ], entries: { "7" => [] }) }

  before { allow(EngineClient).to receive(:new).and_return(engine) }

  it "importa um arquivo e cai no lote" do
    visit new_import_path

    attach_file "file", file_fixture_path("itau-0314.ret")
    click_button "Import"

    expect(engine.imported).to eq([ "itau-0314.ret" ])
    expect(page).to have_content("Return file imported.")
    expect(page).to have_content("itau-0314.ret")
  end

  it "importar sem escolher arquivo diz o que fazer, sem pedir desculpa" do
    page.driver.post import_path, {}

    expect(page.status_code).to eq(422)
    expect(page.body).to include("Choose a return file before importing.")
    expect(page.body).not_to include("Oops")
  end

  it "arquivo recusado pelo motor vira mensagem acionavel" do
    allow(EngineClient).to receive(:new)
      .and_return(FakeEngine.new(raises: EngineClient::Error.new("FILE_TYPE_NOT_ACCEPTED")))

    visit new_import_path
    attach_file "file", file_fixture_path("itau-0314.ret")
    click_button "Import"

    expect(page).to have_content("not a text return file")
  end

  def file_fixture_path(name)
    path = Rails.root.join("spec/fixtures/files", name)
    FileUtils.mkdir_p(path.dirname)
    # Fixture sintetica: um registro CNAB 400 de 400 posicoes, gerado aqui.
    File.write(path, "#{'1'.ljust(400)}\n") unless path.exist?
    path.to_s
  end
end
