# frozen_string_literal: true

# A borda onde centavo vira texto de humano. O motor nunca faz isso.
module LedgerHelper
  # Centavos ausentes NAO viram zero.
  #
  # Uma linha rejeitada nao tem valor -- o parser nao conseguiu ler. Renderizar
  # "R$ 0,00" ali afirmaria que o banco mandou zero, que e uma frase diferente e
  # falsa. Desconhecido tem simbolo proprio, e o mesmo invariante que vale no
  # motor e no banco vale tambem na tela.
  def format_cents(cents)
    return t("batches.show.none") if cents.nil?

    number_to_currency(cents.to_i / 100.0)
  end

  # Data dentro de tabela e ISO nos dois idiomas: ordem lexicografica bate com a
  # cronologica e a coluna nao muda de largura ao trocar de idioma. Formato longo
  # fica no cabecalho e no detalhe.
  def table_date(value)
    return t("batches.show.none") if value.blank?

    Date.parse(value.to_s).iso8601
  end

  def long_date(value)
    return t("batches.show.none") if value.blank?

    l(Date.parse(value.to_s), format: :long)
  end

  # Ocorrencia chega como {code, params} e vira frase AQUI. Um terceiro idioma
  # nao toca uma linha de Kotlin.
  def occurrence_text(occurrence)
    return nil if occurrence.blank?

    code = occurrence["code"]
    params = (occurrence["params"] || {}).symbolize_keys
    params[:line] ||= occurrence["line"]

    t("occurrence.#{code}", **params, default: code)
  end

  def status_label(status) = t("status.#{status}", default: status)

  def status_glyph(status) = t("status_glyph.#{status}", default: "•")

  def match_reason_label(reason)
    return nil if reason.blank?

    t("match_reason.#{reason}", default: reason)
  end

  # O token de cor de cada estado. A marca de 3px na borda vem da classe
  # `.state-<ESTADO>` no CSS; aqui so o nome do token, para o glifo.
  def status_token(status)
    {
      "MATCHED" => "matched",
      "NEEDS_REVIEW" => "review",
      "UNMATCHED" => "rule",
      "REJECTED" => "divergence"
    }.fetch(status, "ink")
  end
end
