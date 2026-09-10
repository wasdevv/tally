# frozen_string_literal: true

class ApplicationController < ActionController::Base
  include Localizable

  allow_browser versions: :modern

  # Motor fora do ar nao e erro 500: a tela diz o que aconteceu e o que fazer.
  rescue_from EngineClient::Error, with: :render_engine_error

  private

  def engine = @engine ||= EngineClient.new

  def render_engine_error(error)
    @error = error
    status = error.is_a?(EngineClient::Unavailable) ? :service_unavailable : :bad_gateway
    render "shared/engine_error", status: status
  end
end
