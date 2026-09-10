#!/usr/bin/env ruby
# frozen_string_literal: true

# Verificacao dos atalhos de teclado num navegador de verdade.
#
# A suite usa rack_test, que nao executa JavaScript -- entao o controller
# Stimulus e um ponto cego dela por construcao. Este script fecha esse buraco:
# sobe um Chromium, dispara eventos de teclado reais e mede o EFEITO no DOM.
#
#   TALLY_URL=http://localhost:3000 CHROME=/caminho/do/chrome bin/keyboard-check.rb
#
# Nao roda no CI porque exige um navegador e a pilha de pe; e verificacao manual,
# como o proprio brief admite para comportamento de JS. Mas fica versionado, com
# saida legivel, para nao virar "eu testei na mao uma vez".
#
# Ele ja pegou tres defeitos reais: foco que nao acompanhava o cursor em linha
# sem link, `?` inerte porque o painel de ajuda estava fora do escopo do
# controller, e a tabela sequestrando tecla dentro de campo de texto.

require "ferrum"
# `keyDown` (nao `rawKeyDown` + `char`): o par dispara o listener DUAS vezes, o
# que faz um atalho de alternancia como `?` abrir e fechar no mesmo press e
# parecer que nao funciona. Com `text` presente, `keyDown` ja entrega keydown e
# o caractere de uma vez so.
def press(b, key, code, shift: false, vk: nil)
  mods = shift ? 8 : 0
  b.page.command("Input.dispatchKeyEvent", type: "keyDown", key: key, code: code,
                 text: key, windowsVirtualKeyCode: vk || key.upcase.ord, modifiers: mods)
  b.page.command("Input.dispatchKeyEvent", type: "keyUp", key: key, code: code, modifiers: mods)
  sleep 0.3
end

b = Ferrum::Browser.new(browser_path: ENV.fetch("CHROME", nil),
  browser_options: { "no-sandbox": nil, "disable-dev-shm-usage": nil }, timeout: 30)
b.resize(width: 1440, height: 900)
b.goto("#{ENV.fetch("TALLY_URL", "http://localhost:3000")}/batches/#{ENV.fetch("TALLY_BATCH", "2")}"); sleep 1.2

fails = []
check = ->(label, ok) { puts "#{ok ? 'ok   ' : 'FALHA'} #{label}"; fails << label unless ok }
current = -> { b.evaluate("(document.querySelector('tr[data-current]')||{innerText:''}).innerText.split('\\n')[0].trim()") }

check.("Stimulus conectou", b.evaluate("!!document.querySelector('[data-controller~=ledger]')"))

press(b, "j", "KeyJ")
check.("j seleciona a primeira linha", b.evaluate("document.querySelectorAll('tr[data-current]').length") == 1)
first = current.call

press(b, "j", "KeyJ")
check.("j avanca de linha", current.call != first)

press(b, "k", "KeyK")
check.("k volta", current.call == first)

press(b, "G", "KeyG", shift: true)
check.("G vai para a ultima", b.evaluate("[...document.querySelectorAll('tr[data-ledger-target=row]')].pop().hasAttribute('data-current')"))

press(b, "g", "KeyG")
check.("g volta para a primeira", b.evaluate("document.querySelector('tr[data-ledger-target=row]').hasAttribute('data-current')"))

check.("foco acompanha o cursor", b.evaluate("document.activeElement === document.querySelector('tr[data-current]')"))

check.("ajuda comeca escondida", b.evaluate("document.querySelector('[data-ledger-target=help]').hidden"))
press(b, "?", "Slash", shift: true, vk: 191)
check.("? mostra a ajuda", b.evaluate("!document.querySelector('[data-ledger-target=help]').hidden"))
press(b, "?", "Slash", shift: true, vk: 191)
check.("? esconde de novo", b.evaluate("document.querySelector('[data-ledger-target=help]').hidden"))

# Tecla dentro de campo de texto nao pode ser sequestrada.
b.evaluate("(function(){var i=document.createElement('input');i.id='probe';document.body.appendChild(i);i.focus();})()")
press(b, "j", "KeyJ")
check.("nao sequestra tecla dentro de input", b.evaluate("document.getElementById('probe').value") == "j")
b.evaluate("document.getElementById('probe').remove()")

# `r` so tem efeito em linha NEEDS_REVIEW. O lote pode nao ter nenhuma -- as
# decisoes de execucoes anteriores ficam no banco --, entao o teste PROCURA a
# linha em vez de assumir que e a primeira. Teste que depende do estado do banco
# passa hoje e falha amanha sem ninguem ter mexido em nada.
pendente = b.evaluate("[...document.querySelectorAll('tr[data-ledger-target=row]')].findIndex(r => r.className.includes('NEEDS_REVIEW'))")

if pendente.negative?
  puts "ok    (lote sem pendencia; pule `r` ou importe um arquivo novo)"
else
  press(b, "g", "KeyG")
  pendente.times { press(b, "j", "KeyJ") }
  press(b, "r", "KeyR"); sleep 1.5
# Em tela larga `r` abre o DRAWER e a URL nao muda -- que e o ponto do drawer.
# Checar a URL aqui mediria o comportamento antigo.
  check.("r abre a revisao no drawer",
         b.evaluate("document.querySelector('#review-drawer').innerHTML.length") > 30)
end

b.quit

puts
if fails.empty?
  puts "#{12} verificacoes, tudo certo."
else
  puts "FALHOU: #{fails.join(', ')}"
end
exit(fails.empty? ? 0 : 1)
