# frozen_string_literal: true

require "spec_helper"
# Atribuicao, nao `||=`: herdar RAILS_ENV do ambiente deixaria a suite rodar
# contra o que estiver de pe la fora.
ENV["RAILS_ENV"] = "test"

require_relative "../config/environment"
abort("A suite nao roda em producao") if Rails.env.production?

require "rspec/rails"
require "capybara/rspec"

Dir[Rails.root.join("spec/support/**/*.rb")].sort.each { |f| require f }

# rack_test: sem Selenium, sem navegador de verdade. O fluxo principal e HTML e
# form, e o que rack_test nao cobre (Stimulus) e validado a mao no app.
Capybara.default_driver = :rack_test

RSpec.configure do |config|
  config.infer_spec_type_from_file_location!
  config.filter_rails_from_backtrace!
  config.expect_with(:rspec) { |c| c.syntax = :expect }

  # O console nao tem banco: nada de fixtures nem de transactional fixtures.
  config.before do
    I18n.locale = I18n.default_locale
  end
end
