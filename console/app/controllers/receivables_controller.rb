# frozen_string_literal: true

class ReceivablesController < ApplicationController
  def index
    @receivables = engine.receivables
  end
end
