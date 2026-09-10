# frozen_string_literal: true

# A fila humana: o que o motor recusou decidir sozinho.
class ReviewsController < ApplicationController
  def show
    @batch = engine.batch(params[:batch_id])
    @entry = find_entry or return render "shared/not_under_review", status: :not_found
  end

  def update
    # String vazia do formulario e "nenhum destes". `presence` traduz isso para
    # nil, que e o que o motor entende -- e mandar "" seria pedir um recebivel
    # de id vazio, que nao existe.
    engine.decide(params[:batch_id], params[:line], params[:receivable_id].presence)

    respond_to do |format|
      # Turbo Stream: a linha resolvida SAI da fila e o sumario e substituido,
      # sem recarregar a tabela inteira. O que o operador percebe e a proxima
      # linha subindo -- que e a informacao real da acao dele.
      format.turbo_stream do
        @batch = engine.batch(params[:batch_id])
        @entry_id = helpers.dom_id_for_entry(params[:batch_id], "line" => params[:line])
        flash.now[:notice] = t("flash.decided")
      end

      # Sem JS o fluxo continua inteiro: redirect para a fila, como antes. O
      # atalho e o Stream sao caminho mais curto, nunca o unico.
      format.html do
        redirect_to batch_path(params[:batch_id], status: "NEEDS_REVIEW"),
                    notice: t("flash.decided")
      end
    end
  end

  private

  def find_entry
    engine.entries(params[:batch_id], status: "NEEDS_REVIEW")
          .find { |entry| entry["line"].to_s == params[:line].to_s }
  end
end
