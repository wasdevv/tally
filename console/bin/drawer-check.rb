#!/usr/bin/env ruby
# frozen_string_literal: true

# Verificacao do drawer de revisao e do Turbo Stream, num navegador de verdade.
#
# Mede o que rack_test nao alcanca: que a revisao abre AO LADO da tabela em
# >=1024px sem trocar de URL, que decidir remove a linha sem recarregar, e que
# abaixo do breakpoint o mesmo link navega para a pagina inteira.
#
#   TALLY_URL=http://localhost:3000 CHROME=/caminho/do/chrome bin/drawer-check.rb
#
# Precisa de pelo menos uma linha NEEDS_REVIEW no lote apontado por TALLY_BATCH.

require "ferrum"
base  = ENV.fetch("TALLY_URL", "http://localhost:3000")
batch = ENV.fetch("TALLY_BATCH", "2")

b = Ferrum::Browser.new(browser_path: ENV.fetch("CHROME", nil),
  browser_options: { "no-sandbox": nil, "disable-dev-shm-usage": nil }, timeout: 30)
fails = []
check = ->(l, ok) { puts "#{ok ? 'ok   ' : 'FALHA'} #{l}"; fails << l unless ok }

# >= 1024px: drawer ao lado, tabela continua visivel
b.resize(width: 1440, height: 900)
b.goto("#{base}/batches/#{batch}"); sleep 1.0
# `click()` do DOM e nao o do Ferrum: o do Ferrum clica por coordenada, e a
# linha pode estar sob o cabecalho depois do scroll -- falha de instrumento que
# parece bug de produto.
b.evaluate("document.querySelector('a[data-review]').click()"); sleep 1.5
check.("drawer preenchido", b.evaluate("document.querySelector('#review-drawer').innerText.length") > 30)
check.("tabela continua visivel", b.evaluate("!!document.querySelector('table.ledger')"))
check.("nao trocou de URL", !b.current_url.include?("/reviews/"))
antes = b.evaluate("document.querySelectorAll('tr[data-ledger-target=row]').length")

# decide pelo drawer: a linha some sem recarregar
b.evaluate("document.querySelector('#review-drawer input[type=radio]').click()")
b.evaluate("document.querySelector('#review-drawer input[type=submit]').click()"); sleep 1.8
depois = b.evaluate("document.querySelectorAll('tr[data-ledger-target=row]').length")
check.("a linha resolvida saiu da tabela (#{antes} -> #{depois})", depois == antes - 1)
check.("tabela nao recarregou (continua na mesma URL)", !b.current_url.include?("/reviews/"))
check.("aviso de decisao aparece", b.evaluate("document.querySelector('#flash').innerText").include?("ecis"))

# < 1024px: sem drawer, o mesmo link navega para a pagina inteira
b.resize(width: 375, height: 800)
b.goto("#{base}/batches/#{batch}"); sleep 1.0
link = b.at_css("a[data-review]")
if link
  b.evaluate("document.querySelector('a[data-review]').click()"); sleep 1.5
  check.("em tela estreita navega para a pagina de revisao", b.current_url.include?("/reviews/"))
else
  puts "ok    (nenhuma linha em revisao restante para o teste estreito)"
end
b.quit

puts
puts fails.empty? ? "drawer e Turbo Stream: tudo certo." : "FALHOU: #{fails.join(', ')}"
exit(fails.empty? ? 0 : 1)
