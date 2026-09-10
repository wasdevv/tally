# frozen_string_literal: true

require "rails_helper"
require "yaml"

# Contrato que envelhece em silencio nao e contrato, e documentacao errada.
#
# Este spec compara `docs/openapi.yml` com os controllers de verdade: rota que
# existe no codigo e nao no documento (ou o contrario) reprova. Nao valida
# schema -- isso quem faz e `engine_contract_spec`, contra o motor de pe.
RSpec.describe "docs/openapi.yml" do
  SPEC_PATH = Rails.root.join("../docs/openapi.yml").cleanpath
  API_DIR = Rails.root.join("../engine/src/main/kotlin/dev/wasdevv/tally/api").cleanpath

  def documented
    YAML.safe_load_file(SPEC_PATH)["paths"].flat_map { |path, ops|
      ops.keys.grep(/\A(get|post|patch|put|delete)\z/).map { |verb| "#{verb.upcase} #{path}" }
    }.sort
  end

  # Le os controllers: @RequestMapping da o prefixo, e cada @GetMapping /
  # @PostMapping da o sufixo. `{id}` no OpenAPI, `{id}` no Spring -- mesma forma.
  def implemented
    Dir[API_DIR.join("*Controller.kt")].flat_map { |file|
      source = File.read(file)
      base = source[/@RequestMapping\("([^"]+)"\)/, 1]
      next [] unless base

      source.scan(/@(Get|Post|Patch|Put|Delete)Mapping(?:\("([^"]*)"\))?/).map do |verb, suffix|
        "#{verb.upcase} #{base}#{suffix}"
      end
    }.sort
  end

  it "encontra o motor e o documento no lugar esperado" do
    expect(SPEC_PATH).to exist
    expect(implemented).not_to be_empty
  end

  it "documenta exatamente as rotas que existem" do
    expect(documented).to eq(implemented)
  end

  # O plano falava em "seis endpoints REST". Sao sete operacoes, e a discrepancia
  # fica registrada aqui e em DECISIONS.md em vez de o numero ser repetido de
  # boca.
  it "tem sete operacoes, e o numero e conferido e nao afirmado" do
    expect(documented.size).to eq(7)
  end
end
