# frozen_string_literal: true

# Precedencia de locale, nesta ordem: `?locale=` -> sessao -> Accept-Language -> en.
#
# `I18n.available_locales` e allowlist, nao sugestao: locale vindo da URL e
# entrada de usuario, e sem a lista um `?locale=../../etc` viraria busca de
# arquivo. Nao ha heuristica por IP -- quem esta viajando nao quer trocar de
# idioma por causa do aeroporto.
module Localizable
  extend ActiveSupport::Concern

  included do
    around_action :switch_locale
    helper_method :current_locale, :available_locales
  end

  private

  def switch_locale(&)
    I18n.with_locale(current_locale, &)
  end

  def current_locale
    @current_locale ||= begin
      chosen = permitted(params[:locale])
      session[:locale] = chosen.to_s if chosen
      chosen || permitted(session[:locale]) || from_accept_language || I18n.default_locale
    end
  end

  def available_locales = I18n.available_locales

  def permitted(candidate)
    return nil if candidate.blank?

    candidate.to_sym if I18n.available_locales.include?(candidate.to_sym)
  end

  # "pt-BR,pt;q=0.9,en;q=0.8" -- respeita a ordem declarada e ignora o resto.
  def from_accept_language
    header = request.env["HTTP_ACCEPT_LANGUAGE"].to_s
    return nil if header.blank?

    header.split(",")
          .map { |part| part.split(";").first.to_s.strip }
          .lazy
          .filter_map { |tag| permitted(tag) || by_language(tag) }
          .first
  end

  # Navegador que manda "pt" quer portugues, e pt-BR e o portugues que existe
  # aqui. Casar so tag exata devolveria ingles para quem pediu portugues.
  def by_language(tag)
    language = tag.split("-").first.to_s.downcase
    return nil if language.blank?

    I18n.available_locales.find { |locale| locale.to_s.split("-").first.downcase == language }
  end
end
