# frozen_string_literal: true

class BatchesController < ApplicationController
  def index
    @batches = engine.batches
  end

  def show
    @batch = engine.batch(params[:id])
    @status = params[:status].presence
    @entries = engine.entries(params[:id], status: @status)
  end
end
