# frozen_string_literal: true

require "rails_helper"

# O buraco que rack_test nao cobre: ele nao busca CSS, entao uma pagina servida
# SEM folha de estilo nenhuma passa em toda a suite de sistema sem reclamar.
#
# Este spec nao julga aparencia -- ele afirma que a folha COMPILADA do Tailwind
# esta entre as linkadas e que ela tem utilitario de verdade dentro, nao so os
# tokens escritos a mao. Um `tailwindcss:build` que falha em silencio no Docker
# derruba o visual inteiro sem derrubar um unico teste.
RSpec.describe "Folha de estilo", type: :request do
  let(:build) { Rails.root.join("app/assets/builds/tailwind.css") }

  before { allow(EngineClient).to receive(:new).and_return(FakeEngine.new(batches: [])) }

  it "a folha compilada do Tailwind esta entre as linkadas" do
    get batches_path

    expect(response.body).to match(%r{href="/assets/tailwind[^"]*\.css"})
  end

  it "a folha compilada existe e traz utilitarios, nao so os tokens" do
    skip "rode `bin/rails tailwindcss:build` antes" unless build.exist?

    css = build.read

    expect(css).to include(".max-w-6xl")
    expect(css).to include(".min-h-11")
    # E os tokens do projeto continuam la, no fim do mesmo arquivo.
    expect(css).to include("--divergence")
  end
end
