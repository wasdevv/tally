# frozen_string_literal: true

require "rails_helper"

# O gate que o i18n-tasks NAO consegue dar.
#
# `i18n-tasks missing` compara os dois YAML entre si: se um codigo faltar nos
# DOIS idiomas, ele passa. Mas o conjunto de codigos nao e definido aqui, e
# definido pelo enum do motor -- e um codigo novo em Kotlin sem traducao nenhuma
# chega na tela como o proprio identificador, em maiusculas, para o operador ler.
#
# Este spec le o enum do motor e exige o par nos dois idiomas. E por isso que
# `OccurrenceCode` e enum e nao String livre: conjunto fechado se pode provar.
RSpec.describe "Codigos do motor" do
  ENGINE_LEDGER = Rails.root.join(
    "../engine/src/main/kotlin/dev/wasdevv/tally/domain/ledger/Ledger.kt"
  ).cleanpath

  def codes_in(enum_name)
    source = File.read(ENGINE_LEDGER)
    body = source[/enum class #{enum_name} \{(.*?)\}/m, 1] or
      raise "enum #{enum_name} nao encontrado em #{ENGINE_LEDGER}"

    body.scan(/^\s*([A-Z][A-Z_]*)\s*,/).flatten
  end

  it "encontra o motor no lugar esperado" do
    expect(ENGINE_LEDGER).to exist
  end

  it "todo codigo de ocorrencia tem texto em ingles e em portugues" do
    codes = codes_in("OccurrenceCode")
    expect(codes).not_to be_empty

    faltando = codes.flat_map do |code|
      I18n.available_locales.filter_map do |locale|
        "#{locale}: occurrence.#{code}" unless I18n.exists?("occurrence.#{code}", locale)
      end
    end

    expect(faltando).to eq([])
  end

  it "todo motivo de casamento tem texto nos dois idiomas" do
    faltando = codes_in("MatchReason").flat_map do |reason|
      I18n.available_locales.filter_map do |locale|
        "#{locale}: match_reason.#{reason}" unless I18n.exists?("match_reason.#{reason}", locale)
      end
    end

    expect(faltando).to eq([])
  end

  it "todo estado tem rotulo e glifo nos dois idiomas" do
    faltando = codes_in("EntryStatus").flat_map do |status|
      I18n.available_locales.flat_map do |locale|
        %w[status status_glyph].filter_map do |prefix|
          "#{locale}: #{prefix}.#{status}" unless I18n.exists?("#{prefix}.#{status}", locale)
        end
      end
    end

    expect(faltando).to eq([])
  end

  # Glifo repetido derruba a distincao sem cor -- o motivo de o glifo existir.
  it "cada estado tem um glifo diferente dos outros" do
    glifos = codes_in("EntryStatus").map { |status| I18n.t("status_glyph.#{status}") }

    expect(glifos.uniq.size).to eq(glifos.size)
  end
end
