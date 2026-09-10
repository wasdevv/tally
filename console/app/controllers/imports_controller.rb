# frozen_string_literal: true

class ImportsController < ApplicationController
  def new
  end

  def create
    file = params[:file]
    if file.blank?
      @error = EngineClient::Error.new("FILE_MISSING")
      return render :new, status: :unprocessable_entity
    end

    result = engine.import(io: file.tempfile, filename: file.original_filename)
    batch = result.fetch("batch")

    notice = result["alreadyImported"] ? :already_imported : :imported
    redirect_to batch_path(batch.fetch("id")), notice: t("flash.#{notice}")
  end
end
