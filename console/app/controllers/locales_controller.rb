# frozen_string_literal: true

# Troca de idioma sem perder onde a pessoa estava.
class LocalesController < ApplicationController
  def update
    # `permitted` ja aplicou a allowlist em `current_locale`; guardar o
    # resultado dela e nao o parametro cru e o que impede gravar lixo na sessao.
    session[:locale] = current_locale.to_s
    redirect_back fallback_location: batches_path
  end
end
